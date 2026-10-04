package com.unciv.logic

import com.unciv.logic.chain.SharedSaveProgress
import org.junit.Assert.*
import org.junit.Test

class SharedSaveProgressTests {
    private fun branch() = GameInfo().apply {
        continuedFromSave = "parent"
        continuedFromTurn = 66
        turns = 72
    }

    @Test fun showsSnapshotProgressWithOrWithoutListedSource() {
        assertEquals(6, SharedSaveProgress.from(branch(), "parent", 66)?.turns)
        assertEquals(66, SharedSaveProgress.from(branch(), "parent")?.start)
        assertEquals(0, SharedSaveProgress.from(branch().apply { turns = 66 }, "parent")?.turns)
    }

    @Test fun rejectsInconsistentLineageAndTurnOrder() {
        assertNull(SharedSaveProgress.from(branch(), null))
        assertNull(SharedSaveProgress.from(branch(), "other"))
        assertNull(SharedSaveProgress.from(branch(), "parent", 65))
        assertNull(SharedSaveProgress.from(branch().apply { turns = 65 }, "parent"))
        assertNull(SharedSaveProgress.from(branch().apply { continuedFromTurn = -1 }, "parent"))
        assertNull(SharedSaveProgress.from(GameInfo(), "parent"))
    }
}
