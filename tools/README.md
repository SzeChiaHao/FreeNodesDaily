# NoMoreWalls 免费节点 · 一体化自动管家

本目录是你（szech）这台电脑上"免费节点"任务的全套工具。相比旧方案（抓取、测速分开、手动跑），
现在是一条**全自动流水线**：抓取 → 测速 → 按速度排序 → 过滤垃圾节点 → 导出多客户端订阅 → 同步进 Clash Verge。

## 一、核心脚本（新）

| 文件 | 作用 |
|---|---|
| `daily_auto.py` | **核心**。抓取 + 独立 mihomo 内核测速 + 按下载速度降序排序 + 导出多客户端订阅 + 同步 Clash Verge |
| `sources.py` | 源清单解析、镜像兜底（顺序自学习）、源体检。`daily_auto.py` 和 `fetch_nodes.py` 共用 |
| `sources.list` | **节点源清单**（文本文件）。加源 / 停源改这里，不用动 Python |
| `daily_auto.ps1` | 包装脚本：调用 daily_auto.py，写 UTF-8 日志，自动清理旧日志 |
| `setup_auto.ps1` | 一键安装自动任务（每日 08:00 + 开机登录自启），无需管理员权限 |

旧脚本（`fetch_nodes.py`、`node_quality.py`、`update_nomorewalls.ps1` 等）仍保留，
`daily_auto.py` 复用了它们的"节点解析"和"mihomo 测速框架"，但旧脚本不再作为主入口。

## 二、自动任务（已装好）

| 触发 | 方式 | 说明 |
|---|---|---|
| 每日 08:00 | 计划任务 `AutoFreeNodes Daily` | 每天定时跑完整流程 |
| 开机登录 | 启动文件夹 `AutoFreeNodes.vbs` | 登录后静默跑一次（无窗口） |

两次完整运行之间内置 **6 小时 guard**：即便每日任务和开机自启撞上，也不会重复跑。
（脚本里有"上次成功运行时间"标记，`--force` 可强制忽略。）

> 旧的每周任务「NoMoreWalls 独立节点抓取」已被本方案取代，可在任务计划程序里停用。
> 「NoMoreWalls 订阅更新」（每天 07:30 更新仓库）保留不动，它负责维护仓库本体。

## 三、手动使用

### 源体检（先跑这个，几十秒）

```powershell
python D:\Walls\tools\daily_auto.py --doctor
```

并发探一遍 `sources.list` 里的每个源，列出：通不通、解析出多少节点、耗时、走的哪个镜像，
结果写到 `output/sources.md`，末尾附一段「可直接粘回 sources.list 的清单」（死源已帮你注释好）。
加源/停源之前先跑它，比整轮跑（几分钟到十几分钟）快得多。

### 完整跑一次（抓取 + 测速 + 排序 + 同步）

```powershell
powershell -ExecutionPolicy Bypass -File D:\Walls\tools\daily_auto.ps1
```

### 只测延迟、不测下载速度（快速，约 4 分钟）

```powershell
powershell -ExecutionPolicy Bypass -File D:\Walls\tools\daily_auto.ps1 -Quick
```

### 直接调 Python（更多参数）

```powershell
python D:\Walls\tools\daily_auto.py --force            # 忽略 6 小时 guard 强制跑
python D:\Walls\tools\daily_auto.py --skip-sync        # 只生成文件，不写 Clash Verge
python D:\Walls\tools\daily_auto.py --top 50           # 最终保留 50 个节点
python D:\Walls\tools\daily_auto.py --min-speed 0.5    # 达标速度提高到 0.5 MB/s
python D:\Walls\tools\daily_auto.py --no-label         # 不给节点名加速度前缀
```

常用参数：`--max-nodes`（进入测速的候选上限，默认 600）、`--max-delay`（延迟阈值 ms，默认 3000）、
`--speed-top`（做下载测速的节点数，默认 150）、`--speed-max-delay`（参与测速的延迟上限 ms，默认 1500）、
`--min-speed`（合格最低速度 MB/s，默认 0.2）、`--top`（最终保留数，默认 40）、`--min-per-type` / `--keep-per-type`（协议保底与配额）、`--exclude-types`（排除某些协议，例如 `http,socks5`）。

抓取相关的三个：`--workers`（并发数，默认 8）、`--timeout`（单源超时秒）、`--sources-file`（换成别的清单文件）、
`--region-min`（地区组至少几个节点才建，默认 2；设 0 则有一个就建）、
`--delay-workers`（延迟测试并发，默认 16）、`--max-per-type`（单协议封顶，默认 `http=10,socks5=4`，压掉抢名额的 http 节点）。

