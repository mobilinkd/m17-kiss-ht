package com.mobilinkd.m17kissht;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PRBS9UnitTest {

    @Test
    public void init() {
        PRBS9 prbs = new PRBS9();
        assertEquals(false, prbs.sync());
    }

    @Test
    public void generate_first_four() {
        PRBS9 prbs = new PRBS9();
        assertEquals(prbs.generate(), 0);
        assertEquals(prbs.generate(), 0);
        assertEquals(prbs.generate(), 0);
        assertEquals(prbs.generate(), 0);
    }

    @Test
    public void generate_first_eight() {
        PRBS9 prbs = new PRBS9();
        assertEquals(prbs.generate(), 0);
        assertEquals(prbs.generate(), 0);
        assertEquals(prbs.generate(), 0);
        assertEquals(prbs.generate(), 0);
        assertEquals(prbs.generate(), 1);
        assertEquals(prbs.generate(), 0);
        assertEquals(prbs.generate(), 0);
        assertEquals(prbs.generate(), 0);
    }

    @Test
    public void generate() {
        byte[] expected = new byte[] { 0x08, (byte) 0xc2, 0x72, (byte) 0xac, 0x37, (byte) 0xa6, (byte) 0xe4, 0x50, (byte) 0xad, 0x3f, 0x64, (byte) 0x96, (byte) 0xfc, (byte) 0x9a};

        PRBS9 prbs = new PRBS9();

        for (int i = 0; i != expected.length; ++i) {
            int check = expected[i] & 0xFF;
            for (int j = 8; j != 0; --j) {
                int test = (1 << (j - 1));
                int output = prbs.generate();
                if ((check & test) == test) {
                    assertEquals(output, 1);
                } else {
                    assertEquals(output, 0);
                }
            }
        }
    }

    @Test
    public void sync() {
        PRBS9 prbs = new PRBS9();
        assertEquals(false, prbs.sync());

        byte[] input = new byte[] { 0x08, (byte) 0xc2, 0x72, (byte) 0xac, 0x37, (byte) 0xa6, (byte) 0xe4, 0x50, (byte) 0xad, 0x3f, 0x64, (byte) 0x96, (byte) 0xfc, (byte) 0x9a};

        int flag = input.length - 1;
        for (int i = 0; i != input.length; ++i) {
            int element = input[i] & 0xFF;
            int lowbit = 0;
            if (i == flag) {
                lowbit = 2;
            }
            for (int j = 8; j != lowbit; --j) {
                int test = (1 << (j - 1));
                if ((element & test) == test) {
                    prbs.validate(1);
                } else {
                    prbs.validate(0);
                }
            }
        }
        assertEquals(true, prbs.sync());
    }


    @Test
    public void validate_frame() {
        PRBS9 prbs = new PRBS9();
        assertEquals(false, prbs.sync());

        byte[] input = new byte[] {
                0x08, (byte) 0xc2, 0x72, (byte) 0xac, 0x37, (byte) 0xa6, (byte) 0xe4, 0x50,
                (byte) 0xad, 0x3f, 0x64, (byte) 0x96, (byte) 0xfc, (byte) 0x9a, (byte) 0x99,
                (byte) 0x80, (byte) 0xc6, 0x51, (byte) 0xa5, (byte) 0xfd, 0x16, 0x3a, (byte) 0xcb,
                0x3c, 0x78
        };

        assertEquals(25, input.length);
        int flag = input.length - 1;
        for (int i = 0; i != input.length; ++i) {
            int element = input[i] & 0xFF;
            int lowbit = 0;
            if (i == flag) {
                lowbit = 3;
            }
            for (int j = 8; j != lowbit; --j) {
                int test = (1 << (j - 1));
                if ((element & test) == test) {
                    prbs.validate(1);
                } else {
                    prbs.validate(0);
                }
            }
        }
        assertEquals(true, prbs.sync());
        assertEquals(197, prbs.bits());
        assertEquals(0, prbs.errors());
    }
}
