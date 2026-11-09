package com.mywatt.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControllerTest {

    /** Warm-up 10 s, one 20 s rep, a recovery that falls back after 30 s without HR, cool-down 10 s. */
    private fun short() = Session(
        "Short",
        listOf(WarmUp(10), Rep(20, 200), Recovery(HrGate(fallbackSeconds = 30)), CoolDown(10)),
    )

    private fun SessionController.ticks(n: Int, watts: Int = 100) = repeat(n) { tick(watts, null, 7) }

    @Test
    fun `pausing freezes the session where it stands`() {
        val controller = SessionController(short())
        controller.ticks(5)
        assertEquals(4, controller.state.value.secondsInBlock)

        controller.setPaused(true)
        assertTrue(controller.paused.value)
        controller.ticks(10)
        assertEquals(4, controller.state.value.secondsInBlock)
        assertEquals(WarmUp(10), controller.state.value.block)

        controller.togglePause()
        assertFalse(controller.paused.value)
        controller.ticks(1)
        assertEquals(5, controller.state.value.secondsInBlock)
    }

    @Test
    fun `ending early keeps the completed reps and ignores further ticks`() {
        val controller = SessionController(short())
        controller.ticks(10)
        controller.ticks(20, watts = 200)
        controller.ticks(5)
        assertEquals(Recovery(HrGate(fallbackSeconds = 30)), controller.state.value.block)
        assertNull(controller.result.value)

        controller.end()
        val result = controller.result.value
        assertNotNull(result)
        assertTrue(controller.ended)
        assertFalse(result!!.completed)
        assertEquals(listOf(200), result.reps.map { it.averageWatts })
        assertEquals("All 1 reps on target · Ended early", result.verdict)

        val frozen = controller.state.value
        controller.ticks(50)
        assertEquals(frozen, controller.state.value)
        controller.end()
        assertEquals(result, controller.result.value)
    }

    @Test
    fun `the result appears by itself when the cool-down finishes`() {
        val controller = SessionController(short())
        controller.ticks(10)
        controller.ticks(20, watts = 200)
        controller.ticks(30)
        controller.ticks(10)
        assertNull(controller.result.value)
        assertFalse(controller.state.value.finished)

        controller.ticks(1)
        val result = controller.result.value
        assertNotNull(result)
        assertTrue(result!!.completed)
        assertTrue(controller.state.value.finished)
        assertEquals(listOf(30), result.reps.map { it.recoverySeconds })
        assertEquals("All 1 reps on target · Recovery 30 s", result.verdict)
    }

    @Test
    fun `pause is refused once the session has ended`() {
        val controller = SessionController(short())
        controller.end()
        controller.setPaused(true)
        assertFalse(controller.paused.value)
    }
}
