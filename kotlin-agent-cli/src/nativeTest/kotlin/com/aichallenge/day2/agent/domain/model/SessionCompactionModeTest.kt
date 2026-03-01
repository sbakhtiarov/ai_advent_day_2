package com.aichallenge.day2.agent.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionCompactionModeTest {
    @Test
    fun fromIdOrNullResolvesBranchingMode() {
        assertEquals(
            SessionCompactionMode.BRANCHING,
            SessionCompactionMode.fromIdOrNull("branching"),
        )
    }

    @Test
    fun fromIdOrNullResolvesFactMapMode() {
        assertEquals(
            SessionCompactionMode.FACT_MAP,
            SessionCompactionMode.fromIdOrNull("fact-map"),
        )
    }

    @Test
    fun fromIdOrNullReturnsNullForUnknownId() {
        assertEquals(
            null,
            SessionCompactionMode.fromIdOrNull("unknown-mode"),
        )
    }
}
