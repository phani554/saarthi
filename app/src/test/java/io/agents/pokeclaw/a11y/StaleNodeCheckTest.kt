// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.a11y

import io.agents.pokeclaw.a11y.StaleNodeCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class StaleNodeCheckTest {
    private fun j(refreshed: Boolean = true, visible: Boolean = true, text: String? = "Add", nowText: String? = "Add",
                  cx: Int = 100, cy: Int = 200, nx: Int = 100, ny: Int = 200) =
        StaleNodeCheck.judge(refreshed, visible, text, "", cx, cy, nowText, "", nx, ny)

    @Test fun fresh() = assertEquals(Verdict.FRESH, j())
    @Test fun whitespaceOnlyChangeIsFresh() = assertEquals(Verdict.FRESH, j(nowText = " Add "))
    @Test fun gone() {
        assertEquals(Verdict.GONE, j(refreshed = false))
        assertEquals(Verdict.GONE, j(visible = false))
    }
    @Test fun moved() = assertEquals(Verdict.MOVED, j(ny = 400))
    @Test fun smallJitterIsFresh() = assertEquals(Verdict.FRESH, j(nx = 110, ny = 190))
    @Test fun relabelled() = assertEquals(Verdict.TEXT_CHANGED, j(nowText = "Pay ₹200"))
}
