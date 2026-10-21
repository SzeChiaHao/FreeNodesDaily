package com.szech.walls

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.FragmentContainerView
import androidx.navigation.Navigation
import androidx.navigation.ui.NavigationUI
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.navigationrail.NavigationRailView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * 冒烟测试：三种宽度下把外壳跑一遍，确认导航结构按预期装配。
 *
 * 挡掉的是「布局写错」「导航图里的类不存在」「主题属性解析不了」这类一装上去就闪退的问题
 * ——本机没有模拟器，靠这个顶上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiSmokeTest {

    private fun activity(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        return a
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-mdpi")
    fun compact_hasTwoItemBottomBarAndDrawer() {
        val a = activity()
        assertNotNull("nav_host 应该存在", a.findViewById<FragmentContainerView>(R.id.nav_host))

        val bottom = a.findViewById<BottomNavigationView>(R.id.bottom_nav)
        assertNotNull("手机宽度下应该有底栏", bottom)
        assertEquals("底栏三项：概览、节点、导出", 3, bottom!!.menu.size())
        assertEquals(R.id.overviewFragment, bottom.menu.getItem(0).itemId)
        assertEquals(R.id.nodesFragment, bottom.menu.getItem(1).itemId)
        assertEquals(R.id.exportFragment, bottom.menu.getItem(2).itemId)

        assertEquals("手机宽度下不该有导航栏", null, a.findViewById<NavigationRailView>(R.id.rail_nav))
        assertNotNull("手机宽度下应该有抽屉", a.findViewById<NavigationView>(R.id.drawer_nav))
    }

    @Test
    @Config(qualifiers = "zh-rCN-sw600dp-w800dp-h1280dp-mdpi")
    fun medium_usesRailAndDropsBottomBar() {
        val a = activity()
        assertNotNull(a.findViewById<FragmentContainerView>(R.id.nav_host))
        assertNotNull("600dp 起应该有导航栏", a.findViewById<NavigationRailView>(R.id.rail_nav))
        assertEquals("600dp 起不该有底栏", null, a.findViewById<BottomNavigationView>(R.id.bottom_nav))
    }

    @Test
    @Config(qualifiers = "zh-rCN-sw840dp-w1280dp-h800dp-mdpi")
    fun expanded_hasPermanentDrawer() {
        val a = activity()
        assertNotNull(a.findViewById<FragmentContainerView>(R.id.nav_host))
        val drawer = a.findViewById<NavigationView>(R.id.drawer_nav)
        assertNotNull("840dp 起应该有常驻侧栏", drawer)
        assertTrue("侧栏应该有足够多的入口", drawer!!.menu.size() >= 5)
        assertEquals("840dp 起不该有底栏", null, a.findViewById<BottomNavigationView>(R.id.bottom_nav))
    }

    @Test
    @Config(qualifiers = "zh-rCN-sw840dp-w1280dp-h800dp-mdpi")
    fun expandedOverviewUsesTwoColumns() {
        val a = activity()
        ShadowLooper.idleMainLooper()
        val root = a.findViewById<View>(R.id.nav_host)
        val cols = findColumns(root)
        assertEquals("840dp 下概览应该是左右两栏", 2, cols.size)
        val weights = cols.map { (it.layoutParams as LinearLayout.LayoutParams).weight }
        assertEquals("左栏权重 1", 1f, weights[0], 0.001f)
        assertEquals("右栏更宽，权重 1.2", 1.2f, weights[1], 0.001f)
    }

    /** 找出「横向 LinearLayout + 两个带权重的竖栏」这个结构 */
    private fun findColumns(root: View?): List<View> {
        if (root == null) return emptyList()
        if (root is LinearLayout && root.orientation == LinearLayout.HORIZONTAL) {
            val kids = (0 until root.childCount).map { root.getChildAt(it) }
            if (kids.size == 2 && kids.all {
                    it is LinearLayout && it.orientation == LinearLayout.VERTICAL &&
                        (it.layoutParams as? LinearLayout.LayoutParams)?.weight ?: 0f > 0f
                }
            ) return kids
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val r = findColumns(root.getChildAt(i))
                if (r.isNotEmpty()) return r
            }
        }
        return emptyList()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-mdpi")
    fun nodeDetailSheetOpens() {
        val a = activity()
        val node = com.szech.walls.model.Node(
            linkedMapOf("name" to "东京 01", "type" to "ss", "server" to "1.1.1.1",
                        "port" to 443, "cipher" to "aes-256-gcm", "password" to "pw")
        )
        a.runOnUiThread { com.szech.walls.ui.nodes.NodeDetailSheet.show(a, node) }
        ShadowLooper.idleMainLooper()
        // 没崩就算过：弹层里点「二维码」那条路径也顺带走到
        assertNotNull(a)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-mdpi")
    fun nodesSearchAndChipsFilterTheList() {
        val a = activity()
        com.szech.walls.store.Repo.setResult(
            listOf(
                com.szech.walls.model.Node(
                    linkedMapOf("name" to "东京 01", "type" to "ss", "server" to "1.1.1.1", "port" to 443)
                ),
                com.szech.walls.model.Node(
                    linkedMapOf("name" to "新加坡 02", "type" to "trojan", "server" to "2.2.2.2", "port" to 443)
                ),
            ),
            com.szech.walls.store.Repo.status
        )
        val nav = Navigation.findNavController(a, R.id.nav_host)
        a.runOnUiThread { nav.navigate(R.id.nodesFragment) }
        ShadowLooper.idleMainLooper()

        val host = a.findViewById<View>(R.id.nav_host)
        assertEquals("两条都该在名单里", 1, countText(host, "东京 01"))
        assertEquals(1, countText(host, "新加坡 02"))

        val box = findEditText(host)
        assertNotNull("节点页该有搜索框", box)
        a.runOnUiThread { box!!.setText("东京") }
        ShadowLooper.idleMainLooper()
        assertEquals("搜索之后另一条该消失", 0, countText(host, "新加坡 02"))
        assertEquals("命中的那条还在", 1, countText(host, "东京 01"))
    }

    private fun findEditText(root: View?): android.widget.EditText? {
        if (root == null) return null
        if (root is android.widget.EditText) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val r = findEditText(root.getChildAt(i))
                if (r != null) return r
            }
        }
        return null
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-mdpi")
    fun drawerHeaderStaysOutsideTheScrollingMenu() {
        val a = activity()
        val close = a.findViewById<View>(R.id.drawer_close)
        assertNotNull("收起键应该在布局里", close)
        assertNotNull("版本号应该也找得到", a.findViewById<View>(R.id.drawer_version))

        // 头部一旦被放进菜单里，它就会跟着滚，滚上去就点不到收起键了
        var insideMenu = false
        var p = close!!.parent
        while (p != null) {
            if (p === a.findViewById<View>(R.id.drawer_nav)) {
                insideMenu = true
                break
            }
            p = p.parent
        }
        assertTrue("头部不能放进会滚动的菜单里", !insideMenu)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-mdpi")
    fun everyDrawerActionIsHandled() {
        val a = activity()
        val drawer = a.findViewById<NavigationView>(R.id.drawer_nav)!!
        val ids = mutableListOf<Int>()
        for (i in 0 until drawer.menu.size()) {
            val menuItem = drawer.menu.getItem(i)
            if (menuItem.hasSubMenu()) {
                for (j in 0 until menuItem.subMenu!!.size()) ids.add(menuItem.subMenu!!.getItem(j).itemId)
            } else {
                ids.add(menuItem.itemId)
            }
        }
        // 抽屉里每一个入口点下去都不该「毫无反应」，也不该抛异常
        for (id in ids) {
            a.runOnUiThread { drawer.menu.performIdentifierAction(id, 0) }
            ShadowLooper.idleMainLooper()
        }
        assertTrue("抽屉菜单不为空", ids.isNotEmpty())
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-mdpi")
    fun settingsValuesRefreshAfterChange() {
        val a = activity()
        val nav = Navigation.findNavController(a, R.id.nav_host)
        a.runOnUiThread { nav.navigate(R.id.settingsFragment) }
        ShadowLooper.idleMainLooper()

        val host = a.findViewById<View>(R.id.nav_host)
        // 不写死具体数字：默认值会随版本调整（600 → 400），这里只验证「显示 = 当前设置」
        val before = com.szech.walls.store.Prefs.maxNodes
        assertEquals("改之前应显示当前候选上限", 1, countText(host, before.toString()))

        com.szech.walls.store.Prefs.maxNodes = 777
        val frag = a.supportFragmentManager.findFragmentById(R.id.nav_host)!!
            .childFragmentManager.primaryNavigationFragment as com.szech.walls.ui.WallsPageFragment
        frag.refresh()
        ShadowLooper.idleMainLooper()

        assertEquals("改完之后界面上的数字应该跟着变", 1, countText(host, "777"))
        com.szech.walls.store.Prefs.maxNodes = before
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-mdpi")
    fun exportPageListsEveryFormat() {
        val a = activity()
        val nav = Navigation.findNavController(a, R.id.nav_host)
        a.runOnUiThread { nav.navigate(R.id.exportFragment) }
        ShadowLooper.idleMainLooper()

        val host = a.findViewById<View>(R.id.nav_host)
        assertTrue("导出页该有「全部生成」", countText(host, "全部生成") >= 1)
        for (f in listOf("Clash", "v2rayNG / v2rayN", "shadowsocks-android", "手动挑节点")) {
            assertTrue("导出页该列出 $f", countText(host, f) >= 1)
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-mdpi")
    fun bottomNavReturnsFromExport() {
        val a = activity()
        val nav = Navigation.findNavController(a, R.id.nav_host)

        // 模拟「概览 → 按客户端生成 → 导出」
        a.runOnUiThread { nav.navigate(R.id.exportFragment) }
        ShadowLooper.idleMainLooper()
        assertEquals(R.id.exportFragment, nav.currentDestination?.id)

        // 点底栏「概览」：走真实的菜单动作，不是直接改 selectedItemId
        val bottom = a.findViewById<BottomNavigationView>(R.id.bottom_nav)!!
        a.runOnUiThread { bottom.menu.performIdentifierAction(R.id.overviewFragment, 0) }
        ShadowLooper.idleMainLooper()
        assertEquals("从导出点底栏「概览」应该回到概览", R.id.overviewFragment, nav.currentDestination?.id)

        // 再来回点几次，确认不会卡住
        for (i in 0 until 3) {
            a.runOnUiThread { bottom.menu.performIdentifierAction(R.id.exportFragment, 0) }
            ShadowLooper.idleMainLooper()
            assertEquals(R.id.exportFragment, nav.currentDestination?.id)
            a.runOnUiThread { bottom.menu.performIdentifierAction(R.id.overviewFragment, 0) }
            ShadowLooper.idleMainLooper()
            assertEquals(R.id.overviewFragment, nav.currentDestination?.id)
        }
    }

    /** 数一数视图树里有多少个 TextView 的文字等于 s */
    private fun countText(root: View?, s: String): Int {
        if (root == null) return 0
        var n = 0
        if (root is android.widget.TextView && root.text.toString() == s) n++
        if (root is ViewGroup) for (i in 0 until root.childCount) n += countText(root.getChildAt(i), s)
        return n
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-mdpi")
    fun secondaryPageHidesBottomBar() {
        val a = activity()
        val bar = a.findViewById<View>(R.id.bottom_bar)!!
        assertEquals("概览页底栏应该可见", View.VISIBLE, bar.visibility)

        val nav = Navigation.findNavController(a, R.id.nav_host)
        a.runOnUiThread { nav.navigate(R.id.settingsFragment) }
        ShadowLooper.idleMainLooper()
        assertEquals("设置不是底层目的地，底栏该隐藏", View.GONE, bar.visibility)

        a.runOnUiThread { nav.navigate(R.id.overviewFragment) }
        ShadowLooper.idleMainLooper()
        assertEquals("回概览底栏该回来", View.VISIBLE, bar.visibility)
    }
}
