@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.dlidar.mobile.data.scanner

import com.dlidar.mobile.data.protocol.session.DefaultProtocolSessionFactory
import com.dlidar.mobile.data.scanner.log.ScannerLogImpl
import com.dlidar.mobile.domain.model.LogCursor
import com.dlidar.mobile.domain.model.LogEntry
import com.dlidar.mobile.domain.model.LogLevel
import com.dlidar.mobile.domain.model.LogPage
import com.dlidar.mobile.domain.model.LogSource
import com.dlidar.mobile.domain.model.LogThreshold
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.domain.result.Outcome
import com.dlidar.mobile.testing.FakeScanner
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** log.get against the fake scanner, which plays the scanner's paging contract. */
class LogReadTest {

    private val fake = FakeScanner()

    private fun TestScope.connected(): Pair<ScannerRepositoryImpl, ScannerLogImpl> {
        val repository = ScannerRepositoryImpl(fake, DefaultProtocolSessionFactory(), backgroundScope, { Instant.EPOCH })
        repository.connect(ScannerCandidate("AA:BB", "dLidar", -50))
        runCurrent()
        return repository to ScannerLogImpl(repository)
    }

    private val logRequests: List<JsonObject>
        get() = fake.requests.filter { it["cmd"]?.jsonPrimitive?.content == "log.get" }

    private fun Outcome<LogPage>.page(): LogPage = (this as Outcome.Ok).value

    private fun texts(page: LogPage) = page.entries.map { it.text }

    @After
    fun noProtocolViolations() {
        assertEquals(emptyList<String>(), fake.violations)
    }

    @Test
    fun `hello listing log get offers the log`() = runTest {
        assertTrue(connected().first.snapshot.value.canReadLog)
    }

    @Test
    fun `an older scanner gets no log get`() = runTest {
        fake.hello = FakeScanner.defaultHello(commands = FakeScanner.WIFI_COMMANDS)
        val (repository, log) = connected()

        assertFalse(repository.snapshot.value.canReadLog)
        assertEquals(Outcome.Failed(DomainError.NotSupported), log.read(null, LogThreshold.Info))
        assertTrue(logRequests.isEmpty())
    }

    @Test
    fun `the first page starts at the newest line and is mapped`() = runTest {
        fake.log("dLidar started")
        fake.log("dlidar.service: Failed with result 'signal'.", level = "warning", source = "system", t = 1_793_781_400_123)
        val (_, log) = connected()

        val page = log.read(null, LogThreshold.Info).page()

        assertEquals(
            LogEntry(Instant.ofEpochMilli(1_793_781_400_123), "36276c97", LogLevel.Warning, LogSource.System, "dlidar.service: Failed with result 'signal'."),
            page.entries.first(),
        )
        assertEquals(listOf("dlidar.service: Failed with result 'signal'.", "dLidar started"), texts(page))
        assertNull(page.older)
        // App choice: 100 per request, the lowest level always named; no cursor on the first page.
        assertEquals(buildJsonObject {
            put("limit", 100)
            put("level", "info")
        }, logRequests.single()["args"])
    }

    @Test
    fun `older pages follow the cursor until the start of the log`() = runTest {
        repeat(250) { fake.log("line $it") }
        val (_, log) = connected()

        val first = log.read(null, LogThreshold.Info).page()
        // New lines do not move a cursor.
        fake.log("newer line")
        val second = log.read(first.older, LogThreshold.Info).page()
        val third = log.read(second.older, LogThreshold.Info).page()

        assertEquals((249 downTo 150).map { "line $it" }, texts(first))
        assertEquals((149 downTo 50).map { "line $it" }, texts(second))
        assertEquals((49 downTo 0).map { "line $it" }, texts(third))
        assertNull(third.older)
        assertEquals(JsonPrimitive(first.older!!.raw), logRequests[1]["args"]!!.let { it as JsonObject }["before"])
    }

    @Test
    fun `a page that fits fewer lines than asked is taken as it is`() = runTest {
        repeat(30) { fake.log("long line $it") }
        fake.logLinesPerAnswer = 13
        val (_, log) = connected()

        val page = log.read(null, LogThreshold.Info).page()

        assertEquals(13, page.entries.size)
        assertEquals(1, logRequests.size)
        assertEquals(LogCursor("s=17"), page.older)
    }

    @Test
    fun `empty answers with more are paged through until lines come`() = runTest {
        fake.log("old error", level = "error")
        repeat(30) { fake.log("info $it") }
        fake.logScanWindow = 10
        val (_, log) = connected()

        val page = log.read(null, LogThreshold.Error).page()

        assertEquals(listOf("old error"), texts(page))
        assertEquals(4, logRequests.size)
        assertEquals("error", (logRequests.first()["args"] as JsonObject)["level"]!!.jsonPrimitive.content)
        assertNull(page.older)
    }

    @Test
    fun `after five empty answers the read returns the empty page and its cursor`() = runTest {
        fake.log("old error", level = "error")
        repeat(95) { fake.log("info $it") }
        fake.logScanWindow = 10
        val (_, log) = connected()

        val page = log.read(null, LogThreshold.Error).page()

        assertTrue(page.entries.isEmpty())
        assertEquals(LogCursor("s=46"), page.older)
        assertEquals(5, logRequests.size)

        // Reading on from that cursor finds the line.
        assertEquals(listOf("old error"), texts(log.read(page.older, LogThreshold.Error).page()))
    }

    @Test
    fun `an entry of an unexpected shape is skipped, the rest of the page kept`() = runTest {
        fake.log("first")
        fake.logLines += buildJsonObject {
            put("t", "not a number")
            put("text", "broken")
        }
        fake.log("third", level = "debug", source = "kernel")
        val (_, log) = connected()

        val page = log.read(null, LogThreshold.Info).page()

        // Unknown level and source values are kept as generic ones.
        assertEquals(listOf("third", "first"), texts(page))
        assertEquals(LogLevel.Unknown("debug"), page.entries.first().level)
        assertEquals(LogSource.Unknown("kernel"), page.entries.first().source)
    }

    @Test
    fun `an unreadable journal is a generic error with the scanner's text`() = runTest {
        fake.logUnavailable = "cannot open the journal: Permission denied"
        val (_, log) = connected()

        assertEquals(
            Outcome.Failed(DomainError.Remote("unavailable", "cannot open the journal: Permission denied")),
            log.read(null, LogThreshold.Info),
        )
    }

    @Test
    fun `no request without a live session`() = runTest {
        val (repository, log) = connected()
        repository.disconnect()
        runCurrent()

        assertEquals(Outcome.Failed(DomainError.NotConnected), log.read(null, LogThreshold.Info))
        assertTrue(logRequests.isEmpty())
    }

    @Test
    fun `a link lost during a read fails it`() = runTest {
        fake.silentCommands += "log.get"
        val (_, log) = connected()

        val read = backgroundScope.async { log.read(null, LogThreshold.Info) }
        runCurrent()
        fake.dropLink()
        runCurrent()

        assertEquals(Outcome.Failed(DomainError.LinkLost), read.await())
    }
}
