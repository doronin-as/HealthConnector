package ru.doronin.healthconnector

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Restores HealthConnector settings from the JSON file exported by the app.
 *
 * Android scoped storage intentionally prevents a freshly reinstalled app from
 * silently walking arbitrary Documents/Downloads files. We therefore do a
 * best-effort automatic lookup in locations the current installation can read
 * and fall back to ACTION_OPEN_DOCUMENT from PermissionGateActivity.
 */
object ConfigAutoRestore {
    const val PREF_AUTO_RESTORE_ATTEMPTED = "config_auto_restore_attempted_v1"
    const val DEFAULT_FILE_NAME = "healthconnector-config.json"
    private const val MAX_CONFIG_BYTES = 512 * 1024

    data class ParsedConfig(
        val endpoint: String,
        val days: Int,
        val backgroundSync: Boolean,
        val token: String?,
        val version: Int
    )

    data class RestoreResult(
        val restored: Boolean,
        val source: String? = null,
        val tokenRestored: Boolean = false,
        val message: String
    )

    private data class Candidate(
        val label: String,
        val modifiedAt: Long,
        val readText: () -> String?
    )

    suspend fun tryAutoRestore(context: Context): RestoreResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val existingEndpoint = prefs.getString("endpoint", "").orEmpty().trim()
        if (existingEndpoint.isNotBlank()) {
            return@withContext RestoreResult(
                restored = false,
                message = "Конфигурация уже настроена"
            )
        }

        val candidates = buildList {
            addAll(persistedUriCandidates(app))
            addAll(appFileCandidates(app))
            addAll(sharedFileCandidates(app))
            addAll(mediaStoreDownloadCandidates(app))
        }.sortedByDescending { it.modifiedAt }

        for (candidate in candidates) {
            val raw = runCatching { candidate.readText() }.getOrNull() ?: continue
            val parsed = runCatching { parse(raw) }.getOrNull() ?: continue
            apply(app, parsed)
            return@withContext RestoreResult(
                restored = true,
                source = candidate.label,
                tokenRestored = !parsed.token.isNullOrBlank(),
                message = "Конфигурация восстановлена из ${candidate.label}"
            )
        }

        RestoreResult(
            restored = false,
            message = "Автоматически доступный JSON-конфиг не найден"
        )
    }

    suspend fun importFromUri(context: Context, uri: Uri): RestoreResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val raw = readUriText(app, uri)
            ?: return@withContext RestoreResult(false, message = "Не удалось прочитать JSON")
        val parsed = runCatching { parse(raw) }.getOrElse { error ->
            return@withContext RestoreResult(
                false,
                source = uri.toString(),
                message = error.message ?: "Некорректный JSON-конфиг"
            )
        }
        apply(app, parsed)
        RestoreResult(
            restored = true,
            source = uri.toString(),
            tokenRestored = !parsed.token.isNullOrBlank(),
            message = "Конфигурация восстановлена"
        )
    }

    internal fun parse(raw: String): ParsedConfig {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES) {
            "JSON-конфиг слишком большой"
        }
        val json = JSONObject(raw)
        require(json.optString("format") == "HealthConnectorConfig") {
            "Это не конфигурация HealthConnector"
        }

        val version = json.optInt("version", 1).coerceAtLeast(1)
        val endpoint = json.optString("endpoint").trim()
        require(endpoint.isNotBlank()) { "В конфигурации отсутствует endpoint" }
        val safeEndpoint = EndpointSecurity.requireHttps(endpoint)

        val days = json.optInt("days", 7).coerceIn(1, 30)
        val backgroundSync = if (json.has("backgroundSync")) {
            json.optBoolean("backgroundSync", true)
        } else {
            true
        }

        // Legacy/user-created configuration may contain a token. Modern exports
        // deliberately omit it; if absent, an already configured secure token is
        // preserved instead of being cleared.
        val token = json.optString("token").trim().takeIf { it.isNotEmpty() }

        return ParsedConfig(
            endpoint = safeEndpoint,
            days = days,
            backgroundSync = backgroundSync,
            token = token,
            version = version
        )
    }

    private fun apply(context: Context, config: ParsedConfig) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("endpoint", config.endpoint)
            .putInt("days", config.days)
            .putBoolean(BackgroundSyncScheduler.PREF_ENABLED, config.backgroundSync)
            .putBoolean(PREF_AUTO_RESTORE_ATTEMPTED, true)
            .remove("token")
            .apply()

        if (!config.token.isNullOrBlank()) {
            SecureTokenStore(context).setToken(config.token)
        }
        BackgroundSyncScheduler.apply(context)
    }

    private fun persistedUriCandidates(context: Context): List<Candidate> =
        context.contentResolver.persistedUriPermissions.mapNotNull { permission ->
            if (!permission.isReadPermission) return@mapNotNull null
            val uri = permission.uri
            val name = queryDisplayName(context, uri) ?: uri.lastPathSegment.orEmpty()
            if (!looksLikeConfigName(name)) return@mapNotNull null
            Candidate(
                label = name.ifBlank { "сохранённый документ" },
                modifiedAt = Long.MAX_VALUE - 1,
                readText = { readUriText(context, uri) }
            )
        }

    private fun appFileCandidates(context: Context): List<Candidate> {
        val roots = listOfNotNull(
            context.filesDir,
            context.getExternalFilesDir(null),
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ).distinctBy { it.absolutePath }
        return roots.flatMap(::scanReadableDirectory)
    }

    @Suppress("DEPRECATION")
    private fun sharedFileCandidates(context: Context): List<Candidate> {
        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        )
        return roots.flatMap(::scanReadableDirectory)
    }

    private fun scanReadableDirectory(root: File): List<Candidate> {
        if (!root.exists() || !root.canRead() || !root.isDirectory) return emptyList()
        return runCatching {
            root.listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.isFile && it.canRead() && looksLikeConfigName(it.name) }
                .map { file ->
                    Candidate(
                        label = file.absolutePath,
                        modifiedAt = file.lastModified(),
                        readText = {
                            if (file.length() > MAX_CONFIG_BYTES) null
                            else file.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        }
                    )
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun mediaStoreDownloadCandidates(context: Context): List<Candidate> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.SIZE
        )

        return runCatching {
            resolver.query(
                collection,
                projection,
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("healthconnector-config%.json"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIndex).orEmpty()
                        if (!looksLikeConfigName(name)) continue
                        val size = cursor.getLong(sizeIndex)
                        if (size > MAX_CONFIG_BYTES) continue
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                        add(
                            Candidate(
                                label = "Downloads/$name",
                                modifiedAt = cursor.getLong(modifiedIndex) * 1000L,
                                readText = { readUriText(context, uri) }
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun readUriText(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytes(MAX_CONFIG_BYTES + 1)
            if (bytes.size > MAX_CONFIG_BYTES) return@runCatching null
            bytes.toString(Charsets.UTF_8)
        }
    }.getOrNull()

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.getString(0)
        }
    }.getOrNull()

    private fun looksLikeConfigName(name: String): Boolean {
        val normalized = name.trim().lowercase()
        return normalized == DEFAULT_FILE_NAME ||
            (normalized.startsWith("healthconnector-config") && normalized.endsWith(".json"))
    }
}
