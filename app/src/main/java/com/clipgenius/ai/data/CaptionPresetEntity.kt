package com.clipgenius.ai.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.clipgenius.ai.state.CaptionPreset

@Entity(tableName = "caption_presets")
data class CaptionPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val fontFamily: String,
    val fontSizeSp: Float,
    val fontWeight: Int,
    val textColor: String,
    val strokeColor: String,
    val strokeWidthDp: Float,
    val shadowColor: String,
    val shadowRadiusDp: Float,
    val backgroundColor: String,
    val textAlignment: String,
    val verticalPositionPercent: Float,
    val animationType: String,
    val animationDurationMs: Int,
    val wordEmphasisColor: String,
    val allCaps: Boolean,
    val isImported: Boolean
) {
    fun toDomain(): CaptionPreset {
        return CaptionPreset(
            id = id,
            name = name,
            fontFamily = fontFamily,
            fontSizeSp = fontSizeSp,
            fontWeight = fontWeight,
            textColor = textColor,
            strokeColor = strokeColor,
            strokeWidthDp = strokeWidthDp,
            shadowColor = shadowColor,
            shadowRadiusDp = shadowRadiusDp,
            backgroundColor = backgroundColor,
            textAlignment = textAlignment,
            verticalPositionPercent = verticalPositionPercent,
            animationType = animationType,
            animationDurationMs = animationDurationMs,
            wordEmphasisColor = wordEmphasisColor,
            allCaps = allCaps,
            isImported = isImported
        )
    }

    companion object {
        fun fromDomain(preset: CaptionPreset): CaptionPresetEntity {
            return CaptionPresetEntity(
                id = preset.id,
                name = preset.name,
                fontFamily = preset.fontFamily,
                fontSizeSp = preset.fontSizeSp,
                fontWeight = preset.fontWeight,
                textColor = preset.textColor,
                strokeColor = preset.strokeColor,
                strokeWidthDp = preset.strokeWidthDp,
                shadowColor = preset.shadowColor,
                shadowRadiusDp = preset.shadowRadiusDp,
                backgroundColor = preset.backgroundColor,
                textAlignment = preset.textAlignment,
                verticalPositionPercent = preset.verticalPositionPercent,
                animationType = preset.animationType,
                animationDurationMs = preset.animationDurationMs,
                wordEmphasisColor = preset.wordEmphasisColor,
                allCaps = preset.allCaps,
                isImported = preset.isImported
            )
        }
    }
}
