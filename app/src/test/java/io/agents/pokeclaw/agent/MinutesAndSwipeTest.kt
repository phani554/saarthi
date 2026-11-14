package io.agents.pokeclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class MinutesAndSwipeTest {
    @Test fun swipesCompareByDirection() {
        assertEquals("swipe:left", StuckDetector.normaliseAction("""swipe:{"duration_ms": 500, "start_x": 1100, "start_y": 200, "end_x": 200, "end_y": 210}"""))
        assertEquals("swipe:left", StuckDetector.normaliseAction("""swipe:{"duration_ms": 600, "start_x": 1050, "start_y": 204, "end_x": 150, "end_y": 200}"""))
        assertEquals("tap_node:{\"id\":\"3\"}", StuckDetector.normaliseAction("tap_node:{\"id\":\"3\"}"))
    }

    @Test fun fiveSwipesEscalate() {
        val d = StuckDetector()
        var last: StuckDetector.Detection? = null
        for (i in 0 until 7) {
            last = d.record("com.flipkart.android", """swipe:{"duration_ms": ${500 + i * 10}, "start_x": ${1100 - i}, "start_y": 200, "end_x": 200, "end_y": 210}""", i, 3, null)
        }
        assertNotNull(last)
        assertTrue(last!!.level != StuckDetector.RecoveryLevel.HINT)
    }

    @Test fun minutesTileIsAPosition() {
        assertTrue(FlipkartMinutesSwitch.wanted("Add KitKat to my Flipkart minutes cart"))
        assertFalse(FlipkartMinutesSwitch.wanted("Add KitKat to my Flipkart cart"))
        val (x, y) = FlipkartMinutesSwitch.point(1260, 2800)
        assertTrue(x in 348..612 && y in 126..282)
    }
}
