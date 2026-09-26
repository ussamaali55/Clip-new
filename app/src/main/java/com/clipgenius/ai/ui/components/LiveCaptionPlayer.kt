package com.clipgenius.ai.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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
import com.clipgenius.ai.state.CaptionCue
import com.clipgenius.ai.state.CaptionPreset
import com.clipgenius.ai.verification.TimeUtils
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/**
 * Phase 11 Vertical Preview Player with Live Subtitle Overlay & Safe Area Guidelines.
 * Renders heavy-stroked outline titles, rounded background boxes, animated word scaling,
 * karaoke word-by-word highlighted captions, and safe area guides.
 */
@Composable
fun LiveCaptionPlayer(
    videoPath: String,
    cues: List<CaptionCue>,
    preset: CaptionPreset,
    showGuides: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val videoFile = remember(videoPath) { File(videoPath) }

    if (!videoFile.exists()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("Draft video file not found. Render first.", color = Color.White)
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
            delay(30) // Ultra smooth updates for text animation synchrony
        }
    }

    // Identify active cue and active word
    val activeCue = remember(cues, currentPositionMs) {
        cues.find { currentPositionMs >= it.cueStartMs && currentPositionMs <= it.cueEndMs }
    }

    val activeWordIndex = remember(activeCue, currentPositionMs) {
        activeCue?.wordTimings?.indexOfFirst { currentPositionMs >= it.startMs && currentPositionMs <= it.endMs } ?: -1
    }

    // Scale animation factor for pop effect
    var popScale by remember { mutableStateOf(1f) }
    LaunchedEffect(activeCue?.id) {
        if (activeCue != null && preset.animationType == "pop") {
            popScale = 1.2f
            delay(80)
            popScale = 1.0f
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F0F0F))
            .padding(8.dp)
    ) {
        Column {
            // Container holding 9:16 Portrait Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                // Squeeze to 9:16 ratio container
                Box(
                    modifier = Modifier
                        .height(340.dp)
                        .width(191.dp) // 9:16 proportion of 340dp height
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1E1E1E))
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

                    // Safe Area Guidelines and Subtitle Overlay
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height

                        // Draw guides (Top 12%, Bottom 18%)
                        if (showGuides) {
                            val topY = h * 0.12f
                            val bottomY = h * 0.82f // 100% - 18%

                            // Top boundary (Red alert zone)
                            drawLine(
                                color = Color.Red.copy(alpha = 0.4f),
                                start = Offset(0f, topY),
                                end = Offset(w, topY),
                                strokeWidth = 1.5.dp.toPx()
                            )
                            // Bottom boundary (Red alert zone)
                            drawLine(
                                color = Color.Red.copy(alpha = 0.4f),
                                start = Offset(0f, bottomY),
                                end = Offset(w, bottomY),
                                strokeWidth = 1.5.dp.toPx()
                            )
                        }

                        // Render Active Cue
                        activeCue?.let { cue ->
                            drawIntoCanvas { canvas ->
                                val nativeCanvas = canvas.nativeCanvas

                                val textPaint = Paint().apply {
                                    isAntiAlias = true
                                    textSize = preset.fontSizeSp * (h / 340f) * 0.7f // scaled
                                    typeface = when (preset.fontFamily) {
                                        "serif" -> Typeface.SERIF
                                        "monospace" -> Typeface.MONOSPACE
                                        "sans-serif-black" -> Typeface.create("sans-serif", Typeface.BOLD)
                                        else -> Typeface.DEFAULT_BOLD
                                    }
                                    textAlign = when (preset.textAlignment) {
                                        "LEFT" -> Paint.Align.LEFT
                                        "RIGHT" -> Paint.Align.RIGHT
                                        else -> Paint.Align.CENTER
                                    }
                                }

                                val linesToDraw = cue.lines.map { if (preset.allCaps) it.uppercase(Locale.US) else it }
                                val textHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
                                val lineSpacing = 4f * (h / 340f)

                                // Safe position layout clamp
                                val targetVerticalY = h * (preset.verticalPositionPercent / 100f)
                                val finalY = targetVerticalY.coerceIn(h * 0.12f, h * 0.82f)

                                linesToDraw.forEachIndexed { idx, lineText ->
                                    val currentLineY = finalY + (idx * (textHeight + lineSpacing)) - ((linesToDraw.size - 1) * textHeight / 2f)

                                    // 1. Draw rounded background highlight box if specified
                                    val bgHex = preset.backgroundColor
                                    if (bgHex != "#00000000" && bgHex.isNotBlank()) {
                                        val textWidth = textPaint.measureText(lineText)
                                        val paddingX = 8f * (w / 191f)
                                        val paddingY = 4f * (h / 340f)

                                        val rectLeft = when (preset.textAlignment) {
                                            "LEFT" -> 16f
                                            "RIGHT" -> w - textWidth - 16f
                                            else -> (w - textWidth) / 2f
                                        }

                                        drawRect(
                                            color = Color(android.graphics.Color.parseColor(bgHex)),
                                            topLeft = Offset(rectLeft - paddingX, currentLineY + textPaint.fontMetrics.ascent - paddingY),
                                            size = Size(textWidth + (paddingX * 2), textHeight + (paddingY * 2))
                                        )
                                    }

                                    // 2. Draw Stroked Text Outline
                                    val strokeHex = preset.strokeColor
                                    if (preset.strokeWidthDp > 0 && strokeHex != "#00000000" && strokeHex.isNotBlank()) {
                                        textPaint.style = Paint.Style.STROKE
                                        textPaint.strokeWidth = preset.strokeWidthDp * (h / 340f)
                                        textPaint.color = android.graphics.Color.parseColor(strokeHex)

                                        val strokeX = when (preset.textAlignment) {
                                            "LEFT" -> 16f
                                            "RIGHT" -> w - 16f
                                            else -> w / 2f
                                        }
                                        nativeCanvas.drawText(lineText, strokeX, currentLineY, textPaint)
                                    }

                                    // 3. Draw Shadow
                                    val shadowHex = preset.shadowColor
                                    if (preset.shadowRadiusDp > 0 && shadowHex != "#00000000" && shadowHex.isNotBlank()) {
                                        textPaint.style = Paint.Style.FILL
                                        textPaint.setShadowLayer(
                                            preset.shadowRadiusDp * (h / 340f),
                                            2f,
                                            2f,
                                            android.graphics.Color.parseColor(shadowHex)
                                        )
                                    }

                                    // 4. Draw Karaoke Word-by-Word emphasized highlight
                                    textPaint.style = Paint.Style.FILL
                                    val fillX = when (preset.textAlignment) {
                                        "LEFT" -> 16f
                                        "RIGHT" -> w - 16f
                                        else -> w / 2f
                                    }

                                    if (preset.animationType == "word-highlight" && activeWordIndex != -1) {
                                        // Dynamic highlight of currently active word
                                        textPaint.color = android.graphics.Color.parseColor(preset.textColor)
                                        textPaint.clearShadowLayer()

                                        // To draw karaoke style, we decompose the line into words
                                        val lineWords = lineText.split(" ")
                                        var wordXOffset = if (preset.textAlignment == "CENTER") {
                                            (w - textPaint.measureText(lineText)) / 2f
                                        } else if (preset.textAlignment == "LEFT") {
                                            16f
                                        } else {
                                            w - textPaint.measureText(lineText) - 16f
                                        }

                                        lineWords.forEach { word ->
                                            val isWordActive = cue.wordTimings.getOrNull(activeWordIndex)?.word?.equals(word, ignoreCase = true) == true
                                            textPaint.color = if (isWordActive) {
                                                android.graphics.Color.parseColor(preset.wordEmphasisColor)
                                            } else {
                                                android.graphics.Color.parseColor(preset.textColor)
                                            }

                                            nativeCanvas.drawText(word, wordXOffset, currentLineY, textPaint)
                                            wordXOffset += textPaint.measureText("$word ")
                                        }
                                    } else {
                                        // Standard full line text draw
                                        textPaint.color = android.graphics.Color.parseColor(preset.textColor)
                                        nativeCanvas.drawText(lineText, fillX, currentLineY, textPaint)
                                    }
                                }
                            }
                        }
                    }
                }

                // Play / Pause floating indicator
                IconButton(
                    onClick = {
                        val p = exoPlayer ?: return@IconButton
                        if (p.isPlaying) p.pause() else p.play()
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Playback Control",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Time Slider and Controls
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
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(imageVector = Icons.Default.Replay, contentDescription = "Restart", tint = Color.White)
                }

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = TimeUtils.formatMs(currentPositionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
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
                        exoPlayer?.seekTo(currentPositionMs)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
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
                    fontSize = 10.sp
                )
            }
        }
    }
}
