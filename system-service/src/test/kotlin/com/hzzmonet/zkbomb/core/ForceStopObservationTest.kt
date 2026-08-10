package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BombResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ForceStopObservationTest {

    @Test
    fun `stopped flag and STOPPED observation succeed`() {
        var observations = 0
        val result = verifier().verify(
            stoppedFlag = { true },
            processObservation = {
                observations += 1
                ProcessObservation.STOPPED
            },
        )

        assertEquals(BombResult.Status.SUCCESS, result.status)
        assertEquals(1, observations)
    }

    @Test
    fun `stopped flag and RUNNING observation retry then fail`() {
        var observations = 0
        val sleeps = mutableListOf<Long>()
        val result = verifier(sleep = { sleeps += it }).verify(
            stoppedFlag = { true },
            processObservation = {
                observations += 1
                ProcessObservation.RUNNING
            },
        )

        assertEquals(BombResult.Status.FAILED, result.status)
        assertEquals(ATTEMPTS, observations)
        assertEquals(List(ATTEMPTS - 1) { DELAY_MILLIS }, sleeps)
    }

    @Test
    fun `stopped flag and UNKNOWN observation retry then report unavailable`() {
        var observations = 0
        val result = verifier().verify(
            stoppedFlag = { true },
            processObservation = {
                observations += 1
                ProcessObservation.UNKNOWN
            },
        )

        assertEquals(BombResult.Status.BACKEND_UNAVAILABLE, result.status)
        assertEquals(ATTEMPTS, observations)
    }

    @Test
    fun `STOPPED observation cannot succeed without stopped flag`() {
        val result = verifier().verify(
            stoppedFlag = { false },
            processObservation = { ProcessObservation.STOPPED },
        )

        assertEquals(BombResult.Status.FAILED, result.status)
    }

    @Test
    fun `exception and null process queries are UNKNOWN`() {
        val throwing = PackageProcessObserver { error("query failed") }
        val unavailable = PackageProcessObserver { null }
        val unidentified = PackageProcessObserver { listOf(null) }

        assertEquals(ProcessObservation.UNKNOWN, throwing.observe(PACKAGE_NAME))
        assertEquals(ProcessObservation.UNKNOWN, unavailable.observe(PACKAGE_NAME))
        assertEquals(ProcessObservation.UNKNOWN, unidentified.observe(PACKAGE_NAME))
    }

    @Test
    fun `matching process is RUNNING and disappeared process is STOPPED`() {
        var processes: List<List<String>?>? = listOf(listOf(PACKAGE_NAME))
        val observer = PackageProcessObserver { processes }

        assertEquals(ProcessObservation.RUNNING, observer.observe(PACKAGE_NAME))
        processes = listOf(listOf("com.example.other"))
        assertEquals(ProcessObservation.STOPPED, observer.observe(PACKAGE_NAME))
        processes = emptyList()
        assertEquals(ProcessObservation.STOPPED, observer.observe(PACKAGE_NAME))
    }

    private fun verifier(sleep: (Long) -> Unit = {}) = ForceStopVerifier(
        attempts = ATTEMPTS,
        delayMillis = DELAY_MILLIS,
        sleep = sleep,
    )

    private companion object {
        const val PACKAGE_NAME = "com.example.target"
        const val ATTEMPTS = 3
        const val DELAY_MILLIS = 5L
    }
}
