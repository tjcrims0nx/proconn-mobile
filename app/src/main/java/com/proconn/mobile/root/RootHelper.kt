package com.proconn.mobile.root

import android.content.Context
import java.io.File

/**
 * Root bridge for the input-shaping daemon (`rootdaemon`, shipped in
 * assets/). All privileged work happens through here:
 *
 *  - root / /dev/uinput / controller-node diagnostics
 *  - deploying the daemon binary to app-private storage
 *  - starting it as root (setsid, detached) and stopping it again
 *  - reading the ADS trigger state it publishes (plain file, no root
 *    needed to read — the daemon writes into our own files dir)
 *
 * Shell calls are blocking and MUST run off the main thread.
 */
object RootHelper {
    const val DAEMON_ASSET = "rootdaemon"
    const val PID_FILE = "rootdaemon.pid"
    const val STATE_FILE = "ads_state"
    const val LOG_FILE = "rootdaemon.log"

    data class Diag(val name: String, val ok: Boolean, val detail: String)

    fun daemonFile(ctx: Context) = File(ctx.filesDir, DAEMON_ASSET)
    fun pidFile(ctx: Context) = File(ctx.filesDir, PID_FILE)
    fun stateFile(ctx: Context) = File(ctx.filesDir, STATE_FILE)
    fun logFile(ctx: Context) = File(ctx.filesDir, LOG_FILE)

    /** Run a command as root. Returns (exitCode, combined stdout+stderr). */
    fun su(cmd: String): Pair<Int, String> {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText() +
                p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            code to out.trim()
        } catch (e: Exception) {
            -1 to (e.message ?: "su failed")
        }
    }

    fun isRooted(): Boolean {
        val (code, out) = su("id")
        return code == 0 && out.contains("uid=0")
    }

    fun hasUinput(): Boolean = su("test -c /dev/uinput").first == 0

    /**
     * Ask the deployed daemon to list candidate controller nodes and
     * return the first gamepad node (e.g. "/dev/input/event7"), or null.
     */
    fun findControllerNode(ctx: Context): String? {
        val bin = daemonFile(ctx).absolutePath
        val (code, out) = su("$bin --find")
        if (code != 0) return null
        return out.lines()
            .mapNotNull { line -> line.split(" ").firstOrNull { it.startsWith("/dev/input/event") } }
            .firstOrNull()
    }

    /** Copy the daemon out of assets and make it executable. */
    fun deployDaemon(ctx: Context): Boolean {
        return try {
            val dst = daemonFile(ctx)
            ctx.assets.open(DAEMON_ASSET).use { inp ->
                dst.outputStream().use { out -> inp.copyTo(out) }
            }
            su("chmod 755 ${dst.absolutePath}").first == 0
        } catch (e: Exception) {
            false
        }
    }

    /** True when the pidfile exists and its process is alive. */
    fun isDaemonRunning(ctx: Context): Boolean {
        val pf = pidFile(ctx)
        if (!pf.exists()) return false
        val pid = try {
            pf.readText().trim().toIntOrNull()
        } catch (e: Exception) {
            null
        } ?: return false
        return su("kill -0 $pid").first == 0
    }

    /**
     * Start shaping with the given tuning. The daemon grabs the physical
     * controller (EVIOCGRAB), applies deadzone/curve/ADS damping to the
     * sticks, and re-emits everything through a virtual gamepad. Buttons,
     * triggers and d-pad pass through untouched.
     */
    fun startDaemon(
        ctx: Context,
        deadzone: Float,
        power: Float,
        damping: Float
    ): Boolean {
        if (!deployDaemon(ctx)) return false
        val bin = daemonFile(ctx).absolutePath
        val pid = pidFile(ctx).absolutePath
        val state = stateFile(ctx).absolutePath
        val log = logFile(ctx).absolutePath
        // Drop any stale ADS state before (re)start.
        try {
            stateFile(ctx).delete()
        } catch (e: Exception) {
        }
        // Damping 0 in the UI means "no smoothing" for the visualizer —
        // for the daemon it must mean "no ADS slowdown" (1.0), never 0.
        val dmp = if (damping < 0.3f) 1.0f else damping.coerceIn(0.3f, 1.0f)
        val cmd = "setsid $bin --deadzone $deadzone --power $power --damping $dmp " +
            "--pidfile $pid --state $state --log $log >/dev/null 2>&1 < /dev/null &"
        if (su(cmd).first != 0) return false
        // Give the daemon a moment to grab the controller and create
        // the virtual gamepad, then verify it is actually alive.
        Thread.sleep(1500)
        return isDaemonRunning(ctx)
    }

    fun stopDaemon(ctx: Context): Boolean {
        try {
            pidFile(ctx).readText().trim().toIntOrNull()?.let { pid ->
                su("kill $pid")
            }
        } catch (e: Exception) {
        }
        // Fallback in case the pidfile went stale.
        su("pkill -f ${daemonFile(ctx).absolutePath}")
        Thread.sleep(600)
        return !isDaemonRunning(ctx)
    }

    /**
     * ADS trigger state published by the daemon ("1"/"0"). Plain file in
     * our own storage — readable without root.
     */
    fun readAdsState(ctx: Context): Boolean {
        return try {
            stateFile(ctx).readText().trim() == "1"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Prerequisite checks shown in the Tuner card. Stops at the first
     * failure — each step depends on the previous one.
     */
    fun diagnostics(ctx: Context): List<Diag> {
        val out = mutableListOf<Diag>()
        val (rc, idOut) = su("id")
        val rooted = rc == 0 && idOut.contains("uid=0")
        out += Diag(
            "Root access", rooted,
            if (rooted) (idOut.lineSequence().firstOrNull()?.take(48) ?: "ok")
            else "su failed — tap Grant when your root manager asks"
        )
        if (!rooted) return out
        val uin = hasUinput()
        out += Diag(
            "Virtual gamepad (/dev/uinput)", uin,
            if (uin) "present" else "missing — this kernel can't create virtual controllers"
        )
        if (!uin) return out
        val node = findControllerNode(ctx)
        out += Diag(
            "Controller input node", node != null,
            node ?: "no gamepad in /dev/input — is the controller connected and awake?"
        )
        return out
    }
}
