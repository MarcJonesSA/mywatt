package com.mywatt.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {

    private fun session(id: String) = requireNotNull(Library.byId(id)) { "no session $id" }

    // ---- Parsing ------------------------------------------------------------------------------

    @Test
    fun `every session parses and builds`() {
        assertEquals(
            listOf(
                "three-by-one", "ronnestad-30-15", "vo2-5x5", "vo2-3x3", "piano-fingers", "lead-weight",
                "stepdown", "threshold-5x8", "sweet-spot-3x10", "tempo-3x10", "low-cadence-force",
                "neuro-sprints", "tabata", "z2-45", "test-ramp", "test-20",
            ),
            Library.sessions.map { it.id },
        )
        Library.sessions.forEach { s ->
            assertTrue(s.id, s.name.isNotBlank())
            assertTrue(s.id, s.oneLineWhy.isNotBlank())
            // Building checks every block (positive lengths and targets).
            listOf(100, 200, 400).forEach { ftp -> s.build(ftp, RecoverySetting.Trained) }
        }
    }

    @Test
    fun `regions and screen modes are as listed`() {
        val expected = mapOf(
            "three-by-one" to (Region.VO2max to ScreenMode.NumberFirst),
            "ronnestad-30-15" to (Region.VO2max to ScreenMode.NumberFirst),
            "vo2-5x5" to (Region.VO2max to ScreenMode.Dial),
            "vo2-3x3" to (Region.VO2max to ScreenMode.NumberFirst),
            "piano-fingers" to (Region.Anaerobic to ScreenMode.NumberFirst),
            "lead-weight" to (Region.Anaerobic to ScreenMode.NumberFirst),
            "stepdown" to (Region.VO2max to ScreenMode.NumberFirst),
            "threshold-5x8" to (Region.Threshold to ScreenMode.Dial),
            "sweet-spot-3x10" to (Region.Threshold to ScreenMode.Dial),
            "tempo-3x10" to (Region.Threshold to ScreenMode.Dial),
            "low-cadence-force" to (Region.Threshold to ScreenMode.Dial),
            "neuro-sprints" to (Region.Neuromuscular to ScreenMode.NumberFirst),
            "tabata" to (Region.Anaerobic to ScreenMode.NumberFirst),
            "z2-45" to (Region.Endurance to ScreenMode.Dial),
            "test-ramp" to (Region.Test to ScreenMode.NumberFirst),
            "test-20" to (Region.Test to ScreenMode.NumberFirst),
        )
        assertEquals(expected, Library.sessions.associate { it.id to (it.region to it.screenMode) })
    }

    // ---- Durations ----------------------------------------------------------------------------

    @Test
    fun `total durations are as listed`() {
        // Worked by hand from the session list: 5 min warm-up and 3 min cool-down unless
        // stated, gated recoveries counted as 2 min, fixed recoveries only between reps.
        val seconds = mapOf(
            "three-by-one" to 300 + 3 * (60 + 120) + 180,
            "ronnestad-30-15" to 300 + 3 * 13 * (30 + 15) + 2 * 180 + 180,
            "vo2-5x5" to 300 + 5 * (300 + 120) + 180,
            "vo2-3x3" to 300 + 3 * (180 + 120) + 180,
            "piano-fingers" to 300 + 7 * (60 + 120) + 180,
            "lead-weight" to 300 + (120 + 120) + 6 * (60 + 120) + 180,
            "stepdown" to 300 + 3 * (3 * 60 + 120) + 180,
            "threshold-5x8" to 300 + 5 * 480 + 4 * 180 + 180,
            "sweet-spot-3x10" to 300 + 3 * 600 + 2 * 240 + 180,
            "tempo-3x10" to 300 + 3 * 600 + 2 * 300 + 180,
            "low-cadence-force" to 300 + 3 * 480 + 2 * 240 + 180,
            "neuro-sprints" to 300 + 6 * 14 + 5 * 180 + 180,
            "tabata" to 300 + 8 * (20 + 10) + 180,
            "z2-45" to 300 + 45 * 60 + 180,
            // FTP 200: ramp to ~267 W, so 100..260 W in full (9 steps) and half of 280 W.
            "test-ramp" to 300 + 9 * 60 + 30 + 180,
            "test-20" to 600 + 300 + 1200 + 300,
        )
        assertEquals(seconds, Library.sessions.associate { it.id to it.nominalSeconds() })
        Library.sessions.forEach { s ->
            assertEquals(s.id, s.durationMinutes, Math.round(s.nominalSeconds() / 60.0).toInt())
        }
    }

    // ---- Targets ------------------------------------------------------------------------------

    @Test
    fun `targets scale with FTP`() {
        Library.sessions.forEach { lib ->
            val at200 = lib.build(200)
            val at300 = lib.build(300)
            at200.blocks.zip(at300.blocks).forEach { (a, b) ->
                when (a) {
                    is Rep -> assertEquals(lib.id, Math.round((b as Rep).targetWatts / 1.5).toInt(), a.targetWatts)
                    is Easy -> assertEquals(lib.id, Math.round((b as Easy).targetWatts!! / 1.5).toInt(), a.targetWatts)
                    else -> Unit
                }
            }
            assertEquals(200, at200.ftpWatts)
        }
        val threeByOne = session("three-by-one").build(200)
        assertEquals(listOf(220, 220, 220), threeByOne.blocks.filterIsInstance<Rep>().map { it.targetWatts })
        assertEquals(WarmUp(300, 110), threeByOne.blocks.first())
        assertEquals(CoolDown(180, 100), threeByOne.blocks.last())
        assertEquals(
            listOf(330, 330, 330),
            session("three-by-one").build(300).blocks.filterIsInstance<Rep>().map { it.targetWatts },
        )
        // 108% of 237 W is 255.96 W.
        assertEquals(256, session("vo2-5x5").build(237).blocks.filterIsInstance<Rep>().first().targetWatts)
        // The ramp is in absolute watts and does not move with FTP.
        assertEquals(Ramp(100, 20, 60), session("test-ramp").build(320).blocks[1])
    }

    @Test
    fun `gated recoveries use the rider's setting`() {
        val s = session("vo2-5x5").build(250, RecoverySetting.Peak)
        assertEquals(RecoverySetting.Peak, s.recoverySetting)
        assertEquals(setOf(HrGate(RecoverySetting.Peak)), s.blocks.filterIsInstance<Recovery>().map { it.gate }.toSet())
        assertEquals(5, s.blocks.count { it is Recovery })
    }

    // ---- Shapes -------------------------------------------------------------------------------

    @Test
    fun `Ronnestad is three sets of thirteen 30-15s with fixed set breaks`() {
        val s = session("ronnestad-30-15").build(200)
        val reps = s.blocks.filterIsInstance<Rep>()
        assertEquals(39, reps.size)
        assertEquals(3, s.setCount)
        assertEquals(List(13) { 1 } + List(13) { 2 } + List(13) { 3 }, reps.map { it.set })
        assertTrue(reps.all { it.seconds == 30 && it.targetWatts == 240 })
        val easy = s.blocks.filterIsInstance<Easy>()
        assertEquals(39 + 2, easy.size)
        assertEquals(2, easy.count { it == Easy(180, 100) })
        assertEquals(39, easy.count { it == Easy(15, 120) })
        assertTrue(s.blocks.none { it is Recovery })
        // Set 1 ends with its 13th 15 s easy, then the 3 min break, then set 2.
        val firstBreak = s.blocks.indexOf(Easy(180, 100))
        assertEquals(Easy(15, 120), s.blocks[firstBreak - 1])
        assertEquals(Rep(30, 240, set = 2), s.blocks[firstBreak + 1])
    }

    @Test
    fun `fixed recoveries sit between reps, gated ones follow every rep`() {
        val threshold = session("threshold-5x8").build(200).blocks
        assertEquals(
            listOf(WarmUp(300, 110)) +
                List(5) { Rep(480, 196) }.flatMapIndexed { i, r -> if (i < 4) listOf(r, Easy(180, 110)) else listOf(r) } +
                CoolDown(180, 100),
            threshold,
        )
        val stepdown = session("stepdown").build(200).blocks
        assertEquals(
            listOf(WarmUp(300, 110)) +
                List(3) { listOf(Rep(60, 260), Rep(60, 234), Rep(60, 210), Recovery()) }.flatten() +
                CoolDown(180, 100),
            stepdown,
        )
        assertEquals(
            listOf(115, 120, 125, 130, 135, 140, 140),
            session("piano-fingers").blocks.filterIsInstance<RepStep>().map { it.percent },
        )
        assertEquals(
            listOf(120 to 115, 60 to 120, 60 to 125, 60 to 130, 60 to 135, 60 to 140, 60 to 140),
            session("lead-weight").blocks.filterIsInstance<RepStep>().map { it.seconds to it.percent },
        )
        assertEquals(7, session("lead-weight").blocks.count { it == GatedStep })
    }

    @Test
    fun `special reps carry their cadence, sprint window and kind`() {
        val force = session("low-cadence-force").build(200).blocks.filterIsInstance<Rep>()
        assertEquals(List(3) { Rep(480, 170, cadenceRpm = 60) }, force)

        val sprints = session("neuro-sprints").build(200).blocks.filterIsInstance<Rep>()
        assertEquals(List(6) { Rep(14, 500, bestWindowSeconds = 10, kind = RepKind.SPRINT) }, sprints)
        assertEquals(5, session("neuro-sprints").blocks.count { it == EasyStep(180, 50) })

        val tabata = session("tabata").build(200).blocks
        assertEquals(8, tabata.count { it == Rep(20, 340) })
        assertEquals(8, tabata.count { it == Easy(10, 80) })

        val z2 = session("z2-45").build(200).blocks
        assertEquals(listOf(WarmUp(300, 110), Rep(2700, 130, kind = RepKind.STEADY), CoolDown(180, 100)), z2)
    }

    @Test
    fun `the tests are what they say`() {
        val ramp = session("test-ramp").build(200)
        assertEquals(FtpTest.RAMP, ramp.ftpTest)
        assertEquals(listOf(WarmUp(300, 100), Ramp(100, 20, 60), CoolDown(180, 100)), ramp.blocks)

        val twenty = session("test-20").build(240)
        assertEquals(FtpTest.TWENTY_MINUTE, twenty.ftpTest)
        assertEquals(
            listOf(
                WarmUp(600, 144, 216),
                Easy(300, 120),
                // Target shown is the current FTP.
                Rep(1200, 240, kind = RepKind.SELF_PACED),
                CoolDown(300, 120),
            ),
            twenty.blocks,
        )
        assertNull(session("vo2-5x5").ftpTest)
    }

    @Test
    fun `a build warm-up rises in a straight line`() {
        val build = session("test-20").build(200).blocks.first() as WarmUp
        assertEquals("Build", build.label)
        assertEquals(120, build.targetAt(0))
        assertEquals(150, build.targetAt(300))
        assertEquals(180, build.targetAt(599))
        assertEquals("Warm-up", WarmUp(300, 110).label)
    }

    @Test
    fun `malformed library text is rejected with the session named`() {
        fun bad(blocks: String, extra: String = "") = "id: x\nname: X\nregion: Test\nminutes: 9\nmode: Dial\nwhy: y\nblocks: $blocks$extra"
        assertEquals(1, Library.parse(bad("rep 1m @100")).size)
        listOf(
            bad("rep 1m"),
            bad("rep 1m @0"),
            bad("rep 0s @100"),
            bad("3 x rep 1m @100"),
            bad("3 x [rep 1m @100"),
            bad("jog 1m @50"),
            bad("rep 10s @100 best 20s"),
            bad("ramp 100 +20W/1m"),
            bad("rep 1m @100", "\nregion: Test"),
            bad("rep 1m @100", "\nflavour: mint"),
            bad("rep 1m @100").replace("mode: Dial", "mode: Wheel"),
            bad("rep 1m @100").replace("region: Test", "region: Moon"),
        ).forEach { text ->
            val e = assertThrows(LibraryParseException::class.java) { Library.parse(text) }
            assertTrue(e.message!!, e.message!!.startsWith("x:") || e.message!!.contains("twice") || e.message!!.contains("unknown key"))
        }
        assertThrows(LibraryParseException::class.java) {
            Library.parse(bad("rep 1m @100") + "\n\n" + bad("rep 1m @100"))
        }
    }

    @Test
    fun `defaults can be replaced or dropped`() {
        val text = "id: x\nname: X\nregion: Test\nminutes: 1\nmode: Dial\nwhy: y\nwarmup: none\ncooldown: 30s @40\nblocks: rep 30s @100"
        assertEquals(listOf(RepStep(30, 100), CoolDownStep(30, 40)), Library.parse(text).single().blocks)
    }

    // ---- Riding them --------------------------------------------------------------------------

    @Test
    fun `every library session runs to the end`() {
        Library.sessions.forEach { lib ->
            val controller = SessionController(lib.build(200))
            var guard = 0
            // Ride everything at 300 W with HR 120 so every gate opens at its minimum and the
            // ramp runs until 300 W is below the step.
            while (controller.result.value == null) {
                controller.tick(300, 120, 12, 300, 90)
                check(++guard < 10_000) { "${lib.id} never finished" }
            }
            val result = controller.result.value!!
            assertTrue(lib.id, result.completed)
            assertEquals(lib.id, result.sessionId)
            assertEquals(200, result.ftpWatts)
        }
    }

    @Test
    fun `a library session's result carries its id, FTP and test`() {
        val controller = SessionController(session("test-ramp").build(210, RecoverySetting.Intermediate))
        controller.end()
        val result = controller.result.value
        assertNotNull(result)
        assertEquals("test-ramp", result!!.sessionId)
        assertEquals(210, result.ftpWatts)
        assertEquals(FtpTest.RAMP, result.ftpTest)
        assertEquals(RecoverySetting.Intermediate, result.recoverySetting)
        assertNull(result.ftpEstimate)
        assertEquals("No FTP estimate · Ended early", result.verdict)
        assertFalse(result.completed)
    }
}
