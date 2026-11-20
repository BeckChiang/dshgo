package com.dshgo.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小组件不该被插件后台会话铺满：前缀识别要覆盖 dsh-mnemon 的两族会话。
 *
 * 解析路径（`WidgetState.parse` / `keepMissing`）也走同一个 [isInternalSession]，
 * 但 JVM 单测里 `org.json` 是未实现的桩（`Method optJSONArray ... not mocked`），
 * 所以那里只靠代码共用同一判定，不在这里用 JSON 断言。
 */
class InternalSessionTest {

    @Test
    fun `mnemon 的两族会话都算内部会话`() {
        assertTrue(isInternalSession("mnemon-review-v1-0f62f101-4560-4317-a2b5-530bf461fb2b"))
        assertTrue(isInternalSession("mnemon-run-v1-0d961c4f-08c4-4bf7-8177-3214036cc065"))
    }

    @Test
    fun `真实会话与只是含关键字的 id 都放行`() {
        assertFalse(isInternalSession("9f2c1a4e-1111-2222-3333-444455556666"))
        assertFalse(isInternalSession("session-006c2a55-eb92-4c95-9c29-0ca87b698a87"))
        assertFalse("只是含关键字、不以前缀开头就不算", isInternalSession("dsh-mnemon-review"))
        assertFalse(isInternalSession(""))
    }
}
