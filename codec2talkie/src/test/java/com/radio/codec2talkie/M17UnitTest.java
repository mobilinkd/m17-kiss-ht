package com.radio.codec2talkie;

import com.radio.codec2talkie.kiss.KissCallback;
import com.radio.codec2talkie.m17.M17Callback;
import com.radio.codec2talkie.m17.M17Processor;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.*;

public class M17UnitTest {

    private final M17Callback m17Callback = new M17Callback() {
        @Override
        protected void onStartTransmit() throws IOException {

        }

        @Override
        protected void onSend(byte[] data) throws IOException {

        }

        @Override
        protected void onStopTransmit() throws IOException {

        }

        @Override
        protected void onReceive(byte[] data) {

        }
    };

    @Test
    public void callsign_encoder() {
        byte[] expected = new byte[]{(byte) 0xd7, (byte) 0x8a, 0x0f, 0x00, 0x00, 0x00};
        byte[] encoded = M17Processor.encode("WX9O");
        assertArrayEquals(expected, encoded);
    }

    @Test
    public void callsign_decoder() {
        byte[] encoded = new byte[]{(byte) 0xd7, (byte) 0x8a, 0x0f, 0x00, 0x00, 0x00};
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
        byte[] encoded = new byte[]{(byte) 0xd7, (byte) 0x8a, 0x0f, 0x00, 0x00, 0x00};
        assertArrayEquals(encoded, Arrays.copyOfRange(lsf, 0, 6));
    }

    @Test
    public void lich() {
        M17Processor processor = new M17Processor(m17Callback, "WX9O");
        byte[] lich = processor.getLichSegment(0);
        byte[] encoded = new byte[]{(byte) 0xd7, (byte) 0x8a, 0x0f, 0x00, 0x00};
        assertArrayEquals(encoded, Arrays.copyOfRange(lich, 0, 5));
        assertEquals(0, lich[5]);
    }

    @Test
    public void colorCode() {
        M17Processor processor = new M17Processor(m17Callback, "WX9O");
        processor.setColorCode(5);
        byte[] lich = processor.getLichSegment(0);
        byte[] encoded = new byte[]{(byte) 0xd7, (byte) 0x8a, 0x0f, 0x00, 0x00};
        assertArrayEquals(encoded, Arrays.copyOfRange(lich, 0, 5));
        assertEquals(5, lich[5]);
    }
}