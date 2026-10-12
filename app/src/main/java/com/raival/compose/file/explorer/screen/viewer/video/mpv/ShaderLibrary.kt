package com.raival.compose.file.explorer.screen.viewer.video.mpv

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.raival.compose.file.explorer.R
import java.io.File
import java.util.UUID

/** What the video player renders with. Chosen by the user only. */
sealed interface ShaderSelection {
    data object Off : ShaderSelection
    data class Anime4KPreset(val mode: Anime4K.Mode, val level: Anime4K.Level) : ShaderSelection
    data class Custom(val presetId: String) : ShaderSelection

    /** One of [BundledShaders.presets] (ArtCNN, Anime4K GAN). */
    data class Bundled(val presetId: String) : ShaderSelection

    fun encode(): String = when (this) {
        Off -> "off"
        is Anime4KPreset -> "a4k:${mode.name}:${level.name}"
        is Custom -> "custom:$presetId"
        is Bundled -> "bundled:$presetId"
    }

    companion object {
        fun decode(value: String?): ShaderSelection {
            val parts = value?.split(":") ?: return Off
            return when (parts.firstOrNull()) {
                "a4k" -> {
                    val mode = parts.getOrNull(1)?.let { runCatching { Anime4K.Mode.valueOf(it) }.getOrNull() }
                    val level = parts.getOrNull(2)?.let { runCatching { Anime4K.Level.valueOf(it) }.getOrNull() }
                    if (mode != null && level != null && level != Anime4K.Level.OFF) {
                        Anime4KPreset(mode, level)
                    } else {
                        Off
                    }
                }

                "custom" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { Custom(it) } ?: Off
                "bundled" -> parts.getOrNull(1)?.takeIf { BundledShaders.preset(it) != null }
                    ?.let { Bundled(it) } ?: Off
                else -> Off
            }
        }
    }
}

/** A shader file usable in presets: bundled ("builtin:Name.glsl") or imported ("user:Name.glsl"). */
data class ShaderRef(val id: String) {
    val isBuiltIn: Boolean get() = id.startsWith(BUILT_IN)
    val fileName: String get() = id.substringAfter(':')
    val displayName: String get() = fileName.removeSuffix(".glsl").removeSuffix(".hook")

    companion object {
        const val BUILT_IN = "builtin:"
        const val USER = "user:"
    }
}

/** A user-defined chain of shaders, applied in order. */
data class ShaderPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val shaders: List<String>
)

/**
 * Bundled Anime4K shaders, user-imported shader files and user presets.
 *
 * Imported files live in the app's private storage, so presets keep working even if the
 * original file is moved or deleted.
 */
class ShaderLibrary(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("video_player", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val userDir: File
        get() = File(appContext.filesDir, "mpv/shaders/user").apply { mkdirs() }

    fun builtInShaders(): List<ShaderRef> =
        BundledShaders.allFiles.map { ShaderRef(ShaderRef.BUILT_IN + it) }

    fun userShaders(): List<ShaderRef> =
        userDir.listFiles { f -> f.isFile }
            ?.sortedBy { it.name.lowercase() }
            ?.map { ShaderRef(ShaderRef.USER + it.name) }
            .orEmpty()

    /** Copies a shader file into the library. */
    fun importShader(uri: Uri): Result<ShaderRef> = runCatching {
        val resolver = appContext.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "shader.glsl"

        val bytes = resolver.openInputStream(uri)?.use { input ->
            val data = input.readBytes()
            require(data.size <= MAX_SHADER_BYTES) { "File is larger than 4 MB" }
            data
        } ?: error("Cannot read $displayName")

        val text = bytes.toString(Charsets.UTF_8)
        require(text.contains("//!HOOK") || text.contains("//!DESC")) {
            "$displayName is not an mpv user shader (no //!HOOK)"
        }
        // libplacebo's OpenGL ES backend has no compute support: such passes would silently
        // never run. Say so instead of importing a shader that does nothing.
        require(!text.lines().any { it.trimStart().startsWith("//!COMPUTE") }) {
            appContext.getString(R.string.video_shader_compute_unsupported, displayName)
        }

        var name = displayName.replace(Regex("[^A-Za-z0-9._()\\- ]"), "_").trim()
        if (!name.endsWith(".glsl") && !name.endsWith(".hook")) name += ".glsl"
        var target = File(userDir, name)
        var n = 2
        while (target.exists() && !target.readBytes().contentEquals(bytes)) {
            target = File(userDir, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.'))
            n++
        }
        target.writeBytes(bytes)
        ShaderRef(ShaderRef.USER + target.name)
    }

    fun deleteUserShader(ref: ShaderRef) {
        if (!ref.isBuiltIn) File(userDir, ref.fileName).delete()
    }

    /** The file handed to mpv: bundled shaders are installed sanitized; user shaders get a sanitized copy. */
    fun fileOf(ref: ShaderRef): File? {
        if (ref.isBuiltIn) return File(BundledShaders.install(appContext), ref.fileName).takeIf { it.isFile }
        val original = File(userDir, ref.fileName).takeIf { it.isFile } ?: return null
        val sanitizedDir = File(appContext.filesDir, "mpv/shaders/user-gles-s${ShaderSanitizer.VERSION}").apply { mkdirs() }
        val sanitized = File(sanitizedDir, ref.fileName)
        if (!sanitized.isFile || sanitized.lastModified() < original.lastModified()) {
            runCatching { sanitized.writeText(ShaderSanitizer.sanitize(original.readText())) }
                .onFailure { return original }
        }
        return sanitized
    }

    // --- Presets ----------------------------------------------------------------------------

    fun presets(): List<ShaderPreset> {
        val json = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<ShaderPreset>>(json, object : TypeToken<List<ShaderPreset>>() {}.type)
        }.getOrNull().orEmpty()
    }

    fun savePreset(preset: ShaderPreset) {
        val list = presets().filterNot { it.id == preset.id } + preset
        prefs.edit { putString(KEY_PRESETS, gson.toJson(list)) }
    }

    fun deletePreset(id: String) {
        prefs.edit { putString(KEY_PRESETS, gson.toJson(presets().filterNot { it.id == id })) }
    }

    // --- Selection ----------------------------------------------------------------------------

    var selection: ShaderSelection
        get() {
            prefs.getString(KEY_SELECTION, null)?.let { return ShaderSelection.decode(it) }
            // Settings from the first player version (Anime4K mode + level).
            return ShaderSelection.decode(
                "a4k:${prefs.getString("anime4k_mode", "A")}:${prefs.getString("anime4k_level", "OFF")}"
            )
        }
        set(value) = prefs.edit { putString(KEY_SELECTION, value.encode()) }

    /** Absolute shader paths for [selection], in order. Missing files are skipped. */
    fun shaderFiles(selection: ShaderSelection): List<File> = when (selection) {
        ShaderSelection.Off -> emptyList()
        is ShaderSelection.Anime4KPreset -> {
            val dir = BundledShaders.install(appContext)
            Anime4K.shaderChain(selection.mode, selection.level).map { File(dir, it) }
        }

        is ShaderSelection.Bundled -> BundledShaders.preset(selection.presetId)
            ?.let { BundledShaders.files(appContext, it) }
            .orEmpty()

        is ShaderSelection.Custom -> presets().firstOrNull { it.id == selection.presetId }
            ?.shaders?.mapNotNull { fileOf(ShaderRef(it)) }
            .orEmpty()
    }

    private companion object {
        const val KEY_PRESETS = "custom_shader_presets"
        const val KEY_SELECTION = "shader_selection"
        const val MAX_SHADER_BYTES = 4 * 1024 * 1024
    }
}
