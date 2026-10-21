package com.szech.walls.parse

import com.szech.walls.core.B64
import com.szech.walls.core.Text
import com.szech.walls.model.JsonUtil
import com.szech.walls.model.Node
import org.json.JSONObject
import java.util.Locale

/**
 * 把各种分享链接（ss / ssr / vmess / vless / trojan / hy2 / hysteria / tuic）
 * 解析成 Clash 风格的代理字典。逻辑对齐 Windows 版 tools/fetch_nodes.py。
 */
object UriParser {

    val URI_RE = Regex(
        """(?:vmess|vless|trojan|ss|ssr|hy2|hysteria2|hysteria|tuic)://[^\s"'<>\\]+"""
    )
    val HTTP_RE = Regex("""https?://[^\s"'<>()\\]+""")

    fun extractUris(text: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (m in URI_RE.findAll(text)) seen.add(m.value)
        return seen.toList()
    }

    fun extractHttpLinks(text: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (m in HTTP_RE.findAll(text)) seen.add(m.value)
        return seen.toList()
    }

    private fun intOf(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: 0
        else -> 0
    }

    private fun isTruthy(v: Any?): Boolean {
        val s = v?.toString()?.lowercase(Locale.ROOT) ?: return false
        return s == "1" || s == "true"
    }

    private val IPV4_RE = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * 判断这是不是一个「假节点」（内网 / 回环 / 保留地址）。
     *
     * 以前这里用 InetAddress.getByName(host)，等于给每个**域名**节点发一次真 DNS 查询。
     * 去重阶段是串行的、节点数上千，在手机上这一步骤能悄悄吃掉几分钟，而日志里一句提示都没有
     * ——「抓取完成」和「延迟测试开始」之间那段空窗就是它。PC 端 tools/fetch_nodes.py 用的是
     * ipaddress.ip_address()，只解析字面量、不查 DNS，所以这边也得照做：
     * 只认真正的 IP 字面量，域名一律当作「不是假节点」，交给后面的 TCP 测试去判死活。
     */
    fun isDecoy(server: String?): Boolean {
        val s = (server ?: "").trim().lowercase(Locale.ROOT)
        if (s.isEmpty()) return true
        if (s == "localhost" || s.startsWith("localhost.")) return true
        var host = s.substringBefore('%')
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
        parseIpv4(host)?.let { return isDecoyV4(it) }
        isDecoyV6(host)?.let { return it }
        return false // 域名：不做 DNS，就当作候选留着
    }

    /** "1.2.3.4" -> [1,2,3,4]；不是合法点分十进制就返回 null */
    private fun parseIpv4(h: String): IntArray? {
        val m = IPV4_RE.find(h) ?: return null
        val out = IntArray(4)
        for (i in 0 until 4) {
            val v = m.groupValues[i + 1].toIntOrNull() ?: return null
            if (v > 255) return null
            out[i] = v
        }
        return out
    }

    private fun isDecoyV4(a: IntArray): Boolean {
        val (p, q) = a[0] to a[1]
        return when {
            p == 0 -> true                        // 0.0.0.0/8 本网络
            p == 10 -> true                       // 10/8 私有
            p == 127 -> true                      // 127/8 回环
            p == 100 && q in 64..127 -> true      // 100.64/10 运营商级 NAT
            p == 169 && q == 254 -> true          // 169.254/16 链路本地
            p == 172 && q in 16..31 -> true       // 172.16/12 私有
            p == 192 && q == 168 -> true          // 192.168/16 私有
            p == 192 && q == 0 && a[2] == 0 -> true        // 192.0.0/24 协议保留
            p == 192 && q == 0 && a[2] == 2 -> true        // 192.0.2/24 文档用
            p == 198 && q in 18..19 -> true       // 198.18/15 基准测试
            p == 198 && q == 51 && a[2] == 100 -> true     // 198.51.100/24 文档用
            p == 203 && q == 0 && a[2] == 113 -> true      // 203.0.113/24 文档用
            p >= 224 -> true                      // 224/4 组播 + 240/4 保留
            else -> false
        }
    }

