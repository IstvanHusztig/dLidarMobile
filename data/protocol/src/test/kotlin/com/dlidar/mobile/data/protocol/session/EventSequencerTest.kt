package com.dlidar.mobile.data.protocol.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventSequencerTest {

    private val sequencer = EventSequencer()

    @Test
    fun `consecutive seq numbers from 1 are no gap`() {
        for (seq in 1L..5L) assertNull(sequencer.check(seq, null))
    }

    @Test
    fun `a jump is a gap and checking continues from the new number`() {
        sequencer.check(1, null)
        assertEquals(SequenceGap(expected = 2, actual = 4, dropped = null), sequencer.check(4, null))
        assertNull(sequencer.check(5, null))
    }

    @Test
    fun `a first event other than 1 is a gap`() {
        assertEquals(SequenceGap(expected = 1, actual = 3, dropped = null), sequencer.check(3, null))
    }

    @Test
    fun `dropped is a gap even when seq is continuous`() {
        sequencer.check(1, null)
        assertEquals(SequenceGap(expected = 2, actual = 2, dropped = 3), sequencer.check(2, 3))
    }

    @Test
    fun `a missing seq is a gap`() {
        assertEquals(SequenceGap(expected = 1, actual = null, dropped = null), sequencer.check(null, null))
    }

    @Test
    fun `a repeated seq is a gap`() {
        sequencer.check(1, null)
        sequencer.check(2, null)
        assertEquals(SequenceGap(expected = 3, actual = 2, dropped = null), sequencer.check(2, null))
    }
}
