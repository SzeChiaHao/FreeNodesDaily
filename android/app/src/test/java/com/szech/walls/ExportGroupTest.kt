package com.szech.walls

import com.szech.walls.export.Formats
import com.szech.walls.model.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

/**
 * 导出配置的分层：实测通过的进「自动选择」，未实测的单独成组、不参与自动选择。
 *
 * 这是「导进去却连不上」那个问题的关键：手机端只能真测 ss / trojan / vless，
 * 其余协议只证明端口能连。如果它们混进自动选择，客户端就会去连一堆死节点。
 */
class ExportGroupTest {

    private fun node(name: String, speed: Double): Node {
        val m = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "ss",
            "server" to "203.0.113.9",
            "port" to 8388,
            "cipher" to "aes-256-gcm",
            "password" to "pw"
        )
        return Node(m).apply { this.speed = speed }
    }

    @Suppress("UNCHECKED_CAST")
    private fun groups(yml: String): Map<String, List<String>> {
        val root = Yaml().load<Map<String, Any?>>(yml)
        val list = root["proxy-groups"] as List<Map<String, Any?>>
        return list.associate { g ->
            (g["name"] as String) to ((g["proxies"] as? List<*>)?.map { it.toString() } ?: emptyList())
        }
    }

    @Test
    fun `未实测节点单独成组且不进自动选择`() {
        val yml = Formats.clashYaml(listOf(node("ok-node", 2.5), node("dead-node", -1.0)))
        val g = groups(yml)

        assertEquals(listOf("ok-node"), g["🚀 自动选择"])
        assertEquals(listOf("ok-node"), g["🔁 故障转移"])
        // 未实测的要单独一组，名字里明说「可能连不上」
        val unverified = g.entries.firstOrNull { it.key.contains("未实测") }
        assertNotNull("应当有「未实测」单独的组", unverified)
        assertEquals(listOf("dead-node"), unverified!!.value)
        // 手动选择里两边都能选到（给人留出手动试的余地）
        assertEquals(listOf("ok-node", "dead-node"), g["🐟 手动选择"])
    }

    @Test
    fun `全都没实测时自动选择退回全部以免配置不可用`() {
        val yml = Formats.clashYaml(listOf(node("a", -1.0), node("b", -1.0)))
        val g = groups(yml)
        assertEquals(listOf("a", "b"), g["🚀 自动选择"])
    }

    @Test
    fun `配置里带上了能独立运行所需的基础字段`() {
        val yml = Formats.clashYaml(listOf(node("ok", 1.0)))
        val root = Yaml().load<Map<String, Any?>>(yml)
        assertEquals(7890, root["mixed-port"])
        assertEquals("rule", root["mode"])
        assertTrue((root["rules"] as List<*>).contains("MATCH,🚀 自动选择"))
    }
}
