package com.szech.walls.pipeline

import com.szech.walls.model.Node
import com.szech.walls.net.Tunnel
import com.szech.walls.net.TunnelFactory
import com.szech.walls.net.TunnelHttp
import com.szech.walls.net.skipCertVerify
import com.szech.walls.net.usesTls

/** 一个被测目标 */
data class Target(val host: String, val port: Int, val path: String, val tls: Boolean)

/**
 * 真实隧道测速：借节点本身去访问一个已知 URL，量出握手延迟和下载速度。
 * 这是整个工具的地基 —— 排名、过滤、速度前缀都依赖它。
 */
object Prober {

    const val HANDSHAKE_MS = 6000
    const val BODY_MS = 9000

    /** 延迟探测最多跑几轮（每轮换一遍目标） */
    const val LATENCY_ROUNDS = 2

    /**
     * 延迟探测目标：先打纯 HTTP 的 Cloudflare 204（不跑 TLS 握手，一个 RTT 就回），
     * 不行再试 gstatic / 明文 Google 连通性检查。
     *
     * 顺序是量出来的：PC 端同批节点用 google.com 只有一个位数存活，
     * 换成 Cloudflare 明文 204 后明显改善；这里保持同一口径，
     * 也让手机端和电脑端测出来的数字能对比。
     */
    val LATENCY_TARGETS = listOf(
        Target("cp.cloudflare.com", 80, "/generate_204", false),
        Target("www.gstatic.com", 443, "/generate_204", true),
        Target("connectivitycheck.gstatic.com", 80, "/generate_204", false)
    )

    val SPEED_TARGETS = listOf(
        Target("speed.cloudflare.com", 443, "/__down?bytes=%%N%%", true),
        Target("speed.cloudflare.com", 80, "/__down?bytes=%%N%%", false),
        Target("cachefly.cachefly.net", 80, "/1mb.test", false)
    )

    data class Result(val latencyMs: Int, val speedMbps: Double)

    /**
     * 先按节点自己的证书设置测一次；如果是自签证书的免费节点（TLS 握手失败），
     * 再忽略证书校验测一次 —— 只为测速，不用于真正代理流量。
     */
    fun measureWithFallback(
        n: Node,
        speedBytes: Int,
        isCancelled: () -> Boolean = { false }
    ): Result? {
        measure(n, speedBytes, insecure = n.skipCertVerify, isCancelled = isCancelled)?.let { return it }
        if (n.usesTls && !n.skipCertVerify) {
            return measure(n, speedBytes, insecure = true, isCancelled = isCancelled)
        }
        return null
    }

    fun measure(
        n: Node,
        speedBytes: Int,
        insecure: Boolean,
        isCancelled: () -> Boolean = { false }
    ): Result? {
        // 1) 真实握手延迟。免费节点的后端是负载均衡的，偶尔会碰到抽风的实例，
        //    所以每个目标允许重试，避免把「偶尔抖一下」的节点误杀。
        var latency = -1
        outer@ for (round in 0 until LATENCY_ROUNDS) {
            for (t in LATENCY_TARGETS) {
                if (isCancelled()) return null
                val tunnel = TunnelFactory.create(n, insecure) ?: return null
                try {
                    tunnel.connect()
                    tunnel.open(t.host, t.port)
                    val r = TunnelHttp.get(tunnel, t.tls, t.host, t.port, t.path, 0, HANDSHAKE_MS, HANDSHAKE_MS)
                    if (r.ok) {
                        latency = r.ttfbMs.toInt().coerceAtLeast(1)
                        break@outer
                    }
                } catch (e: Exception) {
                    // 换下一个目标
                } finally {
                    tunnel.close()
                }
            }
        }
        if (latency < 0) return null

        // 2) 真实下载速度
        var speed = -1.0
        if (speedBytes > 0) {
            for (t in SPEED_TARGETS) {
                if (isCancelled()) break
                val path = t.path.replace("%%N%%", speedBytes.toString())
                val tunnel = TunnelFactory.create(n, insecure) ?: break
                try {
                    tunnel.connect()
                    tunnel.open(t.host, t.port)
                    val r = TunnelHttp.get(tunnel, t.tls, t.host, t.port, path, speedBytes, HANDSHAKE_MS, BODY_MS)
                    if (r.ok && r.bytes > 16 * 1024 && r.elapsedMs > 0) {
                        // 只算「收到首字节之后」的那段：TLS 握手 + 请求往返动辄 200~500ms，
                        // 算进下载时间里会把速度系统性压低（512KB 只下 1 秒时能低 20%~50%）。
                        val bodyMs = (r.elapsedMs - r.ttfbMs).coerceAtLeast(1)
                        speed = r.bytes.toDouble() / (bodyMs.toDouble() / 1000.0) / 1e6
                        break
                    }
                } catch (e: Exception) {
                    // 换下一个目标
                } finally {
                    tunnel.close()
                }
            }
        }
        return Result(latency, speed)
    }
}
