package com.dshgo.app.data

import java.net.URI

/**
 * 从 `/__dshgo__/info` 上报的地址里挑「真正能用」的那条。
 *
 * ## 为什么要挑，而不是拿第一条
 *
 * 老版本 PC 插件的 `addrs.lan` 里混着 Clash/mihomo 的 TUN 地址
 * （198.18.0.1，RFC 2544 压测段，只在 PC 内部有效）。当时的
 * [ConnectionStore.applyReported] 直接取 `firstOrNull { it.startsWith("http") }`，
 * 于是把手机上的局域网连接改写成 198.18.0.1 —— 一出家里就永远连不上。
 *
 * 插件的 2.1.3 已经在服务端过滤了；这里再做一遍是「双保险」：
 * 用户可能连着旧版 PC 插件，手机端不能把判断权全交出去。
 *
 * 规则与插件侧 `usableLanAddresses()` 保持一致：
 * - 丢掉 0.x、127/8、224+（组播/保留）、169.254/16（链路本地）、198.18-198.19（压测段）
 * - 优先级 192.168 → 10/8 → 172.16-31 → 其它
 * - 全被过滤掉时返回 null，调用方**保留原地址**，不要清空
 */
object LanUrls {

    /**
     * 这个 host 值不值得写进连接。
     *
     * 不是 IPv4 字面量（用户自建的域名）时放行 —— 拿不准就别乱删，
     * 排序时也会把它放在最后。
     */
    fun isUsable(host: String): Boolean {
        val p = parts(host) ?: return true
        val a = p[0]
        val b = p[1]
        if (a == 0 || a == 127 || a >= 224) return false
        if (a == 169 && b == 254) return false
        if (a == 198 && (b == 18 || b == 19)) return false
        return true
    }

    /** 越小越优先：真实局域网在前，Tailscale/公网在后。 */
    fun rank(host: String): Int {
        val p = parts(host) ?: return 4
        return when {
            p[0] == 192 && p[1] == 168 -> 0
            p[0] == 10 -> 1
            p[0] == 172 && p[1] in 16..31 -> 2
            else -> 3
        }
    }

    /**
     * 从上报列表里选出该用的那条 URL。
     *
     * 只接受 http(s)、必须带 host；规整掉末尾斜杠；同分时保持上报顺序。
     * 挑不出来就返回 null。
     */
    fun pick(urls: List<String>): String? {
        val seen = HashSet<String>()
        return urls.asSequence()
            .mapNotNull { normalize(it) }
            .filter { seen.add(it) }
            .filter { isUsable(hostOf(it)) }
            .sortedBy { rank(hostOf(it)) }
            .firstOrNull()
    }

    private fun normalize(url: String): String? {
        val t = url.trim().trimEnd('/')
        if (t.isEmpty()) return null
        if (!t.startsWith("http://") && !t.startsWith("https://")) return null
        val host = hostOf(t)
        if (host.isBlank()) return null
        return t
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host.orEmpty() }.getOrDefault("")

    private fun parts(host: String): IntArray? {
        val ps = host.trim().split('.')
        if (ps.size != 4) return null
        val out = IntArray(4)
        for (i in 0..3) {
            val n = ps[i].toIntOrNull() ?: return null
            if (n < 0 || n > 255) return null
            out[i] = n
        }
        return out
    }
}
