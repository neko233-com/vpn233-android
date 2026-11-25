package com.neko233.vpn233.data

import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

object SubscriptionParser {

    fun normalizeToClashYaml(rawContent: String): String {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) return rawContent
        if (looksLikeClashYaml(trimmed)) return rawContent

        val decoded = tryDecodeBase64Text(trimmed)
        val candidate = if (decoded != null && decoded.isNotBlank()) decoded.trim() else trimmed
        if (looksLikeClashYaml(candidate)) return candidate

        val lines = candidate.lines().map { it.trim() }.filter { it.contains("://") }.distinct()
        if (lines.isEmpty()) return rawContent

        val nodes = mutableListOf<ParsedProxy>()
        val usedNames = hashSetOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "GLOBAL", "PROXY", "自动选择", "故障转移")
        lines.forEachIndexed { idx, line ->
            parseUri(line, idx + 1)?.let { node ->
                var unique = node.name.ifBlank { "Node-${idx + 1}" }
                var suffix = 2
                while (!usedNames.add(unique)) {
                    unique = "${node.name}-$suffix"
                    suffix++
                }
                nodes.add(node.copy(name = unique, yamlLines = listOf("  - name: ${q(unique)}") + node.yamlLines.drop(1)))
            }
        }
        if (nodes.isEmpty()) return rawContent
        return buildClashYaml(nodes)
    }

    private fun looksLikeClashYaml(text: String): Boolean {
        return Regex("(?m)^(proxies|proxy-providers)\\s*:").containsMatchIn(text)
    }

    private fun tryDecodeBase64Text(input: String): String? {
        val compact = input.replace("\r", "").replace("\n", "").trim()
        if (compact.length < 16 || compact.contains("://") || compact.contains("proxies:")) return null
        val normalized = compact.replace('-', '+').replace('_', '/')
        val padded = when (normalized.length % 4) {
            2 -> "$normalized=="
            3 -> "$normalized="
            else -> normalized
        }
        return runCatching {
            val bytes = Base64.getDecoder().decode(padded)
            val str = bytes.toString(Charsets.UTF_8)
            if (looksLikeClashYaml(str) || str.contains("://")) str else null
        }.getOrNull()
    }

    data class ParsedProxy(
        val name: String,
        val yamlLines: List<String>
    )

    fun parseUri(uriStr: String, fallbackIdx: Int = 1): ParsedProxy? {
        val lower = uriStr.lowercase()
        return when {
            lower.startsWith("vmess://") -> parseVmess(uriStr, fallbackIdx)
            lower.startsWith("vless://") -> parseVless(uriStr, fallbackIdx)
            lower.startsWith("trojan://") -> parseTrojan(uriStr, fallbackIdx)
            lower.startsWith("ss://") -> parseShadowsocks(uriStr, fallbackIdx)
            lower.startsWith("hy2://") || lower.startsWith("hysteria2://") -> parseHysteria2(uriStr, fallbackIdx)
            lower.startsWith("tuic://") -> parseTuic(uriStr, fallbackIdx)
            lower.startsWith("socks://") || lower.startsWith("socks5://") -> parseSocks(uriStr, fallbackIdx)
            else -> null
        }
    }

    private fun parseVmess(raw: String, idx: Int): ParsedProxy? {
        val body = raw.substringAfter("://").substringBefore('#').trim()
        val jsonText = tryDecodeRawBase64(body) ?: return null
        return runCatching {
            val obj = JSONObject(jsonText)
            val name = obj.optString("ps").ifBlank { "VMess-$idx" }
            val server = obj.optString("add")
            val port = obj.opt("port")?.toString()?.toIntOrNull() ?: return null
            val uuid = obj.optString("id")
            if (server.isBlank() || uuid.isBlank() || port !in 1..65535) return null
            val aid = obj.opt("aid")?.toString()?.toIntOrNull() ?: 0
            val net = obj.optString("net", "tcp").lowercase()
            val tls = obj.optString("tls").equals("tls", true)
            val sni = obj.optString("sni").ifBlank { obj.optString("host") }
            val host = obj.optString("host")
            val path = obj.optString("path", "/")
            val alpn = obj.optString("alpn")
            val fp = obj.optString("fp")
            val lines = mutableListOf(
                "  - name: ${q(name)}",
                "    type: vmess",
                "    server: ${q(server)}",
                "    port: $port",
                "    uuid: ${q(uuid)}",
                "    alterId: $aid",
                "    cipher: ${q(obj.optString("scy", "auto").ifBlank { "auto" })}",
                "    udp: true"
            )
            if (tls) {
                lines.add("    tls: true")
                if (sni.isNotBlank()) lines.add("    servername: ${q(sni)}")
                if (fp.isNotBlank()) lines.add("    client-fingerprint: ${q(fp)}")
                if (alpn.isNotBlank()) {
                    lines.add("    alpn: [${alpn.split(',').joinToString(", ") { q(it.trim()) }}]")
                }
            }
            if (net == "ws") {
                lines.add("    network: ws")
                lines.add("    ws-opts:")
                lines.add("      path: ${q(path.ifBlank { "/" })}")
                if (host.isNotBlank()) {
                    lines.add("      headers:")
                    lines.add("        Host: ${q(host)}")
                }
            } else if (net == "grpc") {
                lines.add("    network: grpc")
                if (path.isNotBlank()) {
                    lines.add("    grpc-opts:")
                    lines.add("      grpc-service-name: ${q(path)}")
                }
            }
            ParsedProxy(name, lines)
        }.getOrNull()
    }

    private data class SafeUri(
        val scheme: String,
        val userInfo: String?,
        val host: String,
        val port: Int?,
        val path: String,
        val query: Map<String, String>,
        val fragment: String
    )

    private fun parseSafeUri(raw: String): SafeUri? {
        val schemeIdx = raw.indexOf("://")
        if (schemeIdx <= 0) return null
        val scheme = raw.substring(0, schemeIdx).lowercase()
        val afterScheme = raw.substring(schemeIdx + 3)
        val fragIdx = afterScheme.indexOf('#')
        val mainPart = if (fragIdx >= 0) afterScheme.substring(0, fragIdx) else afterScheme
        val fragment = if (fragIdx >= 0) decodeFrag(afterScheme.substring(fragIdx + 1)) else ""

        val queryIdx = mainPart.indexOf('?')
        val authorityAndPath = if (queryIdx >= 0) mainPart.substring(0, queryIdx) else mainPart
        val rawQuery = if (queryIdx >= 0) mainPart.substring(queryIdx + 1) else null

        val slashIdx = authorityAndPath.indexOf('/')
        val authority = if (slashIdx >= 0) authorityAndPath.substring(0, slashIdx) else authorityAndPath
        val path = if (slashIdx >= 0) authorityAndPath.substring(slashIdx) else "/"

        val atIdx = authority.lastIndexOf('@')
        val userInfo = if (atIdx >= 0) decode(authority.substring(0, atIdx)) else null
        val hostPort = (if (atIdx >= 0) authority.substring(atIdx + 1) else authority).trim()
        if (hostPort.isBlank()) return null

        val host: String
        val port: Int?
        if (hostPort.startsWith("[")) {
            val endBracket = hostPort.indexOf(']')
            if (endBracket <= 1) return null
            host = hostPort.substring(1, endBracket)
            val rest = hostPort.substring(endBracket + 1)
            if (rest.isNotEmpty() && !rest.startsWith(":")) return null
            port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: return null else null
        } else {
            val colonIdx = hostPort.lastIndexOf(':')
            if (colonIdx > 0) {
                host = hostPort.substring(0, colonIdx)
                if (':' in host) return null // IPv6 literals must be bracketed.
                port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: return null
            } else {
                host = hostPort
                port = null
            }
        }
        if (host.isBlank() || (port != null && port !in 1..65535)) return null
        return SafeUri(scheme, userInfo, host, port, path, parseQuery(rawQuery), fragment)
    }

    private fun parseVless(raw: String, idx: Int): ParsedProxy? {
        val uri = parseSafeUri(raw) ?: return null
        val server = uri.host
        val port = uri.port ?: 443
        val uuid = uri.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val qMap = uri.query
        val name = uri.fragment.ifBlank { "VLESS-$idx" }
        val security = qMap["security"].orEmpty().lowercase()
        val net = qMap["type"].orEmpty().lowercase().ifBlank { "tcp" }
        val lines = mutableListOf(
            "  - name: ${q(name)}",
            "    type: vless",
            "    server: ${q(server)}",
            "    port: $port",
            "    uuid: ${q(uuid)}",
            "    udp: true"
        )
        qMap["flow"]?.takeIf { it.isNotBlank() }?.let { lines.add("    flow: ${q(it)}") }
        if (security == "tls" || security == "reality") {
            lines.add("    tls: true")
            qMap["sni"]?.takeIf { it.isNotBlank() }?.let { lines.add("    servername: ${q(it)}") }
            qMap["fp"]?.takeIf { it.isNotBlank() }?.let { lines.add("    client-fingerprint: ${q(it)}") }
            if (qMap["allowInsecure"] == "1" || qMap["insecure"] == "1") {
                lines.add("    skip-cert-verify: true")
            }
            if (security == "reality") {
                val pbk = qMap["pbk"].orEmpty()
                if (pbk.isNotBlank()) {
                    lines.add("    reality-opts:")
                    lines.add("      public-key: ${q(pbk)}")
                    qMap["sid"]?.takeIf { it.isNotBlank() }?.let { lines.add("      short-id: ${q(it)}") }
                }
            }
        }
        when (net) {
            "ws" -> {
                lines.add("    network: ws")
                lines.add("    ws-opts:")
                lines.add("      path: ${q(qMap["path"].orEmpty().ifBlank { "/" })}")
                qMap["host"]?.takeIf { it.isNotBlank() }?.let {
                    lines.add("      headers:")
                    lines.add("        Host: ${q(it)}")
                }
            }
            "grpc" -> {
                lines.add("    network: grpc")
                qMap["serviceName"]?.takeIf { it.isNotBlank() }?.let {
                    lines.add("    grpc-opts:")
                    lines.add("      grpc-service-name: ${q(it)}")
                }
            }
        }
        return ParsedProxy(name, lines)
    }

    private fun parseTrojan(raw: String, idx: Int): ParsedProxy? {
        val uri = parseSafeUri(raw) ?: return null
        val server = uri.host
        val port = uri.port ?: 443
        val password = uri.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val qMap = uri.query
        val name = uri.fragment.ifBlank { "Trojan-$idx" }
        val lines = mutableListOf(
            "  - name: ${q(name)}",
            "    type: trojan",
            "    server: ${q(server)}",
            "    port: $port",
            "    password: ${q(password)}",
            "    udp: true"
        )
        qMap["sni"]?.takeIf { it.isNotBlank() }?.let { lines.add("    sni: ${q(it)}") }
        if (qMap["allowInsecure"] == "1" || qMap["insecure"] == "1") {
            lines.add("    skip-cert-verify: true")
        }
        if (qMap["type"].equals("ws", true)) {
            lines.add("    network: ws")
            lines.add("    ws-opts:")
            lines.add("      path: ${q(qMap["path"].orEmpty().ifBlank { "/" })}")
            qMap["host"]?.takeIf { it.isNotBlank() }?.let {
                lines.add("      headers:")
                lines.add("        Host: ${q(it)}")
            }
        }
        return ParsedProxy(name, lines)
    }

    private fun parseShadowsocks(raw: String, idx: Int): ParsedProxy? {
        val withoutScheme = raw.substringAfter("://")
        val fragPart = withoutScheme.substringAfter('#', "")
        val name = decodeFrag(fragPart).ifBlank { "SS-$idx" }
        val mainPart = withoutScheme.substringBefore('#').substringBefore('?')
        val userAndHost = if (mainPart.contains('@')) {
            val u = mainPart.substringBefore('@')
            val hp = mainPart.substringAfter('@')
            val decodedU = if (!u.contains(':')) tryDecodeRawBase64(u) ?: u else u
            "$decodedU@$hp"
        } else {
            tryDecodeRawBase64(mainPart) ?: return null
        }
        val creds = userAndHost.substringBeforeLast('@')
        val hostPort = userAndHost.substringAfterLast('@').substringBefore('/')
        val method = creds.substringBefore(':')
        val password = creds.substringAfter(':', "")
        val host = hostPort.substringBeforeLast(':').trim('[', ']')
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
        if (host.isBlank() || method.isBlank() || port !in 1..65535) return null
        return ParsedProxy(
            name,
            listOf(
                "  - name: ${q(name)}",
                "    type: ss",
                "    server: ${q(host)}",
                "    port: $port",
                "    cipher: ${q(method)}",
                "    password: ${q(password)}",
                "    udp: true"
            )
        )
    }

    private fun parseHysteria2(raw: String, idx: Int): ParsedProxy? {
        val uri = parseSafeUri(raw) ?: return null
        val server = uri.host
        val port = uri.port ?: 443
        val password = uri.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val qMap = uri.query
        val name = uri.fragment.ifBlank { "HY2-$idx" }
        val lines = mutableListOf(
            "  - name: ${q(name)}",
            "    type: hysteria2",
            "    server: ${q(server)}",
            "    port: $port",
            "    password: ${q(password)}"
        )
        qMap["sni"]?.takeIf { it.isNotBlank() }?.let { lines.add("    sni: ${q(it)}") }
        if (qMap["insecure"] == "1" || qMap["allowInsecure"] == "1") {
            lines.add("    skip-cert-verify: true")
        }
        qMap["obfs"]?.takeIf { it.isNotBlank() && it != "none" }?.let {
            lines.add("    obfs: ${q(it)}")
            qMap["obfs-password"]?.takeIf { op -> op.isNotBlank() }?.let { op ->
                lines.add("    obfs-password: ${q(op)}")
            }
        }
        return ParsedProxy(name, lines)
    }

    private fun parseTuic(raw: String, idx: Int): ParsedProxy? {
        val uri = parseSafeUri(raw) ?: return null
        val server = uri.host
        val port = uri.port ?: 443
        val ui = uri.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val uuid = ui.substringBefore(':')
        val password = ui.substringAfter(':', "")
        val qMap = uri.query
        val name = uri.fragment.ifBlank { "TUIC-$idx" }
        val lines = mutableListOf(
            "  - name: ${q(name)}",
            "    type: tuic",
            "    server: ${q(server)}",
            "    port: $port",
            "    uuid: ${q(uuid)}",
            "    password: ${q(password)}",
            "    udp: true"
        )
        qMap["sni"]?.takeIf { it.isNotBlank() }?.let { lines.add("    sni: ${q(it)}") }
        qMap["congestion_control"]?.takeIf { it.isNotBlank() }?.let { lines.add("    congestion-controller: ${q(it)}") }
        if (qMap["allow_insecure"] == "1" || qMap["insecure"] == "1") {
            lines.add("    skip-cert-verify: true")
        }
        return ParsedProxy(name, lines)
    }

    private fun parseSocks(raw: String, idx: Int): ParsedProxy? {
        val uri = parseSafeUri(raw) ?: return null
        val server = uri.host
        val port = uri.port ?: 1080
        val name = uri.fragment.ifBlank { "SOCKS-$idx" }
        val lines = mutableListOf(
            "  - name: ${q(name)}",
            "    type: socks5",
            "    server: ${q(server)}",
            "    port: $port",
            "    udp: true"
        )
        uri.userInfo?.takeIf { it.contains(':') }?.let { ui ->
            lines.add("    username: ${q(ui.substringBefore(':'))}")
            lines.add("    password: ${q(ui.substringAfter(':'))}")
        }
        return ParsedProxy(name, lines)
    }

    private fun buildClashYaml(nodes: List<ParsedProxy>): String = buildString {
        appendLine("mixed-port: 7890")
        appendLine("mode: rule")
        appendLine("ipv6: false")
        appendLine("proxies:")
        nodes.forEach { node ->
            node.yamlLines.forEach { appendLine(it) }
        }
        appendLine("proxy-groups:")
        appendLine("  - name: \"PROXY\"")
        appendLine("    type: select")
        appendLine("    proxies:")
        appendLine("      - \"自动选择\"")
        appendLine("      - \"故障转移\"")
        nodes.forEach { appendLine("      - ${q(it.name)}") }
        appendLine("      - DIRECT")
        appendLine("  - name: \"自动选择\"")
        appendLine("    type: url-test")
        appendLine("    url: \"https://www.gstatic.com/generate_204\"")
        appendLine("    interval: 300")
        appendLine("    tolerance: 50")
        appendLine("    proxies:")
        nodes.forEach { appendLine("      - ${q(it.name)}") }
        appendLine("  - name: \"故障转移\"")
        appendLine("    type: fallback")
        appendLine("    url: \"https://www.gstatic.com/generate_204\"")
        appendLine("    interval: 300")
        appendLine("    proxies:")
        nodes.forEach { appendLine("      - ${q(it.name)}") }
        appendLine("rules:")
        appendLine("  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve")
        appendLine("  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve")
        appendLine("  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve")
        appendLine("  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve")
        appendLine("  - GEOIP,CN,DIRECT")
        appendLine("  - MATCH,PROXY")
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        rawQuery.split('&').forEach { kv ->
            val idx = kv.indexOf('=')
            if (idx > 0) {
                val k = decode(kv.substring(0, idx))
                val v = decode(kv.substring(idx + 1))
                map[k] = v
            }
        }
        return map
    }

    private fun decodeFrag(frag: String?): String = if (frag.isNullOrBlank()) "" else decode(frag)

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun tryDecodeRawBase64(s: String): String? {
        val norm = s.trim().replace('-', '+').replace('_', '/')
        val padded = when (norm.length % 4) {
            2 -> "$norm=="
            3 -> "$norm="
            else -> norm
        }
        return runCatching { Base64.getDecoder().decode(padded).toString(Charsets.UTF_8) }.getOrNull()
    }

    private fun q(s: String): String = JSONObject.quote(s)
}
