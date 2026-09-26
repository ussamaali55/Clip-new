package com.clipgenius.ai.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.clipgenius.ai.state.SourceRange
import com.clipgenius.ai.state.VerifiedClip
import com.clipgenius.ai.verification.TimeUtils
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.max

/**
 * Phase 8 Adjust Trim Dialog.
 * Allows fine-tuning start, end, and hook boundaries with mini-player preview.
 * Re-runs Phase 7 verifier checks (bounds, start < end, video duration, min duration).
 * Re-cuts only if times changed.
 */
@Composable
fun AdjustTrimDialog(
    clip: VerifiedClip,
    sourceVideoPath: String?,
    videoDurationMs: Long,
    minLengthSeconds: Int = 30,
    onDismiss: () -> Unit,
    onSaveAndRecut: (startMs: Long, endMs: Long, hookStartMs: Long, hookEndMs: Long, timesChanged: Boolean) -> Unit
) {
    val initialRange = clip.verifiedRanges.firstOrNull() ?: SourceRange(0L, 30_000L)
    var startInput by remember { mutableStateOf(TimeUtils.formatMs(initialRange.startMs)) }
    var endInput by remember { mutableStateOf(TimeUtils.formatMs(initialRange.endMs)) }
    var hookStartInput by remember { mutableStateOf(TimeUtils.formatMs(clip.verifiedHookStartMs)) }
    var hookEndInput by remember { mutableStateOf(TimeUtils.formatMs(clip.verifiedHookEndMs)) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosMs by remember { mutableLongStateOf(0L) }

    // Prefer previewing the source video so user can scrub before or after the cut
    val videoFile = remember(sourceVideoPath, clip.draftVideoPath) {
        val src = sourceVideoPath?.let { File(it) }
        if (src != null && src.exists()) src else clip.draftVideoPath?.let { File(it) }
    }

    DisposableEffect(videoFile) {
        if (videoFile != null && videoFile.exists()) {
            val player = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
                prepare()
                playWhenReady = false
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }
                })
            }
            exoPlayer = player
        }

        onDispose {
            exoPlayer?.release()
            exoPlayer = null
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosMs = exoPlayer?.currentPosition ?: 0L
            delay(150)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Adjust Clip Timestamps",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = clip.originalCandidate.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Mini Preview Video
                if (exoPlayer != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = false
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        IconButton(
                            onClick = {
                                exoPlayer?.let {
                                    if (it.isPlaying) it.pause() else it.play()
                                }
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Position: ${TimeUtils.formatMs(currentPosMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row {
                            TextButton(
                                onClick = {
                                    val parsed = TimeUtils.parseToMs(startInput) ?: 0L
                                    exoPlayer?.seekTo(parsed)
                                }
                            ) {
                                Text("Seek Start", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(
                                onClick = {
                                    val parsed = TimeUtils.parseToMs(hookStartInput) ?: 0L
                                    exoPlayer?.seekTo(parsed)
                                }
                            ) {
                                Text("Seek Hook", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Inputs
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startInput,
                        onValueChange = {
                            startInput = it
                            validationError = null
                        },
                        label = { Text("Start (MM:SS)") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("adjust_start_input"),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = endInput,
                        onValueChange = {
                            endInput = it
                            validationError = null
                        },
                        label = { Text("End (MM:SS)") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("adjust_end_input"),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hookStartInput,
                        onValueChange = {
                            hookStartInput = it
                            validationError = null
                        },
                        label = { Text("Hook Start") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = hookEndInput,
                        onValueChange = {
                            hookEndInput = it
                            validationError = null
                        },
                        label = { Text("Hook End") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                AnimatedVisibility(visible = validationError != null) {
                    Text(
                        text = validationError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sMs = TimeUtils.parseToMs(startInput)
                    val eMs = TimeUtils.parseToMs(endInput)
                    val hsMs = TimeUtils.parseToMs(hookStartInput) ?: (sMs ?: 0L)
                    val heMs = TimeUtils.parseToMs(hookEndInput) ?: ((sMs ?: 0L) + 3000L)

                    if (sMs == null || eMs == null) {
                        validationError = "Invalid timestamp format. Use MM:SS or HH:MM:SS."
                        return@Button
                    }
                    if (sMs >= eMs) {
                        validationError = "Start time must be earlier than end time."
                        return@Button
                    }
                    if (sMs < 0 || (videoDurationMs > 0 && eMs > videoDurationMs)) {
                        validationError = "Times must fall within video duration (0..${TimeUtils.formatMs(videoDurationMs)})."
                        return@Button
                    }
                    val dur = eMs - sMs
                    val minAllowedMs = max(0L, (minLengthSeconds - 5) * 1000L)
                    if (dur < minAllowedMs) {
                        validationError = "Clip duration (${dur / 1000}s) is shorter than minimum (${minLengthSeconds}s)."
                        return@Button
                    }

                    val timesChanged = sMs != initialRange.startMs ||
                            eMs != initialRange.endMs ||
                            hsMs != clip.verifiedHookStartMs ||
                            heMs != clip.verifiedHookEndMs

                    onSaveAndRecut(sMs, eMs, hsMs, heMs, timesChanged)
                },
                modifier = Modifier.testTag("save_adjust_times_button")
            ) {
                Text("Save & Re-cut")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
