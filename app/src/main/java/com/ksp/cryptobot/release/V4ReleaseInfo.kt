package com.ksp.cryptobot.release

object V4ReleaseInfo {
    const val VERSION_NAME = "4.0.7"
    const val VERSION_CODE = 112
    const val ROOM_SCHEMA_VERSION = 11
    const val CLOUDSHARE_PROTOCOL = "2026-07-26"
    const val MIGRATION_STAGE_COUNT = 6
    const val MIGRATION_STAGE_COMPLETE = 6

    val stages: List<String> = listOf(
        "CloudShare + data foundation",
        "Collective learning",
        "Governance + production safety",
        "Advanced execution + portfolio/risk",
        "Research + strategy/AI expansion",
        "Unified UI + hardening + release integration"
    )
}

data class V4VerificationItem(
    val status: String,
    val name: String,
    val detail: String
)
