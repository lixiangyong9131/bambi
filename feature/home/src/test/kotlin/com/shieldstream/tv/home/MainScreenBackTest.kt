package com.shieldstream.tv.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenBackTest {

    @Test
    fun `exit confirmation requires the root top bar to be focused`() {
        assertTrue(shouldRequestExit(categoryOpen = false, topBarFocused = true))
        assertFalse(shouldRequestExit(categoryOpen = false, topBarFocused = false))
        assertFalse(shouldRequestExit(categoryOpen = true, topBarFocused = true))
        assertFalse(shouldRequestExit(categoryOpen = true, topBarFocused = false))
    }

    @Test
    fun `top bar focused arms exit on first back and exits on second back`() {
        assertEquals(
            MainBackOutcome.ARM_EXIT_PROMPT,
            resolveMainBackOutcome(categoryOpen = false, topBarFocused = true, exitArmed = false),
        )
        assertEquals(
            MainBackOutcome.EXIT_APP,
            resolveMainBackOutcome(categoryOpen = false, topBarFocused = true, exitArmed = true),
        )
    }

    @Test
    fun `content focus returns to top bar instead of exiting`() {
        assertEquals(
            MainBackOutcome.BACK_TO_TOP,
            resolveMainBackOutcome(categoryOpen = false, topBarFocused = false, exitArmed = false),
        )
        assertEquals(
            MainBackOutcome.CLOSE_CATEGORY,
            resolveMainBackOutcome(categoryOpen = true, topBarFocused = true, exitArmed = true),
        )
    }
}
