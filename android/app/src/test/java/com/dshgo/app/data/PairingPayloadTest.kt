package com.dshgo.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 扫码载荷解析。
 *
 * 这段逻辑决定「扫一次能不能把两条通道都加进来」，所以边界要钉住：
 * 单地址的旧码、别的二维码、坏 JSON 都必须老实返回 null，
 * 让调用方退回「当成一个地址」的老路，而不是把一段 JSON 当地址连上去。
 */
class PairingPayloadTest {

    private val pairJson = """{"dshgo":2,"deviceId":"c524f6ab3dc8","name":"Beck-LS",""" +
        """"urls":["http://192.168.31.221:3081","http://100.75.57.100:3081"]}"""

    @Test
    fun `解析出两条地址与设备身份`() {
        val pair = PairingPayload.parse(pairJson)
        assertEquals(listOf("http://192.168.31.221:3081", "http://100.75.57.100:3081"), pair?.urls)
        assertEquals("c524f6ab3dc8", pair?.deviceId)
        assertEquals("Beck-LS", pair?.name)
    }

    @Test
    fun `普通地址与杂码一律不认`() {
        assertNull(PairingPayload.parse("http://192.168.31.221:3081"))
        assertNull(PairingPayload.parse("just some text"))
        assertNull(PairingPayload.parse(""))
        // 版本不认识：留给将来的格式，老 App 不猜
        assertNull(PairingPayload.parse("""{"dshgo":3,"urls":["http://a:1"]}"""))
        // 声明了版本但没地址
        assertNull(PairingPayload.parse("""{"dshgo":2,"urls":[]}"""))
        assertNull(PairingPayload.parse("""{"dshgo":2}"""))
        // 坏 JSON
        assertNull(PairingPayload.parse("""{"dshgo":2,"urls":["http://a:1""""))
    }

    @Test
    fun `数组里的转义与空白被清理`() {
        val raw = """{"dshgo": 2, "name": "", "urls": ["http://a.example:3081", "http://b.example:3081"]}"""
        val pair = PairingPayload.parse(raw)
        assertEquals(2, pair?.urls?.size)
        assertEquals("http://b.example:3081", pair?.urls?.get(1))
    }

    @Test
    fun `没有 deviceId 也能用`() {
        val pair = PairingPayload.parse("""{"dshgo":2,"urls":["http://10.0.0.2:3081"]}""")
        assertEquals(listOf("http://10.0.0.2:3081"), pair?.urls)
        assertEquals("", pair?.deviceId)
        assertEquals("", pair?.name)
    }
}
