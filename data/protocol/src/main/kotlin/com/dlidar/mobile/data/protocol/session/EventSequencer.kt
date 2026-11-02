package com.dlidar.mobile.data.protocol.session

/**
 * Checks event `seq` numbers on one connection. They count from 1; anything other than the next
 * number, a missing seq, or a `dropped` field is a gap.
 */
class EventSequencer {
    private var last = 0L

    fun check(seq: Long?, dropped: Long?): SequenceGap? {
        val expected = last + 1
        if (seq != null) last = seq
        return if (seq == expected && dropped == null) null else SequenceGap(expected, seq, dropped)
    }
}
