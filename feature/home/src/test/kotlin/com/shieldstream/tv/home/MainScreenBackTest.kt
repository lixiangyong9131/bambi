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
    fun `content focus opens rail in place while non-home rail returns to home before exit`() {
        assertEquals(
            MainBackOutcome.OPEN_RAIL_IN_PLACE,
            resolveMainBackOutcome(categoryOpen = false, topBarFocused = false, onHomeSection = false, exitArmed = false),
        )
        assertEquals(
            MainBackOutcome.BACK_TO_HOME,
            resolveMainBackOutcome(categoryOpen = false, topBarFocused = true, onHomeSection = false, exitArmed = false),
        )
        assertEquals(
            MainBackOutcome.CLOSE_SEARCH,
            resolveMainBackOutcome(categoryOpen = false, searchOpen = true, topBarFocused = false, onHomeSection = false, exitArmed = false),
        )
        assertEquals(
            MainBackOutcome.CLOSE_CATEGORY,
            resolveMainBackOutcome(categoryOpen = true, topBarFocused = true, exitArmed = true),
        )
    }
}
