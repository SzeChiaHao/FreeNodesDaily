package com.szech.walls.ui.guide

import com.szech.walls.R
import com.szech.walls.ui.WallsPageFragment

/** 用户指南：平实口语讲清楚怎么用、为什么这样做 */
class GuideFragment : WallsPageFragment() {

    override fun build() {
        val col = page.col
        ui.header(col, R.drawable.ic_menu_book, "用户指南", "怎么用，以及它是怎么跑起来的")

        // ---------- 第一次用 ----------
        val c1 = ui.card(col, R.drawable.ic_play_arrow, "第一次用，按这个顺序来")
        val s1 = ui.section(c1)
        s1.addView(
            ui.label(
                "1. 先回「概览」，点一下「开始更新」。它会去各个源上把节点抓下来，然后逐个测速。" +
                    "头一回大概要一两分钟，时间基本都花在测速上。\n\n" +
                    "2. 测完之后切到「节点」，那里是按速度排好的名单，最快的排在最前头。" +
                    "想看某一条的底细就点它，里面能复制分享链接。\n\n" +
                    "3. 跑完去「导出」，挑你那个客户端认的文件，点右边的生成按钮。文件会落进「下载/Walls/」。" +
                    "Clash 系拿 clash.yaml，v2rayNG、NekoBox 拿 v2ray-订阅.txt，Shadowsocks 系拿 sip008.json。\n\n" +
                    "4. 打开你装的客户端，找「导入配置」或者「从文件导入」，把刚生成的那个文件挑进去就行了。" +
                    "以后想换新的，回「概览」再跑一次，再导一遍。同名文件会覆盖，客户端里还是同一条配置。" +
                    "要是「抓取后自动保存」开着，连生成这步都不用点，跑完直接去客户端导文件就行。",
                R.style.TextAppearance_Walls_TileLabel
            )
        )

        // ---------- 两个按钮的区别 ----------
        val c2 = ui.card(col, R.drawable.ic_bolt, "「开始更新」和「快速更新」有什么不一样")
        val s2 = ui.section(c2)
        s2.addView(
            ui.label(
                "「开始更新」会真的下点东西来测速度，量出来的数靠谱，代价是慢。\n\n" +
                    "「快速更新」只连一下看通不通，快得多，但名单里没有速度，谁也不比谁快慢，" +
                    "只能拿来应急看看现在有没有活着的节点。",
                R.style.TextAppearance_Walls_TileLabel
            )
        )

        // ---------- 节点太少 ----------
        val c3 = ui.card(col, R.drawable.ic_filter_alt, "抓到的节点很少，是不是坏了")
        val s3 = ui.section(c3)
        s3.addView(
            ui.label(
                "多半没坏。免费节点时好时坏，晚上尤其容易挂，同一批源白天能活几十个，到了半夜可能就剩十几个。\n\n" +
                    "要是嫌少，去设置里把「只保留可实测协议的节点」关掉。这个开关开着的时候，" +
                    "vmess、hysteria2 这些本机测不了速度的协议会被直接扔掉；关掉以后它们也能留在名单里，" +
                    "数目会多不少，只是排名只能看延迟，不代表真实快慢。",
                R.style.TextAppearance_Walls_TileLabel
            )
        )

        // ---------- 原理 ----------
        val c4 = ui.card(col, R.drawable.ic_monitoring, "它是怎么跑起来的")
        val s4 = ui.section(c4)
        s4.addView(
            ui.label(
                "先是抓。应用去一批公开的订阅源上把节点地址扒下来，去重之后放进候选池。\n\n" +
                    "然后是测。先挨个连一下，看看通不通，这一步很快。通的里面再挑一批，" +
                    "真的建一条隧道下一小段文件，用花了多少时间算出速度。这一步慢，也是最花流量的，" +
                    "默认每个节点下 512KB，四十个节点加起来二十兆左右。\n\n" +
                    "最后是排。按测出来的速度从快到慢排好，留下前面若干个，" +
                    "再转成 Clash、v2ray、Shadowsocks 各家认得的格式，写成文件放进「下载/Walls/」。" +
                    "到客户端里挑文件导入，拿到的就是这个。\n\n" +
                    "整个过程都在你自己的手机上跑，中间不经过任何服务器。节点是别人公开出来的，" +
                    "这个应用只是把它们找出来、测一测、整理好，既不提供也不担保任何一个。",
                R.style.TextAppearance_Walls_TileLabel
            )
        )

        // ---------- 提醒 ----------
        val c5 = ui.card(col, R.drawable.ic_error, "几句提醒")
        val s5 = ui.section(c5)
        s5.addView(
            ui.label(
                "免费节点是公开的，谁都能看见，别拿它登录要紧的账号，也别在上面做转账之类的事。\n\n" +
                    "文件都放在「下载/Walls/」，同名覆盖，不会越攒越多；想腾地方直接删掉整个文件夹也不影响应用。" +
                    "客户端把导入的文件拷走了，换了新文件之后还得再导一次，它不知道原文件变了。",
                R.style.TextAppearance_Walls_TileLabel
            )
        )
    }
}
