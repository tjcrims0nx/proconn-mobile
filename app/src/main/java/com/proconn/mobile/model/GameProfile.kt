package com.proconn.mobile.model

/**
 * Per-game "best aim" profile: the baseline tuning APPLY BEST AIM SETTINGS
 * applies for a given game. Values are starting points — the player
 * fine-tunes from here in the aim-tuning sliders and in-game settings.
 *
 * Package names verified 2026-09-30 (APKPure / Uptodown / APKFab listings).
 */
data class GameProfile(
    val id: String,
    val displayName: String,
    val shortName: String,
    val packageNames: List<String>,
    /** Fixed deadzone fraction; ignored when [useMeasuredDeadzone] is true. */
    val deadzone: Float,
    /** When true, APPLY BEST uses the measured deadzone, or 5% if none measured. */
    val useMeasuredDeadzone: Boolean = false,
    val damping: Float,
    /** Name of the TunerFragment.CurveKind to apply. */
    val curve: String,
    val aimDial: Int,
    /** One-line note shown under the Game card. */
    val note: String
)

object GameProfiles {

    val ALL = listOf(
        GameProfile(
            id = "codm",
            displayName = "Call of Duty Mobile",
            shortName = "CODM",
            packageNames = listOf("com.activision.callofduty.shooter"),
            deadzone = 0.05f,
            useMeasuredDeadzone = true,
            damping = 0f,
            curve = "DYNAMIC",
            aimDial = 65,
            note = "Pro COD baseline: measured deadzone, zero damping, Dynamic curve."
        ),
        GameProfile(
            id = "pubg",
            displayName = "PUBG Mobile",
            shortName = "PUBG",
            packageNames = listOf("com.tencent.ig"),
            deadzone = 0.06f,
            damping = 0.10f,
            curve = "PRECISE",
            aimDial = 55,
            note = "A touch of damping steadies spray control — starting point."
        ),
        GameProfile(
            id = "bloodstrike",
            displayName = "Bloodstrike",
            shortName = "Bloodstrike",
            packageNames = listOf("com.netease.newspike"),
            deadzone = 0.05f,
            damping = 0.05f,
            curve = "DYNAMIC",
            aimDial = 60,
            note = "Fast time-to-kill: keep it raw and responsive — starting point."
        ),
        GameProfile(
            id = "fortnite",
            displayName = "Fortnite",
            shortName = "Fortnite",
            packageNames = listOf("com.epicgames.fortnite"),
            deadzone = 0.07f,
            damping = 0.15f,
            curve = "PRECISE",
            aimDial = 50,
            note = "Build-heavy play benefits from steadier micro-aim — starting point."
        ),
        GameProfile(
            id = "destiny",
            displayName = "Destiny Rising",
            shortName = "Destiny",
            packageNames = listOf("com.netease.g108na"),
            deadzone = 0.05f,
            damping = 0.05f,
            curve = "DYNAMIC",
            aimDial = 60,
            note = "FPS/TPS hybrid: balanced baseline — starting point."
        )
    )

    fun byId(id: String): GameProfile =
        ALL.firstOrNull { it.id == id } ?: ALL[0]

    /** Match a foreground package name to a profile, or null if unknown. */
    fun byPackage(packageName: String?): GameProfile? {
        if (packageName.isNullOrEmpty()) return null
        return ALL.firstOrNull { it.packageNames.contains(packageName) }
    }
}
