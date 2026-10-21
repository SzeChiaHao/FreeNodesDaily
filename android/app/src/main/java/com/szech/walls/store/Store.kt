package com.szech.walls.store

import android.content.Context
import android.content.SharedPreferences
import com.szech.walls.model.JsonUtil
import com.szech.walls.model.Node
import com.szech.walls.pipeline.DefaultSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Prefs {

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.getSharedPreferences("walls", Context.MODE_PRIVATE)
            migrateDefaults()
        }
    }

    /**
     * 默认值搬家。
     *
     * 只在「用户没改过」时换掉默认（存的值等于旧默认才动），并且只做一次，
     * 免得把用户自己调过的数字盖掉。
     */
    private fun migrateDefaults() {
        val rev = sp.getInt("cfgRev", 1)
        if (rev >= 2) return
        // v2.3：候选 600→400（一轮快三分之一）、测速名额 80→150（实测得更多）
        val e = sp.edit()
        if (sp.getInt("maxNodes", 600) == 600) e.putInt("maxNodes", 400)
        if (sp.getInt("speedTop", 80) == 80) e.putInt("speedTop", 150)
        e.putInt("cfgRev", 2).apply()
    }

    /** 抓取跑完之后，自动把各种格式的配置文件写进「下载/Walls/」 */
    var autoSave: Boolean
        get() = sp.getBoolean("autoSave", true)
        set(v) = sp.edit().putBoolean("autoSave", v).apply()

    /** 是否已经弹过「允许后台运行」的说明 */
    var powerAsked: Boolean
        get() = sp.getBoolean("powerAsked", false)
        set(v) = sp.edit().putBoolean("powerAsked", v).apply()

    /** 每次生成时顺手删掉以前生成的配置文件（只删本应用造的） */
    var cleanOldExports: Boolean
        get() = sp.getBoolean("cleanOldExports", true)
        set(v) = sp.edit().putBoolean("cleanOldExports", v).apply()

    /** 上次生成配置文件的时间（毫秒），0 表示还没生成过 */
    var lastExportAt: Long
        get() = sp.getLong("lastExportAt", 0L)
        set(v) = sp.edit().putLong("lastExportAt", v).apply()

    var autoDaily: Boolean
        get() = sp.getBoolean("autoDaily", true)
        set(v) = sp.edit().putBoolean("autoDaily", v).apply()

    var quick: Boolean
        get() = sp.getBoolean("quick", false)
        set(v) = sp.edit().putBoolean("quick", v).apply()

    var maxNodes: Int
        get() = sp.getInt("maxNodes", 400)
        set(v) = sp.edit().putInt("maxNodes", v).apply()

    var keepTop: Int
        get() = sp.getInt("keepTop", 40)
        set(v) = sp.edit().putInt("keepTop", v).apply()

    var speedTop: Int
        get() = sp.getInt("speedTop", 150)
        set(v) = sp.edit().putInt("speedTop", v).apply()

    var speedBytesKb: Int
        get() = sp.getInt("speedBytesKb", 512)
        set(v) = sp.edit().putInt("speedBytesKb", v).apply()

    var minSpeedTenths: Int
        get() = sp.getInt("minSpeedTenths", 2)
        set(v) = sp.edit().putInt("minSpeedTenths", v).apply()

    /** 只保留「本机能真实测速」的协议（ss / trojan / vless），其余协议只做 TCP 延迟、默认不选 */
    var measurableOnly: Boolean
        get() = sp.getBoolean("measurableOnly", false)
        set(v) = sp.edit().putBoolean("measurableOnly", v).apply()

    var labelSpeed: Boolean
        get() = sp.getBoolean("labelSpeed", true)
        set(v) = sp.edit().putBoolean("labelSpeed", v).apply()

    /**
     * 导出时是否带上「未实测」的节点。
     *
     * 默认关：手机端只能真测 ss / trojan / vless，vmess 之类只测过 TCP 端口，
     * 而「端口能连」远不等于能用。以前这些节点混在主名单里，导进客户端就是一片连不上。
     * 打开后它们会单独放一个组，不参与自动选择。
     */
    var includeUnverified: Boolean
        get() = sp.getBoolean("includeUnverified", false)
        set(v) = sp.edit().putBoolean("includeUnverified", v).apply()

    var mirrorFirst: Boolean
        get() = sp.getBoolean("mirrorFirst", true)
        set(v) = sp.edit().putBoolean("mirrorFirst", v).apply()

    var sources: List<String>
        get() {
            val raw = sp.getString("sources", null) ?: return DefaultSources.LIST
            return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        }
        set(v) = sp.edit().putString("sources", v.joinToString("\n")).apply()

    var discovered: List<String>
        get() {
            val raw = sp.getString("discovered", null) ?: return emptyList()
            return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        }
        set(v) = sp.edit().putString("discovered", v.joinToString("\n")).apply()

    fun resetSources() {
        sp.edit().remove("sources").apply()
    }
}

