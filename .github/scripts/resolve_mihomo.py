"""解析 mihomo 最新 Linux amd64 压缩包的下载地址（给 GitHub Actions 用）。

单独放在文件里而不是塞进 workflow 的内联脚本：YAML 的 block scalar 对内联
Python 的缩进非常敏感，抽出来之后本地也能直接跑：

    python .github/scripts/resolve_mihomo.py
"""

import json
import sys
import urllib.request

API = "https://api.github.com/repos/MetaCubeX/mihomo/releases/latest"


def main() -> int:
    req = urllib.request.Request(API, headers={"Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.load(resp)
    urls = [
        a["browser_download_url"]
        for a in data.get("assets", [])
        if a["name"].startswith("mihomo-linux-amd64-compatible")
        and a["name"].endswith(".gz")
    ]
    if not urls:
        print("没有找到 mihomo-linux-amd64-compatible 资产", file=sys.stderr)
        return 1
    print(urls[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
