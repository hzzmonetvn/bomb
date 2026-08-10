package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.SelectedProcessMemoryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessSnapshotBackendTest {

    @Test
    fun `list of 192 processes never invokes expensive memory sampling`() {
        val memory = RecordingMemorySource()
        val backend = backend(
            processes = List(200) { index -> process(pid = index + 1) },
            memory = memory,
            proc = FakeProcReader(rssBytes = 4_096L),
        )

        val snapshot = backend.processSnapshot()

        assertEquals(192, snapshot.processes.size)
        assertTrue(snapshot.truncated)
        assertTrue(memory.pids.isEmpty())
        assertTrue(snapshot.processes.all { it.pssBytes == null && it.privateDirtyBytes == null })
        assertTrue(snapshot.processes.all { it.rssBytes == 4_096L })
    }

    @Test
    fun `selected process invokes memory source with exactly that one pid`() {
        val memory = RecordingMemorySource(
            reading = ProcessMemoryReading(
                pssBytes = 8_192L,
                privateDirtyBytes = 4_096L,
                rssBytes = 12_288L,
            ),
        )
        val backend = backend(listOf(process(41), process(42)), memory)

        val result = backend.selectedProcessMemory(42)

        assertEquals(listOf(42), memory.pids)
        assertEquals(SelectedProcessMemoryStatus.AVAILABLE, result.parsedStatus)
        assertEquals(8_192L, result.pssBytes)
        assertEquals(4_096L, result.privateDirtyBytes)
        assertEquals(12_288L, result.rssBytes)
    }

    @Test
    fun `invalid and non-visible pids are rejected before sampling`() {
        val memory = RecordingMemorySource()
        val backend = backend(listOf(process(42)), memory)

        assertEquals(
            SelectedProcessMemoryStatus.INVALID_PID,
            backend.selectedProcessMemory(-1).parsedStatus,
        )
        assertEquals(
            SelectedProcessMemoryStatus.NOT_VISIBLE,
            backend.selectedProcessMemory(99).parsedStatus,
        )
        assertTrue(memory.pids.isEmpty())
    }

    @Test
    fun `pid outside bounded snapshot is not sampled`() {
        val memory = RecordingMemorySource(ProcessMemoryReading(1_024L, null, null))
        val backend = backend(
            processes = List(200) { index -> process(pid = index + 1) },
            memory = memory,
        )

        val result = backend.selectedProcessMemory(193)

        assertEquals(SelectedProcessMemoryStatus.NOT_VISIBLE, result.parsedStatus)
        assertTrue(memory.pids.isEmpty())
    }

    @Test
    fun `process disappearing during sample returns typed unavailable`() {
        val inventory = SequencedInventory(
            listOf(process(42)),
            emptyList(),
        )
        val memory = RecordingMemorySource(ProcessMemoryReading(1_024L, null, null))
        val backend = ProcessSnapshotBackend(inventory, memory, FakeProcReader(), { 123L })

        val result = backend.selectedProcessMemory(42)

        assertEquals(listOf(42), memory.pids)
        assertEquals(SelectedProcessMemoryStatus.DISAPPEARED, result.parsedStatus)
        assertNull(result.pssBytes)
        assertNull(result.privateDirtyBytes)
        assertNull(result.rssBytes)
    }

    @Test
    fun `pid reused by another process during sample is treated as disappeared`() {
        val inventory = SequencedInventory(
            listOf(process(42)),
            listOf(process(42).copy(uid = 20_042, processName = "com.example.reused")),
        )
        val memory = RecordingMemorySource(ProcessMemoryReading(1_024L, null, null))
        val backend = ProcessSnapshotBackend(inventory, memory, FakeProcReader(), { 123L })

        val result = backend.selectedProcessMemory(42)

        assertEquals(listOf(42), memory.pids)
        assertEquals(SelectedProcessMemoryStatus.DISAPPEARED, result.parsedStatus)
        assertNull(result.pssBytes)
    }

    @Test
    fun `unreadable metric stays null without fabricating zero`() {
        val partial = backend(
            listOf(process(42)),
            RecordingMemorySource(ProcessMemoryReading(null, 2_048L, null)),
        ).selectedProcessMemory(42)

        assertEquals(SelectedProcessMemoryStatus.AVAILABLE, partial.parsedStatus)
        assertNull(partial.pssBytes)
        assertEquals(2_048L, partial.privateDirtyBytes)
        assertNull(partial.rssBytes)

        val unavailable = backend(
            listOf(process(42)),
            RecordingMemorySource(ProcessMemoryReading(null, null, null)),
        ).selectedProcessMemory(42)
        assertEquals(SelectedProcessMemoryStatus.UNAVAILABLE, unavailable.parsedStatus)
        assertFalse(unavailable.available)
    }

    @Test
    fun `capability probe samples only the first visible pid without fallback`() {
        val memory = RecordingMemorySource()
        val backend = backend(listOf(process(42), process(43)), memory)

        assertFalse(backend.probeSelectedProcessMemory(excludedPid = 42))
        assertEquals(listOf(43), memory.pids)
    }

    @Test
    fun `unreadable proc fields do not remove the process from the list`() {
        val proc = FakeProcReader(throwOnRead = true)
        val snapshot = backend(
            listOf(process(42)),
            RecordingMemorySource(),
            proc,
        ).processSnapshot()

        assertEquals(1, snapshot.processes.size)
        assertNull(snapshot.processes.single().rssBytes)
        assertNull(snapshot.processes.single().cpuTimeTicks)
        assertNull(snapshot.processes.single().threadCount)
        assertNull(snapshot.processes.single().startTimeTicks)
    }

    private fun backend(
        processes: List<ObservedProcess>,
        memory: RecordingMemorySource,
        proc: ProcessProcReader = FakeProcReader(),
    ) = ProcessSnapshotBackend(
        inventory = ProcessInventory { processes },
        memorySource = memory,
        proc = proc,
        elapsedRealtime = { 123L },
    )

    private fun process(pid: Int) = ObservedProcess(
        pid = pid,
        uid = 10_000 + pid,
        processName = "com.example.app$pid",
        packageNames = listOf("com.example.app$pid"),
        importance = pid,
        importanceReasonCode = 0,
        foreground = false,
    )

    private class RecordingMemorySource(
        private val reading: ProcessMemoryReading? = null,
    ) : SelectedProcessMemorySource {
        val pids = mutableListOf<Int>()

        override fun read(pid: Int): ProcessMemoryReading? {
            pids += pid
            return reading
        }
    }

    private class SequencedInventory(vararg values: List<ObservedProcess>) : ProcessInventory {
        private val snapshots = ArrayDeque(values.toList())
        private var last = values.lastOrNull().orEmpty()

        override fun runningProcesses(): List<ObservedProcess> {
            if (snapshots.isNotEmpty()) last = snapshots.removeFirst()
            return last
        }
    }

    private class FakeProcReader(
        private val rssBytes: Long? = null,
        private val throwOnRead: Boolean = false,
    ) : ProcessProcReader {
        override fun readProcess(pid: Int): ProcProcessStat? {
            if (throwOnRead) error("unreadable")
            return ProcProcessStat(1L, 2, 3L)
        }

        override fun readRssBytes(pid: Int): Long? {
            if (throwOnRead) error("unreadable")
            return rssBytes
        }

        override fun readCpuTotals(): CpuTotals? = null
    }
}
