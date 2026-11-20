package com.dshgo.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 上报地址的挑选规则。
 *
 * 背景：老版本 PC 插件的 `addrs.lan` 里混着 Clash TUN 的 198.18.0.1，
 * 而 [ConnectionStore.applyReported] 曾经直接取第一条 —— 手机上的局域网
 * 连接就被改写成这个「只在 PC 内部有效」的地址，出门必失联。
 */
class LanUrlsTest {

    @Test
    fun `虚拟网卡地址不参与挑选`() {
        val urls = listOf("http://198.18.0.1:3081", "http://192.168.31.221:3081")
        assertEquals("http://192.168.31.221:3081", LanUrls.pick(urls))
    }

    @Test
    fun `全是虚拟或链路本地地址时返回 null 由调用方保留旧地址`() {
        assertNull(LanUrls.pick(listOf("http://198.18.0.1:3081", "http://169.254.3.7:3081")))
        assertNull(LanUrls.pick(listOf("http://198.19.5.5:3081")))
    }

    @Test
    fun `优先级 192168 高于 10 和 172`() {
        assertEquals(
            "http://192.168.0.9:3081",
            LanUrls.pick(listOf("http://172.20.0.3:3081", "http://10.1.2.3:3081", "http://192.168.0.9:3081")),
        )
        assertEquals("http://10.1.2.3:3081", LanUrls.pick(listOf("http://172.20.0.3:3081", "http://10.1.2.3:3081")))
    }

    @Test
    fun `Tailscale 地址可用但排在真实局域网之后`() {
        val urls = listOf("http://100.75.57.100:3081", "http://192.168.31.221:3081")
        assertEquals("http://192.168.31.221:3081", LanUrls.pick(urls))
        assertEquals("http://100.75.57.100:3081", LanUrls.pick(listOf("http://100.75.57.100:3081")))
    }

    @Test
    fun `末尾斜杠被规整`() {
        assertEquals("http://192.168.31.221:3081", LanUrls.pick(listOf("http://192.168.31.221:3081/")))
        assertEquals("http://192.168.31.221:3081", LanUrls.pick(listOf("http://192.168.31.221:3081", "http://192.168.31.221:3081/")))
    }

    @Test
    fun `非 http 条目被跳过`() {
        assertNull(LanUrls.pick(listOf("ftp://192.168.31.221:3081", "")))
        assertEquals("http://192.168.31.221:3081", LanUrls.pick(listOf("ftp://x", "http://192.168.31.221:3081")))
    }
}
