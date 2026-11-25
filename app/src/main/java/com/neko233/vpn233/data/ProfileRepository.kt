package com.neko233.vpn233.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.neko233.vpn233.core.MihomoBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ProfileRepository(private val context: Context) {
    private val profilesRoot = File(context.filesDir, "profiles").apply { mkdirs() }
    private val metaFile = File(context.filesDir, "vpn233/profiles.json")
    private val lock = Any()

    private val _profiles = MutableStateFlow<List<ProfileItem>>(emptyList())
    val profiles: StateFlow<List<ProfileItem>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    init {
        metaFile.parentFile?.mkdirs()
        reloadFromDisk()
    }

    fun getProfileDir(id: String): File = File(profilesRoot, id).apply { mkdirs() }

    fun getActiveProfileDir(): File? {
        val id = _activeProfileId.value ?: return null
        val dir = File(profilesRoot, id)
        return if (File(dir, "config.yaml").exists()) dir else null
    }

    fun getActiveProfile(): ProfileItem? {
        val id = _activeProfileId.value ?: return null
        return _profiles.value.firstOrNull { it.id == id }
    }

    fun readProfileYaml(id: String): String {
        val f = File(getProfileDir(id), "config.yaml")
        return if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    fun reloadFromDisk() {
        synchronized(lock) {
            if (!metaFile.exists()) {
                _profiles.value = emptyList()
                _activeProfileId.value = null
                return
            }
            runCatching {
                val root = JSONObject(metaFile.readText(Charsets.UTF_8))
                val activeId = root.optString("activeId").ifBlank { null }
                val arr = root.optJSONArray("profiles") ?: JSONArray()
                val list = ArrayList<ProfileItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id")
                    if (id.isBlank() || !File(getProfileDir(id), "config.yaml").exists()) continue
                    list.add(
                        ProfileItem(
                            id = id,
                            name = o.optString("name", "未命名配置"),
                            sourceType = runCatching {
                                ProfileSourceType.valueOf(o.optString("sourceType", ProfileSourceType.LOCAL.name))
                            }.getOrDefault(ProfileSourceType.LOCAL),
                            url = o.optString("url", ""),
                            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                            autoUpdateMinutes = o.optInt("autoUpdateMinutes", 0),
                            uploadBytes = o.optLong("uploadBytes", -1L),
                            downloadBytes = o.optLong("downloadBytes", -1L),
                            totalBytes = o.optLong("totalBytes", -1L),
                            expireEpochSec = o.optLong("expireEpochSec", -1L)
                        )
                    )
                }
                _profiles.value = list
                _activeProfileId.value = when {
                    activeId != null && list.any { it.id == activeId } -> activeId
                    else -> list.firstOrNull()?.id
                }
            }
        }
    }

    private fun saveMeta() {
        val arr = JSONArray()
        for (p in _profiles.value) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("sourceType", p.sourceType.name)
                put("url", p.url)
                put("updatedAt", p.updatedAt)
                put("autoUpdateMinutes", p.autoUpdateMinutes)
                put("uploadBytes", p.uploadBytes)
                put("downloadBytes", p.downloadBytes)
                put("totalBytes", p.totalBytes)
                put("expireEpochSec", p.expireEpochSec)
            })
        }
        val root = JSONObject().apply {
            put("activeId", _activeProfileId.value ?: "")
            put("profiles", arr)
        }
        val tmp = File(metaFile.parentFile, "profiles.json.tmp")
        tmp.writeText(root.toString(2), Charsets.UTF_8)
        tmp.renameTo(metaFile)
    }

    fun setAutoUpdateMinutes(id: String, minutes: Int) {
        synchronized(lock) {
            _profiles.value = _profiles.value.map {
                if (it.id == id) it.copy(autoUpdateMinutes = minutes.coerceAtLeast(0)) else it
            }
            saveMeta()
        }
    }

    fun findByIdOrName(idOrName: String): ProfileItem? {
        return _profiles.value.firstOrNull { it.id == idOrName || it.name == idOrName }
    }

    fun setActiveProfile(id: String) {
        synchronized(lock) {
            if (_profiles.value.any { it.id == id }) {
                _activeProfileId.value = id
                saveMeta()
            }
        }
    }

    fun renameProfile(id: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        synchronized(lock) {
            _profiles.value = _profiles.value.map {
                if (it.id == id) it.copy(name = trimmed) else it
            }
            saveMeta()
        }
    }

    fun deleteProfile(id: String) {
        synchronized(lock) {
            File(profilesRoot, id).deleteRecursively()
            val updated = _profiles.value.filterNot { it.id == id }
            _profiles.value = updated
            if (_activeProfileId.value == id) {
                _activeProfileId.value = updated.firstOrNull()?.id
            }
            saveMeta()
        }
    }

    suspend fun saveYamlContent(
        id: String?,
        name: String,
        yamlContent: String,
        sourceType: ProfileSourceType = ProfileSourceType.LOCAL,
        url: String = ""
    ): Result<ProfileItem> = withContext(Dispatchers.IO) {
        runCatching {
            val targetId = id ?: UUID.randomUUID().toString()
            val stageDir = File(context.cacheDir, "stage_$targetId").apply {
                deleteRecursively()
                mkdirs()
            }
            try {
                val normalizedYaml = SubscriptionParser.normalizeToClashYaml(yamlContent)
                File(stageDir, "config.yaml").writeText(normalizedYaml, Charsets.UTF_8)
                val err = MihomoBridge.validateConfig(stageDir.absolutePath)
                if (err.isNotBlank()) {
                    throw IllegalArgumentException("配置校验失败: $err")
                }
                val destDir = getProfileDir(targetId)
                File(destDir, "config.yaml").writeText(normalizedYaml, Charsets.UTF_8)
                val existing = _profiles.value.firstOrNull { it.id == targetId }
                val item = (existing ?: ProfileItem(
                    id = targetId,
                    name = name.ifBlank { "配置 ${(_profiles.value.size + 1)}" },
                    sourceType = sourceType,
                    url = url
                )).copy(
                    name = name.ifBlank { existing?.name ?: "配置" },
                    updatedAt = System.currentTimeMillis()
                )
                synchronized(lock) {
                    val list = _profiles.value.toMutableList()
                    val idx = list.indexOfFirst { it.id == targetId }
                    if (idx >= 0) list[idx] = item else list.add(0, item)
                    _profiles.value = list
                    if (_activeProfileId.value == null) {
                        _activeProfileId.value = item.id
                    }
                    saveMeta()
                }
                item
            } finally {
                stageDir.deleteRecursively()
            }
        }
    }

    suspend fun importFromUrl(
        rawUrl: String,
        customName: String = "",
        existingId: String? = null
    ): Result<ProfileItem> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedUrl = rawUrl.trim()
            require(trimmedUrl.startsWith("http://", true) || trimmedUrl.startsWith("https://", true)) {
                "仅支持 http:// 或 https:// 订阅链接"
            }
            val conn = (URL(trimmedUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 25_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ClashMetaForAndroid/2.11.16.Meta")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("服务器返回 HTTP $code")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (body.isBlank()) throw IllegalStateException("订阅内容为空")

            val userInfo = parseUserInfo(conn.getHeaderField("Subscription-Userinfo"))
            val updateHours = conn.getHeaderField("Profile-Update-Interval")?.trim()?.toIntOrNull() ?: 0
            val headerName = parseContentDispositionName(conn.getHeaderField("Content-Disposition"))
            val urlHostName = runCatching { URL(trimmedUrl).host }.getOrDefault("订阅配置")
            val existing = existingId?.let { eid -> _profiles.value.firstOrNull { it.id == eid } }
            val resolvedName = customName.trim().ifBlank {
                existing?.name ?: headerName?.ifBlank { null } ?: urlHostName
            }

            val id = existingId ?: UUID.randomUUID().toString()
            val stageDir = File(context.cacheDir, "stage_$id").apply {
                deleteRecursively()
                mkdirs()
            }
            try {
                val normalizedYaml = SubscriptionParser.normalizeToClashYaml(body)
                File(stageDir, "config.yaml").writeText(normalizedYaml, Charsets.UTF_8)
                val err = MihomoBridge.validateConfig(stageDir.absolutePath)
                if (err.isNotBlank()) {
                    throw IllegalArgumentException("订阅校验失败: $err")
                }
                val destDir = getProfileDir(id)
                File(destDir, "config.yaml").writeText(normalizedYaml, Charsets.UTF_8)
                val item = ProfileItem(
                    id = id,
                    name = resolvedName,
                    sourceType = ProfileSourceType.URL,
                    url = trimmedUrl,
                    updatedAt = System.currentTimeMillis(),
                    autoUpdateMinutes = if (updateHours > 0) updateHours * 60 else (existing?.autoUpdateMinutes ?: 0),
                    uploadBytes = userInfo.upload,
                    downloadBytes = userInfo.download,
                    totalBytes = userInfo.total,
                    expireEpochSec = userInfo.expire
                )
                synchronized(lock) {
                    val list = _profiles.value.toMutableList()
                    val idx = list.indexOfFirst { it.id == id }
                    if (idx >= 0) list[idx] = item else list.add(0, item)
                    _profiles.value = list
                    if (_activeProfileId.value == null) {
                        _activeProfileId.value = item.id
                    }
                    saveMeta()
                }
                item
            } finally {
                stageDir.deleteRecursively()
            }
        }
    }

    suspend fun importFromUri(uri: Uri): Result<List<ProfileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = queryDisplayName(uri) ?: "imported.yaml"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("无法读取所选文件")
            importBytes(bytes, fileName)
        }
    }

    suspend fun importBytes(bytes: ByteArray, fileName: String): List<ProfileItem> = withContext(Dispatchers.IO) {
        val lower = fileName.lowercase()
        val isZip = lower.endsWith(".cba") || lower.endsWith(".zip") ||
            (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())
        if (isZip) {
            importArchive(ByteArrayInputStream(bytes))
        } else {
            val text = bytes.toString(Charsets.UTF_8)
            val baseName = fileName.substringBeforeLast('.').ifBlank { "导入配置" }
            val item = saveYamlContent(
                id = null,
                name = baseName,
                yamlContent = text,
                sourceType = ProfileSourceType.FILE_IMPORT
            ).getOrThrow()
            listOf(item)
        }
    }

    private suspend fun importArchive(input: InputStream): List<ProfileItem> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name.replace('\\', '/')] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val imported = mutableListOf<ProfileItem>()
        // Support Clash Meta .cba or generic zip backups containing config.yaml / *.yaml
        val yamlEntries = entries.entries.filter { (path, _) ->
            val p = path.lowercase()
            !p.contains("/providers/") && (p.endsWith(".yaml") || p.endsWith(".yml"))
        }
        val metaMap = parseOptionalProfilesMetadata(entries)
        for ((path, data) in yamlEntries) {
            val folderKey = path.substringBeforeLast('/', "").substringAfterLast('/')
            val meta = metaMap[folderKey]
            val candidateName = meta?.first ?: path.substringAfterLast('/').substringBeforeLast('.').let {
                if (it.equals("config", true) && folderKey.isNotBlank()) folderKey else it
            }
            val candidateUrl = meta?.second ?: ""
            val yamlStr = data.toString(Charsets.UTF_8)
            val res = saveYamlContent(
                id = null,
                name = candidateName.ifBlank { "导入配置" },
                yamlContent = yamlStr,
                sourceType = if (candidateUrl.isNotBlank()) ProfileSourceType.URL else ProfileSourceType.FILE_IMPORT,
                url = candidateUrl
            )
            res.getOrNull()?.let { item ->
                // Copy matching providers folder if present in archive
                val prefix = path.substringBeforeLast('/', "")
                if (prefix.isNotEmpty()) {
                    val pDir = getProfileDir(item.id)
                    entries.forEach { (ep, ed) ->
                        if (ep.startsWith("$prefix/providers/")) {
                            val rel = ep.removePrefix("$prefix/")
                            val outFile = File(pDir, rel)
                            outFile.parentFile?.mkdirs()
                            outFile.writeBytes(ed)
                        }
                    }
                }
                imported.add(item)
            }
        }
        if (imported.isEmpty()) {
            throw IllegalArgumentException("压缩包内未找到有效的 Clash YAML 配置文件")
        }
        return imported
    }

    private fun parseOptionalProfilesMetadata(entries: Map<String, ByteArray>): Map<String, Pair<String, String>> {
        val map = HashMap<String, Pair<String, String>>()
        entries.entries.firstOrNull { it.key.endsWith("profiles.json", true) }?.let { (_, bytes) ->
            runCatching {
                val root = JSONObject(bytes.toString(Charsets.UTF_8))
                val arr = root.optJSONArray("profiles") ?: return@runCatching
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id").ifBlank { o.optString("uuid") }
                    val name = o.optString("name")
                    val url = o.optString("source").ifBlank { o.optString("url") }
                    if (id.isNotBlank() && name.isNotBlank()) {
                        map[id] = name to url
                    }
                }
            }
        }
        return map
    }

    suspend fun scanDropInImports(): List<ProfileItem> = withContext(Dispatchers.IO) {
        val dropDir = context.getExternalFilesDir("import") ?: return@withContext emptyList()
        if (!dropDir.exists()) {
            dropDir.mkdirs()
            return@withContext emptyList()
        }
        val files = dropDir.listFiles()?.filter {
            it.isFile && (it.name.endsWith(".yaml", true) ||
                it.name.endsWith(".yml", true) ||
                it.name.endsWith(".zip", true))
        }.orEmpty()
        val imported = mutableListOf<ProfileItem>()
        for (f in files) {
            runCatching {
                val list = importBytes(f.readBytes(), f.name)
                imported.addAll(list)
                f.delete()
            }
        }
        imported
    }

    fun exportAllBackupZip(): File {
        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val outFile = File(outDir, "vpn233-backup.zip")
        ZipOutputStream(outFile.outputStream()).use { zos ->
            if (metaFile.exists()) {
                zos.putNextEntry(ZipEntry("profiles.json"))
                zos.write(metaFile.readBytes())
                zos.closeEntry()
            }
            for (p in _profiles.value) {
                val cfg = File(getProfileDir(p.id), "config.yaml")
                if (cfg.exists()) {
                    zos.putNextEntry(ZipEntry("profiles/${p.id}/config.yaml"))
                    zos.write(cfg.readBytes())
                    zos.closeEntry()
                }
            }
        }
        return outFile
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private data class UserInfo(
        val upload: Long = -1L,
        val download: Long = -1L,
        val total: Long = -1L,
        val expire: Long = -1L
    )

    private fun parseUserInfo(header: String?): UserInfo {
        if (header.isNullOrBlank()) return UserInfo()
        var up = -1L
        var down = -1L
        var total = -1L
        var expire = -1L
        header.split(';').forEach { part ->
            val kv = part.trim().split('=', limit = 2)
            if (kv.size == 2) {
                val v = kv[1].trim().toLongOrNull() ?: -1L
                when (kv[0].trim().lowercase()) {
                    "upload" -> up = v
                    "download" -> down = v
                    "total" -> total = v
                    "expire" -> expire = v
                }
            }
        }
        return UserInfo(up, down, total, expire)
    }

    private fun parseContentDispositionName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val utf8Match = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(header)
        if (utf8Match != null) {
            return runCatching { URLDecoder.decode(utf8Match.groupValues[1], "UTF-8") }.getOrNull()
        }
        val plainMatch = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)
        return plainMatch?.groupValues?.get(1)?.trim()
    }
}
