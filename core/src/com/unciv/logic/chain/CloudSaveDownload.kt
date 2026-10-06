package com.unciv.logic.chain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** Bounded part downloads, preserving order, with a delayed fallback instead of serial gateway waits. */
object CloudSaveDownload {
    suspend fun fetch(ids: List<String>, hedgeDelayMillis: Long = 350,
                      fetchPart: suspend (String, Int) -> ByteArray?): ByteArray = coroutineScope {
        val limit = Semaphore(3)
        ids.map { id -> async {
            limit.withPermit {
                coroutineScope {
                    val result = CompletableDeferred<ByteArray>()
                    val failures = AtomicInteger()
                    val jobs = (0..1).map { gateway -> launch {
                        if (gateway == 1) delay(hedgeDelayMillis)
                        val bytes = try { fetchPart(id, gateway) }
                        catch (ex: CancellationException) { throw ex }
                        catch (_: Exception) { null }
                        if (bytes != null) result.complete(bytes)
                        else if (failures.incrementAndGet() == 2)
                            result.completeExceptionally(IllegalStateException("Could not download part $id of the save. " +
                                "A save recorded in the last few minutes may not have reached the network yet - try again shortly."))
                    } }
                    try { result.await() } finally { jobs.forEach { it.cancel() } }
                }
            }
        } }.awaitAll().let { parts ->
            java.io.ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        }
    }
}
