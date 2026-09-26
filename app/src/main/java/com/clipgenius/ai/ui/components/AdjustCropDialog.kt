package com.clipgenius.ai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clipgenius.ai.state.VerifiedClip
import java.util.Locale

/**
 * Phase 9 Manual Crop Adjustment Dialog.
 * Allows tweaking horizontal crop position (left <-> right) and face margin tightness.
 * Updates crop path instantly without re-running ML Kit face detection.
 */
@Composable
fun AdjustCropDialog(
    clip: VerifiedClip,
    initialOffsetX: Float,
    initialMargin: Float,
    onDismiss: () -> Unit,
    onSaveCropSettings: (offsetX: Float, margin: Float) -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(initialOffsetX) }
    var faceMargin by remember { mutableFloatStateOf(initialMargin) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Manual Crop Adjustment",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = clip.originalCandidate.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Slider 1: Horizontal Crop Position
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Crop Position (Left ↔ Right)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when {
                            offsetX < -0.05f -> "Left (${String.format(Locale.US, "%.0f%%", -offsetX * 100)})"
                            offsetX > 0.05f -> "Right (${String.format(Locale.US, "%.0f%%", offsetX * 100)})"
                            else -> "Auto Center"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Slider(
                    value = offsetX,
                    onValueChange = { offsetX = it },
                    valueRange = -1.0f..1.0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("crop_position_slider")
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Slider 2: Face Margin
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Face Margin / Headroom",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${String.format(Locale.US, "%.1fx", faceMargin)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Slider(
                    value = faceMargin,
                    onValueChange = { faceMargin = it },
                    valueRange = 0.8f..1.5f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("face_margin_slider")
                )

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = {
                        offsetX = 0f
                        faceMargin = 1.0f
                    },
                    modifier = Modifier.testTag("reset_to_auto_button")
                ) {
                    Text("Reset to AI Auto Path")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveCropSettings(offsetX, faceMargin) },
                modifier = Modifier.testTag("save_crop_adjust_button")
            ) {
                Text("Apply & Update")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
