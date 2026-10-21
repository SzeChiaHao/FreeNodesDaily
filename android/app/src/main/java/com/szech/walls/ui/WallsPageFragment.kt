package com.szech.walls.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

/**
 * 四个页面的共同外壳：建好零件库和一页可滚动的骨架，内容交给子类。
 *
 * 顺带说明：页面自己不管生命周期里的数据回收，数据都在 Repo / Prefs / RunController 三个
 * 单例里；转屏重建页面时重新读一遍就行。
 */
abstract class WallsPageFragment : Fragment() {

    protected lateinit var ui: WallsUi
        private set
    private lateinit var _page: WallsUi.Page
    protected val page: WallsUi.Page get() = _page

    /** 子类在这里往 page.col 里塞内容 */
    protected abstract fun build()

    /** 页面回到前台、或别处改了数据时重新渲染 */
    open fun refresh() {}

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        ui = WallsUi(requireContext())
        _page = ui.page()
        build()
        // 手机宽屏以外都有底部导航栏，内容滚到底时会被它盖住一截，这里补足留白。
        if (resources.configuration.smallestScreenWidthDp < 600) {
            _page.col.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(96))
        }
        return _page.root
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }
}
