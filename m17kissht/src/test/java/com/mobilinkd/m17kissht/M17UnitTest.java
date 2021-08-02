package com.mobilinkd.m17kissht;

import com.mobilinkd.m17kissht.m17.M17Callback;
import com.mobilinkd.m17kissht.m17.M17Processor;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class M17UnitTest {

    private final M17Callback m17Callback = new M17Callback() {

        @Override
        protected void onSend(byte[] data) throws IOException {

        }

        @Override
        protected void onReceiveLinkSetup(String callsign) {

        }

        @Override
        protected void onReceiveAudio(byte[] data) {

        }

        @Override
        protected void onReceiveBERT(byte[] data) {

        }
    };

    @Test
    public void callsign_encoder() {
        byte[] expected = new byte[]{0x00, 0x00, 0x00, 0x0f, (byte) 0x8a, (byte) 0xd7};
        byte[] encoded = M17Processor.encode("WX9O");
        assertArrayEquals(expected, encoded);
    }

    @Test
    public void callsign_decoder() {
        byte[] encoded = new byte[]{0x00, 0x00, 0x00, 0x0f, (byte) 0x8a, (byte) 0xd7};
        String decoded = M17Processor.decode(encoded);
        assertEquals("WX9O", decoded);
    }

    @Test
    public void round_trip() {
        String decoded = M17Processor.decode(M17Processor.encode("WX9O"));
        assertEquals("WX9O", decoded);
    }

    @Test
    public void construct() {
        M17Processor processor = new M17Processor(m17Callback, "WX9O");
        byte[] lsf = processor.getLinkSetupFrame();
        byte[] broadcast = new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        assertArrayEquals(broadcast, Arrays.copyOfRange(lsf, 0, 6));
        byte[] encoded = new byte[]{0x00, 0x00, 0x00, 0x0f, (byte) 0x8a, (byte) 0xd7};
        assertArrayEquals(encoded, Arrays.copyOfRange(lsf, 6, 12));
    }

    @Test
    public void lich() {
        M17Processor processor = new M17Processor(m17Callback, "WX9O");
        byte[] lich = processor.getLichSegment(1);
        byte[] encoded1 = new byte[]{(byte) 0xff, 0x00, 0x00, 0x00, 0x0f};
        assertArrayEquals(encoded1, Arrays.copyOfRange(lich, 0, 5));
        assertEquals(32, lich[5]);
        lich = processor.getLichSegment(2);
        byte[] encoded2 = new byte[]{(byte) 0x8a, (byte) 0xd7, 0x05, 0x05, 0x00};
        assertArrayEquals(encoded2, Arrays.copyOfRange(lich, 0, 5));
        assertEquals(64, lich[5]);
    }

    @Test
    public void colorCode() {
        M17Processor processor = new M17Processor(m17Callback, "WX9O");
        byte[] lsf = processor.getLinkSetupFrame();
        assertEquals(0x05, lsf[12]);
        assertEquals(0x05, lsf[13]);
        processor.setColorCode(5);
        lsf = processor.getLinkSetupFrame();
        assertEquals(0x02, lsf[12]);
        assertEquals((byte) 0x85, lsf[13]);
    }
}