## 四、输出文件（`D:\Walls\tools\output\`）

跑完一次会同时产出好几种格式，**不同客户端各拿各的**：

| 文件 | 给谁用 |
|---|---|
| `best_nodes.yml` | **Clash 系**：Clash Verge、Clash Meta for Android、FlClash、NekoBox、mihomo。节点按实测下载速度降序，名字带 `X.XMB/s ` 前缀 |
| `sip008.json` | **Shadowsocks 官方 JSON 订阅**：shadowsocks-android、ss-windows、sing-box 都认 |
| `ss-base64.txt` | base64 的 `ss://` 列表：shadowsocks-android 的「订阅」、Shadowrocket |
| `ss-plain.txt` | 明文 `ss://` 一行一个，复制粘贴就能导入 |
| `gui-config.json` | 旧版 shadowsocks 客户端的「导入配置」 |
| `v2ray-base64.txt` | **通用订阅**（base64 分享链接）：v2rayN、v2rayNG、Shadowrocket、NekoBox |
| `all-links.txt` | 明文分享链接（`ss://` / `vmess://` / `vless://` / `trojan://` / `hysteria2://` / `tuic://`），方便自己挑 |
| `singbox-outbounds.json` | sing-box 的 `outbounds` 片段（含一个 selector），粘进你自己的配置即可 |
| `report.md` | 人类可读的测速报告（排名、延迟、速度） |

`best_nodes.yml` 里现在有三类组：

- `🚀 自动选择`（url-test）：Clash 自动选延迟最低的节点；
- `🔁 故障转移`（fallback）：按顺序往后试，前一个不通才换下一个；
- 地区组（`🇭🇰 香港`、`🇯🇵 日本`、`🇺🇸 美国` …，各自 url-test）：想要「就固定用某个地区」时用；
  地区是从节点名里认的（中文名 / 国旗 / ISO 两字母码都认），一个地区至少 2 个节点才建组；
- `🐟 手动选择(按速度排序)`：节点按实测下载速度从快到慢排列，手动挑最快的用。

### 只给纯 Shadowsocks 客户端出订阅

`sip008.json` / `ss-*` 只包含 Shadowsocks 节点。而入选的前几名常常全是 `http` / `socks5`
（这类协议没有标准的分享链接格式），会把 ss 挤掉。所以脚本做了两件事：

1. **候选池协议保底**（`--min-per-type`，默认 `ss=80,vmess=80,...`）：抓取时按源轮转挑选，
   再给每种协议补足名额，保证池子里 ss 不会被大源淹没；
2. **最终名单协议配额**（`--keep-per-type`，默认 `ss=6,vmess=5,...`）：入选名单里每种协议
   至少留几条，从存活池里按速度补。

另外 `fetch_nodes.py` 的 `SOURCES` 里补了两个 Shadowsocks 大户
（`mahdibland/ShadowsocksAggregator` 的 `sub_merge.txt` 和 `Eternity.yml`，
前者 4000+ 条里就有 1800 多个 ss），没有它们池子里基本捞不到 ss。

想彻底不看到 http 节点，可以加 `--exclude-types http,socks5`。

## 五、如何查看结果（Clash Verge）

`daily_auto.py` 会自动把 `best_nodes.yml` 同步成 Clash Verge 里的一个**本地订阅**
`auto_best_nodes.yml`（profiles.yaml 里 uid 为 `AutoBestNodes`）。
在 Clash Verge 里选它，就能看到按速度排好、带速度标注的节点。
若 Clash Verge 当时正在运行，重启一次（或切一下订阅）即可加载最新结果。

## 六、如何增删节点源（含动态发现）

**主入口是 `sources.list`**（纯文本，不用改代码）：

```text
# 注释
https://raw.githubusercontent.com/owner/repo/main/clash.yaml
!https://需要代理才下得动的源/xxx                     # 行首 ! ：要本地代理
*https://某个收集订阅链接的页面/README.md          # 行首 * ：订阅列表页，展开里面的链接
https://raw.githubusercontent.com/owner/repo/main/sub#max=200&ignore=vmess,ss
```

行末 `#max=N` 只取前 N 个节点；`#ignore=a,b` 丢掉这些协议；`EOF` 一行表示读到这为止。
没这个文件时自动退回 `daily_auto.py` 里的 `EXTRA_SOURCES` + `fetch_nodes.py` 里的 `SOURCES`（老行为不变）。

