package com.neko233.vpn233.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.neko233.vpn233.BuildConfig
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
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
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
            replaceTextFile(File(pDir, "merge.yaml"), trimmed)
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

    fun validateGlobalMergeYaml(
        globalMergeYaml: String,
        overrideJsonBuilder: (String) -> String
    ): Result<Unit> = runCatching {
        val trimmed = globalMergeYaml.trim()
        if (trimmed.isEmpty()) return@runCatching
        val activeDir = getActiveProfileDir() ?: _profiles.value.firstOrNull()?.let { getProfileDir(it.id) }
        if (activeDir != null) {
            val err = overrideValidator(activeDir.absolutePath, overrideJsonBuilder(trimmed))
            if (err.isNotBlank()) throw IllegalArgumentException("全局 Merge 覆写校验失败: $err")
            return@runCatching
        }
        val probeDir = File(cacheDir, "probe_global_merge").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            File(probeDir, "config.yaml").writeText(
                "proxies:\n  - {name: Probe, type: socks5, server: 127.0.0.1, port: 1080}\nproxy-groups:\n  - {name: PROXY, type: select, proxies: [Probe, DIRECT]}\nrules:\n  - MATCH,PROXY\n",
                Charsets.UTF_8
            )
            val err = overrideValidator(probeDir.absolutePath, overrideJsonBuilder(trimmed))
            if (err.isNotBlank()) throw IllegalArgumentException("全局 Merge 覆写校验失败: $err")
        } finally {
            probeDir.deleteRecursively()
        }
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

    private fun replaceTextFile(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File.createTempFile(".vpn233-", ".tmp", file.parentFile)
        try {
            temp.writeText(text, Charsets.UTF_8)
            Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
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
            // Different subscriptions can temporarily serve identical YAML. Their URLs,
            // update schedules and future node lists must remain independent.
            return current.firstOrNull { it.url.trim() == trimmedUrl }
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
        url: String = "",
        autoUpdateMinutes: Int = -1,
        uploadBytes: Long = -1L,
        downloadBytes: Long = -1L,
        totalBytes: Long = -1L,
        expireEpochSec: Long = -1L,
        archiveProviders: Map<String, ByteArray> = emptyMap(),
        archivePrevalidated: Boolean = false
    ): Result<ProfileSaveResult> = synchronized(lock) { runCatching {
        val normalizedYaml = SubscriptionParser.normalizeToClashYaml(yamlContent)
        val newHash = sha256Hex(normalizedYaml)
        val matched = synchronized(lock) {
            resolveMatchedProfile(id, name, sourceType, url, newHash)
        }
        val targetId = matched?.id ?: id ?: UUID.randomUUID().toString()
        cacheDir.mkdirs()
        val stageDir = Files.createTempDirectory(cacheDir.toPath(), "stage_").toFile()
        try {
            File(stageDir, "config.yaml").writeText(normalizedYaml, Charsets.UTF_8)
            archiveProviders.forEach { (path, bytes) ->
                require(path.startsWith("providers/") && path.split('/').none { it == ".." || it == "." || it.isEmpty() })
                File(stageDir, path).apply { parentFile?.mkdirs(); writeBytes(bytes) }
            }
            val err = if (archivePrevalidated) "" else configValidator(stageDir.absolutePath)
            if (err.isNotBlank()) {
                throw IllegalArgumentException("配置校验失败: $err")
            }
            val prevYaml = if (matched != null) readProfileYaml(matched.id) else ""
            val contentChanged = matched == null || sha256Hex(prevYaml) != newHash
            val destDir = getProfileDir(targetId)
            if (contentChanged || !File(destDir, "config.yaml").exists()) {
                replaceTextFile(File(destDir, "config.yaml"), normalizedYaml)
            }
            archiveProviders.forEach { (path, bytes) ->
                File(destDir, path).apply { parentFile?.mkdirs(); writeBytes(bytes) }
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
                sourceType = if (url.isNotBlank()) ProfileSourceType.URL else (matched?.sourceType ?: sourceType),
                url = url.trim().ifBlank { matched?.url ?: "" },
                updatedAt = System.currentTimeMillis(),
                autoUpdateMinutes = if (autoUpdateMinutes >= 0) autoUpdateMinutes else (matched?.autoUpdateMinutes ?: 0),
                uploadBytes = if (uploadBytes >= 0L) uploadBytes else (matched?.uploadBytes ?: -1L),
                downloadBytes = if (downloadBytes >= 0L) downloadBytes else (matched?.downloadBytes ?: -1L),
                totalBytes = if (totalBytes >= 0L) totalBytes else (matched?.totalBytes ?: -1L),
                expireEpochSec = if (expireEpochSec >= 0L) expireEpochSec else (matched?.expireEpochSec ?: -1L)
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
    } }

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
    ): ProfileSaveResult = synchronized(lock) {
        val currentExisting = if (existing != null) {
            _profiles.value.firstOrNull { it.id == existing.id }
                ?: throw IllegalStateException("订阅已被删除")
        } else findByUrl(trimmedUrl)
        val targetId = currentExisting?.id ?: UUID.randomUUID().toString()
        val normalizedYaml = SubscriptionParser.normalizeToClashYaml(body)
        val newHash = sha256Hex(normalizedYaml)
        cacheDir.mkdirs()
        val stageDir = Files.createTempDirectory(cacheDir.toPath(), "stage_").toFile()
        try {
            File(stageDir, "config.yaml").writeText(normalizedYaml, Charsets.UTF_8)
            val err = configValidator(stageDir.absolutePath)
            if (err.isNotBlank()) {
                throw IllegalArgumentException("订阅校验失败: $err")
            }
            val prevYaml = if (currentExisting != null) readProfileYaml(currentExisting.id) else ""
            val contentChanged = currentExisting == null || sha256Hex(prevYaml) != newHash
            val destDir = getProfileDir(targetId)
            if (contentChanged || !File(destDir, "config.yaml").exists()) {
                replaceTextFile(File(destDir, "config.yaml"), normalizedYaml)
            }
            val item = ProfileItem(
                id = targetId,
                name = resolvedName,
                sourceType = ProfileSourceType.URL,
                url = trimmedUrl,
                updatedAt = System.currentTimeMillis(),
                autoUpdateMinutes = currentExisting?.autoUpdateMinutes
                    ?: if (updateHours > 0) (updateHours.toLong() * 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
                updateMode = currentExisting?.updateMode ?: ProfileUpdateMode.AUTO,
                customUserAgent = currentExisting?.customUserAgent.orEmpty(),
                hasMergeYaml = currentExisting?.hasMergeYaml ?: false,
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
            ProfileSaveResult(item = item, created = currentExisting == null, contentChanged = contentChanged)
        } finally {
            stageDir.deleteRecursively()
        }
    }

    fun importBytesWithResult(bytes: ByteArray, fileName: String): List<ProfileSaveResult> {
        require(bytes.size <= ArchiveFiles.MAX_INPUT_BYTES) { "导入文件超过大小限制" }
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

    private data class ArchiveMetadata(val activeId: String?, val profiles: Map<String, JSONObject>)
    private data class ArchiveProfile(
        val sourceId: String,
        val name: String,
        val yaml: String,
        val metadata: JSONObject?,
        val providers: Map<String, ByteArray>,
        val merge: String?
    )

    private fun importArchiveWithResult(input: InputStream): List<ProfileSaveResult> = synchronized(lock) {
        val entries = ArchiveFiles.readZip(input)
        val metadata = parseOptionalProfilesMetadata(entries)
        val yamlEntries = entries.entries.filter { (path, _) ->
            val parts = path.lowercase().split('/')
            val name = parts.last()
            "providers" !in parts && name != "merge.yaml" && name != "merge.yml" &&
                (name.endsWith(".yaml") || name.endsWith(".yml"))
        }
        require(yamlEntries.isNotEmpty()) { "压缩包内未找到 Clash YAML 配置文件" }

        val candidates = yamlEntries.map { (path, data) ->
            val prefix = path.substringBeforeLast('/', "")
            val folderKey = prefix.substringAfterLast('/')
            val meta = metadata.profiles[folderKey]
            val name = meta?.optString("name")?.takeIf { it.isNotBlank() }
                ?: path.substringAfterLast('/').substringBeforeLast('.').let {
                    if (it.equals("config", true) && folderKey.isNotBlank()) folderKey else it
                }
            val entryPrefix = if (prefix.isEmpty()) "" else "$prefix/"
            val providers = entries.filterKeys { it.startsWith("${entryPrefix}providers/") }
                .mapKeys { it.key.removePrefix(entryPrefix) }
            ArchiveProfile(
                sourceId = folderKey,
                name = name.ifBlank { "导入配置" },
                yaml = SubscriptionParser.normalizeToClashYaml(data.toString(Charsets.UTF_8)),
                metadata = meta,
                providers = providers,
                merge = entries["${entryPrefix}merge.yaml"]?.toString(Charsets.UTF_8)
            )
        }

        // Validate the entire archive before changing an existing profile. Provider files must
        // be present during validation because a config can reference them by relative path.
        cacheDir.mkdirs()
        val stage = Files.createTempDirectory(cacheDir.toPath(), "archive_").toFile()
        try {
            candidates.forEachIndexed { index, candidate ->
                val dir = File(stage, index.toString()).apply { mkdirs() }
                File(dir, "config.yaml").writeText(candidate.yaml, Charsets.UTF_8)
                candidate.providers.forEach { (path, bytes) ->
                    File(dir, path).apply { parentFile?.mkdirs(); writeBytes(bytes) }
                }
                val error = configValidator(dir.absolutePath)
                require(error.isBlank()) { "配置校验失败: $error" }
                candidate.merge?.trim()?.takeIf { it.isNotEmpty() }?.let { merge ->
                    val override = JSONObject().put("mergeYaml", merge).toString()
                    val mergeError = overrideValidator(dir.absolutePath, override)
                    require(mergeError.isBlank()) { "Merge 覆写校验失败: $mergeError" }
                }
            }

            val wasEmpty = _profiles.value.isEmpty()
            val imported = candidates.map { candidate ->
                val meta = candidate.metadata
                val url = meta?.optString("source")?.ifBlank { meta.optString("url") }.orEmpty()
                val sourceType = meta?.optString("sourceType")?.let { value ->
                    runCatching { ProfileSourceType.valueOf(value) }.getOrNull()
                } ?: if (url.isNotBlank()) ProfileSourceType.URL else ProfileSourceType.FILE_IMPORT
                val existingId = meta?.let { _profiles.value.firstOrNull { p -> p.id == candidate.sourceId }?.id }
                val old = existingId?.let { id -> _profiles.value.firstOrNull { it.id == id } }
                    ?: resolveMatchedProfile(null, candidate.name, sourceType, url, sha256Hex(candidate.yaml))
                val oldDir = old?.let { getProfileDir(it.id) }
                val extraChanged = candidate.merge?.let { it.trim() != old?.let { p -> readMergeYaml(p.id).trim() }.orEmpty() } == true ||
                    candidate.providers.any { (path, data) ->
                        val previous = oldDir?.let { File(it, path) }
                        previous == null || !previous.exists() || !previous.readBytes().contentEquals(data)
                    }
                val saved = saveYamlContentWithResult(
                    id = existingId, name = candidate.name, yamlContent = candidate.yaml,
                    sourceType = sourceType, url = url,
                    archiveProviders = candidate.providers, archivePrevalidated = true
                ).getOrThrow()
                val dir = getProfileDir(saved.item.id)
                candidate.merge?.let { merge ->
                    val file = File(dir, "merge.yaml")
                    if (merge.isBlank()) file.delete() else replaceTextFile(file, merge.trim())
                }
                val item = saved.item.copy(
                    sourceType = if (meta != null) sourceType else saved.item.sourceType,
                    updatedAt = meta?.optLong("updatedAt", saved.item.updatedAt) ?: saved.item.updatedAt,
                    autoUpdateMinutes = meta?.optInt("autoUpdateMinutes", saved.item.autoUpdateMinutes)?.coerceAtLeast(0) ?: saved.item.autoUpdateMinutes,
                    updateMode = meta?.optString("updateMode")?.takeIf { it.isNotBlank() }?.let(ProfileUpdateMode::fromName) ?: saved.item.updateMode,
                    customUserAgent = meta?.optString("customUserAgent", saved.item.customUserAgent) ?: saved.item.customUserAgent,
                    uploadBytes = meta?.optLong("uploadBytes", saved.item.uploadBytes) ?: saved.item.uploadBytes,
                    downloadBytes = meta?.optLong("downloadBytes", saved.item.downloadBytes) ?: saved.item.downloadBytes,
                    totalBytes = meta?.optLong("totalBytes", saved.item.totalBytes) ?: saved.item.totalBytes,
                    expireEpochSec = meta?.optLong("expireEpochSec", saved.item.expireEpochSec) ?: saved.item.expireEpochSec,
                    hasMergeYaml = File(dir, "merge.yaml").let { it.exists() && it.length() > 0L }
                )
                _profiles.value = _profiles.value.map { if (it.id == item.id) item else it }
                candidate.sourceId to saved.copy(item = item, contentChanged = saved.contentChanged || extraChanged)
            }
            val active = imported.firstOrNull { it.first == metadata.activeId }?.second
            if (wasEmpty && active != null) _activeProfileId.value = active.item.id
            saveMeta()
            val results = imported.map { it.second }
            if (active == null) results else listOf(active) + results.filterNot { it === active }
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun parseOptionalProfilesMetadata(entries: Map<String, ByteArray>): ArchiveMetadata {
        val raw = entries.entries.firstOrNull { it.key.substringAfterLast('/').equals("profiles.json", true) }
            ?: return ArchiveMetadata(null, emptyMap())
        val root = JSONObject(raw.value.toString(Charsets.UTF_8))
        val arr = root.optJSONArray("profiles") ?: return ArchiveMetadata(null, emptyMap())
        val map = HashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.optString("id").ifBlank { obj.optString("uuid") }
            if (id.isNotBlank()) map[id] = obj
        }
        return ArchiveMetadata(root.optString("activeId").ifBlank { null }, map)
    }

    fun exportAllBackupZip(): File = synchronized(lock) {
        val outDir = File(cacheDir, "exports").apply { mkdirs() }
        val outFile = File(outDir, "vpn233-backup.zip")
        val tempFile = File.createTempFile("backup_", ".zip", outDir)
        try {
            ZipOutputStream(tempFile.outputStream()).use { zos ->
                var expanded = 0L
                var count = 0
                fun addFile(name: String, file: File) {
                    require(++count <= ArchiveFiles.MAX_ENTRIES) { "备份文件数量超过限制" }
                    require(file.length() <= ArchiveFiles.MAX_ENTRY_BYTES) { "备份文件超过大小限制" }
                    expanded += file.length()
                    require(expanded <= ArchiveFiles.MAX_EXPANDED_BYTES) { "备份内容超过大小限制" }
                    zos.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                if (metaFile.exists()) addFile("profiles.json", metaFile)
                for (p in _profiles.value) {
                    val cfg = File(getProfileDir(p.id), "config.yaml")
                    if (cfg.exists()) addFile("profiles/${p.id}/config.yaml", cfg)
                    val merge = File(getProfileDir(p.id), "merge.yaml")
                    if (merge.exists()) addFile("profiles/${p.id}/merge.yaml", merge)
                    val providers = File(getProfileDir(p.id), "providers")
                    if (providers.isDirectory && !Files.isSymbolicLink(providers.toPath())) {
                        providers.walkTopDown()
                            .onEnter { !Files.isSymbolicLink(it.toPath()) }
                            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                            .forEach { file ->
                                val relative = file.relativeTo(providers).invariantSeparatorsPath
                                addFile("profiles/${p.id}/providers/$relative", file)
                            }
                    }
                }
            }
            Files.move(tempFile.toPath(), outFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            outFile
        } finally {
            tempFile.delete()
        }
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
    fun validateGlobalMergeYaml(globalMergeYaml: String, overrideBuilder: (String) -> String): Result<Unit> =
        storage.validateGlobalMergeYaml(globalMergeYaml, overrideBuilder)
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
        url: String = "",
        autoUpdateMinutes: Int = -1,
        uploadBytes: Long = -1L,
        downloadBytes: Long = -1L,
        totalBytes: Long = -1L,
        expireEpochSec: Long = -1L
    ): Result<ProfileSaveResult> = withContext(Dispatchers.IO) {
        storage.saveYamlContentWithResult(
            id = id,
            name = name,
            yamlContent = yamlContent,
            sourceType = sourceType,
            url = url,
            autoUpdateMinutes = autoUpdateMinutes,
            uploadBytes = uploadBytes,
            downloadBytes = downloadBytes,
            totalBytes = totalBytes,
            expireEpochSec = expireEpochSec
        )
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
            val ua = SubscriptionUserAgent.select(existing?.customUserAgent, BuildConfig.MIHOMO_VERSION, BuildConfig.VERSION_NAME)

            val conn = openUrlWithRoutingPolicy(trimmedUrl, ua, updateMode, runningProxyPort)
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("服务器返回 HTTP $code")
            }
            val body = conn.inputStream.use { ArchiveFiles.readLimited(it, ArchiveFiles.MAX_ENTRY_BYTES) }
                .toString(Charsets.UTF_8)
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
            val bytes = context.contentResolver.openInputStream(uri)?.use { ArchiveFiles.readLimited(it, ArchiveFiles.MAX_INPUT_BYTES) }
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
                val bytes = f.inputStream().use { ArchiveFiles.readLimited(it, ArchiveFiles.MAX_INPUT_BYTES) }
                val list = storage.importBytesWithResult(bytes, f.name).map { it.item }
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
