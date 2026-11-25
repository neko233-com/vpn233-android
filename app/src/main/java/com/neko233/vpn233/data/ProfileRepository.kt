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
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ProfileSaveResult(
    val item: ProfileItem,
    val created: Boolean,
    val contentChanged: Boolean
)

/**
 * Pure filesystem-backed profile store separated from Android Context so JVM tests can verify
 * idempotent imports, metadata persistence, and archive extraction directly.
 */
internal class ProfileStorageEngine(
    filesDir: File,
    private val cacheDir: File,
    private val configValidator: (String) -> String,
    private val overrideValidator: (String, String) -> String = { dir, _ -> configValidator(dir) }
) {
    private val profilesRoot = File(filesDir, "profiles").apply { mkdirs() }
    private val metaFile = File(filesDir, "vpn233/profiles.json")
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

    fun readMergeYaml(id: String): String {
        val f = File(getProfileDir(id), "merge.yaml")
        return if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    fun saveMergeYaml(id: String, mergeYaml: String, baseOverrideJson: (String) -> String = {
        JSONObject().put("mergeYaml", it).toString()
    }): Result<ProfileItem> = runCatching {
        val existing = _profiles.value.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("配置不存在")
        val trimmed = mergeYaml.trim()
        val pDir = getProfileDir(id)
        if (trimmed.isNotEmpty()) {
            val err = overrideValidator(pDir.absolutePath, baseOverrideJson(trimmed))
            if (err.isNotBlank()) {
                throw IllegalArgumentException("Merge 覆写校验失败: $err")
            }
            File(pDir, "merge.yaml").writeText(trimmed, Charsets.UTF_8)
        } else {
            File(pDir, "merge.yaml").delete()
        }
        val updatedItem = existing.copy(hasMergeYaml = trimmed.isNotEmpty())
        synchronized(lock) {
            _profiles.value = _profiles.value.map { if (it.id == id) updatedItem else it }
            saveMeta()
        }
        updatedItem
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
                            updateMode = ProfileUpdateMode.fromName(o.optString("updateMode", ProfileUpdateMode.AUTO.name)),
                            customUserAgent = o.optString("customUserAgent", ""),
                            hasMergeYaml = File(getProfileDir(id), "merge.yaml").let { it.exists() && it.length() > 0L },
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
                put("updateMode", p.updateMode.name)
                put("customUserAgent", p.customUserAgent)
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
        java.nio.file.Files.move(
            tmp.toPath(),
            metaFile.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
    }

    fun setAutoUpdateMinutes(id: String, minutes: Int) {
        synchronized(lock) {
            _profiles.value = _profiles.value.map {
                if (it.id == id) it.copy(autoUpdateMinutes = minutes.coerceAtLeast(0)) else it
            }
            saveMeta()
        }
    }

    fun setProfileUpdateOptions(
        id: String,
        autoUpdateMinutes: Int,
        updateMode: ProfileUpdateMode,
        customUserAgent: String
    ): ProfileItem? {
        synchronized(lock) {
            var updatedItem: ProfileItem? = null
            _profiles.value = _profiles.value.map {
                if (it.id == id) {
                    it.copy(
                        autoUpdateMinutes = autoUpdateMinutes.coerceAtLeast(0),
                        updateMode = updateMode,
                        customUserAgent = customUserAgent.trim()
                    ).also { item -> updatedItem = item }
                } else it
            }
            saveMeta()
            return updatedItem
        }
    }

    fun findByIdOrName(idOrName: String): ProfileItem? =
        _profiles.value.firstOrNull { it.id == idOrName || it.name == idOrName }

    fun findByUrl(url: String): ProfileItem? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        return _profiles.value.firstOrNull { it.sourceType == ProfileSourceType.URL && it.url.trim() == trimmed }
    }

    fun setActiveProfile(id: String): Boolean {
        synchronized(lock) {
            if (_activeProfileId.value == id) return false
            if (_profiles.value.any { it.id == id }) {
                _activeProfileId.value = id
                saveMeta()
                return true
            }
            return false
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

    private fun resolveMatchedProfile(
        explicitId: String?,
        name: String,
        sourceType: ProfileSourceType,
        url: String,
        normalizedHash: String
    ): ProfileItem? {
        val current = _profiles.value
        if (!explicitId.isNullOrBlank()) {
            return current.firstOrNull { it.id == explicitId }
        }
        val trimmedUrl = url.trim()
        if (trimmedUrl.isNotEmpty()) {
            current.firstOrNull { it.url.trim() == trimmedUrl }?.let { return it }
        }
        val trimmedName = name.trim()
        if (trimmedName.isNotEmpty() && trimmedName !in GENERIC_IMPORT_NAMES) {
            current.firstOrNull { it.name == trimmedName && it.sourceType == sourceType }?.let { return it }
        }
        return current.firstOrNull { p ->
            val disk = readProfileYaml(p.id)
            disk.isNotEmpty() && sha256Hex(disk) == normalizedHash
        }
    }

    private fun disambiguateGenericName(desired: String): String {
        val base = desired.ifBlank { "配置" }
        val existingNames = _profiles.value.map { it.name }.toSet()
        if (base !in existingNames) return base
        var idx = 2
        while ("$base ($idx)" in existingNames) {
            idx++
        }
        return "$base ($idx)"
    }

    fun saveYamlContentWithResult(
        id: String?,
        name: String,
        yamlContent: String,
        sourceType: ProfileSourceType = ProfileSourceType.LOCAL,
        url: String = ""
    ): Result<ProfileSaveResult> = runCatching {
        val normalizedYaml = SubscriptionParser.normalizeToClashYaml(yamlContent)
        val newHash = sha256Hex(normalizedYaml)
        val matched = synchronized(lock) {
            resolveMatchedProfile(id, name, sourceType, url, newHash)
        }
        val targetId = matched?.id ?: id ?: UUID.randomUUID().toString()
        val stageDir = File(cacheDir, "stage_$targetId").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            File(stageDir, "config.yaml").writeText(normalizedYaml, Charsets.UTF_8)
            val err = configValidator(stageDir.absolutePath)
            if (err.isNotBlank()) {
                throw IllegalArgumentException("配置校验失败: $err")
            }
            val prevYaml = if (matched != null) readProfileYaml(matched.id) else ""
            val contentChanged = matched == null || sha256Hex(prevYaml) != newHash
            val destDir = getProfileDir(targetId)
            if (contentChanged || !File(destDir, "config.yaml").exists()) {
                File(destDir, "config.yaml").writeText(normalizedYaml, Charsets.UTF_8)
            }
            val resolvedName = when {
                name.isNotBlank() && matched != null -> name.trim()
                matched != null -> matched.name
                name.isNotBlank() -> disambiguateGenericName(name.trim())
                else -> disambiguateGenericName("配置")
            }
            val item = (matched ?: ProfileItem(
                id = targetId,
                name = resolvedName,
                sourceType = sourceType,
                url = url.trim()
            )).copy(
                name = resolvedName,
                url = url.trim().ifBlank { matched?.url ?: "" },
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
            ProfileSaveResult(item = item, created = matched == null, contentChanged = contentChanged)
        } finally {
            stageDir.deleteRecursively()
        }
    }

    fun upsertUrlPayload(
        trimmedUrl: String,
        resolvedName: String,
        body: String,
        existing: ProfileItem?,
        updateHours: Int,
        uploadBytes: Long,
        downloadBytes: Long,
        totalBytes: Long,
        expireEpochSec: Long
    ): ProfileSaveResult {
        val targetId = existing?.id ?: UUID.randomUUID().toString()
        val normalizedYaml = SubscriptionParser.normalizeToClashYaml(body)
        val newHash = sha256Hex(normalizedYaml)
        val stageDir = File(cacheDir, "stage_$targetId").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            File(stageDir, "config.yaml").writeText(normalizedYaml, Charsets.UTF_8)
            val err = configValidator(stageDir.absolutePath)
            if (err.isNotBlank()) {
                throw IllegalArgumentException("订阅校验失败: $err")
            }
            val prevYaml = if (existing != null) readProfileYaml(existing.id) else ""
            val contentChanged = existing == null || sha256Hex(prevYaml) != newHash
            val destDir = getProfileDir(targetId)
            if (contentChanged || !File(destDir, "config.yaml").exists()) {
                File(destDir, "config.yaml").writeText(normalizedYaml, Charsets.UTF_8)
            }
            val item = ProfileItem(
                id = targetId,
                name = resolvedName,
                sourceType = ProfileSourceType.URL,
                url = trimmedUrl,
                updatedAt = System.currentTimeMillis(),
                autoUpdateMinutes = if (updateHours > 0) updateHours * 60 else (existing?.autoUpdateMinutes ?: 0),
                updateMode = existing?.updateMode ?: ProfileUpdateMode.AUTO,
                customUserAgent = existing?.customUserAgent.orEmpty(),
                hasMergeYaml = existing?.hasMergeYaml ?: false,
                uploadBytes = uploadBytes,
                downloadBytes = downloadBytes,
                totalBytes = totalBytes,
                expireEpochSec = expireEpochSec
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
            return ProfileSaveResult(item = item, created = existing == null, contentChanged = contentChanged)
        } finally {
            stageDir.deleteRecursively()
        }
    }

    fun importBytesWithResult(bytes: ByteArray, fileName: String): List<ProfileSaveResult> {
        val lower = fileName.lowercase()
        val isZip = lower.endsWith(".cba") || lower.endsWith(".zip") ||
            (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())
        return if (isZip) {
            importArchiveWithResult(ByteArrayInputStream(bytes))
        } else {
            val text = bytes.toString(Charsets.UTF_8)
            val baseName = fileName.substringBeforeLast('.').ifBlank { "导入配置" }
            listOf(
                saveYamlContentWithResult(
                    id = null,
                    name = baseName,
                    yamlContent = text,
                    sourceType = ProfileSourceType.FILE_IMPORT
                ).getOrThrow()
            )
        }
    }

    private fun importArchiveWithResult(input: InputStream): List<ProfileSaveResult> {
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
        val imported = mutableListOf<ProfileSaveResult>()
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
            val res = saveYamlContentWithResult(
                id = null,
                name = candidateName.ifBlank { "导入配置" },
                yamlContent = yamlStr,
                sourceType = if (candidateUrl.isNotBlank()) ProfileSourceType.URL else ProfileSourceType.FILE_IMPORT,
                url = candidateUrl
            )
            res.getOrNull()?.let { saved ->
                val prefix = path.substringBeforeLast('/', "")
                if (prefix.isNotEmpty()) {
                    val pDir = getProfileDir(saved.item.id)
                    entries.forEach { (ep, ed) ->
                        if (ep.startsWith("$prefix/providers/")) {
                            val rel = ep.removePrefix("$prefix/")
                            val outFile = File(pDir, rel)
                            outFile.parentFile?.mkdirs()
                            outFile.writeBytes(ed)
                        } else if (ep == "$prefix/merge.yaml" && ed.isNotEmpty()) {
                            File(pDir, "merge.yaml").writeBytes(ed)
                        }
                    }
                }
                imported.add(saved)
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

    fun exportAllBackupZip(): File {
        val outDir = File(cacheDir, "exports").apply { mkdirs() }
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
                val merge = File(getProfileDir(p.id), "merge.yaml")
                if (merge.exists()) {
                    zos.putNextEntry(ZipEntry("profiles/${p.id}/merge.yaml"))
                    zos.write(merge.readBytes())
                    zos.closeEntry()
                }
            }
        }
        return outFile
    }

    companion object {
        private val GENERIC_IMPORT_NAMES = setOf("剪贴板配置", "ADB导入配置", "导入配置", "配置", "未命名配置")

        internal fun sha256Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

class ProfileRepository(private val context: Context) {
    private val storage = ProfileStorageEngine(
        filesDir = context.filesDir,
        cacheDir = context.cacheDir,
        configValidator = { dir -> MihomoBridge.validateConfig(dir) },
        overrideValidator = { dir, ov -> MihomoBridge.validateConfigWithOverride(dir, ov) }
    )
    private val autoUpdateLastAttemptMs = ConcurrentHashMap<String, Long>()

    val profiles: StateFlow<List<ProfileItem>> = storage.profiles
    val activeProfileId: StateFlow<String?> = storage.activeProfileId

    fun getProfileDir(id: String): File = storage.getProfileDir(id)
    fun getActiveProfileDir(): File? = storage.getActiveProfileDir()
    fun getActiveProfile(): ProfileItem? = storage.getActiveProfile()
    fun readProfileYaml(id: String): String = storage.readProfileYaml(id)
    fun readMergeYaml(id: String): String = storage.readMergeYaml(id)
    fun saveMergeYaml(id: String, mergeYaml: String, overrideBuilder: (String) -> String): Result<ProfileItem> =
        storage.saveMergeYaml(id, mergeYaml, overrideBuilder)
    fun setAutoUpdateMinutes(id: String, minutes: Int) = storage.setAutoUpdateMinutes(id, minutes)
    fun setProfileUpdateOptions(
        id: String,
        autoUpdateMinutes: Int,
        updateMode: ProfileUpdateMode,
        customUserAgent: String
    ): ProfileItem? = storage.setProfileUpdateOptions(id, autoUpdateMinutes, updateMode, customUserAgent)
    fun findByIdOrName(idOrName: String): ProfileItem? = storage.findByIdOrName(idOrName)
    fun setActiveProfile(id: String): Boolean = storage.setActiveProfile(id)
    fun renameProfile(id: String, newName: String) = storage.renameProfile(id, newName)
    fun deleteProfile(id: String) = storage.deleteProfile(id)
    fun exportAllBackupZip(): File = storage.exportAllBackupZip()

    suspend fun saveYamlContentWithResult(
        id: String?,
        name: String,
        yamlContent: String,
        sourceType: ProfileSourceType = ProfileSourceType.LOCAL,
        url: String = ""
    ): Result<ProfileSaveResult> = withContext(Dispatchers.IO) {
        storage.saveYamlContentWithResult(id, name, yamlContent, sourceType, url)
    }

    suspend fun saveYamlContent(
        id: String?,
        name: String,
        yamlContent: String,
        sourceType: ProfileSourceType = ProfileSourceType.LOCAL,
        url: String = ""
    ): Result<ProfileItem> = saveYamlContentWithResult(id, name, yamlContent, sourceType, url).map { it.item }

    suspend fun importFromUrlWithResult(
        rawUrl: String,
        customName: String = "",
        existingId: String? = null,
        runningProxyPort: Int = 0
    ): Result<ProfileSaveResult> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedUrl = rawUrl.trim()
            require(trimmedUrl.startsWith("http://", true) || trimmedUrl.startsWith("https://", true)) {
                "仅支持 http:// 或 https:// 订阅链接"
            }
            val existing = existingId?.let { eid -> profiles.value.firstOrNull { it.id == eid } }
                ?: storage.findByUrl(trimmedUrl)
            val updateMode = existing?.updateMode ?: ProfileUpdateMode.AUTO
            val ua = existing?.customUserAgent?.takeIf { it.isNotBlank() }
                ?: "vpn233/1.2 clash-verge/2.2 mihomo/1.19"

            val conn = openUrlWithRoutingPolicy(trimmedUrl, ua, updateMode, runningProxyPort)
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
            val resolvedName = customName.trim().ifBlank {
                existing?.name ?: headerName?.ifBlank { null } ?: urlHostName
            }

            storage.upsertUrlPayload(
                trimmedUrl = trimmedUrl,
                resolvedName = resolvedName,
                body = body,
                existing = existing,
                updateHours = updateHours,
                uploadBytes = userInfo.upload,
                downloadBytes = userInfo.download,
                totalBytes = userInfo.total,
                expireEpochSec = userInfo.expire
            )
        }
    }

    private fun openUrlWithRoutingPolicy(
        urlStr: String,
        userAgent: String,
        mode: ProfileUpdateMode,
        runningProxyPort: Int
    ): HttpURLConnection {
        val proxyCandidates: List<Proxy> = when (mode) {
            ProfileUpdateMode.DIRECT -> listOf(Proxy.NO_PROXY)
            ProfileUpdateMode.PROXY -> {
                if (runningProxyPort in 1024..65535) {
                    listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", runningProxyPort)))
                } else {
                    throw IllegalStateException("当前配置设为仅经代理更新，请先开启 vpn233 隧道")
                }
            }
            ProfileUpdateMode.AUTO -> buildList {
                if (runningProxyPort in 1024..65535) {
                    add(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", runningProxyPort)))
                }
                add(Proxy.NO_PROXY)
            }
        }
        var lastErr: Throwable? = null
        for (proxy in proxyCandidates) {
            try {
                val conn = (URL(urlStr).openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 25_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent)
                }
                conn.responseCode
                return conn
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        throw lastErr ?: IllegalStateException("连接订阅服务器失败")
    }

    suspend fun checkDueAutoUpdates(runningProxyPort: Int): Pair<Int, Boolean> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dueProfiles = profiles.value.filter { p ->
            if (p.url.isBlank() || p.autoUpdateMinutes <= 0) return@filter false
            val intervalMs = p.autoUpdateMinutes * 60_000L
            val lastTry = autoUpdateLastAttemptMs[p.id] ?: 0L
            (now - p.updatedAt) >= intervalMs && (now - lastTry) >= 300_000L
        }
        if (dueProfiles.isEmpty()) return@withContext (0 to false)
        var updatedCount = 0
        var activeChanged = false
        val activeId = activeProfileId.value
        for (p in dueProfiles) {
            autoUpdateLastAttemptMs[p.id] = now
            val res = importFromUrlWithResult(p.url, p.name, p.id, runningProxyPort)
            res.onSuccess { saveRes ->
                updatedCount++
                if (p.id == activeId && saveRes.contentChanged) activeChanged = true
            }
        }
        updatedCount to activeChanged
    }

    suspend fun importFromUrl(
        rawUrl: String,
        customName: String = "",
        existingId: String? = null
    ): Result<ProfileItem> = importFromUrlWithResult(rawUrl, customName, existingId).map { it.item }

    suspend fun importFromUriWithResult(uri: Uri): Result<List<ProfileSaveResult>> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = queryDisplayName(uri) ?: "imported.yaml"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("无法读取所选文件")
            storage.importBytesWithResult(bytes, fileName)
        }
    }

    suspend fun importFromUri(uri: Uri): Result<List<ProfileItem>> =
        importFromUriWithResult(uri).map { list -> list.map { it.item } }

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
                val list = storage.importBytesWithResult(f.readBytes(), f.name).map { it.item }
                imported.addAll(list)
                f.delete()
            }
        }
        imported
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
