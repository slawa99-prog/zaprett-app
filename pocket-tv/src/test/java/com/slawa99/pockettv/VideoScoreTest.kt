package com.slawa99.pockettv

import org.junit.Assert.*
import org.junit.Test

class VideoScoreTest {
    private fun rows(size: Int, vararg passes: Pair<Int,Long>): List<VideoMark> {
        val times = passes.toMap()
        return (1..size).map { i -> times[i]?.let { VideoMark("n$i","pass",it) } ?: VideoMark("n$i","timeout") }
    }
    @Test fun sparseSuccessesSpanGroupsAndTheLastCandidateCanStillWin() {
        val run = rows(15, 3 to 4100L, 8 to 3700L, 12 to 2200L, 15 to 900L)
        for (n in 0 until run.size) assertEquals("after $n",VideoDecision.Continue,VideoRank.decide(run.take(n),300))
        assertEquals(VideoDecision.Apply(run.last()),VideoRank.decide(run,300))
    }
    @Test fun firstFiveCanBeEnoughAndTiesRetainNetworkOrderAcrossGroups() {
        val first = rows(5, 1 to 1500L, 2 to 1300L, 3 to 2200L, 5 to 1300L)
        assertEquals(VideoDecision.Continue,VideoRank.decide(first.take(3),300))
        assertEquals(VideoDecision.Apply(first[1]),VideoRank.decide(first,300))
        val across = rows(7, 1 to 1000L, 6 to 1300L, 7 to 1000L)
        assertEquals(VideoDecision.Apply(across.first()),VideoRank.decide(across,7))
    }
    @Test fun exhaustingTheQueueWithLessThanThreeDistinctPassesKeepsOriginal() {
        for (n in 0..2) {
            val short = rows(8, *(1..n).map { it to 1300L }.toTypedArray())
            assertEquals(VideoDecision.KeepOriginal(n),VideoRank.decide(short,8))
            assertEquals(n,VideoRank.winners(short).size) // retained for manual selection
        }
        val repeated = listOf(VideoMark("same","pass",300),VideoMark("same","pass",400),VideoMark("other","pass",700))
        assertEquals(VideoDecision.KeepOriginal(2),VideoRank.decide(repeated,3))
    }
    @Test fun exportedReportSeparatesPlayerEvidenceFromModuleTimings() {
        val marks=listOf(VideoMark("A","no_service"),VideoMark("B","timeout",detail="0 с видео"),VideoMark("C","pass",1613))
        val v=VideoRun("id","cat","A",true,marks.map { it.name } + listOf("D","E"),12L,rows=marks,phase="done",winner="C")
        val report=v.diagnosticText()
        assertTrue(report.contains("3/5 · воспроизвели видео: 1"))
        assertTrue(report.contains("1. A — Сервис не запустился"))
        assertTrue(report.contains("2. B — Видео не начало"))
        assertTrue(report.contains("0 с видео"))
        assertTrue(report.contains("3. C — 20 с без остановок; время до старта видео: 1613 мс"))
        assertFalse(report.contains("4. D"))
    }
    @Test fun zeroScoreStillGetsAnAttemptButSkippedAndForeignCatalogDoNot() {
        val s=TestSnapshot("a","completed",3,catalogId="c",results=listOf(
            ProbeResult("best","OK",1,1,0,0,0,0),ProbeResult("zero","OK",0,1,0,0,0,0),
            ProbeResult("skip","SKIP",0,0,0,0,0,0)))
        val m=ModuleInfo(strategies=listOf("skip","zero","best"),catalogId="c")
        assertEquals(listOf("best","zero"),VideoRank.candidates(s,m))
        for(bad in listOf(s.copy(profile="all"),s.copy(status="cancelled"),s.copy(catalogId="old"),s.copy(restore="failed")))
            assertTrue(VideoRank.candidates(bad,m).isEmpty())
    }
    @Test fun coarsePlayerTimestampEverySecondIsNotASeek() {
        val meter=VideoMeter(0);var outcome:String?=null
        for(i in 0..57){outcome=meter.sample(i*400L,1,((i*400L)/1000).toDouble());if(outcome!=null) break}
        assertEquals("pass",outcome)
    }
    @Test fun requiresTwentyRealSecondsAndProgressWithoutAStall() {
        val good=VideoMeter(0); var result:String?=null
        for (i in 0..41) result=good.sample(i*500L,1,i*.5)
        assertEquals("pass",result);assertEquals(500L,good.startup)
        val frozen=VideoMeter(0);frozen.sample(10,1,0.0);frozen.sample(510,1,.5)
        assertEquals("stall",frozen.sample(3510,1,.5))
        val buffering=VideoMeter(0);buffering.sample(100,1,.1);buffering.sample(600,1,.6)
        assertEquals("stall",buffering.sample(1100,3,.6))
        val jumped=VideoMeter(0);jumped.sample(100,1,.1);jumped.sample(600,1,.6)
        assertEquals("stall",jumped.sample(1100,1,24.0))
        assertEquals("timeout",VideoMeter(0).sample(35001,1,0.0))
    }
}
