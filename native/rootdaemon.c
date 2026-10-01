/*
 * rootdaemon.c — ProConn Mobile root input daemon
 *
 * Runs as root. Exclusively grabs the physical controller's evdev node,
 * reshapes the stick axes (deadzone / response curve / ADS damping — the
 * same math as the desktop app's sticks.py), and re-emits everything
 * through a uinput virtual gamepad that the game sees instead.
 *
 * Buttons, triggers and d-pad pass through UNCHANGED: this reshapes the
 * user's own thumb input only. It never picks a target and never moves
 * aim on its own.
 *
 * The left-trigger analog axis is watched with a debounced latch and the
 * ADS state is written to --state so the overlay can drive its
 * shrink/pulse effects off the real trigger.
 *
 * Safety: EVIOCGRAB is released by the kernel when our fd closes, so if
 * the daemon dies the physical controller immediately works again.
 *
 * Usage:
 *   rootdaemon --match "Xbox" --deadzone 0.05 --power 1.35 --damping 1.0
 *              --state /path/ads_state --pidfile /path/rootdaemon.pid
 *              --log /path/rootdaemon.log
 *   rootdaemon --find            # list candidate controller event nodes
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <time.h>
#include <dirent.h>
#include <stdarg.h>
#include <sys/ioctl.h>
#include <linux/input.h>
#include <linux/uinput.h>

static volatile int g_running = 1;
static FILE *g_log = NULL;

static void log_msg(const char *fmt, ...) {
    if (!g_log) return;
    time_t t = time(NULL);
    struct tm tm;
    localtime_r(&t, &tm);
    char ts[32];
    strftime(ts, sizeof(ts), "%F %T", &tm);
    fprintf(g_log, "[%s] ", ts);
    va_list ap;
    va_start(ap, fmt);
    vfprintf(g_log, fmt, ap);
    va_end(ap);
    fprintf(g_log, "\n");
    fflush(g_log);
}

/* ---------------- stick math (ported from sticks.py) ---------------- */

static float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static void circular_to_square(float *x, float *y) {
    float X = *x, Y = *y;
    if (X == 0.0f && Y == 0.0f) return;
    float mag = hypotf(X, Y);
    if (mag > 1.0f) { X /= mag; Y /= mag; mag = 1.0f; }
    float ax = fabsf(X), ay = fabsf(Y);
    if (ax > 0.0f && ay > 0.0f) {
        float scale = mag / fmaxf(ax, ay);
        float target = fminf(1.0f, mag * (0.4f + 0.6f * scale));
        X = X / mag * target;
        Y = Y / mag * target;
    }
    *x = X; *y = Y;
}

static void apply_stick(float x, float y, float deadzone, float antideadzone,
                        float power, float sens, int square,
                        float *ox, float *oy) {
    if (fabsf(x) < 0.003f) x = 0.0f;
    if (fabsf(y) < 0.003f) y = 0.0f;
    float mag = hypotf(x, y);
    if (mag < deadzone) { *ox = 0.0f; *oy = 0.0f; return; }
    float norm = (mag - deadzone) / fmaxf(1e-6f, 1.0f - deadzone);
    norm = fminf(1.0f, norm);
    if (power != 1.0f) norm = 1.0f - powf(1.0f - norm, power);
    norm = antideadzone + norm * (1.0f - antideadzone);
    norm = fminf(1.0f, norm * sens);
    if (mag <= 0.0f) { *ox = 0.0f; *oy = 0.0f; return; }
    float X = x / mag * norm, Y = y / mag * norm;
    if (square) circular_to_square(&X, &Y);
    *ox = X; *oy = Y;
}

/* ---------------- config ---------------- */

struct config {
    const char *match;      /* substring of device name, e.g. "Xbox" */
    const char *event;      /* explicit /dev/input/eventN override */
    float deadzone;         /* hipfire deadzone 0..1 */
    float power;            /* response curve exponent (Dynamic=1.35) */
    float damping;          /* ADS damping multiplier 0.3..1.0 */
    float sens;             /* right stick sensitivity multiplier */
    float ads_engage;       /* LT level to enter ADS */
    float ads_release;      /* LT level to leave ADS */
    const char *state;      /* ADS state file path */
    const char *pidfile;
    const char *log;
    int find_only;
};

static void usage(const char *prog) {
    fprintf(stderr,
        "usage: %s [options]\n"
        "  --match NAME        device name substring (default \"Xbox\")\n"
        "  --event PATH        explicit evdev node (skips discovery)\n"
        "  --deadzone F        hipfire deadzone 0..1 (default 0.05)\n"
        "  --power F           response curve exponent (default 1.35)\n"
        "  --damping F         ADS damping 0.3..1.0 (default 1.0)\n"
        "  --sens F            right stick sensitivity (default 1.0)\n"
        "  --state PATH        ADS state file (default none)\n"
        "  --pidfile PATH      pid file (default none)\n"
        "  --log PATH          log file (default none)\n"
        "  --find              list candidate controller nodes and exit\n",
        prog);
}

