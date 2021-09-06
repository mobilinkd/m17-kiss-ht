package com.mobilinkd.m17kissht.m17

import android.util.Log
import java.io.IOException
import java.lang.StringBuilder
import kotlin.Throws

class M17Processor(private val mCallback: M17Callback, callsign: String?) {
    private val mCRC = CRC16()
    private var mEncodedCallsign: ByteArray
    private var mEncodedDestination = encode("BROADCAST")
    var linkSetupFrame: ByteArray
        private set
    private val mLICH = Array(6) { ByteArray(6) }
    private var mChannelAccessNumber = 10
    private var mFrameNumber = 0
    private var mLichCounter = 0
    private fun makeLinkSetupFrame(): ByteArray {
        val result = ByteArray(30)
        // Destination
        for (i in mEncodedDestination.indices) result[i] = mEncodedDestination[i]
        // Source (our callsign)
        for (i in mEncodedCallsign.indices) result[i + 6] = mEncodedCallsign[i]
        result[12] = (mChannelAccessNumber shr 1).toByte()
        result[13] = (0x05 or ((mChannelAccessNumber shl 7) and 0xff)).toByte()
        for (i in 14..27) {
            result[i] = 0x00
        }
        mCRC.reset()
        for (i in 0..27) {
            mCRC.crc(result[i])
        }
        val crc = mCRC.bytes
        result[28] = crc[0]
        result[29] = crc[1]
        return result
    }

    private fun makeLICH() {
        var index = 0
        for (i in 0..5) {
            for (j in 0..4) {
                mLICH[i][j] = linkSetupFrame[index++]
            }
            mLICH[i][5] = (i shl 5 and 0xFF).toByte()
        }
    }

    fun setCallsign(callsign: String) {
        mEncodedCallsign = encode(callsign)
        linkSetupFrame = makeLinkSetupFrame()
        makeLICH()
    }

    fun setDestination(callsign: String) {
        mEncodedDestination = encode(callsign)
        linkSetupFrame = makeLinkSetupFrame()
        makeLICH()
    }

    fun setChannelAccessNumber(can: Int) {
        if (can in 0..15) {
            mChannelAccessNumber = can
            linkSetupFrame = makeLinkSetupFrame()
            makeLICH()
        }
    }

    fun getLichSegment(index: Int): ByteArray {
        assert(index in 0..5)
        return mLICH[index]
    }

    /**
     * Send the link setup frame and initialize the frame counter.
     */
    @Throws(IOException::class)
    fun startTransmit() {
        mFrameNumber = 0
        mLichCounter = 0
        mCallback.onSend(linkSetupFrame.clone())
        if (D) Log.d(TAG, "sending link setup frame")
    }

    /**
     * Construct an audio frame, which includes a 6-byte LICH prefix, plus the 20-byte payload
     * which includes the frame number, payload, and CRC.
     *
     * @param audio is a 16-byte audio payload
     * @param frameNumber is the frame number to use.
     * @return a 26-byte M17 audio frame ready to be KISS-encoded.
     */
    private fun makeAudioFrame(audio: ByteArray, frameNumber: Int): ByteArray {
        assert(audio.size == 16)
        val frame = ByteArray(24)
        if (D) Log.d(TAG, String.format("sending frame %04x", frameNumber))
        System.arraycopy(mLICH[mLichCounter], 0, frame, 0, 6)
        mLichCounter += 1
        if (mLichCounter == mLICH.size) mLichCounter = 0
        frame[6] = (frameNumber shr 8 and 0xFF).toByte()
        frame[7] = (frameNumber and 0xFF).toByte()
        System.arraycopy(audio, 0, frame, 8, 16)
        return frame
    }

    /**
     * Send an M17 audio frame with the Codec2-encoded bytes.
     * @param audio 16 bytes of codec2-encoded audio (2 frames).
     * @throws IOException when the connection is not available.
     */
    @Throws(IOException::class)
    fun send(audio: ByteArray) {
        val frame = makeAudioFrame(audio, mFrameNumber++)
        if (mFrameNumber == 0x8000) mFrameNumber = 0
        mCallback.onSend(frame)
    }

    /**
     * Send an M17 end-of-stream frame with an empty chuck of audio.
     *
     * @throws IOException when the connection is not available.
     */
    @Throws(IOException::class)
    fun stopTransmit() {
        val noAudio = ByteArray(16)
        val frame = makeAudioFrame(noAudio, mFrameNumber or 0x8000)
        mCallback.onSend(frame)
    }

    private fun parseLinkSetupFrame(frame: ByteArray): String {
        return decode(frame.copyOfRange(6, 12))
    }

    fun receive(frame: ByteArray) {
        when (frame.size) {
            30 -> {
                val callsign = parseLinkSetupFrame(frame)
                mCallback.onReceiveLinkSetup(callsign)
            }
            26 -> {
                mCallback.onReceiveRSSI(frame[25].toInt() and 0xFF)
                mCallback.onReceiveAudio(frame.copyOfRange(8, 24))
            }
            24 -> {
                // Extract the audio data.
                mCallback.onReceiveAudio(frame.copyOfRange(8, 24))
            }
        }
    }

    fun receiveBERT(frame: ByteArray?) {
        mCallback.onReceiveBERT(frame)
    }

    companion object {
        private const val D = true
        private const val TAG = "M17Processor"
        @JvmStatic
        fun encode(callsign: String): ByteArray {
            if (callsign == "BROADCAST") {
                val output = ByteArray(6)
                for (i in 0..5) {
                    output[i] = 0xFF.toByte()
                }
                return output
            }

            // Encode the characters to base-40 digits.
            var encoded: Long = 0
            for (i in callsign.length downTo 1) {
                val c = callsign[i - 1]
                encoded *= 40
                if (c in 'A'..'Z') {
                    encoded += (c - 'A' + 1).toLong()
                } else if (c in '0'..'9') {
                    encoded += (c - '0' + 27).toLong()
                } else if (c == '-') {
                    encoded += 37
                } else if (c == '/') {
                    encoded += 38
                } else if (c == '.') {
                    encoded += 39
                }
            }

            // Convert the integer value to a byte array.
            val output = ByteArray(6)
            for (i in 6 downTo 1) {
                output[i - 1] = (encoded and 0xFF).toByte()
                encoded = encoded shr 8
            }
            return output
        }

        @JvmStatic
        fun decode(encodedCallsign: ByteArray): String {
            val decoder = "xABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-/.".toByteArray()

            // Convert byte array to integer value.
            var encoded: Long = 0
            for (i in encodedCallsign.indices) {
                encoded = encoded shl 8
                encoded += (0xFF and encodedCallsign[i].toInt()).toLong()
            }
            assert(encoded and 0xFFFFFFFFFFFFL == encoded)
            val builder = StringBuilder()
            while (encoded != 0L) {
                val index = (encoded % 40).toInt()
                builder.append(decoder[index].toChar())
                encoded /= 40
            }
            return builder.toString()
        }
    }

    init {
        mEncodedCallsign = if (callsign == null) {
            encode("")
        } else {
            encode(callsign)
        }
        linkSetupFrame = makeLinkSetupFrame()
        makeLICH()
    }
}