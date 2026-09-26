package com.clipgenius.ai.ui.components

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.clipgenius.ai.state.ClipCropPlan
import com.clipgenius.ai.state.ClipLayoutPlan
import com.clipgenius.ai.state.CropKeyframe
import com.clipgenius.ai.state.LayoutType
import com.clipgenius.ai.state.TrackingMode
import com.clipgenius.ai.state.TranscriptSegment
import com.clipgenius.ai.verification.TimeUtils
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.max

/**
 * Phase 9 & 10 Interactive Preview Player.
 * Draws real-time face tracking crop overlays for single speakers or multi-person stacked/grid layouts.
 * Includes a Live 9:16 layout program output monitor, divider lines, and dynamic speaker dimming.
 */
@Composable
fun CropOverlayPlayer(
    videoPath: String,
    cropPlan: ClipCropPlan?,
    layoutPlan: ClipLayoutPlan? = null,
    transcriptSegments: List<TranscriptSegment> = emptyList(),
    clipStartOffsetMs: Long = 0L,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val videoFile = remember(videoPath) { File(videoPath) }

    if (!videoFile.exists()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("Video file not found", color = Color.White)
        }
        return
    }

    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }

    DisposableEffect(videoPath) {
        val player = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
            prepare()
            playWhenReady = false
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        totalDurationMs = duration.coerceAtLeast(0L)
                    } else if (playbackState == Player.STATE_ENDED) {
                        isPlaying = false
                        seekTo(0)
                        pause()
                    }
                }
            })
        }
        exoPlayer = player

        onDispose {
            player.release()
            exoPlayer = null
        }
    }

    LaunchedEffect(isPlaying, isSeeking) {
        while (isPlaying && !isSeeking) {
            val p = exoPlayer
            if (p != null) {
                currentPositionMs = p.currentPosition.coerceAtLeast(0L)
                if (totalDurationMs > 0) {
                    sliderPosition = (currentPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                }
            }
            delay(50) // 20 FPS overlay update for fluid movement
        }
    }

    // Determine current speaker
    val activeSpeakerLabel = remember(transcriptSegments, currentPositionMs, clipStartOffsetMs) {
        val absMs = clipStartOffsetMs + currentPositionMs
        val currentSeg = transcriptSegments.find { segment: TranscriptSegment -> absMs >= segment.startMs && absMs <= segment.endMs }
        currentSeg?.speakerLabel
    }

    // Find active single-crop keyframe
    val activeKeyframe = remember(cropPlan, currentPositionMs) {
        val kfs = cropPlan?.keyframes
        if (kfs.isNullOrEmpty()) null
        else {
            kfs.minByOrNull { max(0L, it.timestampMs - currentPositionMs) }
                ?: kfs.lastOrNull()
        }
    }

    // Color definitions for panels
    val panelColors = listOf(
        Color(0xFF2196F3), // Person 1 Blue
        Color(0xFF4CAF50), // Person 2 Green
        Color(0xFFE91E63), // Person 3 Pink
        Color(0xFFFF9800)  // Person 4 Orange
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF141414))
            .padding(10.dp)
    ) {
        // Player & Canvas Container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
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

            // Dynamic 9:16 Canvas Crop Overlay (Supports single or multi-person panels)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasW = size.width
                val canvasH = size.height

                // Get reference dims from crop plan or fallback
                val srcW = cropPlan?.sourceWidth?.toFloat() ?: 1920f
                val srcH = cropPlan?.sourceHeight?.toFloat() ?: 1080f

                // Compute aspect-fit scale
                val scale = minOf(canvasW / srcW, canvasH / srcH)
                val displayedW = srcW * scale
                val displayedH = srcH * scale
                val offsetX = (canvasW - displayedW) / 2.0f
                val offsetY = (canvasH - displayedH) / 2.0f

                val isMultiPerson = layoutPlan != null &&
                    layoutPlan.layoutType != LayoutType.SINGLE &&
                    layoutPlan.layoutType != LayoutType.SINGLE_SPEAKER_FOLLOW &&
                    layoutPlan.panels.size > 1

                if (isMultiPerson) {
                    // Draw each active layout panel's crop bounding box on the original horizontal frame
                    layoutPlan!!.panels.forEachIndexed { idx, panel ->
                        val kfs = panel.cropPlan.keyframes
                        val kf = if (kfs.isEmpty()) null else {
                            kfs.minByOrNull { max(0L, it.timestampMs - currentPositionMs) } ?: kfs.lastOrNull()
                        }
                        val cropX = kf?.cropX ?: ((srcW - panel.cropPlan.cropWidth) / 2f).toInt()
                        val cropY = kf?.cropY ?: 0
                        val cropW = panel.cropPlan.cropWidth
                        val cropH = panel.cropPlan.cropHeight

                        val pColor = panelColors.getOrElse(idx) { Color.White }
                        val isSpeaking = panel.personId.toString() == activeSpeakerLabel ||
                            layoutPlan.persons.find { it.personId == panel.personId }?.label == activeSpeakerLabel

                        drawRect(
                            color = pColor,
                            topLeft = Offset(offsetX + cropX * scale, offsetY + cropY * scale),
                            size = Size(cropW * scale, cropH * scale),
                            style = Stroke(width = if (isSpeaking) 3.dp.toPx() else 1.5.dp.toPx())
                        )
                    }
                } else if (cropPlan != null) {
                    // Single person crop path overlay
                    val kf = activeKeyframe
                    val cropX = kf?.cropX ?: ((srcW - cropPlan.cropWidth) / 2.0f).toInt()
                    val cropY = kf?.cropY ?: 0
                    val cropW = cropPlan.cropWidth
                    val cropH = cropPlan.cropHeight

                    val normCropX = offsetX + (cropX.toFloat() * scale)
                    val normCropY = offsetY + (cropY.toFloat() * scale)
                    val normCropW = cropW.toFloat() * scale
                    val normCropH = cropH.toFloat() * scale

                    // 1. Darken left & right margins outside the 9:16 window
                    drawRect(
                        color = Color.Black.copy(alpha = 0.65f),
                        topLeft = Offset(offsetX, offsetY),
                        size = Size(max(0f, normCropX - offsetX), displayedH)
                    )
                    drawRect(
                        color = Color.Black.copy(alpha = 0.65f),
                        topLeft = Offset(normCropX + normCropW, offsetY),
                        size = Size(max(0f, (offsetX + displayedW) - (normCropX + normCropW)), displayedH)
                    )

                    // 2. Draw 9:16 Window Border
                    val borderColor = when (kf?.mode) {
                        TrackingMode.FACE -> Color(0xFF2196F3)     // Blue
                        TrackingMode.MOVEMENT -> Color(0xFFFF9800) // Orange
                        else -> Color(0xFF9E9E9E)                  // Grey
                    }

                    drawRect(
                        color = borderColor,
                        topLeft = Offset(normCropX, normCropY),
                        size = Size(normCropW, normCropH),
                        style = Stroke(width = 3.dp.toPx())
                    )

                    // 3. Highlight primary face box if available
                    val face = kf?.faceBox
                    if (face != null) {
                        val fLeft = offsetX + (face.left * displayedW)
                        val fTop = offsetY + (face.top * displayedH)
                        val fW = (face.right - face.left) * displayedW
                        val fH = (face.bottom - face.top) * displayedH

                        drawRect(
                            color = Color(0xFF00E676).copy(alpha = 0.8f),
                            topLeft = Offset(fLeft, fTop),
                            size = Size(fW, fH),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }

            // Live 9:16 Program Output Monitor (Floating PIP)
            if (layoutPlan != null && layoutPlan.layoutType != LayoutType.SINGLE && layoutPlan.panels.size > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(width = 75.dp, height = 133.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xE01A1A1A))
                        .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Title bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.15f))
                                .padding(vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "9:16 OUT",
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Panels simulation
                        Box(modifier = Modifier.weight(1f)) {
                            when (layoutPlan.layoutType) {
                                LayoutType.TWO_PERSON_STACKED -> {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        // Top Panel (Person 1)
                                        val p0 = layoutPlan.panels.getOrNull(0)
                                        val speakTop = p0 != null && (p0.personId.toString() == activeSpeakerLabel ||
                                            layoutPlan.persons.find { it.personId == p0.personId }?.label == activeSpeakerLabel)
                                        val dimTop = activeSpeakerLabel != null && !speakTop

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth()
                                                .background(panelColors[0].copy(alpha = if (dimTop) 0.4f else 0.85f))
                                                .border(
                                                    width = if (speakTop) 1.5.dp else 0.dp,
                                                    color = if (speakTop) Color(0xFFFFD700) else Color.Transparent
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("P1", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        // Divider line
                                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White))

                                        // Bottom Panel (Person 2)
                                        val p1 = layoutPlan.panels.getOrNull(1)
                                        val speakBottom = p1 != null && (p1.personId.toString() == activeSpeakerLabel ||
                                            layoutPlan.persons.find { it.personId == p1.personId }?.label == activeSpeakerLabel)
                                        val dimBottom = activeSpeakerLabel != null && !speakBottom

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth()
                                                .background(panelColors[1].copy(alpha = if (dimBottom) 0.4f else 0.85f))
                                                .border(
                                                    width = if (speakBottom) 1.5.dp else 0.dp,
                                                    color = if (speakBottom) Color(0xFFFFD700) else Color.Transparent
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("P2", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                LayoutType.THREE_PERSON_GRID -> {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxSize()
                                                    .background(panelColors[0].copy(alpha = 0.75f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("P1", color = Color.White, fontSize = 9.sp)
                                            }
                                            Box(modifier = Modifier.width(1.dp).fillMaxSize().background(Color.White))
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxSize()
                                                    .background(panelColors[1].copy(alpha = 0.75f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("P2", color = Color.White, fontSize = 9.sp)
                                            }
                                        }
                                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White))
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth()
                                                .background(panelColors[2].copy(alpha = 0.75f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("P3", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                LayoutType.FOUR_PERSON_GRID -> {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                            Box(modifier = Modifier.weight(1f).fillMaxSize().background(panelColors[0].copy(alpha = 0.7f)), contentAlignment = Alignment.Center) { Text("P1", color = Color.White, fontSize = 9.sp) }
                                            Box(modifier = Modifier.width(1.dp).fillMaxSize().background(Color.White))
                                            Box(modifier = Modifier.weight(1f).fillMaxSize().background(panelColors[1].copy(alpha = 0.7f)), contentAlignment = Alignment.Center) { Text("P2", color = Color.White, fontSize = 9.sp) }
                                        }
                                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White))
                                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                            Box(modifier = Modifier.weight(1f).fillMaxSize().background(panelColors[2].copy(alpha = 0.7f)), contentAlignment = Alignment.Center) { Text("P3", color = Color.White, fontSize = 9.sp) }
                                            Box(modifier = Modifier.width(1.dp).fillMaxSize().background(Color.White))
                                            Box(modifier = Modifier.weight(1f).fillMaxSize().background(panelColors[3].copy(alpha = 0.7f)), contentAlignment = Alignment.Center) { Text("P4", color = Color.White, fontSize = 9.sp) }
                                        }
                                    }
                                }

                                else -> {}
                            }
                        }
                    }
                }
            }

            // Play/Pause Overlay Button
            IconButton(
                onClick = {
                    val p = exoPlayer ?: return@IconButton
                    if (p.isPlaying) p.pause() else p.play()
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- TIMELINE MODE STRIP ---
        if (cropPlan != null && cropPlan.keyframes.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (layoutPlan != null && layoutPlan.layoutType != LayoutType.SINGLE) "Stacked Grid Timeline" else "Auto-Crop Strategy Strip",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.8f)
                    )

                    // Mode Legend
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LegendDot(color = Color(0xFF2196F3), label = "Face")
                        LegendDot(color = Color(0xFFFF9800), label = "Movement")
                        LegendDot(color = Color(0xFF9E9E9E), label = "Center")
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Colored Strip with Live Needle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF222222))
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stripW = size.width
                        val stripH = size.height
                        val totalKfs = cropPlan.keyframes.size
                        if (totalKfs > 0) {
                            val segmentW = stripW / totalKfs.toFloat()
                            for (i in cropPlan.keyframes.indices) {
                                val kf = cropPlan.keyframes[i]
                                val color = when (kf.mode) {
                                    TrackingMode.FACE -> Color(0xFF2196F3)
                                    TrackingMode.MOVEMENT -> Color(0xFFFF9800)
                                    TrackingMode.CENTER -> Color(0xFF757575)
                                }
                                drawRect(
                                    color = color,
                                    topLeft = Offset(i * segmentW, 0f),
                                    size = Size(segmentW + 0.5f, stripH)
                                )
                            }
                        }

                        // Playhead needle
                        if (totalDurationMs > 0) {
                            val needleX = (currentPositionMs.toFloat() / totalDurationMs.toFloat()) * stripW
                            drawLine(
                                color = Color.White,
                                start = Offset(needleX, 0f),
                                end = Offset(needleX, stripH),
                                strokeWidth = 2.5.dp.toPx()
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Scrubber and Time Labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    exoPlayer?.seekTo(0)
                    currentPositionMs = 0L
                    sliderPosition = 0f
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Replay,
                    contentDescription = "Restart",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = TimeUtils.formatMs(currentPositionMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.9f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )

            Slider(
                value = sliderPosition,
                onValueChange = { newVal ->
                    isSeeking = true
                    sliderPosition = newVal
                    currentPositionMs = (newVal * totalDurationMs).toLong()
                },
                onValueChangeFinished = {
                    isSeeking = false
                    val targetMs = (sliderPosition * totalDurationMs).toLong()
                    exoPlayer?.seekTo(targetMs)
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
                    .testTag("crop_overlay_scrub_bar"),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                )
            )

            Text(
                text = TimeUtils.formatMs(totalDurationMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp
        )
    }
}