class RunStatus(
    var lastRunAt: Long = 0L,
    var candidates: Int = 0,
    var alive: Int = 0,
    var measured: Int = 0,
    var sourcesOk: Int = 0,
    var sourcesFail: Int = 0,
    var kept: Int = 0,
    var message: String = "",
    var running: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lastRunAt", lastRunAt)
        put("candidates", candidates)
        put("alive", alive)
        put("measured", measured)
        put("sourcesOk", sourcesOk)
        put("sourcesFail", sourcesFail)
        put("kept", kept)
        put("message", message)
        put("running", running)
    }

    companion object {
        fun from(o: JSONObject): RunStatus = RunStatus(
            o.optLong("lastRunAt", 0L),
            o.optInt("candidates"),
            o.optInt("alive"),
            o.optInt("measured"),
            o.optInt("sourcesOk"),
            o.optInt("sourcesFail"),
            o.optInt("kept"),
            o.optString("message", ""),
            o.optBoolean("running", false)
        )
    }
}

/** 结果仓库：节点列表 + 状态，落盘到 filesDir，重启后还在。 */
object Repo {

    private lateinit var dir: File

    var nodes: List<Node> = emptyList()
        internal set
    var status: RunStatus = RunStatus()
        internal set

    fun setResult(list: List<Node>, st: RunStatus) {
        nodes = list
        status = st
        save()
    }

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "walls").apply { mkdirs() }
        load()
    }

    val workDir: File get() = dir

    fun fileOf(name: String): File {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        return f
    }

    fun load() {
        try {
            val nf = File(dir, "nodes.json")
            if (nf.exists()) nodes = nodesFromJson(JSONArray(nf.readText()))
            val sf = File(dir, "status.json")
            if (sf.exists()) status = RunStatus.from(JSONObject(sf.readText()))
        } catch (e: Exception) {
            // 数据损坏就从头来
        }
    }

    /** 节点列表 → JSON（备份、落盘共用同一套格式） */
    fun nodesToJson(list: List<Node> = nodes): JSONArray {
        val arr = JSONArray()
        for (n in list) {
            arr.put(JSONObject().apply {
                put("p", JsonUtil.mapToJson(n.map))
                put("d", n.delay)
                put("s", n.speed)
                put("r", n.realDelay)
                put("n", n.note)
            })
        }
        return arr
    }

    fun nodesFromJson(arr: JSONArray): List<Node> {
        val list = ArrayList<Node>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val map = JsonUtil.jsonToMap(o.optJSONObject("p") ?: JSONObject())
            val n = Node(map)
            n.delay = o.optInt("d", -1)
            n.speed = o.optDouble("s", -1.0)
            n.realDelay = o.optInt("r", -1)
            n.note = o.optString("n", "")
            list.add(n)
        }
        return list
    }

    fun save() {
        try {
            File(dir, "nodes.json").writeText(nodesToJson().toString())
            File(dir, "status.json").writeText(status.toJson().toString())
        } catch (e: Exception) {
            // ignore
        }
    }
}
