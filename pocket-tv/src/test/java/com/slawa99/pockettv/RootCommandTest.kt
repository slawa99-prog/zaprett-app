package com.slawa99.pockettv

import org.junit.Assert.*
import org.junit.Test

class RootCommandTest {
    private fun shell() = RootCommand { ProcessBuilder("/bin/sh", "-c", it).redirectErrorStream(true).start() }

    @Test fun nonInteractiveCommandsReceiveEofBeforeWaitingForExit() {
        // An open stdin pipe used to leave this command waiting until timeout.
        assertEquals("finished", shell().run("cat >/dev/null; printf finished", "state", 2))
    }

    @Test fun timeoutKeepsLastConfirmedPhaseAndPartialOutput() {
        val error = assertThrows(RootCommandFailure::class.java) {
            shell().run("printf '@@PHASE root\\n@@PHASE catalog\\n@@VERSION v72\\n'; exec sleep 10", "state", 1)
        }
        assertTrue(error.message!!.contains("чтение каталога стратегий"))
        assertFalse(error.message!!.contains("Проверьте запрос разрешения"))
        assertTrue(error.diagnostic.contains("@@VERSION v72"))
        assertFalse(error.message!!.contains("@@VERSION"))
    }

    @Test fun noRootResponseAndFailedBridgeRemainDistinct() {
        val noReply = RootCommand.timeoutMessage("state", 25, "")
        assertTrue(noReply.contains("ожидание запуска root-команды"))
        val error = assertThrows(RootCommandFailure::class.java) {
            shell().run("printf '@@PHASE service\\nНе удалось прочитать процессы\\n'; exit 7", "state", 2)
        }
        assertEquals("Не удалось прочитать процессы", error.message)
        assertTrue(error.diagnostic.contains("exit: 7"))
        assertTrue(error.diagnostic.contains("@@PHASE service"))
    }
}