/* ---------------- evdev helpers ---------------- */

static int test_bit(int bit, unsigned long *arr) {
    return (arr[bit / (8 * sizeof(unsigned long))] >> (bit % (8 * sizeof(unsigned long)))) & 1;
}

/* Find first /dev/input/eventN whose name contains `match` and which has
 * ABS_X (i.e. looks like a gamepad). Returns malloc'd path or NULL. */
static char *find_controller(const char *match) {
    for (int i = 0; i < 64; i++) {
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;
        char name[256] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0 &&
            strstr(name, match)) {
            unsigned long absbits[8] = {0};
            if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits) >= 0 &&
                test_bit(ABS_X, absbits)) {
                close(fd);
                printf("FOUND %s name=\"%s\"\n", path, name);
                fflush(stdout);
                return strdup(path);
            }
        }
        close(fd);
    }
    return NULL;
}

static void list_candidates(void) {
    for (int i = 0; i < 64; i++) {
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;
        char name[256] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0) {
            unsigned long absbits[8] = {0};
            int has_abs = ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits) >= 0
                          && test_bit(ABS_X, absbits);
            printf("%s name=\"%s\" gamepad=%d\n", path, name, has_abs);
        }
        close(fd);
    }
    fflush(stdout);
}

static double now_s(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

static void on_signal(int sig) {
    (void)sig;
    g_running = 0;
}

/* ---------------- main ---------------- */

int main(int argc, char **argv) {
    struct config cfg = {
        .match = "Xbox",
        .event = NULL,
        .deadzone = 0.05f,
        .power = 1.35f,
        .damping = 1.0f,
        .sens = 1.0f,
        .ads_engage = 0.20f,
        .ads_release = 0.08f,
        .state = NULL,
        .pidfile = NULL,
        .log = NULL,
        .find_only = 0,
    };

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--find")) cfg.find_only = 1;
        else if (!strcmp(argv[i], "--match") && i + 1 < argc) cfg.match = argv[++i];
        else if (!strcmp(argv[i], "--event") && i + 1 < argc) cfg.event = argv[++i];
        else if (!strcmp(argv[i], "--deadzone") && i + 1 < argc) cfg.deadzone = strtof(argv[++i], NULL);
        else if (!strcmp(argv[i], "--power") && i + 1 < argc) cfg.power = strtof(argv[++i], NULL);
        else if (!strcmp(argv[i], "--damping") && i + 1 < argc) cfg.damping = strtof(argv[++i], NULL);
        else if (!strcmp(argv[i], "--sens") && i + 1 < argc) cfg.sens = strtof(argv[++i], NULL);
        else if (!strcmp(argv[i], "--state") && i + 1 < argc) cfg.state = argv[++i];
        else if (!strcmp(argv[i], "--pidfile") && i + 1 < argc) cfg.pidfile = argv[++i];
        else if (!strcmp(argv[i], "--log") && i + 1 < argc) cfg.log = argv[++i];
        else { usage(argv[0]); return 2; }
    }

    if (cfg.find_only) { list_candidates(); return 0; }

    if (cfg.log) {
        g_log = fopen(cfg.log, "a");
        if (!g_log) { perror("open log"); return 1; }
    }
    log_msg("rootdaemon starting (match=\"%s\")", cfg.match);

    /* --- locate controller --- */
    char *evpath = NULL;
    if (cfg.event) evpath = strdup(cfg.event);
    else evpath = find_controller(cfg.match);
    if (!evpath) {
        log_msg("ERROR: no controller matching \"%s\" found", cfg.match);
        fprintf(stderr, "no controller matching \"%s\"\n", cfg.match);
        return 3;
    }
    log_msg("using %s", evpath);

    int phys = open(evpath, O_RDONLY);
    if (phys < 0) {
        log_msg("ERROR: open %s: %s", evpath, strerror(errno));
        perror("open evdev");
        free(evpath);
        return 1;
    }

    char devname[256] = {0};
    ioctl(phys, EVIOCGNAME(sizeof(devname) - 1), devname);

    /* --- read axis ranges --- */
    struct input_absinfo ai_lx, ai_ly, ai_rx, ai_ry, ai_lt, ai_rt;
    memset(&ai_lx, 0, sizeof(ai_lx));
    if (ioctl(phys, EVIOCGABS(ABS_X), &ai_lx) < 0 ||
        ioctl(phys, EVIOCGABS(ABS_Y), &ai_ly) < 0 ||
        ioctl(phys, EVIOCGABS(ABS_RX), &ai_rx) < 0 ||
        ioctl(phys, EVIOCGABS(ABS_RY), &ai_ry) < 0) {
        log_msg("ERROR: EVIOCGABS failed: %s", strerror(errno));
        close(phys);
        free(evpath);
        return 1;
    }
    int have_lt = ioctl(phys, EVIOCGABS(ABS_Z), &ai_lt) >= 0;
    int have_rt = ioctl(phys, EVIOCGABS(ABS_RZ), &ai_rt) >= 0;
    log_msg("axes: LX[%d,%d] LY[%d,%d] RX[%d,%d] RY[%d,%d] LT:%d RT:%d",
            ai_lx.minimum, ai_lx.maximum, ai_ly.minimum, ai_ly.maximum,
            ai_rx.minimum, ai_rx.maximum, ai_ry.minimum, ai_ry.maximum,
            have_lt, have_rt);

    /* --- exclusive grab --- */
    if (ioctl(phys, EVIOCGRAB, 1) < 0) {
        log_msg("ERROR: EVIOCGRAB failed: %s", strerror(errno));
        fprintf(stderr, "EVIOCGRAB failed: %s\n", strerror(errno));
        close(phys);
        free(evpath);
        return 4;
    }
    log_msg("grabbed %s exclusively", evpath);

    /* --- create uinput virtual gamepad mirroring capabilities --- */
    int ufd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (ufd < 0) {
        log_msg("ERROR: open /dev/uinput: %s", strerror(errno));
        fprintf(stderr, "open /dev/uinput: %s\n", strerror(errno));
        ioctl(phys, EVIOCGRAB, 0);
        close(phys);
        free(evpath);
        return 5;
    }

    unsigned long keybits[(KEY_MAX + 1) / (8 * sizeof(unsigned long))] = {0};
    unsigned long absbits[(ABS_MAX + 1) / (8 * sizeof(unsigned long))] = {0};
    ioctl(phys, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits);
    ioctl(phys, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits);

    ioctl(ufd, UI_SET_EVBIT, EV_KEY);
    ioctl(ufd, UI_SET_EVBIT, EV_ABS);
    ioctl(ufd, UI_SET_EVBIT, EV_SYN);
    for (int i = 0; i <= KEY_MAX; i++)
        if (test_bit(i, keybits)) ioctl(ufd, UI_SET_KEYBIT, i);

    struct uinput_abs_setup abssetup;
    for (int i = 0; i <= ABS_MAX; i++) {
        if (!test_bit(i, absbits)) continue;
        ioctl(ufd, UI_SET_ABSBIT, i);
        memset(&abssetup, 0, sizeof(abssetup));
        abssetup.code = i;
        if (ioctl(phys, EVIOCGABS(i), &abssetup.absinfo) < 0) continue;
        /* keep the exact ranges so the game sees a normal controller */
        if (ioctl(ufd, UI_ABS_SETUP, &abssetup) < 0) {
            log_msg("WARN: UI_ABS_SETUP(%d) failed: %s", i, strerror(errno));
        }
    }

    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    snprintf(usetup.name, UINPUT_MAX_NAME_SIZE, "ProConn Virtual Pad");
    usetup.id.bustype = BUS_BLUETOOTH;
    usetup.id.vendor = 0x045e;
    usetup.id.product = 0x0bd0;
    usetup.id.version = 1;
    if (ioctl(ufd, UI_DEV_SETUP, &usetup) < 0 ||
        ioctl(ufd, UI_DEV_CREATE) < 0) {
        log_msg("ERROR: uinput create failed: %s", strerror(errno));
        fprintf(stderr, "uinput create failed: %s\n", strerror(errno));
        close(ufd);
        ioctl(phys, EVIOCGRAB, 0);
        close(phys);
        free(evpath);
        return 5;
    }
    log_msg("virtual gamepad created");
    /* give Android's input flinger a moment to enumerate the new device */
    usleep(400 * 1000);

    if (cfg.pidfile) {
        FILE *pf = fopen(cfg.pidfile, "w");
        if (pf) { fprintf(pf, "%d\n", getpid()); fclose(pf); }
    }

    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);

    /* --- event loop --- */
    float rx_lx = 0, rx_ly = 0, rx_rx = 0, rx_ry = 0;   /* raw normalized */
    int ads = 0;
    double ads_changed_at = 0;
    int state_written = -1;

    log_msg("entering event loop");
    while (g_running) {
        struct input_event ev;
        ssize_t n = read(phys, &ev, sizeof(ev));
        if (n != sizeof(ev)) {
            if (n < 0 && (errno == EINTR)) continue;
            log_msg("read ended (%s) — controller gone?", n < 0 ? strerror(errno) : "short");
            break;
        }

        if (ev.type == EV_ABS) {
            struct input_event out = ev;
            if (ev.code == ABS_X || ev.code == ABS_Y ||
                ev.code == ABS_RX || ev.code == ABS_RY) {
                struct input_absinfo *ai =
                    ev.code == ABS_X ? &ai_lx : ev.code == ABS_Y ? &ai_ly :
                    ev.code == ABS_RX ? &ai_rx : &ai_ry;
                float span = (float)(ai->maximum - ai->minimum);
                float v = span > 0 ? ((float)(ev.value - ai->minimum) / span) * 2.0f - 1.0f : 0.0f;
                v = clampf(v, -1.0f, 1.0f);
                if (ev.code == ABS_X) rx_lx = v;
                else if (ev.code == ABS_Y) rx_ly = v;
                else if (ev.code == ABS_RX) rx_rx = v;
                else rx_ry = v;

                /* reshape the whole stick, emit both axes */
                float ox1, oy1, ox2, oy2;
                apply_stick(rx_lx, rx_ly, cfg.deadzone, 0.0f, cfg.power, 1.0f, 0, &ox1, &oy1);
                float rdz = fmaxf(cfg.deadzone, 0.035f);
                float rsn = fminf(cfg.sens, 1.8f);
                float rpw = fminf(cfg.power, 2.0f);
                if (ads) {
                    apply_stick(rx_rx, rx_ry, rdz, 0.05f, rpw, rsn, 1, &ox2, &oy2);
                    ox2 *= cfg.damping;
                    oy2 *= cfg.damping;
                } else {
                    apply_stick(rx_rx, rx_ry, cfg.deadzone, 0.0f, cfg.power, cfg.sens, 1, &ox2, &oy2);
                }
                struct input_event oe;
                oe.type = EV_ABS; oe.time = ev.time;
                if (ev.code == ABS_X || ev.code == ABS_Y) {
                    oe.code = ABS_X;
                    oe.value = (int)(ai_lx.minimum + (ox1 + 1.0f) * 0.5f * (ai_lx.maximum - ai_lx.minimum));
                    (void)write(ufd, &oe, sizeof(oe));
                    oe.code = ABS_Y;
                    oe.value = (int)(ai_ly.minimum + (oy1 + 1.0f) * 0.5f * (ai_ly.maximum - ai_ly.minimum));
                    (void)write(ufd, &oe, sizeof(oe));
                } else {
                    oe.code = ABS_RX;
                    oe.value = (int)(ai_rx.minimum + (ox2 + 1.0f) * 0.5f * (ai_rx.maximum - ai_rx.minimum));
                    (void)write(ufd, &oe, sizeof(oe));
                    oe.code = ABS_RY;
                    oe.value = (int)(ai_ry.minimum + (oy2 + 1.0f) * 0.5f * (ai_ry.maximum - ai_ry.minimum));
                    (void)write(ufd, &oe, sizeof(oe));
                }
                continue; /* shaped; don't fall through */
            }
            if (ev.code == ABS_Z && have_lt) {
                float span = (float)(ai_lt.maximum - ai_lt.minimum);
                float lt = span > 0 ? (float)(ev.value - ai_lt.minimum) / span : 0.0f;
                double t = now_s();
                if (!ads && lt >= cfg.ads_engage && (t - ads_changed_at) >= 0.03) {
                    ads = 1; ads_changed_at = t;
                    log_msg("ADS engaged (lt=%.2f)", lt);
                } else if (ads && lt < cfg.ads_release && (t - ads_changed_at) >= 0.10) {
                    ads = 0; ads_changed_at = t;
                    log_msg("ADS released (lt=%.2f)", lt);
                }
            }
            /* triggers, dpad, hats: pass through unchanged */
            (void)write(ufd, &out, sizeof(out));
        } else {
            /* keys, syn, misc: pass through unchanged */
            (void)write(ufd, &ev, sizeof(ev));
            if (ev.type == EV_SYN && ev.code == SYN_REPORT &&
                cfg.state && ads != state_written) {
                FILE *sf = fopen(cfg.state, "w");
                if (sf) { fprintf(sf, "%d\n", ads); fclose(sf); }
                state_written = ads;
            }
        }
    }

    log_msg("shutting down");
    ioctl(ufd, UI_DEV_DESTROY);
    close(ufd);
    ioctl(phys, EVIOCGRAB, 0);
    close(phys);
    if (cfg.pidfile) unlink(cfg.pidfile);
    free(evpath);
    if (g_log) fclose(g_log);
    return 0;
}
