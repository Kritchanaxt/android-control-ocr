package com.example.android_screen_relay.ocr

import android.util.Size

enum class UiAspectRatio(
    val displayName: String,
    val shortDescription: String,
    val value: Float?,
    val targetWidthFactorForPreview: Int,
    val targetHeightFactorForPreview: Int,
    val isPortraitDefault: Boolean = false
) {
    FULL("Full (Sensor Ratio)", "Full", null, 0, 0),
    RATIO_1_1("1:1", "1:1", 1f / 1f, 1, 1),
    RATIO_4_3("4:3", "4:3", 4f / 3f, 4, 3),
    RATIO_3_4("3:4 Portrait", "3:4", 3f / 4f, 3, 4, true),
    RATIO_16_9("16:9", "16:9", 16f / 9f, 16, 9),
    RATIO_9_16("9:16 Portrait", "9:16", 9f / 16f, 9, 16, true);

    val isSpecialRatio: Boolean
        get() = when (this) {
            RATIO_4_3, RATIO_3_4, RATIO_16_9, RATIO_9_16 -> true
            else -> false
        }

    companion object {
        fun fromName(name: String?): UiAspectRatio? = entries.find { it.name == name }
    }
}

enum class VerticalAlignment {
    TOP,
    CENTER,
    BOTTOM
}

data class ResolutionItem(val size: Size?, val displayText: String) {
    override fun toString(): String = displayText
}

val predefinedResolutionsByRatio: Map<UiAspectRatio, List<String>> = mapOf(
    UiAspectRatio.RATIO_1_1 to listOf(
        "720x720", "960x960", "1080x1080", "1200x1200", "1280x1280",
        "1440x1440", "1600x1600", "1920x1920", "2048x2048",
        "2160x2160", "2560x2560", "3000x3000"
    ).sortedWith(compareByDescending { it.split("x").first().toInt() }),

    UiAspectRatio.RATIO_4_3 to listOf(
        "960x720", "1280x960", "1440x1080", "1600x1200", "1920x1440",
        "2048x1536", "2560x1920", "3000x2250"
    ).sortedWith(compareByDescending { it.split("x").first().toInt() }),

    UiAspectRatio.RATIO_3_4 to listOf(
        "720x960", "960x1280", "1080x1440", "1200x1600",
        "1536x2048", "1836x2448", "1920x2560", "2160x2880",
        "2268x3024", "3000x4000"
    ).sortedWith(compareByDescending { it.split("x").first().toInt() }),

    UiAspectRatio.RATIO_16_9 to listOf(
        "1280x720", "1600x900", "1920x1080", "2560x1440", "3000x1688"
    ).sortedWith(compareByDescending { it.split("x").first().toInt() }),

    UiAspectRatio.RATIO_9_16 to listOf(
        "720x1280", "900x1600", "1080x1920", "1215x2160",
        "1440x2560", "1701x3024", "2160x3840", "2268x4032"
    ).sortedWith(compareByDescending { it.split("x").first().toInt() })
)
