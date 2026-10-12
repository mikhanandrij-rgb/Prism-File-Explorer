package com.raival.compose.file.explorer.screen.viewer.video.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raival.compose.file.explorer.App.Companion.globalClass
import com.raival.compose.file.explorer.R
import com.raival.compose.file.explorer.screen.viewer.video.VideoPlayerInstance
import com.raival.compose.file.explorer.screen.viewer.video.model.VideoPlayerState
import com.raival.compose.file.explorer.screen.viewer.video.mpv.Anime4K
import com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderPreset
import com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderRef
import com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderSelection
import com.raival.compose.file.explorer.screen.viewer.video.mpv.UpscalerRanking

private val shaderMimeTypes = arrayOf("*/*")

@Composable
private fun rememberShaderImporter(
    instance: VideoPlayerInstance,
    onImported: () -> Unit
) = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
    if (uris.isEmpty()) return@rememberLauncherForActivityResult
    val (imported, errors) = instance.importShaders(uris)
    if (imported > 0) {
        globalClass.showMsg(globalClass.getString(R.string.video_shaders_imported, imported))
    }
    errors.firstOrNull()?.let { globalClass.showMsg(it) }
    onImported()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShaderSheet(
    state: VideoPlayerState,
    instance: VideoPlayerInstance,
    onStatsChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var editing by remember { mutableStateOf<ShaderPreset?>(null) }
    var deleting by remember { mutableStateOf<ShaderPreset?>(null) }
    val selection = state.shaderSelection
    val anime4k = selection as? ShaderSelection.Anime4KPreset
    // Remembered so switching level keeps the last chosen mode.
    var lastMode by remember { mutableStateOf(anime4k?.mode ?: Anime4K.Mode.A) }
    val importer = rememberShaderImporter(instance) {}

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(stringResource(R.string.video_shaders_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(12.dp))

            SelectableRow(
                selected = selection == ShaderSelection.Off,
                title = stringResource(R.string.video_shaders_off),
                subtitle = stringResource(R.string.anime4k_level_off_desc),
                onClick = { instance.selectShaders(ShaderSelection.Off) }
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            if (!state.gpuSupportsFloat) {
                Text(
                    text = stringResource(R.string.video_gpu_no_float),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // --- Ranked neural upscalers -------------------------------------------------
            Text(stringResource(R.string.upscalers_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.upscalers_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
            )
            UpscalerRanking.entries.forEach { entry ->
                UpscalerRow(
                    entry = entry,
                    selected = selection == entry.selection,
                    onClick = { instance.selectShaders(entry.selection) }
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // --- Anime4K ------------------------------------------------------------------
            Text(stringResource(R.string.anime4k_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            val levels = Anime4K.Level.entries.filter { it != Anime4K.Level.OFF }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                levels.forEachIndexed { index, level ->
                    SegmentedButton(
                        selected = anime4k?.level == level,
                        onClick = { instance.setAnime4K(lastMode, level) },
                        shape = SegmentedButtonDefaults.itemShape(index, levels.size),
                        label = { Text(stringResource(level.labelRes()), maxLines = 1) }
                    )
                }
            }
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource((anime4k?.level ?: Anime4K.Level.FAST).descriptionRes()),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(8.dp))
            Anime4K.Mode.entries.forEach { mode ->
                SelectableRow(
                    selected = (anime4k?.mode ?: lastMode) == mode,
                    enabled = anime4k != null,
                    title = stringResource(mode.labelRes()),
                    subtitle = stringResource(mode.descriptionRes()),
                    onClick = {
                        lastMode = mode
                        anime4k?.let { instance.setAnime4K(mode, it.level) }
                    }
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // --- Custom presets ------------------------------------------------------------
            Text(stringResource(R.string.video_shaders_custom), style = MaterialTheme.typography.titleMedium)
            if (state.customPresets.isEmpty()) {
                Text(
                    stringResource(R.string.video_shaders_custom_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            state.customPresets.sortedBy { it.name.lowercase() }.forEach { preset ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SelectableRow(
                            selected = (selection as? ShaderSelection.Custom)?.presetId == preset.id,
                            title = preset.name,
                            subtitle = preset.shaders.joinToString(" → ") { ShaderRef(it).displayName },
                            onClick = { instance.selectShaders(ShaderSelection.Custom(preset.id)) }
                        )
                    }
                    IconButton(onClick = { editing = preset }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.video_shaders_edit))
                    }
                    IconButton(onClick = { deleting = preset }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { editing = ShaderPreset(name = "", shaders = emptyList()) }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.video_shaders_new_preset))
                }
                OutlinedButton(onClick = { importer.launch(shaderMimeTypes) }) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.video_shaders_import))
                }
            }

            Spacer(Modifier.size(12.dp))
            Text(
                text = stringResource(R.string.anime4k_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.video_stats), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.video_stats_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = state.showStats, onCheckedChange = onStatsChange)
            }
        }
    }

    editing?.let { preset ->
        PresetEditorSheet(
            initial = preset,
            instance = instance,
            onSave = {
                instance.savePreset(it)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    deleting?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.video_shaders_delete_preset)) },
            text = { Text(preset.name) },
            confirmButton = {
                TextButton(onClick = {
                    instance.deletePreset(preset.id)
                    deleting = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetEditorSheet(
    initial: ShaderPreset,
    instance: VideoPlayerInstance,
    onSave: (ShaderPreset) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    val chain = remember { mutableStateListOf<String>().apply { addAll(initial.shaders) } }
    // Bumped after imports/deletions so the shader lists are re-read.
    var libraryVersion by remember { mutableIntStateOf(0) }
    val userShaders = remember(libraryVersion) { instance.userShaders() }
    val builtInShaders = remember { instance.builtInShaders() }
    val importer = rememberShaderImporter(instance) { libraryVersion++ }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                stringResource(
                    if (initial.name.isEmpty()) R.string.video_shaders_new_preset else R.string.video_shaders_edit
                ),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.size(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.video_shaders_preset_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.size(16.dp))
            Text(stringResource(R.string.video_shaders_chain), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.video_shaders_chain_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
            if (chain.isEmpty()) {
                Text(
                    stringResource(R.string.video_shaders_chain_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            chain.forEachIndexed { index, id ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${index + 1}. ${ShaderRef(id).displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(enabled = index > 0, onClick = {
                        chain.add(index - 1, chain.removeAt(index))
                    }) { Icon(Icons.Default.ArrowUpward, contentDescription = null) }
                    IconButton(enabled = index < chain.lastIndex, onClick = {
                        chain.add(index + 1, chain.removeAt(index))
                    }) { Icon(Icons.Default.ArrowDownward, contentDescription = null) }
                    IconButton(onClick = { chain.removeAt(index) }) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.video_shaders_my_files),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { importer.launch(shaderMimeTypes) }) {
                    Text(stringResource(R.string.video_shaders_import))
                }
            }
            if (userShaders.isEmpty()) {
                Text(
                    stringResource(R.string.video_shaders_my_files_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
            userShaders.forEach { ref ->
                ShaderLibraryRow(
                    ref = ref,
                    onAdd = { chain.add(ref.id) },
                    onDelete = {
                        instance.deleteUserShader(ref)
                        chain.removeAll { it == ref.id }
                        libraryVersion++
                    }
                )
            }

            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.video_shaders_builtin), style = MaterialTheme.typography.titleMedium)
            builtInShaders.forEach { ref ->
                ShaderLibraryRow(ref = ref, onAdd = { chain.add(ref.id) }, onDelete = null)
            }

            Spacer(Modifier.size(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    enabled = name.isNotBlank() && chain.isNotEmpty(),
                    onClick = { onSave(initial.copy(name = name.trim(), shaders = chain.toList())) }
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

@Composable
private fun ShaderLibraryRow(ref: ShaderRef, onAdd: () -> Unit, onDelete: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAdd)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = stringResource(R.string.video_shaders_add),
            tint = colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = ref.displayName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp)
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

@Composable
internal fun SelectableRow(
    selected: Boolean,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colorScheme.onSurface else colorScheme.onSurface.copy(alpha = 0.38f)
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f)
                )
            }
        }
    }
}

internal fun Anime4K.Level.labelRes() = when (this) {
    Anime4K.Level.OFF -> R.string.anime4k_level_off
    Anime4K.Level.FAST -> R.string.anime4k_level_fast
    Anime4K.Level.BALANCED -> R.string.anime4k_level_balanced
    Anime4K.Level.QUALITY -> R.string.anime4k_level_quality
    Anime4K.Level.ULTRA -> R.string.anime4k_level_ultra
}

internal fun Anime4K.Level.descriptionRes() = when (this) {
    Anime4K.Level.OFF -> R.string.anime4k_level_off_desc
    Anime4K.Level.FAST -> R.string.anime4k_level_fast_desc
    Anime4K.Level.BALANCED -> R.string.anime4k_level_balanced_desc
    Anime4K.Level.QUALITY -> R.string.anime4k_level_quality_desc
    Anime4K.Level.ULTRA -> R.string.anime4k_level_ultra_desc
}

internal fun Anime4K.Mode.labelRes() = when (this) {
    Anime4K.Mode.A -> R.string.anime4k_mode_a
    Anime4K.Mode.B -> R.string.anime4k_mode_b
    Anime4K.Mode.C -> R.string.anime4k_mode_c
    Anime4K.Mode.AA -> R.string.anime4k_mode_aa
    Anime4K.Mode.BB -> R.string.anime4k_mode_bb
    Anime4K.Mode.CA -> R.string.anime4k_mode_ca
}

internal fun Anime4K.Mode.descriptionRes() = when (this) {
    Anime4K.Mode.A -> R.string.anime4k_mode_a_desc
    Anime4K.Mode.B -> R.string.anime4k_mode_b_desc
    Anime4K.Mode.C -> R.string.anime4k_mode_c_desc
    Anime4K.Mode.AA -> R.string.anime4k_mode_aa_desc
    Anime4K.Mode.BB -> R.string.anime4k_mode_bb_desc
    Anime4K.Mode.CA -> R.string.anime4k_mode_ca_desc
}

/** One ranked upscaler: name, recommended badge, description and quality / load meters. */
@Composable
private fun UpscalerRow(entry: UpscalerRanking.Entry, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (entry.recommended) {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            text = stringResource(R.string.upscalers_recommended),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            upscalerDescription(entry.key)?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 2.dp)) {
                Meter(stringResource(R.string.upscalers_quality), entry.quality, colorScheme.primary)
                Meter(stringResource(R.string.upscalers_load), entry.load, colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun Meter(label: String, value: Int, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label ",
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant
        )
        Text(
            text = "●".repeat(value) + "○".repeat(5 - value),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

private fun upscalerDescription(key: String): Int? = when (key) {
    "artcnn_c4f32_ds" -> R.string.upscaler_artcnn_c4f32_ds_desc
    "artcnn_c4f32" -> R.string.upscaler_artcnn_c4f32_desc
    "artcnn_c4f16_ds" -> R.string.upscaler_artcnn_c4f16_ds_desc
    "a4k_aa_ultra" -> R.string.upscaler_a4k_aa_ultra_desc
    "a4k_a_quality" -> R.string.upscaler_a4k_a_quality_desc
    "a4k_a_fast" -> R.string.upscaler_a4k_a_fast_desc
    "gan_x3_vl" -> R.string.upscaler_gan_x3_vl_desc
    "gan_x4_uul" -> R.string.upscaler_gan_x4_uul_desc
    else -> null
}
