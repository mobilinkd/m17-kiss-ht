package com.mobilinkd.m17kissht


class PRBS9 {

    var state = 1;
    var synced = false;
    var sync_count = 0;
    var bit_count = 0;
    var err_count = 0;
    var history = ByteArray(HISTORY_SIZE);
    var hist_pos = 0;
    var hist_count = 0;

    fun generate(): Int {
        val result = ((state shr TAP_1) xor (state shr TAP_2)) and 1;
        state = ((state shl 1) or result) and MASK;
        return result;
    }

    fun validate(bit: Int): Int {
        assert(bit == 1 || bit == 0)
        var result = 0;
        if (!synced) {
            result = (bit xor (state shr TAP_1) xor (state shr TAP_2)) and 1;
            state = ((state shl 1) or bit) and MASK;
            if (result == 1) {
                sync_count = 0; // error
            } else {
                if (++sync_count == LOCK_COUNT) {
                    synced = true;
                    history.fill(0);
                    hist_count = 0;
                    hist_pos = 0;
                    sync_count = 0;
                }
            }
        } else {
            // PRBS is now free-running.
            result = this.generate()

            bit_count += 1;
            hist_count -= history[hist_pos];
            if (result != bit) {
                err_count += 1;
                hist_count += 1;
                history[hist_pos] = 1;
            } else {
                history[hist_pos] = 0
            }
            if (++hist_pos == HISTORY_SIZE) hist_pos = 0;
            if (hist_count >= UNLOCK_COUNT) synced = false;
        }

        return return if (result == bit) 0 else 1;
    }

    fun sync(): Boolean { return synced; }
    fun errors(): Int { return err_count; }
    fun bits(): Int { return bit_count; }
    fun reset() {
        state = 1;
        synced = false;
        sync_count = 0;
        bit_count = 0;
        err_count = 0;
        history.fill(0);
        hist_count = 0;
        hist_pos = 0;
    }

    companion object {
        private val MASK = 0x1FF;
        private val TAP_1 = 8;		    // Bit 9
        private val TAP_2 = 4;		    // Bit 5
        private val LOCK_COUNT = 18;    // 18 consecutive good bits.
        private val UNLOCK_COUNT = 25;  // 25 out of 128 bits bad.
        private val HISTORY_SIZE = 128;  // Number of bits to count.
    }
}
