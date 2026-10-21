package com.szech.walls.export

import com.szech.walls.model.Node

/**
 * 从节点名认地区，导出 Clash 配置时按地区分组。
 *
 * 跟 Windows 版 tools/daily_auto.py 的 region_of() 同一套关键词，两边结果一致。
 * 节点名是源站起的，什么写法都有，所以只做「认得出就分组、认不出就不管」。
 *
 * 为什么要地区组：Clash 的「自动选择」只看延迟，今天选香港、明天可能换成美国；
 * 想固定用某个地区（比如看港区流媒体），直接选那个地区的组就行。
 */
object Regions {

    private data class Def(val key: String, val display: String, val keywords: List<String>)

    private val DEFS = listOf(
        Def("HK", "🇭🇰 香港", listOf("香港", "港", "🇭🇰", "hk", "hong kong", "hongkong")),
        Def("TW", "🇹🇼 台湾", listOf("台湾", "台灣", "台", "🇹🇼", "tw", "taiwan")),
        Def("JP", "🇯🇵 日本", listOf("日本", "东京", "大阪", "日", "🇯🇵", "jp", "japan")),
        Def("SG", "🇸🇬 新加坡", listOf("新加坡", "狮城", "🇸🇬", "sg", "singapore")),
        Def("US", "🇺🇸 美国", listOf("美国", "美國", "洛杉矶", "圣何塞", "美", "🇺🇸", "us", "usa", "united states")),
        Def("KR", "🇰🇷 韩国", listOf("韩国", "韓國", "首尔", "韩", "🇰🇷", "kr", "korea")),
        Def("GB", "🇬🇧 英国", listOf("英国", "伦敦", "英", "🇬🇧", "uk", "gb", "britain", "london")),
        Def("DE", "🇩🇪 德国", listOf("德国", "德國", "法兰克福", "德", "🇩🇪", "de", "germany")),
        Def("NL", "🇳🇱 荷兰", listOf("荷兰", "荷", "🇳🇱", "nl", "netherlands")),
        Def("FR", "🇫🇷 法国", listOf("法国", "法國", "巴黎", "法", "🇫🇷", "fr", "france")),
        Def("IT", "🇮🇹 意大利", listOf("意大利", "義大利", "米兰", "🇮🇹", "it", "italy")),
        Def("ES", "🇪🇸 西班牙", listOf("西班牙", "🇪🇸", "es", "spain")),
        Def("SE", "🇸🇪 瑞典", listOf("瑞典", "🇸🇪", "se", "sweden")),
        Def("CH", "🇨🇭 瑞士", listOf("瑞士", "🇨🇭", "ch", "switzerland")),
        Def("AT", "🇦🇹 奥地利", listOf("奥地利", "奧地利", "🇦🇹", "at", "austria")),
        Def("DK", "🇩🇰 丹麦", listOf("丹麦", "🇩🇰", "dk", "denmark")),
        Def("FI", "🇫🇮 芬兰", listOf("芬兰", "🇫🇮", "fi", "finland")),
        Def("NO", "🇳🇴 挪威", listOf("挪威", "🇳🇴", "no", "norway")),
        Def("PL", "🇵🇱 波兰", listOf("波兰", "🇵🇱", "pl", "poland")),
        Def("CZ", "🇨🇿 捷克", listOf("捷克", "🇨🇿", "cz", "czech")),
        Def("RO", "🇷🇴 罗马尼亚", listOf("罗马尼亚", "🇷🇴", "ro", "romania")),
        Def("TR", "🇹🇷 土耳其", listOf("土耳其", "🇹🇷", "tr", "turkey")),
        Def("RU", "🇷🇺 俄罗斯", listOf("俄罗斯", "俄", "🇷🇺", "ru", "russia")),
        Def("CA", "🇨🇦 加拿大", listOf("加拿大", "🇨🇦", "ca", "canada")),
        Def("BR", "🇧🇷 巴西", listOf("巴西", "🇧🇷", "br", "brazil")),
        Def("AU", "🇦🇺 澳大利亚", listOf("澳大利亚", "澳洲", "🇦🇺", "au", "australia")),
        Def("IN", "🇮🇳 印度", listOf("印度", "🇮🇳", "in", "india")),
        Def("AE", "🇦🇪 阿联酋", listOf("阿联酋", "迪拜", "🇦🇪", "ae", "dubai")),
        Def("MY", "🇲🇾 马来西亚", listOf("马来西亚", "🇲🇾", "my", "malaysia")),
        Def("VN", "🇻🇳 越南", listOf("越南", "🇻🇳", "vn", "vietnam")),
        Def("TH", "🇹🇭 泰国", listOf("泰国", "🇹🇭", "th", "thailand")),
        Def("PH", "🇵🇭 菲律宾", listOf("菲律宾", "🇵🇭", "ph", "philippines")),
        Def("ID", "🇮🇩 印尼", listOf("印尼", "印度尼西亚", "🇮🇩", "id", "indonesia")),
        Def("ZA", "🇿🇦 南非", listOf("南非", "🇿🇦", "za", "south africa"))
    )

    /** 单节点地区不单独成组，免得配置里堆一堆只有一个成员的下拉项 */
    const val MIN_MEMBERS = 2

    fun regionOf(name: String): String? {
        if (name.isBlank()) return null
        val low = name.lowercase()
        for (d in DEFS) {
            for (kw in d.keywords) {
                if (kw.all { it.code < 128 }) {
                    // 字母关键字要求词边界，否则 "us" 会命中 "russia"、"plus"
                    if (Regex("(?<![a-z])${Regex.escape(kw)}(?![a-z])").containsMatchIn(low)) return d.key
                } else if (name.contains(kw)) {
                    return d.key
                }
            }
        }
        return null
    }

    /** 返回 (显示名, 成员名) 列表，只含节点数 ≥ [min] 的地区 */
    fun groups(nodes: List<Node>, names: List<String>, min: Int = MIN_MEMBERS): List<Pair<String, List<String>>> {
        val byKey = LinkedHashMap<String, MutableList<String>>()
        nodes.forEachIndexed { i, n ->
            val key = regionOf(names.getOrElse(i) { n.name }) ?: return@forEachIndexed
            byKey.getOrPut(key) { ArrayList() }.add(names.getOrElse(i) { n.name })
        }
        val out = ArrayList<Pair<String, List<String>>>()
        for (d in DEFS) {
            val members = byKey[d.key] ?: continue
            if (members.size < min) continue
            out.add(d.display to members)
        }
        return out
    }
}
