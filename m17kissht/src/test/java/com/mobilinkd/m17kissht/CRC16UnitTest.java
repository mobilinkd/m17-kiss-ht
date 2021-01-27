package com.mobilinkd.m17kissht;

import com.mobilinkd.m17kissht.m17.CRC16;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class CRC16UnitTest {

    @Test
    public void empyt() {
        CRC16 crc = new CRC16();
        assertEquals(0xFFFF, crc.get());
    }

    @Test
    public void letter_A() {
        CRC16 crc = new CRC16();
        crc.crc("A".getBytes());
        assertEquals(0x206E, crc.get());
    }

    @Test
    public void numbers() {
        CRC16 crc = new CRC16();
        crc.crc("123456789".getBytes());
        assertEquals(0x772B, crc.get());
    }

    @Test
    public void all() {
        CRC16 crc = new CRC16();
        for (int i = 0; i != 256; ++i) {
            crc.crc(new byte[]{(byte) i});
        }
        assertEquals(0x1C31, crc.get());
    }
}