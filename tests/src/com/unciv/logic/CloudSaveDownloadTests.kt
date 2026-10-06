package com.unciv.logic

import com.unciv.logic.chain.CloudSaveDownload
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CloudSaveDownloadTests {
    @Test fun SlowPrimaryIsCancelledWhenFallbackSucceeds() = runBlocking {
        var primaryCancelled = false
        val bytes = CloudSaveDownload.fetch(listOf("a"), hedgeDelayMillis = 5) { _, gateway ->
            if (gateway == 0) try { delay(10_000); byteArrayOf(0) }
            finally { primaryCancelled = true }
            else byteArrayOf(1)
        }
        assertArrayEquals(byteArrayOf(1), bytes)
        assertTrue(primaryCancelled)
    }
    @Test fun FastPrimaryDoesNotStartFallback() = runBlocking {
        val requests = AtomicInteger()
        val bytes = CloudSaveDownload.fetch(listOf("a"), hedgeDelayMillis = 10_000) { _, _ ->
            requests.incrementAndGet(); byteArrayOf(2)
        }
        assertArrayEquals(byteArrayOf(2), bytes)
        assertEquals(1, requests.get())
    }
    @Test fun FailedGatewayDoesNotPreventOtherGatewayAndPartsKeepTheirOrder() = runBlocking {
        val bytes = CloudSaveDownload.fetch(listOf("a", "b", "c", "d"), hedgeDelayMillis = 0) { id, gateway ->
            if (gateway == 0) throw IllegalStateException("offline")
            delay(if (id == "a") 20L else 1L)
            id.toByteArray()
        }
        assertArrayEquals("abcd".toByteArray(), bytes)
    }
    @Test fun BothGatewaysFailWithoutHanging() = runBlocking {
        try {
            CloudSaveDownload.fetch(listOf("a"), hedgeDelayMillis = 0) { _, _ -> null }
            fail("Expected a download failure")
        } catch (ex: IllegalStateException) { assertTrue(ex.message!!.contains("part a")) }
    }
}
