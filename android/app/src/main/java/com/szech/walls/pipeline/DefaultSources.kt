package com.szech.walls.pipeline

/**
 * 默认节点源，对应 Windows 版 tools/sources.list。
 *
 * 2026-10-21 用 `daily_auto.py --doctor` 在本机实测过一轮（18 个源全部可用，
 * 合计约 6400 个节点），下面就是那批源。三个已经死透的
 * （learnhard-cn / aiboboxx / mfuu）留在文件末尾的注释里备查。
 *
 * 语法见 [SourceSpec]：`*` 或 `#type=list` 表示订阅列表页，`#max=` 限个数，
 * `#ignore=` 丢协议。用户可以在「设置 → 编辑订阅源」里增删，改动保存在本机。
 */
object DefaultSources {

    val LIST: List<String> = listOf(
        // ===== 综合大源（节点多，是主力）=====
        "https://raw.githubusercontent.com/anaer/Sub/main/clash.yaml",
        "https://raw.githubusercontent.com/ermaozi/get_subscribe/main/subscribe/clash.yml",
        "https://raw.githubusercontent.com/free18/v2ray/main/c.yaml",
        // ss 大户：4000 多条里有一大半是 ss，没有它纯 Shadowsocks 客户端基本空手
        "https://raw.githubusercontent.com/mahdibland/ShadowsocksAggregator/master/sub/sub_merge.txt",
        "https://raw.githubusercontent.com/mahdibland/ShadowsocksAggregator/master/Eternity.yml",

        // ===== 中等体量 =====
        "https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list.txt",
        "https://raw.githubusercontent.com/ermaozi/get_subscribe/main/subscribe/v2ray.txt",
        "https://raw.githubusercontent.com/snakem982/proxypool/main/source/v2ray-2.txt",
        "https://raw.githubusercontent.com/snakem982/proxypool/main/source/clash-meta-2.yaml",
        "https://raw.githubusercontent.com/lagzian/SS-Collector/main/configs/subscription-all.b64",

        // ===== 小体量 / 特色源（hy2 这种协议稀有，数量少也得留着）=====
        "https://raw.githubusercontent.com/Surfboardv2ray/TGParse/main/python/hysteria2",
        "https://raw.githubusercontent.com/zhangkaiitugithub/passcro/main/speednodes.yaml",
        "https://raw.githubusercontent.com/freefq/free/master/v2",
        "https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub",
        "https://raw.githubusercontent.com/ripaojiedian/freenode/main/clash",
        "https://raw.githubusercontent.com/ripaojiedian/freenode/main/sub",
        "https://raw.githubusercontent.com/shaoyouvip/free/refs/heads/main/all.yaml",
        "https://raw.githubusercontent.com/Surfboardv2ray/TGParse/main/python/hy2"

        // 已死，留记录（哪天复活了好找回来）：
        // learnhard-cn/free_proxy_ss/clash/clash.provider.yaml —— 全镜像 404
        // aiboboxx/v2rayfree/main/v2 —— 全镜像 404
        // mfuu/v2ray/master/v2ray —— 偶发能通（5 成功 / 59 失败），不值得占名额
        // abshare 的订阅列表页 —— 能打开但解析不出节点
    )

    /** 聚合页种子：专门收集订阅链接的页面，用来动态发现新源 */
    val SEED_PAGES: List<String> = listOf(
        "https://raw.githubusercontent.com/ermaozi/get_subscribe/main/README.md"
    )
}
