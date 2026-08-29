package com.phonecontrol.assistant.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityLabelTest {
    @Test
    fun tapLabelKeepsTheUsefulTargetPhrase() {
        assertEquals(
            "Tapping the newly displayed green target",
            userFacingActivityLabel(
                actionType = ActionType.TAP,
                purpose = "Complete benchmark round 4 by tapping the newly displayed green target",
                targetDescription = "newly displayed green target",
            ),
        )
    }

    @Test
    fun typeLabelDoesNotExposeTheTypedValue() {
        assertEquals(
            "Entering text in the search field",
            userFacingActivityLabel(
                actionType = ActionType.TYPE,
                purpose = "Search for jollof rice",
                targetDescription = "search field",
            ),
        )
    }

    @Test
    fun genericPurposeUsesAReadableGerund() {
        assertEquals(
            "Searching for jollof rice",
            userFacingActivityLabel(
                actionType = null,
                purpose = "search for jollof rice",
            ),
        )
    }
}
