package com.neko233.vpn233.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Bounds untrusted imports before they reach the profile store. */
internal object ArchiveFiles {
    const val MAX_INPUT_BYTES = 70 * 1024 * 1024
    const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 64 * 1024 * 1024
    const val MAX_ENTRIES = 1024

    fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= limit) { "导入内容超过大小限制" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    fun readZip(input: InputStream): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var expanded = 0
        var count = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= MAX_ENTRIES) { "压缩包文件数量超过限制" }
                val name = entry.name.replace('\\', '/')
                val path = name.removeSuffix("/")
                require(path.isNotEmpty() && !name.startsWith('/') && ':' !in path && '\u0000' !in path &&
                    path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                    "压缩包包含不安全的文件路径"
                }
                if (!entry.isDirectory) {
                    require(!entries.containsKey(path)) { "压缩包含有重复文件" }
                    val remaining = MAX_EXPANDED_BYTES - expanded
                    val data = readLimited(zip, minOf(MAX_ENTRY_BYTES, remaining))
                    expanded += data.size
                    entries[path] = data
                }
                zip.closeEntry()
            }
        }
        return entries
    }
}
