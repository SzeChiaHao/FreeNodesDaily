package com.szech.walls.pipeline

/**
 * 一条订阅源。对应 Windows 版 tools/sources.list 的同一套写法。
 *
 * 行首标记：
 *   !  这行需要本地代理才下得动 —— 手机上一般没有，会直接跳过
 *   *  这是「订阅列表页」，页面里的链接会被逐个展开
 * 行末参数（# 后面）：
 *   #type=list          同 *
 *   #max=200            只取前 200 个节点（源很大时省时间、也避免大源吃掉名额）
 *   #ignore=vmess,ss    丢掉这些协议的节点
 *
 * 加源/停源改的就是这类行，保存在设置里（Prefs.sources），不需要改代码。
 */
data class SourceSpec(
    val url: String,
    val isList: Boolean,
    val maxNodes: Int,
    val ignore: Set<String>,
    val needsProxy: Boolean,
    val raw: String
) {
    fun ignores(type: String): Boolean = ignore.contains(type.lowercase())

    companion object {

        fun parseAll(lines: List<String>): List<SourceSpec> {
            val out = ArrayList<SourceSpec>(lines.size)
            val seen = HashSet<String>()
            for (line in lines) {
                val spec = parse(line) ?: continue
                if (!seen.add(spec.url)) continue
                out.add(spec)
            }
            return out
        }

        fun parse(line: String): SourceSpec? {
            var s = line.trim()
            if (s.isEmpty() || s.startsWith("#") || s == "EOF") return null

            var needsProxy = false
            var isList = false
            while (s.isNotEmpty() && (s[0] == '!' || s[0] == '*')) {
                if (s[0] == '!') needsProxy = true else isList = true
                s = s.substring(1)
            }

            var tail = ""
            val hash = s.indexOf('#')
            if (hash >= 0) {
                tail = s.substring(hash + 1)
                s = s.substring(0, hash)
            }
            val url = s.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null

            var maxNodes = 0
            val ignore = LinkedHashSet<String>()
            for (part in tail.split('&')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val k = part.substring(0, eq).trim().lowercase()
                val v = part.substring(eq + 1).trim()
                when (k) {
                    "type" -> if (v.lowercase() == "list") isList = true
                    "max" -> maxNodes = v.toIntOrNull() ?: 0
                    "ignore" -> for (t in v.split(',')) {
                        val tt = t.trim().lowercase()
                        if (tt.isNotEmpty()) ignore.add(tt)
                    }
                }
            }
            return SourceSpec(url, isList, maxNodes, ignore, needsProxy, line.trim())
        }
    }
}
