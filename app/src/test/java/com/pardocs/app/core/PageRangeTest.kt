package com.pardocs.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangeTest {

    private fun ok(expression: String, pageCount: Int): List<Int> {
        val result = PageRange.parse(expression, pageCount)
        assertTrue("Se esperaba Ok, fue Error: ${(result as? PageRange.Result.Error)?.message}", result is PageRange.Result.Ok)
        return (result as PageRange.Result.Ok).pages
    }

    @Test fun emptyExpressionSelectsAllPages() {
        assertEquals(listOf(0, 1, 2, 3, 4), ok("", 5))
        assertEquals(listOf(0, 1, 2, 3, 4), ok("   ", 5))
    }

    @Test fun singlePagesAndDeduplication() {
        assertEquals(listOf(0), ok("1", 5))
        // Duplicates are dropped, keeping first-occurrence order.
        assertEquals(listOf(3, 0, 1), ok("4,1,4,2", 5))
    }

    @Test fun rangesInclusiveOneBased() {
        assertEquals(listOf(0, 1, 2), ok("1-3", 5))
        assertEquals(listOf(2, 3, 4), ok("3-5", 5))
    }

    @Test fun openEndedRangeGoesToLastPage() {
        assertEquals(listOf(1, 2, 3, 4), ok("2-", 5))
    }

    @Test fun commaSemicolonMixedSeparators() {
        assertEquals(listOf(0, 2, 4), ok("1;3,5", 5))
    }

    @Test fun reversedRangeIsRejected() {
        val result = PageRange.parse("4-2", 5)
        assertTrue(result is PageRange.Result.Error)
    }

    @Test fun outOfBoundsPageIsRejected() {
        assertTrue(PageRange.parse("0", 5) is PageRange.Result.Error)
        assertTrue(PageRange.parse("6", 5) is PageRange.Result.Error)
        assertTrue(PageRange.parse("5-6", 5) is PageRange.Result.Error)
    }

    @Test fun nonNumericTokensAreRejected() {
        assertTrue(PageRange.parse("x", 5) is PageRange.Result.Error)
        assertTrue(PageRange.parse("1-x", 5) is PageRange.Result.Error)
        assertTrue(PageRange.parse("1-2-3", 5) is PageRange.Result.Error)
    }
}