**镜像顺序是自学习的**：`raw.githubusercontent.com` 在这台机器上实测完全连不通（40 秒超时），
而 `cdn.jsdelivr.net` 0.4 秒、`gh-proxy.com` 0.45 秒就回来了。所以默认**先镜像、raw 放最后**，
并按历史成功率 / 耗时自动排序（`state/mirror_health.json`）。这一条加上并发抓取（`--workers`，默认 8），
把阶段 1 从过去的五六分钟压到几十秒。

**聚合页自动发现**（方案①）：编辑 `daily_auto.py` 里的 `SEED_PAGES` 列表，加入专门收集订阅链接的页面（如某个 README）。脚本每次运行会：
1. 展开聚合页、提取候选订阅链接；
2. 逐个验证（能解析出节点的才算有效源），有效的自动加入 `state/discovered_sources.txt` 持久化；
3. 每次抓取记录每个源的成败与产出节点数（`state/source_health.json`），动态源连续失败 5 次自动淘汰。

**代理兜底**：若直连+镜像都失败，脚本会尝试走 `local_proxy.conf` 里配置的本地代理（前提是 Clash Verge 在运行且代理端口可用），这样需要代理才能访问的源（如 ermao.net）也能被抓到。

> 现状说明：2026-10-21 体检 `sources.list` 里 18 个源全部可用（合计 6404 个节点）。
> 已死掉的三个（`learnhard-cn/free_proxy_ss`、`aiboboxx/v2rayfree`、`mfuu/v2ray`）已注释保留在文件底部。
> 想扩源：在 TG/论坛/其它聚合仓库看到新的订阅，用 `--doctor` 验一下，能出节点就加进 `sources.list`。

## 七、移除自动任务

```powershell
schtasks /delete /tn "AutoFreeNodes Daily" /f
del "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup\AutoFreeNodes.vbs"
```

## 八、注意事项

### 本版（2026-10-21）改了什么

- **源清单外置成 `sources.list`**，支持 NoMoreWalls 那套标记语法（`!` 需代理 / `*` 列表页 / `#max=` / `#ignore=`）。
  加源/停源不用再改 Python。同时替换了已死的源：`learnhard-cn`、`aiboboxx/v2rayfree`、`mfuu/v2ray`
  已注释保留；新增 4 个实测可用的（`snakem982/proxypool` ×2、`free18/v2ray`、`lagzian/SS-Collector`）。
- **镜像顺序自学习 + 并发抓取**。实测 `raw.githubusercontent.com` 在这台机器上完全连不通（40 秒超时），
  而 cdn.jsdelivr 0.4 秒就回；原来的顺序是「raw 优先」，每个源都要先白等 20 秒超时。
  现在先镜像、raw 放最后，按历史成功率/耗时排序，并且 8 路并发：
  18 个源从 **约 6 分钟降到 5～15 秒**。
- **新增 `--doctor` 体检模式**：并发探一遍所有源，几十秒内给出通/空/死、节点数、耗时、实际走的镜像，
  写 `output/sources.md`，并附一段可直接粘回 `sources.list` 的清单（死源已帮你注释）。
- **延迟测试改成逐节点并发探测**（`--delay-workers`）。原来的做法是把 500～600 个节点
  一次性丢给内核的 url-test，内核自己就撑爆了，大量可用节点被误判成死的。
- **输出加了地区组**（香港/日本/美国/新加坡/台湾… 各自 url-test）与 `🔁 故障转移`（fallback），
  并补上 `mixed-port` / `mode` / `log-level` / `unified-delay` / `tcp-concurrent`，
  这份 yml 自己就能当一份完整配置用。
- **单协议封顶**（`--max-per-type`，默认 `http=10,socks5=4`）：
  以前最终名单很容易被 http 节点刷满（它们快、量大），结果 sip008 / ss-* / v2ray 这些文件几乎是空的。
- **`report.md` 扩写**：除了排名，还有协议与地区分布、每个源的健康度（节点数/耗时/镜像）。
- **`state/source_health.json` 升级**：除 ok/fail，还记最后一次成功时间、产出节点数、最后错误。
- `local_proxy.conf` 改成 `tools/local_proxy.conf` 优先（老位置 `D:\NoMoreWalls\`、`D:\Walls\NoMoreWalls\` 仍兼容）。
- `update_nomorewalls.ps1` 默认仓库路径改为 `D:\NoMoreWalls`。

### 一般事项

- 免费节点随时失效、质量参差，这是常态；本脚本靠"每日刷新 + 实测排序 + 严格过滤"尽量只留可用的。
- 免费节点有安全风险（可能记录流量）：不要通过免费节点登录重要账号、访问明文网站、或挖矿。
- 本工具仅用于学习交流与访问合法内容。
