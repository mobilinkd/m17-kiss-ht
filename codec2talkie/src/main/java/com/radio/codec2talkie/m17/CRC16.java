package com.radio.codec2talkie.m17;

public class CRC16 {

    private int mPoly;
    private int mInit;
    private int mReg;
    
    private int mMask = 0xFFFF;
    private int mMsb = 0x8000;

    public CRC16(int poly, int init) {
        mPoly = poly;
        mInit = init;
        reset();
    }

    public CRC16() {
        mPoly = 0x5935;
        mInit = 0xFFFF;
        reset();
    }

    public void reset() {
        mReg = mInit;
        for (int i = 0; i != 16; ++i) {
            int bit = mReg & 0x01;
            if (bit != 0) {
                mReg ^= mPoly;
            }
            mReg >>= 1;
            if (bit != 0) {
                mReg |= mMsb;
            }
        }
        mReg &= mMask;
    }

    public void crc(byte data) {
        int b = 0xFF & (int) data;
        for (int j = 0; j != 8; j++) {
            int msb = mReg & mMsb;
            mReg = ((mReg << 1) & mMask) | ((b >> (7 - j)) & 0x01);
            if (msb != 0) {
                mReg ^= mPoly;
            }
        }
    }

    public void crc(byte[] data) {
        for (int i = 0; i != data.length; i++) {
            int b = 0xFF & (int) data[i];
            for (int j = 0; j != 8; j++) {
                int msb = mReg & mMsb;
                mReg = ((mReg << 1) & mMask) | ((b >> (7 - j)) & 0x01);
                if (msb != 0) {
                    mReg ^= mPoly;
                }
            }
        }
    }

    public int get() {
        int reg = mReg;
        for (int i = 0; i != 16; ++i) {
            int msb = reg & mMsb;
            reg = ((reg << 1) & mMask);
            if (msb != 0) {
                reg ^= mPoly;
            }
        }
        return reg & mMask;
    }

    public byte[] getBytes() {
        int crc = get();
        byte[] result = new byte[]{(byte) ((crc >> 8) & 0xFF), (byte) (crc & 0xFF)};
        return result;
    }
}
