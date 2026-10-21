package com.szech.walls

import android.os.Build
import android.os.Bundle
import androidx.annotation.IdRes
import androidx.navigation.NavOptions
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.szech.walls.run.RunController
import com.szech.walls.store.Prefs
import com.szech.walls.store.Repo
import com.szech.walls.ui.Backup
import com.szech.walls.ui.QrCode
import com.szech.walls.ui.PowerHelp
import com.szech.walls.ui.WallsCmd
import com.szech.walls.ui.WallsUi
import com.szech.walls.ui.scan.ScanContract

/**
 * 外壳：侧栏 / 导航栏 / 底栏 + 一个放页面的容器。
 *
 * 导航形态按宽度自动切换，靠的是三份布局文件：
 *   layout/            手机：底栏两项 + 汉堡抽屉
 *   layout-sw600dp/    600dp 起：左侧导航栏四项 + 汉堡抽屉
 *   layout-sw840dp/    840dp 起：侧栏常驻，没有抽屉也没有底栏
 *
 * 「顶层目的地」在手机上是概览和节点两项，宽屏上是四项 —— 这样手机上从抽屉进
 * 订阅 / 设置时顶栏会出返回箭头、底栏隐藏，宽屏上则是正常的一等页面。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private lateinit var toolbar: MaterialToolbar
    private lateinit var topLevel: Set<Int>

    /** 页面容器，转场动画只动它，不动页面自己 */
    private var hostView: View? = null

    private var drawerLayout: DrawerLayout? = null
    private var navigationView: NavigationView? = null
    private var bottomBar: View? = null
    private var bottomNav: BottomNavigationView? = null
    private var railNav: NavigationRailView? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { content ->
        if (!content.isNullOrBlank()) {
            QrCode.handleScan(this, content) { refreshPages() }
        }
    }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val msg = Backup.restore(this, uri)
            MaterialAlertDialogBuilder(this)
                .setTitle("恢复备份")
                .setMessage(msg)
                .setPositiveButton("好") { _, _ -> recreate() }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val host = supportFragmentManager
            .findFragmentById(R.id.nav_host) as NavHostFragment
        navController = host.navController

        toolbar = findViewById(R.id.toolbar)
        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.drawer_nav)
        bottomBar = findViewById(R.id.bottom_bar)
        bottomNav = findViewById(R.id.bottom_nav)
        railNav = findViewById(R.id.rail_nav)

        val wide = resources.configuration.smallestScreenWidthDp >= 600
        topLevel = if (wide) {
            setOf(
                R.id.overviewFragment, R.id.nodesFragment,
                R.id.exportFragment, R.id.settingsFragment
            )
        } else {
            setOf(R.id.overviewFragment, R.id.nodesFragment, R.id.exportFragment)
        }

        // 底栏的选中态交给 NavigationUI 同步，点击事件自己接管。
        //
        // 这里踩过一个坑：NavigationUI 更新选中态用的是 MenuItem.setChecked()，
        // 不一定会同步底栏自己的 selectedItemId，于是「当前选中项」和「实际页面」会错开；
        // 错开之后用户点那个看起来没选中、其实内部仍被记成选中的项，Material 直接当成
        // 重复点击吞掉，页面就停在原地（表现为「点概览没反应，只在当前页刷新」）。
        // 所以点击一律走我们自己的 navTo()，不看组件内部状态。
        bottomNav?.let { nav ->
            NavigationUI.setupWithNavController(nav, navController)
            nav.setOnItemSelectedListener { item ->
                val id = item.itemId
                if (navController.graph.findNode(id) != null) {
                    navTo(id, topLevel = true)
                    true
                } else {
                    false
                }
            }
            // 万一组件内部把「选中项」记成了这一项（页面却不是它），
            // 点击会走「重复点击」这条分支，onItemSelected 根本不触发。
            // 这里同样接住：重新导一次航，页面和选中态就对齐了。
            nav.setOnItemReselectedListener { item ->
                val id = item.itemId
                if (navController.currentDestination?.id != id &&
                    navController.graph.findNode(id) != null
                ) {
                    navTo(id, topLevel = true)
                }
            }
        }

        // 侧栏与导航栏：选中态交给 NavigationUI 同步，点击事件自己接管，
        // 因为菜单里混了「扫码导入」这类不是目的地的动作项。
        navigationView?.let { nav ->
            NavigationUI.setupWithNavController(nav, navController)
            nav.setNavigationItemSelectedListener { item -> handleMenu(item, fromDrawer = true) }
        }
        railNav?.setOnItemSelectedListener { item -> handleMenu(item, fromDrawer = false) }

        hostView = findViewById(R.id.nav_host)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            try {
                com.szech.walls.core.Log.add(
                    "页面 -> " + resources.getResourceEntryName(destination.id) +
                        if (RunController.running) "（运行中）" else ""
                )
            } catch (e: Exception) {
                // ignore
            }
            syncNav(destination.id)
            animateContentIn()
        }
        syncNav(navController.currentDestination?.id ?: R.id.overviewFragment)

        // 侧栏头是固定在顶上的（不跟着菜单滚），所以直接从布局里找
        // 收起按钮：常驻侧栏的模式下没有抽屉可收，藏掉
        findViewById<android.widget.ImageButton>(R.id.drawer_close)?.apply {
            visibility = if (drawerLayout == null) View.GONE else View.VISIBLE
            setOnClickListener { drawerLayout?.closeDrawer(GravityCompat.START) }
        }

        // Android 13+ 要用户点头才给发通知；前台服务的进度条也靠它
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
        }

        // 启动时静默检查一次更新（发现有新版才弹窗）
        WallsCmd.checkUpdate(this, this, manual = false)

        updateDrawerHeader()

        // 第一次进来问一次「要不要允许后台运行」，之后不再主动打扰；
        // 想复查去设置里那一条。
        if (!Prefs.powerAsked) {
            window.decorView.post { PowerHelp.ask(this) }
        }
    }

    override fun onResume() {
        super.onResume()
        updateDrawerHeader()
        // 兜底：任何情况下回到前台都保证容器是可见的
        hostView?.apply {
            animate().cancel()
            alpha = 1f
            translationY = 0f
        }
    }

    /**
     * 页面切换的转场：只对容器做一次「淡入 + 轻微上浮」。
     *
     * 之前是给每个页面挂 Fragment 的 setCustomAnimations，alpha 从 0 动画到 1；
     * 手指点得快时上一段动画被打断，页面就永久停在 alpha=0，只剩顶栏标题。
     *
     * 现在换一种写法，把「最后一定是可见的」写死：
     *   - 动画对象是常驻的容器，不是即将被销毁的页面视图；
     *   - 每次开始前先 cancel()，再显式把目标值设成 1；
     *   - withEndAction 里再兜一次 alpha=1、translationY=0；
     *   - onResume 里还会再复位一次。
     * 就算动画被系统掐掉，也不会留在一个看不见的状态。
     */
    private fun animateContentIn() {
        val v = hostView ?: return
        // 用户关掉了系统动画就别硬来
        val scale = try {
            android.provider.Settings.Global.getFloat(
                contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            )
        } catch (e: Exception) {
            1f
        }
        if (scale == 0f) {
            v.alpha = 1f
            v.translationY = 0f
            return
        }
        val dy = 12 * resources.displayMetrics.density
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = dy
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                v.alpha = 1f
                v.translationY = 0f
            }
            .start()
        // 兜底：主线程被别的活占住、动画没跑完时，300ms 后强行恢复可见。
        // 宁可少一段过渡，也不能让用户对着一片空白。
        v.postDelayed({
            if (v.alpha < 1f || v.translationY != 0f) {
                v.animate().cancel()
                v.alpha = 1f
                v.translationY = 0f
            }
        }, 300)
    }

    private fun updateDrawerHeader() {
        findViewById<TextView>(R.id.drawer_version)
            ?.text = "v${WallsCmd.currentVersion(this).first} · ${Repo.nodes.size} 个节点"
    }

    /** 数据被改动后，让当前页面重画一遍 */
    private fun refreshPages() {
        val nav = supportFragmentManager.findFragmentById(R.id.nav_host)
        val current = nav?.childFragmentManager?.primaryNavigationFragment
        if (current is com.szech.walls.ui.WallsPageFragment) current.refresh()
        updateDrawerHeader()
    }

    /** 菜单点击：是目的地就导航，不是就交给各自的动作 */
    private fun handleMenu(item: MenuItem, fromDrawer: Boolean): Boolean {
        when (item.itemId) {
            R.id.action_open_drawer -> {
                drawerLayout?.openDrawer(GravityCompat.START)
                return true
            }
            R.id.action_scan_import -> {
                closeDrawer(fromDrawer)
                scanLauncher.launch(Unit)
                return true
            }
            R.id.action_backup -> {
                closeDrawer(fromDrawer)
                showBackupDialog()
                return true
            }
            R.id.action_edit_sources -> {
                closeDrawer(fromDrawer)
                WallsCmd.showSourcesDialog(this) { refreshPages() }
                return true
            }
            R.id.action_check_update -> {
                closeDrawer(fromDrawer)
                WallsCmd.checkUpdate(this, this, manual = true)
                return true
            }
            R.id.action_github -> {
                closeDrawer(fromDrawer)
                WallsCmd.openUrl(this, WallsCmd.GITHUB_URL)
                return true
            }
            R.id.action_about -> {
                closeDrawer(fromDrawer)
                navWithFade(R.id.aboutFragment)
                return true
            }
            R.id.action_guide -> {
                closeDrawer(fromDrawer)
                navWithFade(R.id.guideFragment)
                return true
            }
            R.id.action_export_all -> {
                closeDrawer(fromDrawer)
                showExportSheet()
                return true
            }
        }
        val id = item.itemId
        if (navController.graph.findNode(id) != null) {
            closeDrawer(fromDrawer)
            navTo(id, topLevel = id in topLevel)
            return true
        }
        return false
    }

    private fun closeDrawer(fromDrawer: Boolean) {
        if (fromDrawer) drawerLayout?.closeDrawer(GravityCompat.START)
    }

    /**
     * 二级页面跳转。
     *
     * 原来这里挂了一套「淡入 + 轻微放大」的自定义动画，结果是：手指点得快的时候，
     * 上一段动画被后一次导航打断，进场页的 alpha 卡在 0，于是只剩顶栏标题、内容一片空白，
     * 点哪儿都没反应，只能杀进程重进。这类 bug 只在真机上偶发，测试很难抓到。
     *
     * 现在改成不带动画的普通导航 + launchSingleTop：
     * 少一点花哨，换「点哪儿就一定出得来」。要动画就等这套导航换回 Compose 再说。
     */
    /**
     * 统一的跳转入口。
     *
     * - 已经在目标页就什么都不做（不会白跑一次重建）；
     * - 顶层页面用 popUpTo(起始页) + launchSingleTop，来回点不会把返回栈堆高；
     * - 二级页面（关于 / 指南）直接压栈，返回时还回上一页。
     */
    private fun navTo(@IdRes id: Int, topLevel: Boolean) {
        if (navController.currentDestination?.id == id) return
        val opts = NavOptions.Builder().setLaunchSingleTop(true)
        if (topLevel) {
            opts.setPopUpTo(navController.graph.startDestinationId, false)
        }
        try {
            navController.navigate(id, null, opts.build())
        } catch (e: Exception) {
            // 目标不是这个图里的页面之类，忽略，别把 App 带崩
        }
    }

    private fun navWithFade(@IdRes id: Int) {
        if (navController.currentDestination?.id == id) return
        navController.navigate(
            id, null,
            NavOptions.Builder()
                .setLaunchSingleTop(true)
                .build()
        )
    }

    /** 导出全部格式：先弹一个底部弹层把七种格式摆出来，想全导还是单导都行 */
    private fun showExportSheet() {
        val ui = WallsUi(this)
        val items = WallsCmd.exportItems(Repo.nodes, WallsCmd.stamp())
        if (items.isEmpty()) {
            WallsCmd.toast(this, "还没有节点可导出，先跑一次更新吧")
            return
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ui.dp(12))
        }
        col.addView(ui.label("导出全部格式", R.style.TextAppearance_Walls_CardTitle).apply {
            setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(4))
        })
        col.addView(ui.label(
            "文件写到「下载/Walls/」，点一条只导这一份。",
            R.style.TextAppearance_Walls_Caption
        ).apply { setPadding(ui.dp(24), 0, ui.dp(24), ui.dp(10)) })

        val card = ui.card(col, 0, "")
        items.forEachIndexed { i, (name, content, mime) ->
            if (i > 0) ui.divider(card)
            card.addView(
                ui.tile(
                    icon = R.drawable.ic_description,
                    title = name,
                    sub = describe(name),
                    chevron = true,
                    onClick = {
                        val written = WallsCmd.writeToDownloads(this, name, content, mime)
                        WallsCmd.toast(
                            this,
                            if (written != null) "已导出 下载/Walls/$written" else "导出失败"
                        )
                    }
                )
            )
        }
        col.addView(ui.space(ui.dp(14)))
        val btn = ui.button(
            "全部导出到下载目录", R.style.ThemeOverlay_Walls_Button, R.drawable.ic_download
        ) {
            WallsCmd.exportToDownloads(this, Repo.nodes)
        }
        btn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(ui.dp(16), 0, ui.dp(16), 0) }
        col.addView(btn)

        val scroll = android.widget.ScrollView(this).apply { addView(col) }
        com.google.android.material.bottomsheet.BottomSheetDialog(this)
            .apply { setContentView(scroll) }
            .show()
    }

    private fun describe(name: String): String = when {
        name.startsWith("clash") -> "Clash / mihomo 用的，Clash Verge、FlClash、NekoBox 都吃这个"
        name.startsWith("v2ray") -> "v2rayNG、v2rayN 的通用订阅（base64）"
        name.startsWith("sip008") -> "Shadowsocks 官方 JSON 订阅格式"
        name.startsWith("ss-plain") -> "ss:// 一行一个，复制粘贴就能导入"
        name.startsWith("ss-base64") -> "shadowsocks-android 的「订阅」那一栏填这个"
        name.startsWith("all-links") -> "所有协议的分享链接，明文，一行一个"
        name.startsWith("report") -> "测速报告，Markdown 格式"
        else -> ""
    }

    private fun showBackupDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("备份与恢复")
            .setMessage(
                "备份内容：11 项设置、订阅源清单、当前节点池（含测速结果）。\n" +
                    "文件写到「下载/Walls/」，恢复时从文件读回来。"
            )
            .setPositiveButton("导出备份") { _, _ ->
                val name = Backup.export(this)
                WallsCmd.toast(this, if (name != null) "已导出 $name" else "导出失败")
            }
            .setNeutralButton("从文件恢复") { _, _ ->
                restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 换页时同步顶栏图标、底栏显隐、三套导航的选中态 */
    private fun syncNav(destinationId: Int) {
        val isTop = destinationId in topLevel
        when {
            isTop && drawerLayout != null -> {
                toolbar.setNavigationIcon(R.drawable.ic_menu)
                toolbar.setNavigationOnClickListener { drawerLayout?.openDrawer(GravityCompat.START) }
            }
            !isTop -> {
                toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
                toolbar.setNavigationOnClickListener { navController.navigateUp() }
            }
            else -> {
                // 840dp 起的常驻侧栏：顶层页面不需要任何导航图标
                toolbar.navigationIcon = null
                toolbar.setNavigationOnClickListener(null)
            }
        }
        bottomBar?.visibility = if (isTop && bottomNav != null) View.VISIBLE else View.GONE
        navigationView?.setCheckedItem(destinationId)

        // 把底栏内部的「选中项」跟真正的目的地对齐。
        // NavigationUI 只 setChecked(true)，不一定改 selectedItemId；一旦错开，
        // 下一次点击就会被当成重复点击吞掉。放到 post 里做，避开导航中的重入。
        bottomNav?.let { bar ->
            if (bar.menu.findItem(destinationId) != null && bar.selectedItemId != destinationId) {
                bar.post {
                    if (bar.selectedItemId != destinationId) bar.selectedItemId = destinationId
                }
            }
        }
    }
}
