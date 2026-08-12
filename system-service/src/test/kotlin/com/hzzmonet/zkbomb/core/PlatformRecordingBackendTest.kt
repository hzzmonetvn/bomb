package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.domain.recorder.AudioCallMode
import com.hzzmonet.zkbomb.domain.recorder.PlatformRecordingRequest
import com.hzzmonet.zkbomb.domain.recorder.RecordingFormat
import com.hzzmonet.zkbomb.domain.recorder.RecordingKind
import java.io.FileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformRecordingBackendTest {
    @Test fun `first non-silent live capture verifies cellular capability`() {
        val port = FakePort(AudioCallMode.IN_CALL)
        val backend = PlatformRecordingBackend(port, elapsedRealtime = { 1_234L })
        val sink = FakeSink()

        assertTrue(backend.start(request(RecordingKind.CELLULAR), sink).isSuccess)
        assertEquals("RECORDING", backend.status().state)
        assertEquals(1_234L, backend.status().startedAtElapsedRealtimeMillis)
        assertTrue(backend.stop("session-1").isSuccess)
        assertEquals("SUPPORTED", backend.status().cellularSupport)
        assertEquals("COMPLETED", backend.status().lastOutcome)
        assertEquals(900, backend.status().lastPeakAmplitude)
        assertTrue(sink.closed)
    }

    @Test fun `framework-silenced capture disables only the matching path`() {
        val port = FakePort(AudioCallMode.IN_COMMUNICATION).apply {
            stopResult = RecordingCaptureResult(true, true, 9_000)
        }
        val backend = PlatformRecordingBackend(port, elapsedRealtime = { 5L })

        assertTrue(backend.start(request(RecordingKind.VOIP), FakeSink()).isSuccess)
        assertEquals(BombResult.Status.FAILED, backend.stop("session-1").status)
        assertEquals("SILENT", backend.status().voipSupport)
        assertEquals("UNPROBED", backend.status().cellularSupport)
        assertEquals("SILENT", backend.status().lastOutcome)
    }

    @Test fun `wrong call mode is refused before capture opens`() {
        val port = FakePort(AudioCallMode.IN_COMMUNICATION)
        val sink = FakeSink()
        val backend = PlatformRecordingBackend(port, elapsedRealtime = { 0L })

        val result = backend.start(request(RecordingKind.CELLULAR), sink)

        assertEquals(BombResult.Status.BACKEND_UNAVAILABLE, result.status)
        assertEquals(0, port.starts)
        assertTrue(sink.closed)
        assertNull(backend.status().activeSessionId)
    }

    @Test fun `session id mismatch cannot stop another recording`() {
        val port = FakePort(AudioCallMode.IN_CALL)
        val backend = PlatformRecordingBackend(port, elapsedRealtime = { 0L })
        backend.start(request(RecordingKind.CELLULAR), FakeSink())

        assertEquals(BombResult.Status.PERMISSION_DENIED, backend.stop("other").status)
        assertEquals("RECORDING", backend.status().state)
        assertFalse(port.stopped)
    }

    @Test fun `missing privileged permission never opens capture`() {
        val port = FakePort(AudioCallMode.IN_CALL).apply { permission = false }
        val backend = PlatformRecordingBackend(port, elapsedRealtime = { 0L })

        assertEquals(
            BombResult.Status.PERMISSION_DENIED,
            backend.start(request(RecordingKind.CELLULAR), FakeSink()).status,
        )
        assertEquals("DENIED", backend.status().cellularSupport)
        assertEquals(0, port.starts)
    }

    @Test fun `invalid output descriptor is rejected before permissions and capture`() {
        val port = FakePort(AudioCallMode.IN_CALL).apply { permission = false }
        val sink = FakeSink(valid = false)
        val backend = PlatformRecordingBackend(port, elapsedRealtime = { 0L })

        assertEquals(
            BombResult.Status.INVALID_ARGUMENT,
            backend.start(request(RecordingKind.CELLULAR), sink).status,
        )
        assertTrue(sink.closed)
        assertEquals(0, port.starts)
    }

    @Test fun `asynchronous recorder failure finalizes session and reports failed outcome`() {
        val port = FakePort(AudioCallMode.IN_CALL)
        var ended = 0
        val backend = PlatformRecordingBackend(port, { 10L }) { ended++ }
        val sink = FakeSink()
        backend.start(request(RecordingKind.CELLULAR), sink)

        port.terminate(RecordingCaptureResult(false, false, 0))

        assertEquals("IDLE", backend.status().state)
        assertEquals("FAILED", backend.status().lastOutcome)
        assertEquals("UNAVAILABLE", backend.status().cellularSupport)
        assertTrue(sink.closed)
        assertEquals(1, ended)
    }

    @Test fun `transient open failure may be retried on the next live call`() {
        val port = FakePort(AudioCallMode.IN_COMMUNICATION).apply { openSucceeds = false }
        val backend = PlatformRecordingBackend(port, elapsedRealtime = { 0L })

        assertEquals(
            BombResult.Status.BACKEND_UNAVAILABLE,
            backend.start(request(RecordingKind.VOIP), FakeSink()).status,
        )
        assertEquals("UNAVAILABLE", backend.status().voipSupport)

        port.openSucceeds = true
        assertTrue(backend.start(request(RecordingKind.VOIP), FakeSink()).isSuccess)
    }

    @Test fun `second session is refused and its descriptor is closed`() {
        val port = FakePort(AudioCallMode.IN_CALL)
        val backend = PlatformRecordingBackend(port, elapsedRealtime = { 0L })
        backend.start(request(RecordingKind.CELLULAR), FakeSink())
        val second = FakeSink()

        assertEquals(
            BombResult.Status.FAILED,
            backend.start(request(RecordingKind.CELLULAR).copy(sessionId = "session-2"), second).status,
        )
        assertTrue(second.closed)
        assertEquals(1, port.starts)
    }

    @Test fun `terminal telemetry is bounded and callback failures cannot corrupt state`() {
        val port = FakePort(AudioCallMode.IN_CALL).apply {
            stopResult = RecordingCaptureResult(true, false, Int.MAX_VALUE)
        }
        val backend = PlatformRecordingBackend(port, { 0L }) {
            error("simulated foreground cleanup failure")
        }

        assertTrue(backend.start(request(RecordingKind.CELLULAR), FakeSink()).isSuccess)
        assertTrue(backend.stop("session-1").isSuccess)
        assertEquals("IDLE", backend.status().state)
        assertEquals(32_767, backend.status().lastPeakAmplitude)
    }

    @Test fun `shutdown releases an active descriptor without publishing a completed outcome`() {
        val port = FakePort(AudioCallMode.IN_CALL)
        val sink = FakeSink()
        val backend = PlatformRecordingBackend(port, elapsedRealtime = { 0L })
        backend.start(request(RecordingKind.CELLULAR), sink)

        backend.stopForShutdown()

        assertTrue(port.stopped)
        assertTrue(sink.closed)
        assertEquals("IDLE", backend.status().state)
        assertNull(backend.status().lastOutcome)
    }

    private fun request(kind: RecordingKind) = PlatformRecordingRequest(
        "session-1",
        kind,
        RecordingFormat.AAC_M4A,
    )

    private class FakeSink(private val valid: Boolean = true) : RecordingSink {
        override val fileDescriptor = FileDescriptor()
        var closed = false
        override fun isValid() = valid
        override fun close() { closed = true }
    }

    private class FakePort(private val mode: AudioCallMode) : PlatformRecordingPort {
        var permission = true
        var starts = 0
        var stopped = false
        var openSucceeds = true
        var stopResult = RecordingCaptureResult(true, false, 900)
        private var termination: ((RecordingCaptureResult) -> Unit)? = null
        override fun capturePermissionHeld() = permission
        override fun currentAudioMode() = mode
        override fun start(
            sink: RecordingSink,
            onTerminated: (RecordingCaptureResult) -> Unit,
        ): PlatformRecordingHandle? {
            starts++
            termination = onTerminated
            if (!openSucceeds) return null
            return object : PlatformRecordingHandle {
                override fun clientSilenced() = stopResult.clientSilenced
                override fun stop(): RecordingCaptureResult {
                    stopped = true
                    sink.close()
                    return stopResult
                }
            }
        }

        fun terminate(result: RecordingCaptureResult) {
            termination?.invoke(result)
        }
    }
}
