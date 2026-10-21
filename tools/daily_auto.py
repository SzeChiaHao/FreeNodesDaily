#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一体化免费节点管家：抓取 -> 测速 -> 按速度排序 -> 生成订阅 -> 同步 Clash Verge

与旧的 fetch_nodes.py / node_quality.py 的关系：
  - 复用这两个脚本里的"节点解析"与"独立 mihomo 内核测速"能力，
    但不走它们各自的 main()，而是重新编排成一条流水线。
  - 关键改进：
      1) 抓取源自动多镜像兜底（raw.githubusercontent 直连 -> jsDelivr -> gh-proxy）
      2) 用 url-test 组批量并发测延迟（比旧版逐个 select 快一个数量级）
      3) 对存活节点测真实下载速度，按速度降序排序
      4) 自动写入 Clash Verge 的本地订阅（profiles.yaml + profiles/{uid}.yaml）
      5) 内置防重复运行 guard（默认 6 小时内不重复跑）

用法:
  python daily_auto.py                 # 正常完整流程
  python daily_auto.py --force         # 忽略 guard 强制跑
  python daily_auto.py --quick         # 只测延迟不测下载速度（快速）
  python daily_auto.py --skip-sync     # 只生成文件,不写 Clash Verge

依赖: requests, pyyaml, 以及 Clash Verge 自带的 verge-mihomo.exe
"""

import argparse
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.parse
from datetime import datetime

import requests
import yaml

# 复用同目录下已有脚本的解析与测速框架
import fetch_nodes as FN
import node_quality as NQ
import export_formats as EF
import sources as SRC

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

GOOGLE_204 = NQ.GOOGLE_204
CF_SPEED = NQ.CF_SPEED

# 延迟探测目标：先打纯 HTTP 的 Cloudflare 204（不跑 TLS 握手，一个 RTT 就回），
# 失败再试 Google 204。实测多目标并不提高存活率（死节点就是死的），
# 所以只留两个，别为了几乎不可能的好转付三倍时间。
DELAY_TARGETS = (
    "http://cp.cloudflare.com/generate_204",
    GOOGLE_204,
)

# ============ 可自行增删的额外节点源 ============
# 与 fetch_nodes.SOURCES 合并使用。每行一个订阅，末尾 #type=list 表示页面里含多个订阅链接。
EXTRA_SOURCES = [
    # "https://raw.githubusercontent.com/xxx/yyy/main/zzz.yaml",
]
# ===============================================

# 聚合页种子：专门收集订阅链接的页面（README 等），脚本会展开它们、发现新订阅源
SEED_PAGES = [
    "https://raw.githubusercontent.com/ermaozi/get_subscribe/main/README.md",
]
# 动态源状态文件
DISCOVERED_FILE = r"D:\Walls\tools\state\discovered_sources.txt"
HEALTH_FILE = r"D:\Walls\tools\state\source_health.json"
DEAD_SOURCE_THRESHOLD = 5  # 连续失败 N 次就淘汰一个动态源
DISCOVER_MAX_CANDIDATES = 20  # 每次最多验证多少个候选链接

DEFAULT_MIHOMO = r"D:\Clash Verge\verge-mihomo.exe"
DEFAULT_OUT = r"D:\Walls\tools\output"
DEFAULT_STATE = r"D:\Walls\tools\state\last_run.txt"
VERGE_PROFILE_UID = "AutoBestNodes"
VERGE_PROFILE_NAME = "auto_best_nodes.yml"


def mirrors(url):
    """把一个订阅链接展开成候选地址（只保留地址，供别处复用）。"""
    return [u for _, u in SRC.ordered_candidates(url)]


def _extract_candidate_urls(text):
    """从聚合页文本里提取候选订阅链接，过滤掉明显的客户端/社交/图片链接。"""
    out = []
    for u in FN.HTTP_RE.findall(text):
        u = u.rstrip(".,;)]}")
        low = u.lower()
        if any(s in low for s in (
            ".png", ".jpg", ".jpeg", ".svg", ".gif", ".ico", ".webp",
            "shields.io", "badge", "apps.apple.com", "play.google.com",
            "t.me/", "telegram", "twitter.com", "x.com/",
            "github.com/", "/releases", "/wiki/", "apkfree", "apkpure",
            "creativecommons", "wikipedia.org",
        )):
            continue
        out.append(u)
    return list(dict.fromkeys(out))


def _load_discovered():
    if os.path.exists(DISCOVERED_FILE):
        try:
            with open(DISCOVERED_FILE, "r", encoding="utf-8") as f:
                return [ln.strip() for ln in f if ln.strip() and not ln.startswith("#")]
        except OSError:
            pass
    return []


def _save_discovered(urls):
    try:
        os.makedirs(os.path.dirname(DISCOVERED_FILE), exist_ok=True)
        with open(DISCOVERED_FILE, "w", encoding="utf-8") as f:
            f.write("\n".join(urls) + "\n")
    except OSError:
        pass


def _load_health():
    return SRC.load_source_health(HEALTH_FILE)


def _save_health(h):
    SRC.save_source_health(h, HEALTH_FILE)


def _get_local_proxy():
    return SRC.read_local_proxy()


def discover_new_sources(timeout, proxy=None, health=None):
    """从聚合页种子展开并验证新的订阅源，返回新发现的源 URL 列表。"""
    t = min(timeout, 10)
    proxy = SRC.read_local_proxy() if proxy is None else proxy
    health = SRC.load_mirror_health() if health is None else health
    session = SRC.new_session()

    def fetch(url):
        text, _ = SRC.fetch_text(url, t, proxy, session, health)
        return text

    known = {s.url for s in SRC.load_sources()}
    known.update(s.split("#")[0].strip() for s in list(FN.SOURCES) + list(EXTRA_SOURCES))
    known.update(_load_discovered())

    candidates = []
    for page in SEED_PAGES:
        text = fetch(page)
        if not text:
            print(f"聚合页种子不可达: {page}")
            continue
        for u in _extract_candidate_urls(text):
            if u not in known and u not in candidates:
                candidates.append(u)
        print(f"聚合页 {page} -> 提取 {len(candidates)} 个候选订阅链接")

    valid = []
    for u in candidates[:DISCOVER_MAX_CANDIDATES]:
        text = fetch(u)
        if not text:
            continue
        uris, proxies = FN.parse_content(text)
        if uris or proxies:
            valid.append(u)
            print(f"  发现新订阅源: {u} ({len(uris) + len(proxies)} 节点)")
    SRC.save_mirror_health(health)
    return valid


# 各协议在 mihomo 里必须齐备的字段；缺一个它就会拒绝整个配置
REQUIRED_FIELDS = {
    "ss": ("cipher", "password"),
    "ssr": ("cipher", "password", "protocol", "obfs"),
    "vmess": ("uuid",),
    "vless": ("uuid",),
    "trojan": ("password",),
    "hysteria2": ("password",),
    "hysteria": ("auth-str",),
    "tuic": ("password",),
    "anytls": ("password",),
    "http": (),
    "socks5": (),
}


def proxy_field_error(p):
    """返回「缺哪个字段」的说明；没问题返回 None。"""
    t = (p.get("type") or "").lower()
    if t not in REQUIRED_FIELDS:
        return f"不认识的协议 {t or '?'}"
    for f in REQUIRED_FIELDS[t]:
        v = p.get(f)
        if v is None or (isinstance(v, str) and not v.strip()):
            return f"缺字段 {f}"
    if t == "hysteria" and not (p.get("auth-str") or p.get("auth_str")):
        return "缺字段 auth-str"
    return None


def _bad_proxy_index(msg):
    """从 mihomo 的报错里取出坏节点的下标：proxy 424: '' has unset fields: password"""
    m = re.search(r"proxy (\d+):", msg or "")
    return int(m.group(1)) if m else None


def _parse_floors(spec):
    """把 "ss=80,vmess=60" 解析成 dict。"""
    out = {}
    for part in (spec or "").split(","):
        part = part.strip()
        if not part or "=" not in part:
            continue
        k, v = part.split("=", 1)
        try:
            out[k.strip().lower()] = int(v)
        except ValueError:
            continue
    return out


DEFAULT_MIN_PER_TYPE = "ss=80,vmess=80,vless=60,trojan=40,hysteria2=40,tuic=20,anytls=20"


def _clean_proxy_fields(p):
    """去掉值为 None / 'None' / 'null' / 空串的垃圾字段，避免污染 Clash 配置。"""
    out = {}
    for k, v in p.items():
        if v is None:
            continue
        if isinstance(v, str) and v.strip().lower() in ("", "none", "null"):
            continue
        out[k] = v
    return out


# ---------------------------------------------------------------- 地区识别
#
# 灵感来自 NoMoreWalls 的 categories：拿节点名里的地区字样分组，每个地区给一个 url-test 组。
# 用途很实际——自动选择偶尔会把香港的节点换成美国的，想「就固定用香港」直接选那个组。
#
# 关键词三类：中文名（含常见简称）、国旗 emoji、ISO 两字母码（AT_speednode_0001 这种命名很常见）。
# 两字母码要求词边界，否则 "us" 会命中 "russia"、"plus"。
REGION_KEYWORDS = [
    ("HK", ("香港", "港", "🇭🇰", "hk", "hong kong", "hongkong")),
    ("TW", ("台湾", "台灣", "台", "🇹🇼", "tw", "taiwan")),
    ("JP", ("日本", "东京", "大阪", "日", "🇯🇵", "jp", "japan")),
    ("SG", ("新加坡", "狮城", "🇸🇬", "sg", "singapore")),
    ("US", ("美国", "美國", "洛杉矶", "圣何塞", "美", "🇺🇸", "us", "usa", "united states")),
    ("KR", ("韩国", "韓國", "首尔", "韩", "🇰🇷", "kr", "korea")),
    ("GB", ("英国", "伦敦", "英", "🇬🇧", "uk", "gb", "britain", "london")),
    ("DE", ("德国", "德國", "法兰克福", "德", "🇩🇪", "de", "germany")),
    ("NL", ("荷兰", "荷", "🇳🇱", "nl", "netherlands")),
    ("FR", ("法国", "法國", "巴黎", "法", "🇫🇷", "fr", "france")),
    ("IT", ("意大利", "義大利", "米兰", "🇮🇹", "it", "italy")),
    ("ES", ("西班牙", "🇪🇸", "es", "spain")),
    ("SE", ("瑞典", "🇸🇪", "se", "sweden")),
    ("CH", ("瑞士", "🇨🇭", "ch", "switzerland")),
    ("AT", ("奥地利", "奧地利", "🇦🇹", "at", "austria")),
    ("DK", ("丹麦", "🇩🇰", "dk", "denmark")),
    ("FI", ("芬兰", "🇫🇮", "fi", "finland")),
    ("NO", ("挪威", "🇳🇴", "no", "norway")),
    ("PL", ("波兰", "🇵🇱", "pl", "poland")),
    ("CZ", ("捷克", "🇨🇿", "cz", "czech")),
    ("RO", ("罗马尼亚", "🇷🇴", "ro", "romania")),
    ("TR", ("土耳其", "🇹🇷", "tr", "turkey")),
    ("RU", ("俄罗斯", "俄", "🇷🇺", "ru", "russia")),
    ("CA", ("加拿大", "🇨🇦", "ca", "canada")),
    ("BR", ("巴西", "🇧🇷", "br", "brazil")),
    ("AU", ("澳大利亚", "澳洲", "🇦🇺", "au", "australia")),
    ("IN", ("印度", "🇮🇳", "in", "india")),
    ("AE", ("阿联酋", "迪拜", "🇦🇪", "ae", "dubai")),
    ("MY", ("马来西亚", "🇲🇾", "my", "malaysia")),
    ("VN", ("越南", "🇻🇳", "vn", "vietnam")),
    ("TH", ("泰国", "🇹🇭", "th", "thailand")),
    ("PH", ("菲律宾", "🇵🇭", "ph", "philippines")),
    ("ID", ("印尼", "印度尼西亚", "🇮🇩", "id", "indonesia")),
    ("ZA", ("南非", "🇿🇦", "za", "south africa")),
]

REGION_DISPLAY = [
    ("HK", "🇭🇰 香港"),
    ("TW", "🇹🇼 台湾"),
    ("JP", "🇯🇵 日本"),
    ("SG", "🇸🇬 新加坡"),
    ("US", "🇺🇸 美国"),
    ("KR", "🇰🇷 韩国"),
    ("GB", "🇬🇧 英国"),
    ("DE", "🇩🇪 德国"),
    ("NL", "🇳🇱 荷兰"),
    ("FR", "🇫🇷 法国"),
    ("IT", "🇮🇹 意大利"),
    ("ES", "🇪🇸 西班牙"),
    ("SE", "🇸🇪 瑞典"),
    ("CH", "🇨🇭 瑞士"),
    ("AT", "🇦🇹 奥地利"),
    ("DK", "🇩🇰 丹麦"),
    ("FI", "🇫🇮 芬兰"),
    ("NO", "🇳🇴 挪威"),
    ("PL", "🇵🇱 波兰"),
    ("CZ", "🇨🇿 捷克"),
    ("RO", "🇷🇴 罗马尼亚"),
    ("TR", "🇹🇷 土耳其"),
    ("RU", "🇷🇺 俄罗斯"),
    ("CA", "🇨🇦 加拿大"),
    ("BR", "🇧🇷 巴西"),
    ("AU", "🇦🇺 澳大利亚"),
    ("IN", "🇮🇳 印度"),
    ("AE", "🇦🇪 阿联酋"),
    ("MY", "🇲🇾 马来西亚"),
    ("VN", "🇻🇳 越南"),
    ("TH", "🇹🇭 泰国"),
    ("PH", "🇵🇭 菲律宾"),
    ("ID", "🇮🇩 印尼"),
    ("ZA", "🇿🇦 南非"),
]

# 单节点地区不单独成组，免得 yml 里堆二十个只有一个成员的下拉项
REGION_MIN_MEMBERS = 2


def region_of(name):
    """从节点名认地区，认不出返回 None（宁可不分组，也别乱分）。"""
    n = name or ""
    low = n.lower()
    for key, kws in REGION_KEYWORDS:
        for kw in kws:
            if kw.isascii():
                # 字母关键字要求词边界，否则 "us" 会命中 "russia"、"plus"
                if re.search(rf"(?<![a-z]){re.escape(kw)}(?![a-z])", low):
                    return key
            elif kw in n:
                return key
    return None


def load_source_list(sources_file=None):
    """
    取本轮要抓的源：优先 sources.list，其次退回代码里的内置清单。

    这样加源/停源只要改文本文件，不用动 Python；老用户没有 sources.list 也照旧能跑。
    """
    srcs = SRC.load_sources(sources_file or SRC.DEFAULT_SOURCES_FILE)
    if not srcs:
        srcs = [SRC.Source(url) for url in list(FN.SOURCES) + list(EXTRA_SOURCES)]
    return srcs


def grab_sources(srcs, timeout, workers, proxy=None, mirror_health=None, quiet=False):
    """
    并发抓取所有源，返回每个源一行结果（含解析出的节点）。

    以前是一条一条串行抓、每条还可能先等 raw 的 20 秒超时，
    17 个源下来光等待就五六分钟。现在并发 8 路 + 镜像优先，
    通常 30～60 秒就全回来了。
    """
    import concurrent.futures as cf

    proxy = SRC.read_local_proxy() if proxy is None else proxy
    mh = SRC.load_mirror_health() if mirror_health is None else mirror_health
    skip = [s for s in srcs if s.needs_proxy and not proxy]
    active = [s for s in srcs if not (s.needs_proxy and not proxy)]
    if skip and not quiet:
        print(f"跳过 {len(skip)} 个需要代理的源（未配置 local_proxy.conf）")

    def work(src):
        t0 = time.time()
        session = SRC.new_session()  # Session 不保证线程安全，每任务一个
        text, mirror = SRC.fetch_text(src.url, timeout, proxy, session, mh)
        secs = time.time() - t0
        if text is None:
            return {
                "src": src, "ok": False, "count": 0, "secs": secs,
                "mirror": "", "items": [], "error": "下载失败",
            }
        try:
            if src.is_list:
                merged = FN.expand_list_page(
                    text,
                    lambda u: SRC.fetch_text(u, timeout, proxy, SRC.new_session(), mh)[0],
                )
                items = list(merged)
            else:
                uris, proxies = FN.parse_content(text)
                items = list(proxies) + [FN.uri_to_proxy(u) for u in uris]
        except Exception as e:  # noqa: BLE001
            return {
                "src": src, "ok": False, "count": 0, "secs": secs,
                "mirror": mirror or "", "items": [], "error": f"解析失败 {e}",
            }
        items = [x for x in items if x]
        if src.ignore:
            bad = set(src.ignore)
            items = [x for x in items if (x.get("type") or "").lower() not in bad]
        if src.max_nodes:
            items = items[: src.max_nodes]
        return {
            "src": src, "ok": True, "count": len(items), "secs": secs,
            "mirror": mirror or "", "items": items,
            "error": "" if items else "解析出 0 个节点",
        }

    rows = [None] * len(active)
    with cf.ThreadPoolExecutor(max_workers=workers) as ex:
        futures = {ex.submit(work, s): i for i, s in enumerate(active)}
        for fut in cf.as_completed(futures):
            i = futures[fut]
            try:
                rows[i] = fut.result()
            except Exception as e:  # noqa: BLE001
                rows[i] = {
                    "src": active[i], "ok": False, "count": 0, "secs": 0,
                    "mirror": "", "items": [], "error": f"异常 {e}",
                }
            if not quiet:
                r = rows[i]
                flag = "OK  " if r["ok"] and r["count"] else "FAIL"
                print(f"[{i:02d}] {flag} {r['count']:>5d} {r['secs']:5.1f}s "
                      f"{r['mirror'] or '-':14s} {r['src'].url}")
    SRC.save_mirror_health(mh)
    return [r for r in rows if r is not None]


def fetch_all(max_nodes, timeout, min_per_type=DEFAULT_MIN_PER_TYPE,
              workers=8, sources_file=None, rows=None):
    """抓取全部来源并解析合并，返回 (proxies, ok_urls, fail_urls, rows)。"""
    srcs = load_source_list(sources_file)
    if rows is None:
        print(f"开始抓取 {len(srcs)} 个源（并发 {workers}）...")
        rows = grab_sources(srcs, timeout, workers)

    ok_urls, fail_urls = [], []
    for r in rows:
        if r["ok"] and r["count"]:
            ok_urls.append(r["src"].url)
        else:
            fail_urls.append(r["src"].url)

    # 每个源单独留一组，供后面的「轮转挑选」用（顺序 = sources.list 顺序）
    groups = [list(r["items"]) for r in rows if r["items"]]
    total = sum(len(g) for g in groups)
    print(f"抓到原始节点 {total} 个（{len(groups)} 个源有产出）")

    # 去重 + 过滤内网/假节点 + 轮转挑选
    #
    # 注意这里不能用「拼成一个大会表再截前 max_nodes 条」的写法：
    # 那样前面几个大源会把名额吃光，后面源里的节点（比如 ShadowsocksAggregator
    # 里 4000 多个中的 1800 个 ss）一个都进不来。改成每个源轮流取一条，
    # 名额就摊平了，ss / vmess 这些小众协议才有机会被测到。
    clean, seen = [], set()
    dropped_field = {}

    def _accept(p):
        if not isinstance(p, dict) or not p.get("server"):
            return None
        if FN.is_decoy(p.get("server")):
            return None
        bad = proxy_field_error(p)
        if bad:
            dropped_field[bad.split()[1] if " " in bad else bad] = \
                dropped_field.get(bad.split()[1] if " " in bad else bad, 0) + 1
            return None
        key = (
            p.get("type"),
            p.get("server"),
            p.get("port"),
            str(p.get("uuid") or p.get("password") or "")[:12],
        )
        if key in seen:
            return None
        seen.add(key)
        return _clean_proxy_fields(p)

    cursors = [0] * len(groups)
    while len(clean) < max_nodes:
        progressed = False
        for gi, g in enumerate(groups):
            while cursors[gi] < len(g):
                item = _accept(g[cursors[gi]])
                cursors[gi] += 1
                if item is None:
                    continue
                clean.append(item)
                progressed = True
                break
            if len(clean) >= max_nodes:
                break
        if not progressed:
            break
    type_stat = {}
    for p in clean:
        type_stat[p.get("type")] = type_stat.get(p.get("type"), 0) + 1

    # ===== 协议保底 =====
    # 只按源轮转还不够：一个源内部 ss 可能排得很靠后。这里对「能转成客户端订阅」
    # 的协议做上浮，保证池子里每种协议都够测，不然用 Shadowsocks / v2rayN 的人
    # 永远拿到空文件。
    floors = _parse_floors(min_per_type)
    extra_budget = 300
    for t, floor in floors.items():
        need = floor - type_stat.get(t, 0)
        if need <= 0:
            continue
        got = 0
        for gi in range(len(groups)):
            g = groups[gi]
            while cursors[gi] < len(g) and got < need and extra_budget > 0:
                item = _accept(g[cursors[gi]])
                cursors[gi] += 1
                if item is None or item.get("type") != t:
                    continue
                clean.append(item)
                got += 1
                extra_budget -= 1
            if got >= need or extra_budget <= 0:
                break
        if got:
            type_stat[t] = type_stat.get(t, 0) + got

    if dropped_field:
        print("字段不全被丢弃：" + "、".join(f"{k}×{v}" for k, v in
                                      sorted(dropped_field.items(), key=lambda kv: -kv[1])))
    print("候选协议分布：" + "、".join(f"{k}×{v}" for k, v in
                                 sorted(type_stat.items(), key=lambda kv: -kv[1])))
    print(f"进入测速的候选：{len(clean)} 个（含协议保底补入的）")

    # 节点名去重
    names = set()
    for p in clean:
        n = p.get("name") or f"{p.get('server')}:{p.get('port')}"
        base, idx = n, 2
        while n in names:
            n = f"{base} #{idx}"
            idx += 1
        names.add(n)
        p["name"] = n
    return clean, ok_urls, fail_urls, rows


# ------------------------------------------------------------------ 体检模式


def doctor(args):
    """
    只体检源、不测速：并发探一遍，列出每个源通不通、产出多少节点、耗时、走的哪个镜像。

    加源/停源前先跑这个，比整轮跑（几分钟）快得多，几十秒就出结果。
    """
    srcs = load_source_list(args.sources_file)
    print(f"体检 {len(srcs)} 个源（并发 {args.workers}，单源超时 {args.timeout}s）...\n")
    rows = grab_sources(srcs, args.timeout, args.workers, quiet=True)

    ok = [r for r in rows if r["ok"] and r["count"]]
    empty = [r for r in rows if r["ok"] and not r["count"]]
    dead = [r for r in rows if not r["ok"]]
    ok.sort(key=lambda r: -r["count"])

    # 更新源健康度
    health = _load_health()
    for r in rows:
        SRC.note_source(
            health, r["src"].url, r["ok"] and bool(r["count"]),
            r["count"], r["mirror"], r["error"],
        )
    _save_health(health)

    def table(rows_):
        if not rows_:
            return []
        out = ["| 状态 | 节点 | 耗时 | 镜像 | 源 |", "|---|---|---|---|---|"]
        for r in rows_:
            st = "可用" if r["ok"] and r["count"] else ("空" if r["ok"] else "不可用")
            out.append(
                f"| {st} | {r['count']} | {r['secs']:.1f}s | {r['mirror'] or '-'} | "
                f"{r['src'].url} |"
            )
        return out

    now = datetime.now().strftime("%Y-%m-%d %H:%M")
    lines = [
        f"# 源体检报告（{now}）",
        "",
        f"- 共 {len(rows)} 个源：可用 {len(ok)}、空 {len(empty)}、不可用 {len(dead)}",
        f"- 命中节点合计 {sum(r['count'] for r in ok)} 个",
        "",
        "## 可用（按节点数排序）",
        "",
    ]
    lines += table(ok)
    if empty:
        lines += ["", "## 能打开但解析不出节点", ""] + table(empty)
    if dead:
        lines += ["", "## 不可用", ""] + table(dead)

    lines += ["", "## 建议的 sources.list", "", "```", "# 可用"]
    for r in ok:
        lines.append(r["src"].url)
    if dead or empty:
        lines.append("")
        lines.append(f"# 以下 {now} 体检不可用，先注释掉")
        for r in dead + empty:
            lines.append("# " + r["src"].url)
    lines.append("```")

    os.makedirs(args.out, exist_ok=True)
    out_path = os.path.join(args.out, "sources.md")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    print(f"可用 {len(ok)} 个（合计 {sum(r['count'] for r in ok)} 节点）、"
          f"空 {len(empty)}、不可用 {len(dead)}")
    for r in dead + empty:
        print(f"  {'不可用' if not r['ok'] else '空  '}  {r['src'].url}")
    print(f"\n明细已写入 {out_path}")
    return 0


def build_test_config(proxies, names, mixed_port, api_port, secret):
    return {
        "mixed-port": mixed_port,
        "allow-lan": False,
        "mode": "rule",
        "log-level": "error",
        "ipv6": False,
        "unified-delay": True,
        "external-controller": f"127.0.0.1:{api_port}",
        "secret": secret,
        "dns": {"enable": False},
        "proxies": proxies,
        "proxy-groups": [
            {
                "name": "TEST",
                "type": "url-test",
                "url": GOOGLE_204,
                "interval": 300,
                "tolerance": 50,
                "proxies": names,
            }
        ],
        "rules": ["MATCH,TEST"],
    }


def batch_delay(runner, group, url, timeout_ms):
    """用 url-test 组批量并发测延迟，返回 {name: delay_ms}，只含存活节点。"""
    q = urllib.parse.quote(url, safe="")
    try:
        r = runner.api(
            "GET",
            f"/group/{group}/delay?url={q}&timeout={timeout_ms}",
            timeout=max(30, timeout_ms * len(str(group)) // 1000 + 60),
        )
        if r.status_code == 200:
            data = r.json()
            if isinstance(data, dict):
                out = {}
                for k, v in data.items():
                    if isinstance(v, (int, float)) and v > 0:
                        out[str(k)] = int(v)
                return out
    except requests.RequestException:
        pass
    return {}


def fallback_delay(runner, names, timeout_ms, limit=200):
    """兜底：逐个测延迟（当批量 API 异常时使用），只测前 limit 个。"""
    out = {}
    for name in names[:limit]:
        d = runner.delay(name, timeout_ms)
        if d:
            out[name] = d
    return out


def probe_delays(runner, names, timeout_ms, workers=12, targets=None):
    """
    逐个测延迟，同时跑 workers 个。

    以前是直接调一次 `/group/TEST/delay`，让内核把**全部候选**（常常 500～600 个）
    同时发出去测——内核自己就撑爆了，大量节点明明能用却被当成死的（实测只活 10%，
    而同批节点里下载测速却有 75% 能跑通，自相矛盾）。改成逐节点并发，数字才可信。

    另一个坑是「目标站」：只测 google.com 时，那些能通但 Google 被墙/被拦的节点
    会被误判。这里按顺序换几个目标，能回一个就算活。
    """
    import concurrent.futures as cf

    targets = tuple(targets or DELAY_TARGETS)
    out = {}

    def one(name):
        for url in targets:
            try:
                d = runner.delay_to(name, url, min(timeout_ms, 2500))
            except Exception:  # noqa: BLE001
                d = None
            if isinstance(d, (int, float)) and d > 0:
                return name, int(d)
        return name, None

    total = len(names)
    with cf.ThreadPoolExecutor(max_workers=max(1, workers)) as ex:
        for i, (name, d) in enumerate(ex.map(one, names), 1):
            if d:
                out[name] = d
            if i % 100 == 0 or i == total:
                print(f"  延迟 {i}/{total} ... 存活 {len(out)}")
    return out


def speedtest(pool, args):
    """起独立 mihomo 内核，对候选节点批量测延迟 + 真实下载速度。"""
    names = [p["name"] for p in pool]
    mixed = NQ.free_port()
    api = NQ.free_port()
    secret = secrets.token_hex(8)
    tmp = tempfile.mkdtemp(prefix="autonodes-")
    cfg_path = os.path.join(tmp, "test.yaml")
    with open(cfg_path, "w", encoding="utf-8") as f:
        yaml.safe_dump(
            build_test_config(pool, names, mixed, api, secret),
            f,
            allow_unicode=True,
            sort_keys=False,
        )

    runner = NQ.MihomoRunner(args.mihomo, tmp, mixed, api, secret)
    results = []
    alive_count = 0
    try:
        # 安全网：mihomo 只要碰到一个不合法的节点就会整个拒绝启动。
        # 抓取阶段已经按协议校验过必填字段了，这里再兜一层 ——
        # 从它的报错里读出坏节点的下标，剔掉重试。
        for _ in range(8):
            try:
                runner.start(cfg_path)
                break
            except RuntimeError as e:
                idx = _bad_proxy_index(str(e))
                if idx is None or not (0 <= idx < len(pool)):
                    raise
                bad = pool.pop(idx)
                names = [p["name"] for p in pool]
                print(f"  mihomo 拒绝第 {idx} 个节点（{bad.get('name')} / {bad.get('type')}），剔除后重试")
                with open(cfg_path, "w", encoding="utf-8") as f:
                    yaml.safe_dump(
                        build_test_config(pool, names, mixed, api, secret),
                        f, allow_unicode=True, sort_keys=False,
                    )
        else:
            raise RuntimeError("mihomo 连续拒绝配置，放弃本次运行")
        session = NQ.http_session(mixed)

        # 阶段 A：延迟
        print(f"测延迟中（并发 {args.delay_workers}）...")
        delays = probe_delays(runner, names, args.max_delay, args.delay_workers)
        if not delays:
            print("逐节点测延迟无结果，回退批量 API")
            delays = batch_delay(runner, "TEST", GOOGLE_204, args.max_delay)
        if not delays:
            print("批量延迟 API 也无结果，回退前 200 个逐个测")
            delays = fallback_delay(runner, names, args.max_delay)
        alive = sorted(delays.items(), key=lambda kv: kv[1])
        alive_count = len(alive)
        print(f"延迟存活 {alive_count}/{len(names)}")

        if args.quick:
            results = [
                {"name": n, "delay": d, "speed": None} for n, d in alive[: args.top * 4]
            ]
        else:
            # 候选：延迟 <= speed_max_delay 的存活节点，按延迟升序取前 speed_top
            cand = [(n, d) for n, d in alive if d <= args.speed_max_delay]
            if not cand:
                cand = alive  # 全部都很慢时兜底
            cand = cand[: args.speed_top]
            print(f"开始对 {len(cand)} 个节点测下载速度 ...")
            for i, (name, d) in enumerate(cand, 1):
                runner.select(name)
                time.sleep(0.3)
                sp = NQ.measure_speed(session, CF_SPEED, 3 * 1024 * 1024, 12)
                results.append({"name": name, "delay": d, "speed": sp})
                if i % 10 == 0:
                    ok = sum(1 for r in results if r["speed"] and r["speed"] >= args.min_speed)
                    print(f"  速度测试 {i}/{len(cand)} ... 达标 {ok}")
    finally:
        runner.stop()
        shutil.rmtree(tmp, ignore_errors=True)
    return results, alive_count


def sync_to_verge(best_yml_path):
    """把生成的订阅写入 Clash Verge 的本地订阅，返回是否成功。"""
    appdata = os.environ.get("APPDATA", "")
    base = os.path.join(appdata, "io.github.clash-verge-rev.clash-verge-rev")
    profiles_yaml = os.path.join(base, "profiles.yaml")
    if not os.path.exists(profiles_yaml):
        return False

    try:
        shutil.copy2(profiles_yaml, profiles_yaml + ".bak")
        with open(profiles_yaml, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
        items = data.setdefault("items", [])
        found = None
        for it in items:
            if isinstance(it, dict) and it.get("uid") == VERGE_PROFILE_UID:
                found = it
                break
        if found is None:
            found = {
                "uid": VERGE_PROFILE_UID,
                "type": "local",
                "name": VERGE_PROFILE_NAME,
                "file": VERGE_PROFILE_UID + ".yaml",
                "desc": "每日自动测速排序",
                "option": {"allow_auto_update": True},
            }
            items.append(found)
        found["updated"] = int(time.time())

        target = os.path.join(base, "profiles", VERGE_PROFILE_UID + ".yaml")
        shutil.copy2(best_yml_path, target)

        with open(profiles_yaml, "w", encoding="utf-8") as f:
            yaml.safe_dump(data, f, allow_unicode=True, sort_keys=False)
        return True
    except Exception as e:  # noqa: BLE001
        print(f"同步 Clash Verge 失败（不影响结果文件）: {e}")
        return False


def should_run(state_file, min_hours):
    # 运行锁：3 小时内有人跑过/正在跑则跳过，防止每日任务与开机自启并发
    lock_path = state_file + ".lock"
    if os.path.exists(lock_path):
        try:
            if time.time() - os.path.getmtime(lock_path) < 3 * 3600:
                return False
        except OSError:
            pass
    if os.path.exists(state_file):
        try:
            last = float(open(state_file, "r", encoding="utf-8").read().strip())
            if time.time() - last < min_hours * 3600:
                return False
        except (OSError, ValueError):
            pass
    return True


def main():
    ap = argparse.ArgumentParser(description="一体化免费节点管家")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--mihomo", default=DEFAULT_MIHOMO)
    ap.add_argument("--state-file", default=DEFAULT_STATE)
    ap.add_argument("--max-nodes", type=int, default=600, help="进入测速的候选节点上限")
    ap.add_argument("--max-delay", type=int, default=3000, help="延迟阈值 ms，超过视为死节点")
    ap.add_argument("--speed-top", type=int, default=150, help="做下载测速的节点数量")
    ap.add_argument("--speed-max-delay", type=int, default=1500, help="参与下载测速的延迟上限 ms")
    ap.add_argument("--min-speed", type=float, default=0.2, help="合格最低下载速度 MB/s")
    ap.add_argument("--top", type=int, default=40, help="最终保留节点数量")
    ap.add_argument(
        "--region-min",
        type=int,
        default=REGION_MIN_MEMBERS,
        help="地区组至少要有几个节点才建（0 = 只要有一个就建）",
    )
    ap.add_argument(
        "--min-per-type",
        default=DEFAULT_MIN_PER_TYPE,
        help="候选池里每种协议至少留几个，例如 ss=80,vmess=80",
    )
    ap.add_argument(
        "--keep-per-type",
        default="ss=6,vmess=5,vless=5,trojan=4,hysteria2=3,tuic=2,anytls=2",
        help="最终名单里每种协议至少保留几条（从存活池按速度补），保证各客户端都有得用",
    )
    ap.add_argument(
        "--exclude-types",
        default="",
        help="排除某些协议，逗号分隔，例如 http,socks5（这类节点转不成分享链接，会挤掉别人）",
    )
    ap.add_argument("--timeout", type=int, default=20, help="抓取单源超时秒")
    ap.add_argument("--workers", type=int, default=8, help="并发抓取的线程数")
    ap.add_argument(
        "--delay-workers",
        type=int,
        default=16,
        help="延迟测试的并发数（逐个探，默认 16；瓶颈是等网络，不是 CPU）",
    )
    ap.add_argument(
        "--max-per-type",
        default="http=10,socks5=4",
        help="最终名单里单个协议的硬上限，例如 http=10,socks5=4；空字符串则不限制",
    )
    ap.add_argument(
        "--sources-file",
        default=SRC.DEFAULT_SOURCES_FILE,
        help="源清单文件（默认 tools/sources.list）",
    )
    ap.add_argument(
        "--doctor",
        action="store_true",
        help="只体检源：并发探一遍，列出通不通/节点数/耗时，写 output/sources.md，不测速",
    )
    ap.add_argument("--min-interval-hours", type=float, default=6.0, help="两次完整运行的最小间隔小时")
    ap.add_argument("--quick", action="store_true", help="只测延迟不测下载速度")
    ap.add_argument("--force", action="store_true", help="忽略 guard 强制运行")
    ap.add_argument("--skip-sync", action="store_true", help="不写 Clash Verge")
    ap.add_argument("--no-label", action="store_true", help="不给节点名加实测速度前缀")
    args = ap.parse_args()

    # 体检模式先把活干完再退出：它不需要 mihomo，也不该被 6 小时 guard 拦住
    if args.doctor:
        os.makedirs(args.out, exist_ok=True)
        return doctor(args)

    if not os.path.exists(args.mihomo):
        print(f"找不到 mihomo 内核: {args.mihomo}")
        return 2

    if not args.force and not should_run(args.state_file, args.min_interval_hours):
        print(
            f"距上次成功运行不足 {args.min_interval_hours} 小时，跳过"
            "（如需强制请加 --force）"
        )
        return 0

    os.makedirs(args.out, exist_ok=True)
    os.makedirs(os.path.dirname(args.state_file), exist_ok=True)

    # 标记正在运行（should_run 里的 3 小时锁据此拦截并发实例）
    try:
        with open(args.state_file + ".lock", "w", encoding="utf-8") as f:
            f.write(str(os.getpid()))
    except OSError:
        pass

    # ===== 阶段 0：从聚合页发现新订阅源 =====
    new_sources = discover_new_sources(args.timeout)
    discovered = _load_discovered()
    for u in new_sources:
        if u not in discovered:
            discovered.append(u)
    _save_discovered(discovered)
    if new_sources:
        print(f"本轮新发现 {len(new_sources)} 个订阅源")

    # ===== 阶段 1：抓取 =====
    print(f"== 阶段 1/3：抓取节点（{datetime.now():%Y-%m-%d %H:%M:%S}）==")
    pool, ok_urls, fail_urls, src_rows = fetch_all(
        args.max_nodes, args.timeout, args.min_per_type,
        workers=args.workers, sources_file=args.sources_file,
    )
    if not pool:
        print("抓取得到 0 节点，保留旧订阅，退出")
        return 1
    print(f"抓取去重过滤后 {len(pool)} 个节点")

    # 更新源健康度（ok/fail + 最后一次成功时间与产出节点数），并淘汰连续失败过多的动态源
    health = _load_health()
    for r in src_rows:
        SRC.note_source(
            health, r["src"].url, r["ok"] and bool(r["count"]),
            r["count"], r["mirror"], r["error"],
        )
    _save_health(health)
    alive = [d for d in discovered if health.get(d, {}).get("fail", 0) < DEAD_SOURCE_THRESHOLD]
    if len(alive) != len(discovered):
        _save_discovered(alive)
        print(f"已淘汰 {len(discovered) - len(alive)} 个连续失败的死源")

    # ===== 阶段 2：测速 =====
    print("== 阶段 2/3：测速 ==")
    results, alive_count = speedtest(pool, args)
    if not results:
        print("测速后 0 存活节点，保留旧订阅，退出")
        return 1

    # 存活率特别低时给一句提示：夜里免费节点本来就会大面积失效，
    # 但另一个常见原因是 Clash Verge 开着 TUN/系统代理，把我们临时起的测速内核又代理了一层，
    # 延迟被括大、握手大量超时。这两种情况表现很像，先把可能性列出来。
    if pool and alive_count and alive_count / len(pool) < 0.2:
        print(f"提示：延迟存活只有 {alive_count}/{len(pool)}（{alive_count / len(pool):.0%}）。")
        print("      免费节点晚间确实会大量失效；如果 Clash Verge 正开着 TUN 模式或系统代理，")
        print("      先把它们关掉再跑一次，测速结果会准很多。")

    # ===== 阶段 3：排序 + 输出 + 同步 =====
    print("== 阶段 3/3：排序 + 输出 ==")
    if args.quick:
        results.sort(key=lambda r: r["delay"])
        tested_all = list(results)  # 截断前的全部存活节点，导出其它格式时按协议补用
        results = results[: args.top]
        qualified_count = len(results)
    else:
        # 有真实测速数据的按下载速度降序、延迟升序；测速失败的剔除
        with_speed = [r for r in results if r["speed"] is not None]
        with_speed.sort(key=lambda r: (-r["speed"], r["delay"]))
        qualified = [r for r in with_speed if r["speed"] >= args.min_speed]
        qualified_count = len(qualified)
        # 优先只保留达标的；一个都不达标时才用相对最好的一批兜底（避免空订阅）
        results = qualified if qualified else with_speed
        tested_all = list(results)
        results = results[: args.top]

    by_name = {p["name"]: p for p in pool}

    # 单协议硬上限：不加这一道，最终名单很容易被 http 节点刷满（它们快、且量大），
    # 结果非 Clash 客端拿到的文件里几乎没东西。默认给 http/socks5 封顶，
    # 把名额留给 ss / vmess / vless 这些能转成分享链接的。
    type_cap = _parse_floors(args.max_per_type)
    if type_cap:
        capped, seen_type = [], {}
        for r in results:
            p = by_name.get(r["name"])
            t = (p.get("type") or "").lower() if p else ""
            cap = type_cap.get(t)
            if cap is not None and seen_type.get(t, 0) >= cap:
                continue
            seen_type[t] = seen_type.get(t, 0) + 1
            capped.append(r)
        if len(capped) != len(results):
            print(f"单协议封顶：{len(results) - len(capped)} 个 "
                  + "、".join(f"{k}≤{v}" for k, v in sorted(type_cap.items()))
                  + " 被压掉，名额让给其它协议")
        results = capped[: args.top]
    # 速度/延迟按名字查（用截断前的 tested_all，配额补进来的节点也要能查到）
    stat_by_name = {r["name"]: r for r in tested_all}
    speed_map = {r["name"]: r["speed"] for r in tested_all}
    ordered = []
    for r in results:
        if r["name"] in by_name:
            ordered.append(by_name[r["name"]])

    # ===== 协议配额 =====
    # 光按速度排，前几名常常全是 http/socks5（它们没法转成 ss:// 或 vmess:// 分享链接），
    # 结果用 Shadowsocks / v2rayN 的人拿到的是空文件。这里给每种协议保底几条。
    quota = _parse_floors(args.keep_per_type)
    picked = {p.get("name") for p in ordered}
    quota_added = {}
    for t, need in quota.items():
        have = sum(1 for p in ordered if (p.get("type") or "").lower() == t)
        if have >= need:
            continue
        for r in tested_all:
            if have >= need:
                break
            nm = r.get("name")
            if nm in picked:
                continue
            p = by_name.get(nm)
            if not p or (p.get("type") or "").lower() != t:
                continue
            picked.add(nm)
            ordered.append(p)
            have += 1
        quota_added[t] = have
    if any(quota_added.values()):
        print("协议配额补入：" + "、".join(
            f"{t}×{v}" for t, v in sorted(quota_added.items()) if v))

    if not ordered:
        print("排序后 0 节点，保留旧订阅，退出")
        return 1

    if args.exclude_types:
        bad = {t.strip().lower() for t in args.exclude_types.split(",") if t.strip()}
        before = len(ordered)
        ordered = [p for p in ordered if (p.get("type") or "").lower() not in bad]
        print(f"按 --exclude-types 过滤掉 {before - len(ordered)} 个 {sorted(bad)} 节点")

    # 可选：给节点名加实测速度前缀，便于在 Clash 里一眼看出快慢
    final_proxies = []
    for p in ordered:
        p = dict(p)
        sp = speed_map.get(p["name"])
        if not args.no_label and not args.quick and sp is not None:
            p["name"] = f"{sp:.1f}MB/s {p['name']}"
        final_proxies.append(p)

    gnames = [p["name"] for p in final_proxies]

    # 地区分组：从节点名里认地区（香港 / 日本 / 新加坡…），每个地区一个 url-test 组。
    # 免费节点的名字是源站起的，什么样都有，所以只做「认得出就分组、认不出就不管」，
    # 分组只影响 Clash 里好不好挑，不影响节点本身。
    region_nodes = {}
    for name in gnames:
        rk = region_of(name)
        if rk:
            region_nodes.setdefault(rk, []).append(name)

    groups = [
        {
            "name": "🚀 自动选择",
            "type": "url-test",
            "url": GOOGLE_204,
            "interval": 300,
            "tolerance": 50,
            "proxies": gnames,
        },
        {
            "name": "🔁 故障转移",
            "type": "fallback",
            "url": GOOGLE_204,
            "interval": 300,
            "proxies": gnames,
        },
    ]
    for key, disp in REGION_DISPLAY:
        members = region_nodes.get(key) or []
        if len(members) < args.region_min:
            continue
        groups.append({
            "name": disp,
            "type": "url-test",
            "url": GOOGLE_204,
            "interval": 300,
            "tolerance": 50,
            "proxies": members,
        })
    groups.append({"name": "🐟 手动选择(按速度排序)", "type": "select", "proxies": gnames})

    cfg = {
        # 带上这几行，这份 yml 自己就能当一份完整配置用（直接给内核/部分客户端）
        "mixed-port": 7890,
        "allow-lan": False,
        "mode": "rule",
        "log-level": "info",
        "unified-delay": True,
        "tcp-concurrent": True,
        "proxies": final_proxies,
        "proxy-groups": groups,
        "rules": ["MATCH,🚀 自动选择"],
    }
    best_path = os.path.join(args.out, "best_nodes.yml")
    with open(best_path, "w", encoding="utf-8") as f:
        yaml.safe_dump(cfg, f, allow_unicode=True, sort_keys=False)
    print(f"已生成 {best_path}（{len(ordered)} 个节点，按速度降序）")

    # 顺手导出各种客户端认识的格式：Shadowsocks(sip008 / ss 链接)、v2rayN(通用订阅)、sing-box…
    # 失败不影响主流程，best_nodes.yml 已经落盘了
    try:
        # 传两份：final_proxies 是入选的；labeled_pool 是全部测过且存活的，
        # 让 ss / v2ray 这些格式能从更大范围里按协议挑，不被 http 类节点挤空。
        labeled_pool = []
        for r in tested_all:
            p = by_name.get(r['name'])
            if not p:
                continue
            q = dict(p)
            sp = r.get('speed')
            if not args.no_label and not args.quick and sp is not None:
                q['name'] = f"{sp:.1f}MB/s {q['name']}"
            labeled_pool.append(q)
        EF.write_all(args.out, final_proxies, pool=labeled_pool)
    except Exception as e:  # noqa: BLE001
        print(f"导出其它客户端格式失败（不影响主流程）: {e}")

    # 报告
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    speed_line = (
        "- 测速模式: 仅延迟（quick）"
        if args.quick
        else f"- 下载达标(≥{args.min_speed}MB/s): {qualified_count}"
    )

    # 协议 / 地区分布，直接看得出这批节点有啥、缺啥
    proto_count = {}
    for p in final_proxies:
        t = (p.get("type") or "?").lower()
        proto_count[t] = proto_count.get(t, 0) + 1
    region_count = {}
    for p in final_proxies:
        rk = region_of(p["name"]) or "未识别"
        region_count[rk] = region_count.get(rk, 0) + 1
    region_disp = dict(REGION_DISPLAY)

    ok_src = [r for r in src_rows if r["ok"] and r["count"]]
    dead_src = [r for r in src_rows if not (r["ok"] and r["count"])]
    ok_src.sort(key=lambda r: -r["count"])

    report_md = [
        f"# 节点测速报告（{now}）",
        "",
        f"- 候选节点: {len(pool)}",
        f"- 延迟存活: {alive_count}",
        speed_line,
        f"- 入选: {len(ordered)}（按下载速度降序）",
        f"- 源: {len(ok_src)} 个有产出 / 共 {len(src_rows)} 个",
        "",
        "## 入选名单",
        "",
        "| 排名 | 节点 | 延迟(ms) | 下载速度(MB/s) |",
        "|---|---|---|---|",
    ]
    for i, p in enumerate(final_proxies, 1):
        orig = p["name"]
        # 名字里可能已经带了速度前缀，查表时要去掉
        if "MB/s " in orig and orig.split("MB/s ", 1)[1] in stat_by_name:
            orig = orig.split("MB/s ", 1)[1]
        st = stat_by_name.get(orig, {}) or {}
        sp = f"{st.get('speed'):.2f}" if st.get("speed") is not None else "-"
        report_md.append(f"| {i} | {p['name']} | {st.get('delay', '-')} | {sp} |")

    report_md += ["", "## 分布", "", "协议：" + "、".join(
        f"{k}×{v}" for k, v in sorted(proto_count.items(), key=lambda kv: -kv[1]))]
    report_md.append("地区：" + "、".join(
        f"{region_disp.get(k, k)}×{v}"
        for k, v in sorted(region_count.items(), key=lambda kv: -kv[1])))

    report_md += ["", "## 源健康度", "", "| 状态 | 节点 | 耗时 | 镜像 | 源 |",
                  "|---|---|---|---|---|"]
    for r in ok_src:
        report_md.append(
            f"| 可用 | {r['count']} | {r['secs']:.1f}s | {r['mirror'] or '-'} | {r['src'].url} |")
    for r in dead_src:
        st = "空" if r["ok"] else "不可用"
        report_md.append(
            f"| {st} | 0 | {r['secs']:.1f}s | {r['mirror'] or '-'} | {r['src'].url} |")
    report_md += ["", "> 源清单在 `tools/sources.list`；`python daily_auto.py --doctor` 可单独体检源。"]

    with open(os.path.join(args.out, "report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(report_md) + "\n")


    synced = False
    if not args.skip_sync:
        synced = sync_to_verge(best_path)
        if synced:
            print(f"已同步到 Clash Verge 订阅「{VERGE_PROFILE_NAME}」")

    with open(args.state_file, "w", encoding="utf-8") as f:
        f.write(str(time.time()))

    print(f"测速池：{len(tested_all)} 个存活节点（入选前 {args.top} 个给 Clash，其余按协议补给其它客户端）")

    print("输出目录里的文件：")
    for fn, desc in (
        ("best_nodes.yml", "Clash / mihomo / Clash Verge / FlClash"),
        ("sip008.json", "Shadowsocks 官方 JSON 订阅"),
        ("ss-base64.txt / ss-plain.txt", "ss:// 订阅 / 明文"),
        ("v2ray-base64.txt / all-links.txt", "v2rayN / v2rayNG / Shadowrocket"),
        ("singbox-outbounds.json", "sing-box outbounds 片段"),
        ("report.md", "测速报告"),
    ):
        print(f"  {fn:32s} {desc}")
    print(
        f"\n完成({now})：入选 {len(ordered)} 个节点，按速度降序。"
        f"{'已同步 Clash Verge。' if synced else '未同步（可手动导入 best_nodes.yml）。'}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