    /**
     * 只做前缀判断，不展开 ::；够用且绝不会误伤公网地址。
     * 返回 null 表示「这压根不是 IPv6 字面量」。
     */
    private fun isDecoyV6(h: String): Boolean? {
        if (!h.contains(':')) return null
        val low = h.lowercase(Locale.ROOT)
        return when {
            low == "::" || low == "::1" -> true            // 未指定 / 回环
            low.startsWith("fe8") || low.startsWith("fe9") ||
                low.startsWith("fea") || low.startsWith("feb") -> true   // fe80::/10 链路本地
            low.startsWith("fc") || low.startsWith("fd") -> true         // fc00::/7 唯一本地
            low.startsWith("ff") -> true                     // ff00::/8 组播
            low.startsWith("2001:db8") -> true               // 文档用
            low.startsWith("::ffff:") -> {
                // IPv4 映射地址：把尾巴当 IPv4 判
                val tail = low.removePrefix("::ffff:")
                parseIpv4(tail)?.let { isDecoyV4(it) } ?: false
            }
            else -> false
        }
    }

    /** 分享链接里的主机名：IPv6 字面量在 URI 里写作 [2001:db8::1]，这里去掉方括号 */
    private fun bareHost(h: String): String = h.trim().removePrefix("[").removeSuffix("]")

    private fun splitHostPort(hostPortRaw: String): Pair<String, Int>? {
        var hp = hostPortRaw
        val q = hp.indexOf('?')
        if (q >= 0) hp = hp.substring(0, q)
        val h = hp.substringBefore('#').trim()
        if (h.contains(":")) {
            val idx = h.lastIndexOf(':')
            val host = h.substring(0, idx).trim().removePrefix("[").removeSuffix("]")
            val port = h.substring(idx + 1).trim().toIntOrNull()
            if (port != null) return host to port
        }
        return null
    }

    // ---------------------------------------------------------------- vmess

