package com.poc.camera.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.poc.camera.R
import com.poc.camera.camera.VideoLook

/**
 * Full-screen settings destination. Every control applies immediately - there is no
 * separate save step, matching the switch/segmented-choice semantics of each field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: CameraSettingsData,
    onSettingsChanged: (CameraSettingsData) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back_content_description),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SettingsSection(title = stringResource(R.string.settings_section_burst)) {
                Text(
                    text = stringResource(R.string.settings_burst_frame_count_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                BurstFrameCountControl(
                    selected = settings.burstFrameCount,
                    onSelected = { onSettingsChanged(settings.copy(burstFrameCount = it)) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_hdr_burst_label),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.settings_hdr_burst_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.hdrBurstEnabled,
                        onCheckedChange = { onSettingsChanged(settings.copy(hdrBurstEnabled = it)) },
                    )
                }
            }

            HorizontalDivider()

            SettingsSection(title = stringResource(R.string.settings_section_night)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_night_mode_label),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.settings_night_mode_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.nightModeEnabled,
                        onCheckedChange = { onSettingsChanged(settings.copy(nightModeEnabled = it)) },
                    )
                }
            }

            HorizontalDivider()

            SettingsSection(title = stringResource(R.string.settings_section_super_resolution)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_super_resolution_label),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.settings_super_resolution_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.superResolutionEnabled,
                        onCheckedChange = { onSettingsChanged(settings.copy(superResolutionEnabled = it)) },
                    )
                }
            }

            HorizontalDivider()

            SettingsSection(title = stringResource(R.string.settings_section_merged_photos)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_apply_finishing_label),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.settings_apply_finishing_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.applyFinishingToMergedPhotos,
                        onCheckedChange = { onSettingsChanged(settings.copy(applyFinishingToMergedPhotos = it)) },
                    )
                }
            }

            HorizontalDivider()

            SettingsSection(title = stringResource(R.string.settings_section_comparison)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_save_comparison_pair_label),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.settings_save_comparison_pair_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.saveComparisonPair,
                        onCheckedChange = { onSettingsChanged(settings.copy(saveComparisonPair = it)) },
                    )
                }
            }

            HorizontalDivider()

            SettingsSection(title = stringResource(R.string.settings_section_rendering)) {
                Text(
                    text = stringResource(R.string.settings_finishing_preset_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                FinishingPresetControl(
                    selected = settings.finishingPreset,
                    onSelected = { onSettingsChanged(settings.copy(finishingPreset = it)) },
                )
                Text(
                    text = stringResource(finishingPresetDescription(settings.finishingPreset)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            SettingsSection(title = stringResource(R.string.settings_section_cinematic)) {
                Text(
                    text = stringResource(R.string.settings_default_look_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                DefaultLookControl(
                    selected = settings.defaultCinematicLook,
                    onSelected = { onSettingsChanged(settings.copy(defaultCinematicLook = it)) },
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun BurstFrameCountControl(
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    val options = CameraSettingsData.ALLOWED_BURST_FRAME_COUNTS
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, frameCount ->
            val description = stringResource(
                R.string.settings_burst_frame_count_option_description,
                frameCount,
            )
            SegmentedButton(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = description },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                selected = selected == frameCount,
                onClick = { onSelected(frameCount) },
                label = { Text(text = frameCount.toString()) },
                icon = {},
            )
        }
    }
}

@Composable
private fun FinishingPresetControl(
    selected: FinishingPreset,
    onSelected: (FinishingPreset) -> Unit,
) {
    val options = FinishingPreset.entries.toList()
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, preset ->
            SegmentedButton(
                modifier = Modifier.heightIn(min = 48.dp),
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                selected = selected == preset,
                onClick = { onSelected(preset) },
                label = { Text(text = stringResource(finishingPresetLabel(preset))) },
                icon = {},
            )
        }
    }
}

private fun finishingPresetLabel(preset: FinishingPreset): Int = when (preset) {
    FinishingPreset.Natural -> R.string.finishing_preset_natural
    FinishingPreset.Vivid -> R.string.finishing_preset_vivid
    FinishingPreset.Detail -> R.string.finishing_preset_detail
}

private fun finishingPresetDescription(preset: FinishingPreset): Int = when (preset) {
    FinishingPreset.Natural -> R.string.finishing_preset_natural_description
    FinishingPreset.Vivid -> R.string.finishing_preset_vivid_description
    FinishingPreset.Detail -> R.string.finishing_preset_detail_description
}

@Composable
private fun DefaultLookControl(
    selected: VideoLook,
    onSelected: (VideoLook) -> Unit,
) {
    val options = VideoLook.entries.toList()
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, look ->
            SegmentedButton(
                modifier = Modifier.heightIn(min = 48.dp),
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                selected = selected == look,
                onClick = { onSelected(look) },
                label = {
                    Text(
                        text = stringResource(
                            if (look == VideoLook.Neutral) R.string.look_neutral else R.string.look_cinematic,
                        ),
                    )
                },
                icon = {},
            )
        }
    }
}
