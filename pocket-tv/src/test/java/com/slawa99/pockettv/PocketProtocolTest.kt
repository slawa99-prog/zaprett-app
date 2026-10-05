package com.slawa99.pockettv

import org.junit.Assert.*
import org.junit.Test

class PocketProtocolTest {
    @Test fun parsesProtocolAndKeepsNamesWithSpaces() {
        val s = PocketProtocol.test("""
            @@ID abc
            @@STATE running
            @@TOTAL 297
            @@PROFILE youtube
            @@TESTING bystro292_3 - Copy
            @@RESULT OK 3 4 2 4 4 4 bystro292_3 - Copy
            @@RESULT SKIP 0 0 0 0 0 0 general_z2
            @@LOGTAIL
            @@RESULT OK 9 9 9 9 9 9 this is raw log, not another result
        """.trimIndent())
        assertEquals(297, s.total)
        assertEquals(2, s.completed)
        assertEquals("bystro292_3 - Copy", s.current)
        assertEquals(75, s.ranked.first().percent)
        assertEquals("general_z2", s.ranked.last().name)
        assertTrue(s.active)
    }
    @Test fun rejectsPartialCorruptAndOutOfRangeMarkers() {
        val s = PocketProtocol.test("""
            @@RESULT OK 6 4 2 4 4 4 impossible
            @@RESULT OK 3 4 2
            @@RESULT OK 30000000000 4 0 4 1 4 overflow
            @@RESULT OK 1 4 1 4 1 4 ../bad
            @@RESULT OK 1 4 1 4 1 4 good
            @@RESULT OK 2 4 2 4 2 4 good
        """.trimIndent())
        assertEquals(1, s.completed)
        assertEquals(50, s.ranked.single().percent)
    }
    @Test fun rankingDoesNotCallUnsupportedChecksSuccess() {
        val s = PocketProtocol.test("""
            @@RESULT OK 0 0 0 0 0 0 unsupported
            @@RESULT OK 0 4 0 4 0 4 zero
            @@RESULT OK 1 1 0 0 0 0 small
            @@RESULT OK 3 3 3 3 3 3 full
        """.trimIndent())
        assertEquals(listOf("full", "small", "zero", "unsupported"), s.ranked.map { it.name })
        assertTrue(s.results.first().description().contains("неизвестен"))
    }
    @Test fun completedAndInterruptedSessionsKeepTheirScores() {
        for (state in listOf("completed", "cancelled", "failed", "interrupted")) {
            val s = PocketProtocol.test("@@STATE $state\n@@RESULT OK 2 4 2 4 2 4 general\n")
            assertFalse(s.active)
            assertEquals(50, s.results.single().percent)
        }
    }
    @Test fun moduleCatalogAndShellQuotingAreSafe() {
        val info = PocketProtocol.module("@@VERSION v71\n@@SERVICE stopped\n@@COMPATIBLE 1\n@@STRATEGY a\n@@STRATEGY name with spaces\n@@STRATEGY ../no\n@@STRATEGY a")
        assertEquals(listOf("a", "name with spaces"), info.strategies)
        assertEquals("'name'\\''s ${'$'}(touch nope)'", PocketProtocol.shellQuote("name's ${'$'}(touch nope)"))
        assertFalse(PocketProtocol.safeName("bad\nname"))
        assertFalse(PocketProtocol.safeName("../strategy"))
    }
    @Test fun failureShowsCauseInsteadOfWaitingOrContinuation() {
        val s = PocketProtocol.test("@@STATE failed\n@@ERROR Не удалось запустить curl\n@@LOGTAIL\nraw log")
        assertFalse(s.active)
        assertEquals("Подбор остановлен: Не удалось запустить curl", s.headline())
        val old = TestSnapshot(status = "failed", tail = "! curl not found or not functional (checked: module/curl)")
        assertTrue(old.headline().contains("curl"))
        assertFalse(old.headline().contains("продолжится"))
    }
}