    private fun parseVmess(uri: String): LinkedHashMap<String, Any?>? {
        val raw = uri.removePrefix("vmess://")
        val decoded = B64.decodeToString(raw) ?: raw
        val data = try {
            JSONObject(decoded)
        } catch (e: Exception) {
            return null
        }
        val host = data.optString("add", "")
        val port = intOf(data.opt("port"))
        if (host.isEmpty() || port <= 0) return null
        val name = data.optString("ps", "").ifBlank { "$host:$port" }
        val p = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "vmess",
            "server" to host,
            "port" to port,
            "uuid" to data.optString("id", ""),
            "alterId" to intOf(data.opt("aid")),
            "cipher" to data.optString("scy", "").ifBlank { "auto" },
            "_uri" to uri
        )
        val net = data.optString("net", "tcp").ifBlank { "tcp" }
        if (net != "tcp") p["network"] = net
        if (net == "ws") {
            val opts = linkedMapOf<String, Any?>(
                "path" to data.optString("path", "").ifBlank { "/" }
            )
            val h = data.optString("host", "")
            if (h.isNotEmpty()) opts["headers"] = linkedMapOf<String, Any?>("Host" to h)
            p["ws-opts"] = opts
        } else if (net == "grpc") {
            p["grpc-opts"] = linkedMapOf<String, Any?>(
                "grpc-service-name" to data.optString("path", "")
            )
        }
        val tlsV = data.opt("tls")
        if (tlsV != null && isTruthy(tlsV)) {
            p["tls"] = true
            p["servername"] = data.optString("sni", "").ifBlank {
                data.optString("host", "").ifBlank { host }
            }
        }
        return p
    }

    // ---------------------------------------------------------------- vless

    // 主机部分允许 [IPv6] 形式。以前写成 ([^:/?#]+)，碰上有冒号的地址就匹配失败、节点被静默丢掉。
    private val VLESS_RE =
        Regex("""vless://([^@]+)@(\[[0-9A-Fa-f:.]+\]|[^:/?#]+):(\d+)([^#]*)(?:#(.*))?""")

    private fun parseVless(uri: String): LinkedHashMap<String, Any?>? {
        val m = VLESS_RE.find(uri) ?: return null
        val uuid = m.groupValues[1]
        val host = bareHost(m.groupValues[2])
        val port = m.groupValues[3].toIntOrNull() ?: return null
        val query = m.groupValues[4]
        val frag = m.groupValues[5]
        val name = Text.unquote(frag).ifBlank { "$host:$port" }
        val p = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "vless",
            "server" to host,
            "port" to port,
            "uuid" to uuid,
            "cipher" to "auto",
            "_uri" to uri
        )
        val net = Text.queryParam(query, "type") ?: "tcp"
        if (net != "tcp" && net != "none") p["network"] = net
        val security = Text.queryParam(query, "security") ?: ""
        if (security == "tls" || security == "reality" || security == "xtls") {
            p["tls"] = true
            p["servername"] = Text.queryParam(query, "sni") ?: host
            if (security == "reality") {
                val pk = Text.queryParam(query, "pbk")
                if (!pk.isNullOrEmpty()) {
                    p["reality-opts"] = linkedMapOf<String, Any?>(
                        "public-key" to pk,
                        "short-id" to (Text.queryParam(query, "sid") ?: "")
                    )
                }
            }
        }
        val flow = Text.queryParam(query, "flow")
        if (!flow.isNullOrEmpty()) p["flow"] = flow
        if (isTruthy(Text.queryParam(query, "allowInsecure")) || isTruthy(Text.queryParam(query, "insecure"))) {
            p["skip-cert-verify"] = true
        }
        val fp = Text.queryParam(query, "fp")
        if (!fp.isNullOrEmpty()) p["client-fingerprint"] = fp
        if (net == "ws") {
            val opts = linkedMapOf<String, Any?>("path" to (Text.queryParam(query, "path") ?: "/"))
            val h = Text.queryParam(query, "host")
            if (!h.isNullOrEmpty()) opts["headers"] = linkedMapOf<String, Any?>("Host" to h)
            p["ws-opts"] = opts
        } else if (net == "grpc") {
            p["grpc-opts"] = linkedMapOf<String, Any?>(
                "grpc-service-name" to (Text.queryParam(query, "serviceName") ?: "")
            )
        }
        return p
    }

    // --------------------------------------------------------------- trojan

    private val TROJAN_RE =
        Regex("""trojan://([^@]*)@(\[[0-9A-Fa-f:.]+\]|[^:/?#]+):(\d+)([^#]*)(?:#(.*))?""")

    private fun parseTrojan(uri: String): LinkedHashMap<String, Any?>? {
        val m = TROJAN_RE.find(uri) ?: return null
        val password = m.groupValues[1]
        val host = bareHost(m.groupValues[2])
        val port = m.groupValues[3].toIntOrNull() ?: return null
        val query = m.groupValues[4]
        val frag = m.groupValues[5]
        val p = linkedMapOf<String, Any?>(
            "name" to Text.unquote(frag).ifBlank { "$host:$port" },
            "type" to "trojan",
            "server" to host,
            "port" to port,
            "password" to password,
            "udp" to true,
            "_uri" to uri
        )
        val security = Text.queryParam(query, "security") ?: "tls"
        if (security == "tls" || security == "reality") {
            p["tls"] = true
            p["servername"] = Text.queryParam(query, "sni") ?: host
        }
        if (isTruthy(Text.queryParam(query, "allowInsecure"))) p["skip-cert-verify"] = true
        val fp = Text.queryParam(query, "fp")
        if (!fp.isNullOrEmpty()) p["client-fingerprint"] = fp
        val net = Text.queryParam(query, "type") ?: "tcp"
        if (net == "ws") {
            p["network"] = "ws"
            val opts = linkedMapOf<String, Any?>("path" to (Text.queryParam(query, "path") ?: "/"))
            val h = Text.queryParam(query, "host")
            if (!h.isNullOrEmpty()) opts["headers"] = linkedMapOf<String, Any?>("Host" to h)
            p["ws-opts"] = opts
        } else if (net == "grpc") {
            p["network"] = "grpc"
            p["grpc-opts"] = linkedMapOf<String, Any?>(
                "grpc-service-name" to (Text.queryParam(query, "serviceName") ?: "")
            )
        }
        return p
    }

    // ------------------------------------------------------------------- ss

    private fun parsePlugin(p: LinkedHashMap<String, Any?>, pluginSpec: String) {
        val parts = pluginSpec.split(';')
        if (parts.isEmpty()) return
        val pluginName = parts[0].trim()
        val kv = linkedMapOf<String, String>()
        for (i in 1 until parts.size) {
            val seg = parts[i]
            if (seg.isEmpty()) continue
            val eq = seg.indexOf('=')
            if (eq >= 0) kv[seg.substring(0, eq)] = Text.unquote(seg.substring(eq + 1))
            else kv[seg] = "true"
        }
        when {
            pluginName.startsWith("obfs-local") || pluginName == "simple-obfs" || pluginName == "obfs" -> {
                p["plugin"] = "obfs"
                val opts = linkedMapOf<String, Any?>(
                    "mode" to (kv["obfs"] ?: "http")
                )
                if (!kv["obfs-host"].isNullOrEmpty()) opts["host"] = kv["obfs-host"]
                p["plugin-opts"] = opts
            }
            pluginName.startsWith("v2ray-plugin") -> {
                p["plugin"] = "v2ray-plugin"
                val opts = linkedMapOf<String, Any?>(
                    "mode" to (kv["mode"] ?: "websocket")
                )
                if (!kv["host"].isNullOrEmpty()) opts["host"] = kv["host"]
                if (!kv["path"].isNullOrEmpty()) opts["path"] = kv["path"]
                if (kv.containsKey("tls")) opts["tls"] = true
                p["plugin-opts"] = opts
            }
        }
    }

    private fun parseSs(uri: String): LinkedHashMap<String, Any?>? {
        var raw = uri.removePrefix("ss://")
        var frag = ""
        val hash = raw.indexOf('#')
        if (hash >= 0) {
            frag = raw.substring(hash + 1)
            raw = raw.substring(0, hash)
        }
        var method = ""
        var password = ""
        var host = ""
        var port = 0

        val at = raw.indexOf('@')
        if (at >= 0) {
            val userinfo = raw.substring(0, at)
            val hostPart = raw.substring(at + 1)
            var dec = B64.decodeToString(userinfo)
            if (dec == null || !dec.contains(':')) dec = Text.unquote(userinfo)
            if (dec == null || !dec.contains(':')) return null
            method = dec.substringBefore(':').trim()
            password = dec.substringAfter(':')
            val hp = splitHostPort(hostPart) ?: return null
            host = hp.first
            port = hp.second
        } else {
            val dec = B64.decodeToString(raw) ?: return null
            val parts = dec.split(':')
            if (parts.size < 4) return null
            method = parts[0].trim()
            password = parts[1]
            host = parts[2]
            port = parts[3].trim().toIntOrNull() ?: return null
        }
        if (host.isEmpty() || port <= 0 || method.isEmpty()) return null
        val p = linkedMapOf<String, Any?>(
            "name" to Text.unquote(frag).ifBlank { "$host:$port" },
            "type" to "ss",
            "server" to host,
            "port" to port,
            "cipher" to method,
            "password" to password,
            "udp" to true,
            "_uri" to uri
        )
        val plugin = Text.queryParam(raw.substringAfter('?', ""), "plugin")
        if (!plugin.isNullOrEmpty()) parsePlugin(p, plugin)
        return p
    }

    // ------------------------------------------------------------------ ssr

    private fun parseSsr(uri: String): LinkedHashMap<String, Any?>? {
        val raw = uri.removePrefix("ssr://")
        val dec = B64.decodeToString(raw) ?: return null
        val body = dec.substringBefore('?')
        val query = dec.substringAfter('?', "")
        val parts = body.split(':')
        if (parts.size < 6) return null
        val server = parts[0]
        val port = parts[1].toIntOrNull() ?: return null
        val protocol = parts[2]
        val method = parts[3]
        val obfs = parts[4]
        val passB64 = parts[5]
        val p = linkedMapOf<String, Any?>(
            "name" to (B64.decodeToString(Text.queryParam(query, "remarks")) ?: "$server:$port"),
            "type" to "ssr",
            "server" to server,
            "port" to port,
            "cipher" to method,
            "password" to (B64.decodeToString(passB64) ?: ""),
            "protocol" to protocol,
            "obfs" to obfs,
            "udp" to true,
            "_uri" to uri
        )
        val obfsparam = Text.queryParam(query, "obfsparam")
        if (!obfsparam.isNullOrEmpty()) {
            val v = B64.decodeToString(obfsparam)
            if (!v.isNullOrEmpty()) p["obfs-param"] = v
        }
        val protoparam = Text.queryParam(query, "protoparam")
        if (!protoparam.isNullOrEmpty()) {
            val v = B64.decodeToString(protoparam)
            if (!v.isNullOrEmpty()) p["protocol-param"] = v
        }
        return p
    }

    // ------------------------------------------------------------- hysteria

    private val HY2_RE =
        Regex("""(?:hysteria2|hy2)://([^@]*)@(\[[0-9A-Fa-f:.]+\]|[^:/?#]+):(\d+)([^#]*)(?:#(.*))?""")

    private fun parseHysteria2(uri: String): LinkedHashMap<String, Any?>? {
        val m = HY2_RE.find(uri) ?: return null
        val auth = m.groupValues[1]
        val host = bareHost(m.groupValues[2])
        val port = m.groupValues[3].toIntOrNull() ?: return null
        val query = m.groupValues[4]
        val p = linkedMapOf<String, Any?>(
            "name" to Text.unquote(m.groupValues[5]).ifBlank { "$host:$port" },
            "type" to "hysteria2",
            "server" to host,
            "port" to port,
            "password" to auth,
            "_uri" to uri
        )
        Text.queryParam(query, "sni")?.let { if (it.isNotEmpty()) p["sni"] = it }
        if (isTruthy(Text.queryParam(query, "insecure"))) p["skip-cert-verify"] = true
        Text.queryParam(query, "obfs")?.let { if (it.isNotEmpty()) p["obfs"] = it }
        Text.queryParam(query, "obfs-password")?.let { if (it.isNotEmpty()) p["obfs-password"] = it }
        return p
    }

    private val HY1_RE =
        Regex("""hysteria://(\[[0-9A-Fa-f:.]+\]|[^:/?#]+):(\d+)([^#]*)(?:#(.*))?""")

    private fun parseHysteria(uri: String): LinkedHashMap<String, Any?>? {
        val m = HY1_RE.find(uri) ?: return null
        val host = bareHost(m.groupValues[1])
        val port = m.groupValues[2].toIntOrNull() ?: return null
        val query = m.groupValues[3]
        val p = linkedMapOf<String, Any?>(
            "name" to Text.unquote(m.groupValues[4]).ifBlank { "$host:$port" },
            "type" to "hysteria",
            "server" to host,
            "port" to port,
            "protocol" to (Text.queryParam(query, "protocol") ?: "udp"),
            "auth-str" to (Text.queryParam(query, "auth") ?: ""),
            "up" to (Text.queryParam(query, "up") ?: "100"),
            "down" to (Text.queryParam(query, "down") ?: "100"),
            "udp" to true,
            "_uri" to uri
        )
        Text.queryParam(query, "sni")?.let { if (it.isNotEmpty()) p["sni"] = it }
        if (isTruthy(Text.queryParam(query, "insecure"))) p["skip-cert-verify"] = true
        return p
    }

    // ----------------------------------------------------------------- tuic

    private val TUIC_RE =
        Regex("""tuic://([^@]+)@(\[[0-9A-Fa-f:.]+\]|[^:/?#]+):(\d+)([^#]*)(?:#(.*))?""")

    private fun parseTuic(uri: String): LinkedHashMap<String, Any?>? {
        val m = TUIC_RE.find(uri) ?: return null
        val userinfo = m.groupValues[1]
        val host = bareHost(m.groupValues[2])
        val port = m.groupValues[3].toIntOrNull() ?: return null
        val query = m.groupValues[4]
        val uuid = userinfo.substringBefore(':')
        val password = if (userinfo.contains(':')) userinfo.substringAfter(':') else ""
        val p = linkedMapOf<String, Any?>(
            "name" to Text.unquote(m.groupValues[5]).ifBlank { "$host:$port" },
            "type" to "tuic",
            "server" to host,
            "port" to port,
            "uuid" to uuid,
            "password" to password,
            "_uri" to uri
        )
        Text.queryParam(query, "congestion_control")?.let { if (it.isNotEmpty()) p["congestion-controller"] = it }
        Text.queryParam(query, "udp_relay_mode")?.let { if (it.isNotEmpty()) p["udp-relay-mode"] = it }
        Text.queryParam(query, "alpn")?.let { if (it.isNotEmpty()) p["alpn"] = it.split(',') }
        Text.queryParam(query, "sni")?.let { if (it.isNotEmpty()) p["sni"] = it }
        if (isTruthy(Text.queryParam(query, "allow_insecure"))) p["skip-cert-verify"] = true
        return p
    }

    fun uriToProxy(uri: String): Node? {
        val map = try {
            val scheme = uri.substringBefore(':').lowercase(Locale.ROOT)
            when (scheme) {
                "vmess" -> parseVmess(uri)
                "vless" -> parseVless(uri)
                "trojan" -> parseTrojan(uri)
                "ss" -> parseSs(uri)
                "ssr" -> parseSsr(uri)
                "hy2", "hysteria2" -> parseHysteria2(uri)
                "hysteria" -> parseHysteria(uri)
                "tuic" -> parseTuic(uri)
                else -> null
            }
        } catch (e: Exception) {
            null
        } ?: return null
        if (map["server"] == null) return null
        return Node(map)
    }
}
