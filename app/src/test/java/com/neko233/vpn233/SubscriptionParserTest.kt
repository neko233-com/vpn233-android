package com.neko233.vpn233

import com.neko233.vpn233.data.SubscriptionParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionParserTest {

    @Test
    fun passthroughExistingClashYaml() {
        val yaml = """
            proxies:
              - name: "HK"
                type: ss
                server: 1.2.3.4
                port: 443
                cipher: aes-128-gcm
                password: secret
        """.trimIndent()
        val out = SubscriptionParser.normalizeToClashYaml(yaml)
        assertTrue(out.contains("name: \"HK\""))
    }

    @Test
    fun decodesBase64ProxyUriListIntoValidClashYaml() {
        val vmessJson = """{"v":"2","ps":"🇯🇵 Tokyo \"Test\"","add":"1.1.1.1","port":"443","id":"11111111-1111-1111-1111-111111111111","aid":"0","net":"ws","host":"cdn.example.com","path":"/ws","tls":"tls"}"""
        val vmessUri = "vmess://" + Base64.getEncoder().encodeToString(vmessJson.toByteArray(Charsets.UTF_8))
        val ssUser = Base64.getEncoder().encodeToString("aes-256-gcm:pass123".toByteArray(Charsets.UTF_8))
        val ssUri = "ss://$ssUser@2.2.2.2:8388#SG-Node"
        val vlessUri = "vless://22222222-2222-2222-2222-222222222222@3.3.3.3:443?security=reality&pbk=PUBKEY&sid=abcd&sni=www.bing.com&fp=chrome#US-Reality"
        val hy2Uri = "hy2://mypass@4.4.4.4:8443?sni=hy2.example.com&insecure=1#HY2-Fast"
        val trojanUri = "trojan://tpass@5.5.5.5:443?sni=tr.example.com#Trojan-1"
        val tuicUri = "tuic://33333333-3333-3333-3333-333333333333:tsecret@6.6.6.6:443?sni=tuic.example.com&congestion_control=bbr#TUIC-1"

        val bundle = listOf(vmessUri, ssUri, vlessUri, hy2Uri, trojanUri, tuicUri).joinToString("\n")
        val b64Sub = Base64.getEncoder().encodeToString(bundle.toByteArray(Charsets.UTF_8))

        val yaml = SubscriptionParser.normalizeToClashYaml(b64Sub)
        assertTrue(yaml.contains("type: vmess"))
        assertTrue(yaml.contains("type: ss"))
        assertTrue(yaml.contains("type: vless"))
        assertTrue(yaml.contains("public-key: \"PUBKEY\""))
        assertTrue(yaml.contains("type: hysteria2"))
        assertTrue(yaml.contains("type: trojan"))
        assertTrue(yaml.contains("type: tuic"))
        assertTrue(yaml.contains("proxy-groups:"))
        assertTrue(yaml.contains("MATCH,PROXY"))
    }

    @Test
    fun deduplicatesIdenticalNodeNames() {
        val ss1 = "ss://YWVzLTEyOC1nY206cGFzcw==@1.1.1.1:443#SameName"
        val ss2 = "ss://YWVzLTEyOC1nY206cGFzcw==@2.2.2.2:443#SameName"
        val ssDup = "ss://YWVzLTEyOC1nY206cGFzcw==@1.1.1.1:443#SameName"
        val yaml = SubscriptionParser.normalizeToClashYaml("$ss1\n$ss2\n$ssDup")
        assertTrue(yaml.contains("\"SameName\""))
        assertTrue(yaml.contains("\"SameName-2\""))
        assertTrue(!yaml.contains("\"SameName-3\""))
    }

    @Test
    fun parsesUnencodedSpacesPipesAndEmojiFragmentsSafely() {
        val vless = "vless://22222222-2222-2222-2222-222222222222@us.example.com:443?security=tls&sni=us.example.com#🇺🇸 美国 01 | 高速"
        val trojan = "trojan://pass123@kr.example.com:443?sni=kr.example.com#🇰🇷 韩国 [专线]"
        val hy2 = "hy2://mypass@jp.example.com:8443?sni=jp.example.com#🇯🇵 日本 HY2"
        val yaml = SubscriptionParser.normalizeToClashYaml("$vless\n$trojan\n$hy2")
        assertTrue(yaml.contains("🇺🇸 美国 01 | 高速"))
        assertTrue(yaml.contains("🇰🇷 韩国 [专线]"))
        assertTrue(yaml.contains("🇯🇵 日本 HY2"))
        assertTrue(yaml.contains("type: vless"))
        assertTrue(yaml.contains("type: trojan"))
        assertTrue(yaml.contains("type: hysteria2"))
    }
}
