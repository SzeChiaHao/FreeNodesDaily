package com.szech.walls.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 速度前缀不能把慢节点写成 0.0MB/s —— 那样看起来和死节点一样。 */
class SpeedLabelTest {

    @Test
    fun slowNodeKeepsTwoDecimals() {
        assertEquals("0.04MB/s", speedLabel(0.04))
        assertEquals("0.07MB/s", speedLabel(0.071))
    }

    @Test
    fun normalSpeedUsesOneDecimal() {
        assertEquals("0.1MB/s", speedLabel(0.1))
        assertEquals("1.2MB/s", speedLabel(1.23))
        assertEquals("11.3MB/s", speedLabel(11.28))
    }
}
