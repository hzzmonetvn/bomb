package com.hzzmonet.zkbomb.domain.taskmanager

import com.hzzmonet.zkbomb.domain.model.ProcessCategory
import com.hzzmonet.zkbomb.domain.model.ProcessClassifier
import com.hzzmonet.zkbomb.domain.model.ProcessItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskManagerEngineTest {

    @Test
    fun `classifier correctly categorizes main process`() {
        val cat = ProcessClassifier.classify(
            processName = "com.example.app",
            packageNames = listOf("com.example.app"),
            uid = 10123,
        )
        assertEquals(ProcessCategory.MAIN, cat)
    }

    @Test
    fun `classifier correctly categorizes app component process`() {
        val cat = ProcessClassifier.classify(
            processName = "com.example.app:push",
            packageNames = listOf("com.example.app"),
            uid = 10123,
        )
        assertEquals(ProcessCategory.APP_COMPONENT, cat)
    }

    @Test
    fun `classifier correctly categorizes isolated process`() {
        val catByUid = ProcessClassifier.classify(
            processName = "isolated_proc",
            packageNames = emptyList(),
            uid = 99050,
        )
        assertEquals(ProcessCategory.ISOLATED, catByUid)

        val catByName = ProcessClassifier.classify(
            processName = "com.example.app:isolated_process0",
            packageNames = listOf("com.example.app"),
            uid = 10123,
        )
        assertEquals(ProcessCategory.ISOLATED, catByName)
    }

    @Test
    fun `classifier correctly categorizes webview process`() {
        val cat = ProcessClassifier.classify(
            processName = "com.example.app:sandboxed_process0",
            packageNames = listOf("com.example.app"),
            uid = 10123,
        )
        assertEquals(ProcessCategory.WEBVIEW, cat)
    }

    @Test
    fun `classifier correctly categorizes native child process`() {
        val cat = ProcessClassifier.classify(
            processName = "my_daemon_helper",
            packageNames = emptyList(),
            uid = 10123,
        )
        assertEquals(ProcessCategory.NATIVE_CHILD, cat)
    }

    @Test
    fun `task manager engine filters by search query`() {
        val items = listOf(
            ProcessItem(100, 10100, 0, "com.foo.app", listOf("com.foo.app"), pssBytes = 1000L, category = ProcessCategory.MAIN),
            ProcessItem(101, 10101, 0, "com.bar.service", listOf("com.bar.app"), pssBytes = 2000L, category = ProcessCategory.APP_COMPONENT),
            ProcessItem(102, 10102, 0, "com.foo.push", listOf("com.foo.app"), pssBytes = 1500L, category = ProcessCategory.APP_COMPONENT),
        )

        val result = TaskManagerEngine.filterAndSort(
            processes = items,
            query = "foo",
            sortBy = ProcessSortBy.PID,
            sortOrder = SortOrder.ASCENDING,
        )

        assertEquals(2, result.size)
        assertEquals(100, result[0].pid)
        assertEquals(102, result[1].pid)
    }

    @Test
    fun `task manager engine filters by category`() {
        val items = listOf(
            ProcessItem(100, 10100, 0, "com.foo.app", listOf("com.foo.app"), category = ProcessCategory.MAIN),
            ProcessItem(101, 10100, 0, "com.foo.app:push", listOf("com.foo.app"), category = ProcessCategory.APP_COMPONENT),
            ProcessItem(102, 10100, 0, "com.foo.app:sandboxed_process0", listOf("com.foo.app"), category = ProcessCategory.WEBVIEW),
        )

        val result = TaskManagerEngine.filterAndSort(
            processes = items,
            categoryFilter = ProcessCategory.APP_COMPONENT,
        )

        assertEquals(1, result.size)
        assertEquals(101, result[0].pid)
    }

    @Test
    fun `task manager engine sorts by RAM descending`() {
        val items = listOf(
            ProcessItem(100, 10100, 0, "app1", pssBytes = 1000L),
            ProcessItem(101, 10101, 0, "app2", pssBytes = 5000L),
            ProcessItem(102, 10102, 0, "app3", pssBytes = 3000L),
        )

        val result = TaskManagerEngine.filterAndSort(
            processes = items,
            sortBy = ProcessSortBy.RAM,
            sortOrder = SortOrder.DESCENDING,
        )

        assertEquals(101, result[0].pid)
        assertEquals(102, result[1].pid)
        assertEquals(100, result[2].pid)
    }

    @Test
    fun `task manager engine sorts by CPU ascending`() {
        val items = listOf(
            ProcessItem(100, 10100, 0, "app1", cpuTimeTicks = 500L),
            ProcessItem(101, 10101, 0, "app2", cpuTimeTicks = 100L),
            ProcessItem(102, 10102, 0, "app3", cpuTimeTicks = 300L),
        )

        val result = TaskManagerEngine.filterAndSort(
            processes = items,
            sortBy = ProcessSortBy.CPU,
            sortOrder = SortOrder.ASCENDING,
        )

        assertEquals(101, result[0].pid)
        assertEquals(102, result[1].pid)
        assertEquals(100, result[2].pid)
    }
}
