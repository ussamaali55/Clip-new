package com.clipgenius.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipgenius.ai.state.ClipLayoutPlan
import com.clipgenius.ai.state.LayoutType
import com.clipgenius.ai.state.VerifiedClip
import java.util.Locale

/**
 * Phase 10 Manual Layout & Person Selection Configuration Dialog.
 * Toggles layout templates and handles checkboxes for "Pick who to show" multi-person pruning.
 */
@Composable
fun AdjustLayoutDialog(
    clip: VerifiedClip,
    layoutPlan: ClipLayoutPlan,
    onDismiss: () -> Unit,
    onSaveLayout: (LayoutType) -> Unit,
    onSaveSelectedPersons: (List<Int>) -> Unit
) {
    var selectedLayoutType by remember { mutableStateOf(clip.layoutType) }
    
    // Checkboxes map for each person profile
    val personSelections = remember {
        mutableStateMapOf<Int, Boolean>().apply {
            layoutPlan.persons.forEach { p ->
                put(p.personId, clip.selectedPersonIds.isEmpty() || clip.selectedPersonIds.contains(p.personId))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Configure Video Layout",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = clip.originalCandidate.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Section 1: Template Selection
                Text(
                    text = "Layout Template",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                val layoutsList = listOf(
                    Triple(LayoutType.AUTO, "Auto", "Automatically select layout based on face count"),
                    Triple(LayoutType.SINGLE, "Single Speaker Crop", "Phase 9 horizontal center follow"),
                    Triple(LayoutType.SINGLE_SPEAKER_FOLLOW, "Single Active Speaker Follow", "Camera follows only active speaker"),
                    Triple(LayoutType.TWO_PERSON_STACKED, "2-Person Stacked", "Top/Bottom split screen with speaker focus"),
                    Triple(LayoutType.THREE_PERSON_GRID, "3-Person Adaptive Grid", "2 on top, 1 wide cell below"),
                    Triple(LayoutType.FOUR_PERSON_GRID, "4-Person Grid", "Even 2x2 grid for four speakers")
                )

                layoutsList.forEach { (type, name, desc) ->
                    val isSelected = selectedLayoutType == type
                    Surface(
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { selectedLayoutType = type }
                            .border(
                                width = if (isSelected) 1.5.dp else 0.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .testTag("layout_option_${type.name}")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(text = name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(text = desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Section 2: checkboxes - Pick Who to Show
                Text(
                    text = "Pick Who to Show",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Deselect people to exclude them from the layout. The layout will recalculate automatically.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (layoutPlan.persons.isEmpty()) {
                    Text("No speakers detected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    layoutPlan.persons.forEach { p ->
                        val isChecked = personSelections[p.personId] ?: true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { personSelections[p.personId] = !isChecked }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { personSelections[p.personId] = it },
                                    modifier = Modifier.testTag("person_checkbox_${p.personId}")
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = p.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val durationSec = String.format(Locale.US, "%.1fs", p.totalVisibleMs / 1000f)
                                    val pct = String.format(Locale.US, "%.0f%%", p.visibleRatio * 100f)
                                    Text(
                                        text = "Visible for $durationSec ($pct of clip)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveLayout(selectedLayoutType)
                    val activeIds = personSelections.filter { it.value }.keys.toList()
                    onSaveSelectedPersons(activeIds)
                    onDismiss()
                },
                modifier = Modifier.testTag("apply_layout_adjust_button")
            ) {
                Text("Apply & Update Layout")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
