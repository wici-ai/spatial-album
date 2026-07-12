package com.wici.androidalbumdemo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplatStreamAccountingTest {
    @Test fun conservesEligibleAndAlphaFilteredRows() {
        assertTrue(SplatStreamAccounting(4, 2, 2, 0).countConserved)
    }

    @Test fun rejectsMissingWireRows() {
        assertFalse(SplatStreamAccounting(4, 2, 1, 0).countConserved)
    }

    @Test fun combinesBatchesAcrossReadBoundaries() {
        val total = SplatStreamAccounting(2, 1, 1) + SplatStreamAccounting(2, 1, 0, 1)
        assertTrue(total.countConserved)
    }

    @Test fun trailingBytesAreExplicit() {
        assertFalse(SplatStreamAccounting(4, 4, partialTrailingBytes = 1).partialTrailingBytes == 0)
    }
}
