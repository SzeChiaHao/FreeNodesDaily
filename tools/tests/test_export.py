#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""导出格式的离线回归测试：hysteria 版本区分、sip008 id、去重键。

    python -m unittest discover -s tools/tests -v
"""

import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import export_formats as EF  # noqa: E402


class HysteriaUriTest(unittest.TestCase):
    def test_v1_uri_has_no_userinfo(self):
        p = {"type": "hysteria", "server": "1.2.3.4", "port": 443,
             "auth-str": "secret", "sni": "example.com"}
        uri = EF.v2ray_uri(p)
        self.assertTrue(uri.startswith("hysteria://1.2.3.4:443?"), uri)
        self.assertIn("auth=secret", uri)
        self.assertNotIn("@", uri)

    def test_v2_uri_keeps_password(self):
        p = {"type": "hysteria2", "server": "1.2.3.4", "port": 443, "password": "pw"}
        uri = EF.v2ray_uri(p)
        self.assertTrue(uri.startswith("hysteria2://pw@1.2.3.4:443"), uri)


class SingboxTest(unittest.TestCase):
    def test_keeps_hysteria_v1_type(self):
        v1 = {"type": "hysteria", "server": "h1.com", "port": 443, "auth-str": "x"}
        v2 = {"type": "hysteria2", "server": "h2.com", "port": 443, "password": "y"}
        out = {o["type"]: o for o in json.loads(EF.singbox_outbounds([v1, v2]))
               if o.get("type") in ("hysteria", "hysteria2")}
        self.assertEqual(out["hysteria"]["auth_str"], "x")
        self.assertEqual(out["hysteria2"]["password"], "y")


class Sip008Test(unittest.TestCase):
    def test_ids_unique_for_same_endpoint(self):
        proxies = [
            {"type": "ss", "server": "1.1.1.1", "port": 443,
             "cipher": "aes-128-gcm", "password": "p1"},
            {"type": "ss", "server": "1.1.1.1", "port": 443,
             "cipher": "aes-128-gcm", "password": "p2"},
        ]
        data = json.loads(EF.sip008(proxies))
        ids = [s["id"] for s in data["servers"]]
        self.assertEqual(len(ids), 2)
        self.assertNotEqual(ids[0], ids[1])


class DedupeTest(unittest.TestCase):
    def test_keeps_different_passwords(self):
        proxies = [
            {"type": "ss", "server": "1.1.1.1", "port": 443,
             "cipher": "aes-128-gcm", "password": "p1"},
            {"type": "ss", "server": "1.1.1.1", "port": 443,
             "cipher": "aes-128-gcm", "password": "p2"},
        ]
        self.assertEqual(len(EF._by_type(proxies, ("ss",), 10)), 2)


if __name__ == "__main__":
    unittest.main()
