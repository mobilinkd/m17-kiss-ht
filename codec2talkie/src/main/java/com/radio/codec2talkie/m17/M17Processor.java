package com.radio.codec2talkie.m17;

import android.util.Log;
import com.radio.codec2talkie.m17.M17Callback;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static android.webkit.ConsoleMessage.MessageLevel.LOG;

public class M17Processor {

    private CRC16 mCRC = new CRC16();
    private M17Callback mCallback;
    private byte[] mEncodedCallsign;
    private byte[] mLinkSetupFrame;
    private byte[][] mLICH = new byte[6][6];
    private int mColorCode = 0;
    private int mFrameNumber = 0;
    private int mLichCounter = 0;

    private static final boolean D = true;
    private static final String TAG = "M17Processor";

    public static byte[] encode(String callsign) {
        // Encode the characters to base-40 digits.
        long encoded = 0;
        for (int i = callsign.length(); i != 0; i--) {
            char c = callsign.charAt(i - 1);
            encoded *= 40;
            if (c >= 'A' && c <= 'Z') {
                encoded += (c - 'A') + 1;
            } else if (c >= '0' && c <= '9') {
                encoded += (c - '0') + 27;
            } else if (c == '-') {
                encoded += 37;
            } else if (c == '/') {
                encoded += 38;
            } else if (c == '.') {
                encoded += 39;
            }
        }

        // Convert the integer value to a byte array.
        byte[] output = new byte[6];
        for (int i = 0; i != 6; ++i) {
            output[i] = (byte) (encoded & 0xFF);
            encoded >>= 8;
        }

        return output;
    }

    public static String decode(byte[] encodedCallsign) {

        byte[] decoder = "xABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-/.".getBytes();

        // Convert byte array to integer value.
        long encoded = 0;
        for (int i = encodedCallsign.length; i != 0; i--) {
            encoded <<= 8;
            encoded += 0xFF & ((int) encodedCallsign[i - 1]);
        }

        assert((encoded & 0xFFFFFFFFFFFFL) == encoded);

        StringBuilder builder = new StringBuilder();
        while (encoded != 0) {
            int index = (int) (encoded % 40);
            assert(index >= 0);
            builder.append((char) decoder[index]);
            encoded /= 40;
        }

        return builder.toString();
    }

    byte[] makeLinkSetupFrame()
    {
        byte[] result = new byte[30];
        for (int i = 0; i != mEncodedCallsign.length; i++) {
            result[i] = mEncodedCallsign[i];
        }
        for (int i = 0; i != mEncodedCallsign.length; i++) {
            result[i + 6] = (byte) 0xFF;
        }
        result[12] = 0x00;
        result[13] = 0x05;
        for (int i = 14; i != 28; i++) {
            result[i] = 0x00;
        }
        mCRC.reset();
        for (int i = 0; i != 28; i++) {
            mCRC.crc(result[i]);
        }
        byte[] crc = mCRC.getBytes();
        result[28] = crc[0];
        result[29] = crc[1];

        return result;
    }

    private void makeLICH() {
        int index = 0;
        for (int i = 0; i != 6; i++) {
            for (int j = 0; j != 5; j++) {
                mLICH[i][j] = mLinkSetupFrame[index++];
            }
            mLICH[i][5] = (byte) ((i << 5) & 0xFF);
        }
    }

    public M17Processor(M17Callback callback, String callsign) {
        mCallback = callback;
        if (callsign == null) {
            mEncodedCallsign = encode("");
        } else {
            mEncodedCallsign = encode(callsign);
        }
        mLinkSetupFrame = makeLinkSetupFrame();
        makeLICH();
    }

    public void setCallsign(String callsign) {
        mEncodedCallsign = encode(callsign);
        mLinkSetupFrame = makeLinkSetupFrame();
        makeLICH();
    }

    public byte[] getLinkSetupFrame() {
        return mLinkSetupFrame;
    }

    public void setColorCode(int colorCode) {
        mColorCode = colorCode;
        makeLICH();
    }

    public byte[] getLichSegment(int index) {
        assert(index >=0 && index < 6);
        return mLICH[index];
    }

    /**
     * Send the link setup frame and initialize the frame counter.
     */
    public void startTransmit() throws IOException {
        mFrameNumber = 0;
        mLichCounter = 0;
        mCallback.onSend(mLinkSetupFrame.clone());
        if (D) Log.d(TAG, "sending link setup frame");
    }

    /**
     * Construct an audio frame, which includes a 6-byte LICH prefix, plus the 20-byte payload
     * which includes the frame number, payload, and CRC.
     *
     * @param audio is a 16-byte audio payload
     * @param frameNumber is the frame number to use.
     * @return a 26-byte M17 audio frame ready to be KISS-encoded.
     */
    private byte[] makeAudioFrame(byte[] audio, int frameNumber) {
        assert(audio.length == 16);

        byte[] frame = new byte[26];

        if (D) Log.d(TAG, String.format("sending frame %04x", frameNumber));

        System.arraycopy(mLICH[mLichCounter],0, frame, 0, 6);
        mLichCounter += 1;
        if (mLichCounter == mLICH.length) mLichCounter = 0;
        frame[6] = (byte) ((frameNumber >> 8) & 0xFF);
        frame[7] = (byte) (frameNumber & 0xFF);
        System.arraycopy(audio,0, frame, 8, 16);
        mCRC.reset();
        mCRC.crc(Arrays.copyOfRange(frame, 6, 24));
        byte[] crc = mCRC.getBytes();
        frame[24] = crc[0];
        frame[25] = crc[1];

        return frame;
    }

    /**
     * Send an M17 audio frame with the Codec2-encoded bytes.
     * @param audio 16 bytes of codec2-encoded audio (2 frames).
     * @throws IOException
     */
    public void send(byte[] audio) throws IOException {
        byte[] frame = makeAudioFrame(audio, mFrameNumber++);
        if (mFrameNumber == 0x8000) mFrameNumber = 0;
        mCallback.onSend(frame);
    }

    /**
     * Send an M17 end-of-stream frame with an empty chuck of audio.
     *
     * @throws IOException
     */
    public void stopTransmit() throws IOException {
        byte[] noAudio = new byte[16];
        byte[] frame = makeAudioFrame(noAudio, mFrameNumber | 0x8000);
        mCallback.onSend(frame);
    }

    private String parseLinkSetupFrame(byte[] frame) {
        return decode(Arrays.copyOfRange(frame, 0, 6));
    }

    public void receive(byte[] frame) {
        if (frame.length == 30) {
            String callsign = parseLinkSetupFrame(frame);
            mCallback.onReceiveLinkSetup(callsign);
        } else if (frame.length == 26) {
            // Assume everything is OK. Just extract the audio data.
            mCallback.onReceiveAudio(Arrays.copyOfRange(frame, 8,24));
        }
    }
}
