package com.szech.walls.ui

import android.content.Context
import android.net.Uri
import com.szech.walls.core.Log
import com.szech.walls.pipeline.Runner
import com.szech.walls.store.Prefs
import com.szech.walls.store.Repo
import com.szech.walls.store.RunStatus
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与恢复。
 *
 * 备份的内容：11 项设置 + 订阅源清单 + 自动发现的源 + 当前节点池（含测速结果）与状态。
 * 存成一个 JSON 写进「下载/Walls/」，恢复时从文件读回来。
 */
object Backup {

    private const val KIND = "walls-backup"
    private const val VERSION = 1

    private val stamp: SimpleDateFormat
        get() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())

    fun export(ctx: Context): String? {
        val obj = JSONObject().apply {
            put("kind", KIND)
            put("version", VERSION)
            put(
                "savedAt",
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            )
            put("settings", JSONObject().apply {
                put("autoSave", Prefs.autoSave)
                put("maxNodes", Prefs.maxNodes)
                put("keepTop", Prefs.keepTop)
                put("speedTop", Prefs.speedTop)
                put("speedBytesKb", Prefs.speedBytesKb)
                put("minSpeedTenths", Prefs.minSpeedTenths)
                put("measurableOnly", Prefs.measurableOnly)
                put("labelSpeed", Prefs.labelSpeed)
                put("autoDaily", Prefs.autoDaily)
                put("mirrorFirst", Prefs.mirrorFirst)
            })
            put("sources", JSONArray(Prefs.sources))
            put("discovered", JSONArray(Prefs.discovered))
            put("nodes", Repo.nodesToJson())
            put("status", Repo.status.toJson())
        }
        val name = "walls-backup-${stamp.format(Date())}.json"
        val written = WallsCmd.writeToDownloads(ctx, name, obj.toString(2), "application/json")
        if (written != null) Log.add("已备份到 下载/Walls/$written")
        return written
    }

    /** 返回一句给用户看的结果说明 */
    fun restore(ctx: Context, uri: Uri): String = try {
        val text = ctx.contentResolver.openInputStream(uri)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        when {
            text.isNullOrBlank() -> "文件是空的，读不出来"

            else -> {
                val o = JSONObject(text)
                if (o.optString("kind") != KIND) {
                    "这不是节点管家的备份文件"
                } else {
                    o.optJSONObject("settings")?.let { s ->
                        Prefs.autoSave = s.optBoolean("autoSave", Prefs.autoSave)
                        Prefs.maxNodes = s.optInt("maxNodes", Prefs.maxNodes)
                        Prefs.keepTop = s.optInt("keepTop", Prefs.keepTop)
                        Prefs.speedTop = s.optInt("speedTop", Prefs.speedTop)
                        Prefs.speedBytesKb = s.optInt("speedBytesKb", Prefs.speedBytesKb)
                        Prefs.minSpeedTenths = s.optInt("minSpeedTenths", Prefs.minSpeedTenths)
                        Prefs.measurableOnly = s.optBoolean("measurableOnly", Prefs.measurableOnly)
                        Prefs.labelSpeed = s.optBoolean("labelSpeed", Prefs.labelSpeed)
                        Prefs.autoDaily = s.optBoolean("autoDaily", Prefs.autoDaily)
                        Prefs.mirrorFirst = s.optBoolean("mirrorFirst", Prefs.mirrorFirst)
                    }
                    o.optJSONArray("sources")?.let { Prefs.sources = it.toStringList() }
                    o.optJSONArray("discovered")?.let { Prefs.discovered = it.toStringList() }
                    val nodes = o.optJSONArray("nodes")?.let { Repo.nodesFromJson(it) } ?: emptyList()
                    if (nodes.isNotEmpty()) {
                        val st = o.optJSONObject("status")?.let { RunStatus.from(it) } ?: Repo.status
                        Repo.setResult(nodes, st)
                    }
                    Runner.schedule(ctx, Prefs.autoDaily)
                    Log.add("已从备份恢复：${Prefs.sources.size} 个源、${nodes.size} 个节点")
                    "已恢复\n\n设置：10 项\n订阅源：${Prefs.sources.size} 个\n节点：${nodes.size} 个"
                }
            }
        }
    } catch (e: Exception) {
        "恢复失败：${e.message}"
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it, "").takeIf { s -> s.isNotEmpty() } }
}
