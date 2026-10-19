#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""不依赖网络的回归测试：把「静默发死节点 / 取样偏头部 / 路径写死」钉住。

    python -m unittest discover -s tools/tests -v
"""

import os
import sys
import tempfile
import types
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import daily_auto as DA  # noqa: E402


def args(**kw):
    base = dict(quick=False, top=40, min_speed=0.2)
    base.update(kw)
    return types.SimpleNamespace(**base)


class ChooseFinalTest(unittest.TestCase):
    def test_drops_unmeasured_and_zero_speed(self):
        results = [
            {"name": "dead", "delay": 80, "speed": None},
            {"name": "stalled", "delay": 90, "speed": 0.0},
            {"name": "slow", "delay": 120, "speed": 0.5},
            {"name": "fast", "delay": 60, "speed": 1.2},
        ]
        final, tested, qualified = DA.choose_final(results, args())
        self.assertEqual([r["name"] for r in final], ["fast", "slow"])
        self.assertEqual(qualified, 2)
        self.assertEqual(len(tested), 2)

    def test_returns_none_when_nothing_measured(self):
        results = [
            {"name": "dead", "delay": 80, "speed": None},
            {"name": "stalled", "delay": 90, "speed": 0.0},
        ]
        final, tested, qualified = DA.choose_final(results, args())
        self.assertIsNone(final)
        self.assertEqual(tested, [])
        self.assertEqual(qualified, 0)

    def test_slow_but_measured_is_kept(self):
        results = [{"name": "slow", "delay": 200, "speed": 0.05}]
        final, _, qualified = DA.choose_final(results, args(min_speed=0.2))
        self.assertEqual([r["name"] for r in final], ["slow"])  # 实测能跑，好过发空
        self.assertEqual(qualified, 0)

    def test_quick_mode_sorts_by_delay(self):
        results = [
            {"name": "b", "delay": 200, "speed": None},
            {"name": "a", "delay": 50, "speed": None},
        ]
        final, _, _ = DA.choose_final(results, args(quick=True))
        self.assertEqual([r["name"] for r in final], ["a", "b"])

    def test_respects_top(self):
        results = [{"name": f"n{i}", "delay": 10 + i, "speed": 1.0} for i in range(5)]
        final, tested, qualified = DA.choose_final(results, args(top=2))
        self.assertEqual([r["name"] for r in final], ["n0", "n1"])
        self.assertEqual(len(tested), 5)
        self.assertEqual(qualified, 5)


class SpeedLabelTest(unittest.TestCase):
    def test_slow_speed_keeps_two_decimals(self):
        self.assertEqual(DA.speed_label(0.04), "0.04MB/s")
        self.assertEqual(DA.speed_label(0.071), "0.07MB/s")

    def test_normal_speed_uses_one_decimal(self):
        self.assertEqual(DA.speed_label(0.1), "0.1MB/s")
        self.assertEqual(DA.speed_label(11.28), "11.3MB/s")


class MirrorTest(unittest.TestCase):
    def test_raw_github_url_gets_mirrors(self):
        url = "https://raw.githubusercontent.com/a/b/main/c/yaml"
        out = DA.mirrors(url)
        self.assertEqual(out[0], url)
        self.assertTrue(any("jsdelivr.net" in u for u in out))
        self.assertTrue(any("gh-proxy.com" in u for u in out))

    def test_mirror_first_moves_direct_to_end(self):
        url = "https://raw.githubusercontent.com/a/b/main/c/yaml"
        with mock.patch.dict(os.environ, {"FND_MIRROR_FIRST": "1"}):
            out = DA.mirrors(url)
        self.assertEqual(out[-1], url)
        self.assertNotEqual(out[0], url)


class MihomoLookupTest(unittest.TestCase):
    def test_env_override_wins(self):
        with tempfile.NamedTemporaryFile(delete=False) as f:
            path = f.name
        try:
            with mock.patch.dict(os.environ, {"MIHOMO_BIN": path}):
                self.assertEqual(DA.find_mihomo(), path)
        finally:
            os.unlink(path)


if __name__ == "__main__":
    unittest.main()
