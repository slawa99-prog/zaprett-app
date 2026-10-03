package com.cherret.zaprett

import com.cherret.zaprett.utils.PersonalStrategyMutator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalStrategyMutatorTest {
    private val udp = "--filter-udp=443 --dpi-desync=fake --dpi-desync-repeats=8 --new"
    private val discord = "--filter-tcp=8443 --dpi-desync=fake,multisplit --dpi-desync-split-pos=1 --new"
    private val tcp = "--filter-tcp=443 --dpi-desync=fake,multisplit --dpi-desync-split-pos=1 " +
        "--dpi-desync-repeats=8 --dpi-desync-fooling=ts --dpi-desync-split-seqovl=681"
    private val seed = listOf(udp, discord, tcp).joinToString("\n")

    @Test
    fun variantsChangeOnlyYouTubeTcpRulesAndRemainUnique() {
        val variants = PersonalStrategyMutator.firstStage(seed)
        assertEquals(8, variants.size)
        assertEquals(8, variants.map { it.content }.toSet().size)
        variants.forEach { variant ->
            assertTrue(variant.content.contains(udp))
            assertTrue(variant.content.contains(discord))
            assertFalse(variant.content == seed)
            assertTrue(variant.content.contains("--filter-tcp=443"))
        }
    }

    @Test
    fun secondStageCombinesWithBestWithoutRepeatingFirstStage() {
        val first = PersonalStrategyMutator.firstStage(seed)
        val excluded = first.mapTo(mutableSetOf(seed)) { it.content }
        val next = PersonalStrategyMutator.nextStage(first.first(), excluded)
        assertEquals(4, next.size)
        assertTrue(next.all { it.content !in excluded && it.label.contains(" + ") })
    }
}
