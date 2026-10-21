package com.szech.walls

import com.szech.walls.export.Regions
import com.szech.walls.pipeline.SourceSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源清单语法与地区识别。
 *
 * 这两个都是从 Windows 版 tools/ 搬过来的纯逻辑，错了不会崩，只会「默默少抓一半节点」，
 * 所以专门盯一下：语法解析、ignore/max 的边界、地区别乱认。
 */
class SourceListTest {

    @Test
    fun `注释与空行被忽略`() {
        assertNull(SourceSpec.parse(""))
        assertNull(SourceSpec.parse("   "))
        assertNull(SourceSpec.parse("# https://example.com/x"))
        assertNull(SourceSpec.parse("EOF"))
        assertNull(SourceSpec.parse("不是链接"))
    }

    @Test
    fun `普通链接`() {
        val s = SourceSpec.parse("https://example.com/sub.yaml")!!
        assertEquals("https://example.com/sub.yaml", s.url)
        assertFalse(s.isList)
        assertFalse(s.needsProxy)
        assertEquals(0, s.maxNodes)
        assertTrue(s.ignore.isEmpty())
    }

    @Test
    fun `行首标记`() {
        val p = SourceSpec.parse("!https://example.com/a")!!
        assertTrue(p.needsProxy)
        val l = SourceSpec.parse("*https://example.com/b")!!
        assertTrue(l.isList)
        val both = SourceSpec.parse("!*https://example.com/c")!!
        assertTrue(both.needsProxy && both.isList)
    }

    @Test
    fun `行末参数`() {
        val s = SourceSpec.parse("https://example.com/x#max=200&ignore=vmess,SS")!!
        assertEquals(200, s.maxNodes)
        assertTrue(s.ignores("vmess"))
        assertTrue(s.ignores("ss"))       // 大小写不敏感
        assertFalse(s.ignores("trojan"))
    }

    @Test
    fun `type等于list等价于星号`() {
        assertTrue(SourceSpec.parse("https://example.com/p#type=list")!!.isList)
    }

    @Test
    fun `去重与坏行跳过`() {
        val specs = SourceSpec.parseAll(
            listOf(
                "https://example.com/a",
                "https://example.com/a",   // 重复
                "# 注释",
                "https://example.com/b#max=10"
            )
        )
        assertEquals(2, specs.size)
        assertEquals(10, specs[1].maxNodes)
    }

    @Test
    fun `地区识别`() {
        assertEquals("HK", Regions.regionOf("🇭🇰 香港 01"))
        assertEquals("JP", Regions.regionOf("日本东京 03"))
        assertEquals("DE", Regions.regionOf("DE_speednode_0003"))
        assertEquals("RU", Regions.regionOf("russia-moscow"))
        // 别把 plus / russia 里的 us 当美国
        assertNull(Regions.regionOf("plus-node"))
        assertNull(Regions.regionOf("unknown-node-01"))
    }
}
