# FreeNodesDaily · 免费节点抓取测速管家

[![daily-nodes](https://github.com/SzeChiaHao/FreeNodesDaily/actions/workflows/daily-nodes.yml/badge.svg)](https://github.com/SzeChiaHao/FreeNodesDaily/actions/workflows/daily-nodes.yml)

抓取公开免费节点 → 实测延迟与下载速度 → 排序过滤 → 导出**各种客户端**的订阅：
Clash / Clash Verge / mihomo、sing-box、v2rayN / v2rayNG、Shadowrocket、Shadowsocks（sip008 / ss 链接）。
每日订阅由 GitHub Actions 生成并发布到 `data` 分支；也可以在本机（Windows / Linux / macOS）
跑同一条 Python 流水线，或用 Android App「节点管家」在手机上跑。

> 免费节点随时失效、质量参差，且有安全风险（可能记录流量）。请勿通过免费节点登录重要账号、
> 访问明文网站或进行挖矿。本项目仅用于学习交流与访问合法内容。

---

## 〇、订阅地址（GitHub Actions 每日生成，推荐）

`data` 分支由 [.github/workflows/daily-nodes.yml](.github/workflows/daily-nodes.yml) 每天 07:00（北京时间）
跑一次「抓取 → mihomo 真实测速 → 导出」后自动更新。合并本 PR 后，需要在 Actions 页面手动
触发一次 `daily-nodes`（Run workflow），`data` 分支才会第一次出现。

| 客户端 | 订阅地址 |
|---|---|
| Clash Verge / mihomo / FlClash | `https://raw.githubusercontent.com/SzeChiaHao/FreeNodesDaily/data/best_nodes.yml` |
| v2rayN / v2rayNG / Shadowrocket | `https://raw.githubusercontent.com/SzeChiaHao/FreeNodesDaily/data/v2ray-base64.txt` |
| sing-box | `https://raw.githubusercontent.com/SzeChiaHao/FreeNodesDaily/data/singbox-outbounds.json` |
| Shadowsocks（sip008） | `https://raw.githubusercontent.com/SzeChiaHao/FreeNodesDaily/data/sip008.json` |
| 明文链接（自己挑） | `https://raw.githubusercontent.com/SzeChiaHao/FreeNodesDaily/data/all-links.txt` |

国内直连 `raw.githubusercontent.com` 通常不通，可把域名换成 jsDelivr：
`https://cdn.jsdelivr.net/gh/SzeChiaHao/FreeNodesDaily@data/best_nodes.yml`。

订阅只包含**本轮真实测出下载速度**的节点；某天一个都没测通时，工作流会失败并保留上一版，
不会把死节点写进订阅。

## 一、目录结构

```
tools/      Windows 端：Python 抓取 + mihomo 内核测速 + 导出多客户端订阅 + 同步 Clash Verge
android/    Android 端：同款逻辑的 Kotlin 实现，抓取/测速/本地订阅服务一体化 App
```

## 二、本机 / CI 端（`tools/`）

一条全自动流水线：**抓取 → 测速 → 按速度排序 → 过滤垃圾节点 → 导出多客户端订阅 → 同步进 Clash Verge**。

`daily_auto.py` 不再依赖写死的盘符：输出默认落在 `tools/output/`，mihomo 会自动在 `PATH`、
`tools/`、Clash Verge 安装目录里查找，也可以用 `--mihomo` 或 `MIHOMO_BIN` 指定。

| 文件 | 作用 |
|---|---|
| `daily_auto.py` | 核心脚本。抓取 + 独立 mihomo 内核测速 + 按下载速度降序排序 + 导出多客户端订阅 + 同步 Clash Verge |
| `daily_auto.ps1` | 包装脚本：调用 `daily_auto.py`，写 UTF-8 日志并自动清理旧日志 |
| `setup_auto.ps1` | 一键安装自动任务（每日 08:00 + 开机登录自启），无需管理员权限 |
| `fetch_nodes.py` | 节点源清单与解析（被 `daily_auto.py` 复用） |
| `node_quality.py` | mihomo 测速框架（延迟 / 下载速度） |
| `update_nomorewalls.ps1`、`scrape_nodes.ps1` | 旧版脚本，保留备用 |

快速开始：

```powershell
# Windows：跑完顺带同步进 Clash Verge
pip install requests pyyaml
powershell -ExecutionPolicy Bypass -File tools\daily_auto.ps1

# Linux / macOS / CI：只产出文件，不碰客户端
python tools/daily_auto.py --force --skip-sync --out dist --top 60
```

失败语义：抓不到节点返回 1；没有任何节点测出速度返回 3，**不会**用死节点兜底。
`tools/output/summary.json` 里是本轮计数（fetched / alive / qualified / kept / 源成功率），
方便 CI 或监控判断质量。

更多参数、节点源增删（含聚合页自动发现）、自动任务安装与卸载，详见 **[tools/README.md](tools/README.md)**。

### 一次运行导出 8 种订阅文件（不同客户端各拿各的）

| 客户端 | 拿哪个文件 |
|---|---|
| Clash Verge、mihomo、FlClash、Clash Meta for Android、NekoBox | `best_nodes.yml` |
| sing-box | `singbox-outbounds.json`（`outbounds` 片段，粘进自己的配置） |
| v2rayN、v2rayNG、Shadowrocket | `v2ray-base64.txt`（通用 base64 订阅） |
| Shadowsocks 系：shadowsocks-android / ss-windows / Shadowrocket | `sip008.json`、`ss-base64.txt`、`ss-plain.txt`、`gui-config.json` |
| 手动挑节点 | `all-links.txt`（明文分享链接） |

文件全部落在 `tools/output/`，细节见 **[tools/README.md](tools/README.md)** 第四节。

## 三、Android 端（`android/`）

「节点管家」App：把上面那套流水线搬到手机上——内置默认节点源、后台定时抓取与测速、
在手机本地起一个订阅 HTTP 服务。注意该服务**只监听 `127.0.0.1`**（不对外开端口），
只能给同一台手机上的客户端用；PC / 电视要订阅请用上面的 `data` 分支地址。

- 包名 `com.szech.walls`，`minSdk 26`，`targetSdk 34`，Kotlin + 原生 View。
- 主要模块：`pipeline/`（默认节点源与流水线）、`net/`（抓取与自研隧道客户端）、
  `parse/`（URI 解析）、`server/`（本地订阅服务）、`export/`（订阅格式导出）、`work/`（定时任务）。
- 手机上没有 mihomo 内核，所以**测速用的协议客户端是自己实现的**：
  Shadowsocks（AEAD / 流式）、Trojan（tcp / ws + TLS）、VLESS（tcp / ws + TLS）。
  做法是拿一批「mihomo 能跑通」的节点当标准答案，做端到端比对，详见 **[android/README.md](android/README.md)**。

发布新版本（App 会自动提示更新）：

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -Release -Notes "这一版改了啥"
```

这条命令会把版本号 +1、编译、传成 GitHub Release 资产、更新仓库里的 `version.json` 并推送。
手机上的 App 就是盯着 `version.json` 的 `versionCode` —— 它变大了就提示升级，所以平时发代码 commit 不会打扰用户。

构建（Windows 一键）：

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1              # 版本 +1 并编译
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -NoBump      # 只重编，不动版本号
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -Version 2.0 # 指定版本名
```

脚本会自动把 `versionCode` 加一（覆盖安装要求新包的 versionCode 更大），编译完把成品复制成 `%USERPROFILE%\Downloads\Walls-<版本>.apk`，直接传手机装即可。

手动构建：

```bash
cd android
./gradlew assembleRelease          # Windows: gradlew.bat assembleRelease
```

签名信息从 `android/local.properties` 读取（该文件不入库，模板见 `android/local.properties.example`）：

```properties
sdk.dir=D\:\\Android\\Sdk
walls.storeFile=walls-release.jks
walls.storePassword=******
walls.keyAlias=wallskey
walls.keyPassword=******
```

不配置 keystore 时 `release` 自动退回默认 debug 签名，仍可正常构建。

## 四、已知限制

- **免费节点寿命是小时级**。订阅每天只更新一次，下午之后可用率会明显下降；这是免费节点的
  固有属性，不是脚本能修好的。要更稳，请让客户端用 `url-test` / `fallback` 组自动切换。
- **只提供公开链接的收集与测速**，不托管任何节点服务器；订阅里的节点随时可能被第三方下线。
- **Android App 的订阅服务只绑 `127.0.0.1`**，只能同机使用；跨设备请用 `data` 分支地址。
- **Android 端的真实测速只覆盖部分协议**（ss / trojan / vless，其中 reality、vision 等变体无法在本机验证）；vmess、hysteria2、tuic 也无法验证，详见 `android/README.md` 的协议矩阵。
- 抓取源全部是 GitHub raw / 镜像，国内网络下可能整轮失败；失败时保留上一版订阅，不会清空。

## 五、免责声明

本仓库只做「公开链接的收集与测速」，不提供任何节点服务器，也不对第三方节点的可用性、
合法性、安全性作任何担保。使用者需自行承担使用风险，并遵守所在地法律法规。
