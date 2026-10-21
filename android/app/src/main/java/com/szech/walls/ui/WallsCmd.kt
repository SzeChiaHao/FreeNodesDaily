package com.szech.walls.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.szech.walls.R
import com.szech.walls.core.Log
import com.szech.walls.export.Formats
import com.szech.walls.model.Node
import com.szech.walls.net.Updater
import com.szech.walls.store.Prefs
import com.szech.walls.store.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.google.android.material.R as MR

/**
 * 各处都要用的动作：复制、导出、版本与更新。
 * 放在这里，免得每个 Fragment 各写一份。
 */
object WallsCmd {

    fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    fun copy(ctx: Context, text: String, tip: String) {
        try {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("walls", text))
            toast(ctx, tip)
        } catch (e: Exception) {
            toast(ctx, "复制失败：${e.message}")
        }
    }

    fun openUrl(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            toast(ctx, "打不开：${e.message}")
        }
    }

    /** 本机版本：versionName 与 versionCode */
    fun currentVersion(ctx: Context): Pair<String, Int> = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") pi.versionCode
        }
        Pair(pi.versionName ?: "?", code)
    } catch (e: Exception) {
        Pair("?", 0)
    }

    // ------------------------------------------------------------ 导出

    /**
     * 一种导出格式。
     *
     * 文件名不再写死成 clash.yaml —— 手机里重名会被系统存成「clash.yaml(1)」，
     * 那种名字没法看也没法管。这里统一改成「前缀-时间戳.后缀」，
     * 比如 clash-20261018-1425.yaml：一眼看得出是什么、什么时候生成的，
     * 也不会撞名；顺带还能按前缀把旧文件清掉。
     */
    data class ExportFormat(
        val key: String,
        val prefix: String,
        val ext: String,
        val mime: String,
        val clients: String,
        val note: String
    )

    val FORMATS: List<ExportFormat> = listOf(
        ExportFormat("clash", "clash", "yaml", "text/yaml", "Clash", "Clash Verge、mihomo、FlClash 都吃这个"),
        ExportFormat("v2ray", "v2ray", "txt", "text/plain", "v2rayNG / v2rayN", "通用 base64 订阅，NekoBox、Shadowrocket 也认"),
        ExportFormat("sip008", "sip008", "json", "application/json", "shadowsocks-android", "官方 JSON 订阅格式"),
        ExportFormat("ssb64", "ss-base64", "txt", "text/plain", "Shadowsocks 系（base64）", "整份列表 base64 编码，填进「订阅」栏"),
        ExportFormat("ssplain", "ss-plain", "txt", "text/plain", "Shadowsocks 系（明文）", "一行一个 ss:// 链接，复制就能加"),
        ExportFormat("all", "all-links", "txt", "text/plain", "手动挑节点", "所有协议的分享链接，明文"),
        ExportFormat("report", "report", "md", "text/markdown", "测速报告", "Markdown 表格，看排名和没测通的原因")
    )

    /** 一次生成共用一个时间戳，七个文件属于同一批，好认也好清 */
    fun stamp(now: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.getDefault()).format(java.util.Date(now))

    fun fileNameOf(fmt: ExportFormat, stamp: String): String = fmt.prefix + "-" + stamp + "." + fmt.ext

    private fun contentOf(fmt: ExportFormat, nodes: List<Node>): String = when (fmt.key) {
        "clash" -> Formats.clashYaml(nodes, "(按速度排序)")
        "v2ray" -> Formats.base64AllLinks(nodes)
        "sip008" -> Formats.sip008(nodes)
        "ssb64" -> Formats.base64Ss(nodes)
        "ssplain" -> Formats.plainSs(nodes)
        "all" -> Formats.plainAllLinks(nodes)
        else -> Formats.reportMd(nodes, "节点测速报告")
    }

    /** 要导出的全部格式：文件名 → 内容、MIME */
    fun exportItems(nodes: List<Node>, stamp: String = stamp()): List<Triple<String, String, String>> =
        FORMATS.map { Triple(fileNameOf(it, stamp), contentOf(it, nodes), it.mime) }

    /** 这一批文件的名字，用来告诉清理逻辑「别删刚生成的」 */
    private fun namesOf(stamp: String): Set<String> =
        FORMATS.map { fileNameOf(it, stamp) }.toSet()

    /**
     * 清掉以前生成的配置文件。
     *
     * 只删我们自己造的（认前缀），别碰用户自己的文件；keep 里的（本次刚生成的）不删。
     */
    fun cleanOldExports(ctx: Context, keep: Set<String> = emptySet()): Int {
        val prefixes = FORMATS.map { it.prefix + "-" }
        var removed = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"
            val args = arrayOf(Environment.DIRECTORY_DOWNLOADS + "/Walls%")
            try {
                ctx.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    sel, args, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        if (name in keep) continue
                        if (prefixes.none { name.startsWith(it) }) continue
                        val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(0))
                        try {
                            if (ctx.contentResolver.delete(uri, null, null) > 0) removed++
                        } catch (e: Exception) {
                            // 删不掉就跳过
                        }
                    }
                }
            } catch (e: Exception) {
                Log.add("清理旧文件失败：${e.message}")
            }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Walls"
            )
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name !in keep && prefixes.any { f.name.startsWith(it) }) {
                    if (f.delete()) removed++
                }
            }
        }
        return removed
    }

    /** 把一份文本写进「下载/Walls/」，同名覆盖。成功返回文件显示名，失败返回 null。 */
    fun writeToDownloads(ctx: Context, name: String, content: String, mime: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Walls"
                )
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) null
            else {
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(content.toByteArray(Charsets.UTF_8))
                }
                name
            }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Walls"
            )
            dir.mkdirs()
            File(dir, name).writeText(content)
            name
        }
    } catch (e: Exception) {
        Log.add("写文件失败（$name）：${e.message}")
        null
    }

    /**
     * 把各种格式的文件写进「下载/Walls/」。
     * cleanOld=true 时先删掉以前生成的（本次要生成的除外）。
     */
    fun exportToDownloads(
        ctx: Context,
        nodes: List<Node>,
        notify: Boolean = true,
        cleanOld: Boolean = Prefs.cleanOldExports
    ): Int {
        if (nodes.isEmpty()) {
            if (notify) toast(ctx, "还没有节点可导出")
            return 0
        }
        val st = stamp()
        val items = exportItems(nodes, st)
        val keep = namesOf(st)
        var cleaned = 0
        if (cleanOld) cleaned = cleanOldExports(ctx, keep)
        var ok = 0
        for ((name, content, mime) in items) {
            if (writeToDownloads(ctx, name, content, mime) != null) ok++
        }
        if (ok > 0) Prefs.lastExportAt = System.currentTimeMillis()
        if (notify) {
            if (ok == items.size) {
                toast(ctx, "已生成 $ok 个文件到 下载/Walls/" + if (cleaned > 0) "，顺手清了 $cleaned 个旧的" else "")
                Log.add("已生成 $ok 个配置文件到 下载/Walls/（清理旧文件 $cleaned 个）")
            } else {
                toast(ctx, "生成失败（成功 $ok/${items.size}）")
            }
        } else if (ok > 0) {
            Log.add("已自动生成 $ok 个配置文件到 下载/Walls/（清理旧文件 $cleaned 个）")
        }
        return ok
    }

    /** 只生成其中一种，其余不动 */
    fun exportOne(ctx: Context, key: String): String? {
        val fmt = FORMATS.firstOrNull { it.key == key } ?: return null
        if (Repo.nodes.isEmpty()) {
            toast(ctx, "还没有节点可导出")
            return null
        }
        val name = fileNameOf(fmt, stamp())
        val ok = writeToDownloads(ctx, name, contentOf(fmt, Repo.nodes), fmt.mime) != null
        if (ok) {
            Prefs.lastExportAt = System.currentTimeMillis()
            toast(ctx, "已生成 下载/Walls/$name")
            return name
        }
        toast(ctx, "生成失败，存储空间不够？")
        return null
    }

    /**
     * 打开「下载/Walls/」：先按目录打开（DocumentsUI 支持），
     * 不行再退一步，打开文件选择器并直接定位到这个目录。
     */
    fun openDownloadsFolder(ctx: Context) {
        val docUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Download/Walls"
        )
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            return
        } catch (e: Exception) {
            // 没有能打开目录的应用，走下一步
        }
        try {
            ctx.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            toast(ctx, "打不开文件管理器，文件在 下载/Walls/")
        }
    }

    fun share(ctx: Context, name: String, content: String, mime: String) {
        try {
            val f = Repo.fileOf("export/$name")
            f.writeText(content)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(i, "分享订阅").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            toast(ctx, "分享失败：${e.message}")
        }
    }

    // ------------------------------------------------------------ 自动更新

    fun checkUpdate(ctx: Context, owner: LifecycleOwner, manual: Boolean) {
        owner.lifecycleScope.launch {
            val (curName, curCode) = currentVersion(ctx)
            val info = withContext(Dispatchers.IO) { Updater.fetchLatest() }
            if (info == null) {
                if (manual) toast(ctx, "检查更新失败：拿不到版本信息（网络问题？）")
                return@launch
            }
            if (!Updater.isNewer(info, curName, curCode)) {
                Log.add("检查更新：已是最新版 v$curName")
                if (manual) toast(ctx, "已是最新版本 v$curName")
                return@launch
            }
            Log.add("发现新版本 v${info.versionName}（当前 v$curName）")
            val sizeText = if (info.size > 0) String.format("%.1f MB", info.size / 1048576.0) else "未知大小"
            val ui = WallsUi(ctx)
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(24), 0, ui.dp(24), ui.dp(4))
                addView(ui.infoRow("当前版本", "v$curName"))
                addView(ui.infoRow("最新版本", "v${info.versionName}"))
                addView(ui.infoRow("安装包", sizeText))
                if (info.publishedAt.isNotEmpty()) addView(ui.infoRow("发布时间", info.publishedAt))
                if (info.notes.isNotEmpty()) {
                    addView(ui.label(info.notes, R.style.TextAppearance_Walls_Caption).apply {
                        setPadding(0, ui.dp(12), 0, 0)
                    })
                }
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle("发现新版本")
                .setView(box)
                .setPositiveButton("下载并安装") { _, _ -> downloadAndInstall(ctx, owner, info) }
                .setNegativeButton("稍后", null)
                .show()
        }
    }

    private fun downloadAndInstall(ctx: Context, owner: LifecycleOwner, info: Updater.Release) {
        val ui = WallsUi(ctx)
        val bar = ui.progressBar().apply {
            visibility = View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val tip = ui.label("正在下载 v${info.versionName}…", R.style.TextAppearance_Walls_Caption)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(4), ui.dp(24), 0)
            addView(tip)
            addView(bar)
        }
        val dlg = MaterialAlertDialogBuilder(ctx)
            .setTitle("下载更新")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("取消", null)
            .create()
        dlg.show()

        owner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                Updater.download(ctx, info, onProgress = { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    bar.post {
                        bar.setProgressCompat(pct, true)
                        tip.text = if (total > 0) {
                            "正在下载 v${info.versionName}… ${pct}%（${done / 1048576} / ${total / 1048576} MB）"
                        } else {
                            "正在下载 v${info.versionName}… ${done / 1048576} MB"
                        }
                    }
                })
            }
            dlg.dismiss()
            if (file == null) {
                Log.add("下载更新失败")
                toast(ctx, "下载失败，可稍后再试")
                return@launch
            }
            Log.add("更新包已下载：${file.name}")
            if (!Updater.canInstall(ctx)) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("还差一步")
                    .setMessage("系统需要你允许「节点管家」安装应用。点确定去设置里打开这个开关，然后回到本页再点一次「检查更新」。")
                    .setPositiveButton("去设置") { _, _ -> Updater.openInstallPermissionSettings(ctx) }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }
            val err = Updater.install(ctx, file)
            if (err != null) {
                Log.add("调起安装失败：$err")
                toast(ctx, "调起安装失败：$err")
            } else {
                toast(ctx, "已调起系统安装器，按提示覆盖安装即可")
            }
        }
    }

    /** 手写一份诊断文件到「下载/Walls/」，用户直接把它发过来 */
    fun exportDiagnostics(ctx: Context) {
        val nl = System.lineSeparator()
        val ver = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            pi.versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        val text = buildString {
            append("时间：").append(java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
            ).format(java.util.Date())).append(nl)
            append("版本：").append(ver).append(nl)
            append("机型：").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(')').append(nl)
            append("节点数：").append(Repo.nodes.size)
                .append(" · 正在运行：").append(com.szech.walls.run.RunController.running).append(nl)
            append(nl).append("---- 运行日志 ----").append(nl)
            append(Log.snapshot().joinToString(nl))
        }
        val name = "diag-" + java.text.SimpleDateFormat(
            "yyyyMMdd-HHmmss", java.util.Locale.getDefault()
        ).format(java.util.Date()) + ".txt"
        val ok = writeToDownloads(ctx, name, text, "text/plain")
        toast(ctx, if (ok != null) "已导出 下载/Walls/$ok" else "导出失败")
    }

    const val GITHUB_URL = "https://github.com/SzeChiaHao/FreeNodesDaily"

    // ------------------------------------------------------------ 订阅源编辑

    /** 订阅源编辑弹窗。抽屉里和设置页里共用同一份。 */
    fun showSourcesDialog(ctx: Context, onSaved: () -> Unit = {}) {
        val ui = WallsUi(ctx)
        val input = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(Prefs.sources.joinToString("\n"))
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
            textSize = 12f
        }
        val wrap = TextInputLayout(
            ContextThemeWrapper(ctx, MR.style.Widget_Material3_TextInputLayout_OutlinedBox)
        ).apply {
            hint = "每行一个；末尾加 #type=list 表示这是订阅合集页"
            addView(input)
        }
        val box = FrameLayout(ctx).apply {
            setPadding(ui.dp(24), ui.dp(4), ui.dp(24), 0)
            addView(wrap)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("编辑订阅源")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                Prefs.sources = input.text.toString().split('\n')
                toast(ctx, "已保存 ${Prefs.sources.size} 个源")
                onSaved()
            }
            .setNeutralButton("恢复默认") { _, _ ->
                Prefs.resetSources()
                toast(ctx, "已恢复默认订阅源")
                onSaved()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
