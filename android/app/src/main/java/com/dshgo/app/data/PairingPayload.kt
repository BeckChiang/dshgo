package com.dshgo.app.data

/**
 * 「一次添加全部地址」的扫码载荷。
 *
 * 电脑端面板会把同一台电脑的两条通道（局域网 + Tailscale）合成一张二维码，
 * 内容是一段固定形状的 JSON：
 *
 * ```json
 * {"dshgo":2,"deviceId":"c524f6ab3dc8","name":"Beck-LS","urls":["http://192.168.31.221:3081","http://100.75.57.100:3081"]}
 * ```
 *
 * 手写一个小解析器而不是用 org.json：形状固定、字段就四个，
 * 而 org.json 在 JVM 单测里是「未实现桩」，一调就抛异常 —— 那样这段逻辑没法测。
 */
data class PairingPayload(
    val name: String,
    val deviceId: String,
    val urls: List<String>,
) {
    companion object {
        /** 载荷格式版本。以后改结构就往上加，老 App 见到不认识的值会当成普通地址。 */
        private const val VERSION = 2

        /**
         * 解析扫码内容。不是本格式就返回 null，调用方退回「当成单个地址」的老路。
         */
        fun parse(raw: String): PairingPayload? {
            val text = raw.trim()
            if (!text.startsWith("{")) return null
            if (intField(text, "dshgo") != VERSION) return null
            val urls = stringArray(text, "urls")?.filter { it.isNotBlank() } ?: return null
            if (urls.isEmpty()) return null
            return PairingPayload(
                name = stringField(text, "name").orEmpty(),
                deviceId = stringField(text, "deviceId").orEmpty(),
                urls = urls,
            )
        }

        private fun intField(text: String, key: String): Int? {
            val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\\d+)").find(text) ?: return null
            return m.groupValues[1].toIntOrNull()
        }

        private fun stringField(text: String, key: String): String? {
            val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)
                ?: return null
            return unescape(m.groupValues[1])
        }

        private fun stringArray(text: String, key: String): List<String>? {
            val m = Regex(
                "\"" + Regex.escape(key) + "\"\\s*:\\s*\\[(.*?)]",
                RegexOption.DOT_MATCHES_ALL,
            ).find(text) ?: return null
            return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
                .findAll(m.groupValues[1])
                .map { unescape(it.groupValues[1]) }
                .toList()
        }

        /** 只处理 JSON 字符串里会出现的那几个转义 —— 地址里不会有别的。 */
        private fun unescape(s: String): String {
            if (!s.contains('\\')) return s
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c != '\\' || i + 1 >= s.length) {
                    out.append(c)
                    i++
                    continue
                }
                when (val n = s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        val code = hex.toIntOrNull(16)
                        if (code != null && hex.length == 4) out.append(code.toChar()) else out.append("\\u$hex")
                        i += 4
                    }
                    else -> out.append(n)
                }
                i += 2
            }
            return out.toString()
        }
    }
}
