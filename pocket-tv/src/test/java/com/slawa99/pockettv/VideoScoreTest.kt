package com.slawa99.pockettv

import org.junit.Assert.*
import org.junit.Test

class VideoScoreTest {
    @Test fun groupMustFinishAndGoesToNextFiveOnlyWithNoPasses() {
        val failed=(1..5).map { VideoMark("n$it","timeout") }
        assertFalse(VideoRank.finished(failed,12))
        val partial=failed+VideoMark("slow","pass",3000)+VideoMark("fast","pass",1200)
        assertFalse(VideoRank.finished(partial,12))
        val full=partial+listOf(VideoMark("tied","pass",1200),VideoMark("skipped","no_service"),VideoMark("bad","stall"))
        assertTrue(VideoRank.finished(full,12))
        assertEquals(listOf("fast","tied","slow"),VideoRank.winners(full).map { it.name })
        assertTrue(VideoRank.finished(failed,5)); assertTrue(VideoRank.winners(failed).isEmpty())
        assertTrue(VideoRank.finished(partial,7))
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
