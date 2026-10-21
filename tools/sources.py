#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
源清单与抓取层：sources.list 解析、镜像兜底、源体检。

这一层是受 NoMoreWalls 的 `sources.list` 启发拆出来的：源清单写在文本文件里，
带少量标记语法，加源/停源不用改 Python 代码。

标记语法（`sources.list` 里）：

    # 注释                    行首 # 就是注释
    !https://...              需要本地代理才下载得动；没有代理就跳过
    *https://...              这是「订阅列表页」，页面里的链接会被逐个展开
    https://... #max=200&ignore=vmess,ss
                              max    只取前 N 个节点
                              ignore 丢掉这些协议的节点
    EOF                       到此为止，后面的不读

镜像：raw.githubusercontent.com 在国内经常连不通（本机实测 40 秒超时），
而 jsDelivr / gh-proxy 通常 0.5 秒内就回。所以默认**先走镜像、raw 放最后**，
并且会按历史成功率与耗时自动调整顺序（state/mirror_health.json）。
"""

from __future__ import annotations

import json
import os
import re
import time
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

import requests

TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
STATE_DIR = os.path.join(TOOLS_DIR, "state")
DEFAULT_SOURCES_FILE = os.path.join(TOOLS_DIR, "sources.list")
SOURCE_HEALTH_FILE = os.path.join(STATE_DIR, "source_health.json")
MIRROR_HEALTH_FILE = os.path.join(STATE_DIR, "mirror_health.json")

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

# 镜像优先级：本机实测 cdn.jsdelivr 最快，raw 最慢/不通。历史数据会覆盖这个默认顺序。
MIRROR_ORDER = (
    "cdn.jsdelivr",   # cdn.jsdelivr.net/gh
    "gh-proxy",       # gh-proxy.com/
    "ghproxy.net",    # ghproxy.net/
    "fastly.jsdelivr",  # fastly.jsdelivr.net/gh
    "ghfast.top",     # ghfast.top/
    "raw",            # 直连 raw.githubusercontent.com
)


@dataclass
class Source:
    """sources.list 里的一行。"""

    url: str
    needs_proxy: bool = False
    is_list: bool = False
    max_nodes: int = 0          # 0 表示不限
    ignore: Tuple[str, ...] = ()
    line: str = ""              # 原始行，报告里回显用


def _parse_tail(tail: str) -> Dict[str, str]:
    """解析行末的 `#max=200&ignore=vmess,ss`（也接受 `#type=list`）。

    只按 `&` 切：ignore 的值本身带逗号，按逗号切会把列表切坏。
    """
    out: Dict[str, str] = {}
    if not tail:
        return out
    for part in tail.split("&"):
        part = part.strip()
        if not part or "=" not in part:
            continue
        k, v = part.split("=", 1)
        out[k.strip().lower()] = v.strip()
    return out


def parse_line(line: str) -> Optional[Source]:
    raw = line.rstrip("\n")
    s = raw.strip()
    if not s or s.startswith("#"):
        return None
    if s == "EOF":
        return None

    needs_proxy = False
    is_list = False
    # 标记只认行首，且最多各出现一次（'!*' 这种组合也接受）
    while s and s[0] in "!*":
        if s[0] == "!":
            needs_proxy = True
        else:
            is_list = True
        s = s[1:]

    tail = ""
    hash_at = s.find("#")
    if hash_at >= 0:
        tail = s[hash_at + 1:]
        s = s[:hash_at]
    url = s.strip()
    if not url.startswith(("http://", "https://", "file://")):
        return None

    params = _parse_tail(tail)
    if params.get("type", "").lower() == "list":
        is_list = True
    ignore = tuple(
        t.strip().lower()
        for t in params.get("ignore", "").split(",")
        if t.strip()
    )
    try:
        max_nodes = int(params.get("max", "0") or 0)
    except ValueError:
        max_nodes = 0
    return Source(url, needs_proxy, is_list, max_nodes, ignore, line=raw)


def load_sources(path: str = DEFAULT_SOURCES_FILE) -> List[Source]:
    """读 sources.list；文件不存在时返回空表（调用方自己退回内置清单）。"""
    if not os.path.exists(path):
        return []
    out: List[Source] = []
    seen = set()
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            if line.strip() == "EOF":
                break
            src = parse_line(line)
            if src is None or src.url in seen:
                continue
            seen.add(src.url)
            out.append(src)
    return out


# ------------------------------------------------------------------ 镜像


def mirror_candidates(url: str) -> List[Tuple[str, str]]:
    """把一个 raw.githubusercontent 链接展开成 [(镜像名, 实际地址)]，只对 raw 展开。"""
    m = re.match(
        r"https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$", url
    )
    if not m:
        return [("direct", url)]
    user, repo, branch, path = m.groups()
    return [
        ("cdn.jsdelivr", f"https://cdn.jsdelivr.net/gh/{user}/{repo}@{branch}/{path}"),
        ("gh-proxy", f"https://gh-proxy.com/{url}"),
        ("ghproxy.net", f"https://ghproxy.net/{url}"),
        ("fastly.jsdelivr", f"https://fastly.jsdelivr.net/gh/{user}/{repo}@{branch}/{path}"),
        ("ghfast.top", f"https://ghfast.top/{url}"),
        ("raw", url),
    ]


def _load_json(path: str, default):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def _save_json(path: str, data) -> None:
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        os.replace(tmp, path)
    except OSError:
        pass


def load_mirror_health() -> Dict[str, dict]:
    return _load_json(MIRROR_HEALTH_FILE, {})


def save_mirror_health(h: Dict[str, dict]) -> None:
    _save_json(MIRROR_HEALTH_FILE, h)


def _mirror_sort_key(name: str, health: Dict[str, dict]):
    """成功率高的排前面，成功率相同则耗时短的排前面；没数据的按默认顺序。"""
    h = health.get(name) or {}
    ok = h.get("ok", 0)
    fail = h.get("fail", 0)
    total = ok + fail
    rate = (ok / total) if total else 0.5
    ms = h.get("ms", 2000) or 2000
    default_rank = MIRROR_ORDER.index(name) if name in MIRROR_ORDER else 99
    return (-round(rate, 2), ms, default_rank)


def ordered_candidates(url: str, health: Optional[Dict[str, dict]] = None):
    health = load_mirror_health() if health is None else health
    cands = mirror_candidates(url)
    return sorted(cands, key=lambda kv: _mirror_sort_key(kv[0], health))


def _note_mirror(health: Dict[str, dict], name: str, ok: bool, ms: int) -> None:
    h = health.setdefault(name, {"ok": 0, "fail": 0, "ms": 2000})
    if ok:
        h["ok"] = h.get("ok", 0) + 1
        # 指数滑动平均，别让一次抖动把历史数据冲掉
        h["ms"] = int(0.7 * h.get("ms", ms) + 0.3 * ms)
    else:
        h["fail"] = h.get("fail", 0) + 1


def new_session() -> requests.Session:
    s = requests.Session()
    s.trust_env = False  # 不吃系统代理/环境变量代理，避免被坏代理卡死
    s.headers.update({"User-Agent": UA})
    return s


def read_local_proxy() -> Optional[str]:
    """本地代理地址；tools/local_proxy.conf 优先（老位置仍兼容）。"""
    for path in (
        os.path.join(TOOLS_DIR, "local_proxy.conf"),
        r"D:\NoMoreWalls\local_proxy.conf",
        r"D:\Walls\NoMoreWalls\local_proxy.conf",
    ):
        if not os.path.exists(path):
            continue
        try:
            with open(path, "r", encoding="utf-8") as f:
                v = f.read().strip()
        except OSError:
            continue
        if not v or v.upper() == "NONE":
            return None
        m = re.search(r"(\d+)", v)
        if m and not _port_open(int(m.group(1))):
            return None
        return v
    return None


def _port_open(port: int) -> bool:
    import socket

    try:
        s = socket.create_connection(("127.0.0.1", port), timeout=1.0)
        s.close()
        return True
    except OSError:
        return False


def fetch_text(
    url: str,
    timeout: float = 12.0,
    proxy: Optional[str] = None,
    session: Optional[requests.Session] = None,
    health: Optional[Dict[str, dict]] = None,
    mirror_timeout: Optional[float] = None,
) -> Tuple[Optional[str], Optional[str]]:
    """
    取一个订阅链接的文本，镜像兜底。返回 (文本, 成功的镜像名)。

    单次超时只作用于「一个候选」：镜像给更短的超时（默认 min(timeout, 8)），
    原件（直连）才用完整 timeout。这样一条死源最坏也拖不死整轮。
    """
    sess = session or new_session()
    health = load_mirror_health() if health is None else health
    mt = mirror_timeout if mirror_timeout is not None else min(timeout, 8.0)
    for name, cand in ordered_candidates(url, health):
        t = timeout if name in ("raw", "direct") else mt
        t0 = time.time()
        try:
            r = sess.get(cand, timeout=t)
        except requests.RequestException:
            _note_mirror(health, name, False, int((time.time() - t0) * 1000))
            continue
        if r.status_code == 200 and r.text:
            _note_mirror(health, name, True, int((time.time() - t0) * 1000))
            return r.text, name
        _note_mirror(health, name, False, int((time.time() - t0) * 1000))

    if proxy:
        t0 = time.time()
        try:
            r = sess.get(url, timeout=timeout, proxies={"http": proxy, "https": proxy})
            if r.status_code == 200 and r.text:
                _note_mirror(health, "local_proxy", True, int((time.time() - t0) * 1000))
                return r.text, "local_proxy"
        except requests.RequestException:
            _note_mirror(health, "local_proxy", False, int((time.time() - t0) * 1000))
    return None, None


# ------------------------------------------------------------------ 源健康


def load_source_health(path: str = SOURCE_HEALTH_FILE) -> Dict[str, dict]:
    return _load_json(path, {})


def save_source_health(h: Dict[str, dict], path: str = SOURCE_HEALTH_FILE) -> None:
    _save_json(path, h)


def note_source(
    health: Dict[str, dict],
    url: str,
    ok: bool,
    count: int = 0,
    mirror: str = "",
    error: str = "",
) -> None:
    """记一个源这次的结果。老的 ok/fail 字段保留，新增 last_* 便于体检报告。"""
    h = health.setdefault(url, {"ok": 0, "fail": 0})
    h.setdefault("ok", 0)
    h.setdefault("fail", 0)
    now = int(time.time())
    if ok:
        h["ok"] += 1
        h["fail"] = 0                 # 成功一次就洗掉连败
        h["last_ok_at"] = now
        h["last_count"] = count
        if mirror:
            h["last_mirror"] = mirror
        h.pop("last_error", None)
    else:
        h["fail"] += 1
        h["last_fail_at"] = now
        if error:
            h["last_error"] = error


def fmt_age(ts: Optional[int]) -> str:
    if not ts:
        return "-"
    d = max(0, int(time.time()) - int(ts))
    if d < 3600:
        return f"{d // 60} 分钟前"
    if d < 86400:
        return f"{d // 3600} 小时前"
    return f"{d // 86400} 天前"
