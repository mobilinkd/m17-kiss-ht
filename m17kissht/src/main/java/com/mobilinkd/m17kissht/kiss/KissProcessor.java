package com.mobilinkd.m17kissht.kiss;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

public class KissProcessor {

    private static final String TAG = KissProcessor.class.getSimpleName();

    private final int KISS_TX_FRAME_MAX_SIZE = 48;

    private final byte KISS_FEND = (byte)0xc0;
    private final byte KISS_FESC = (byte)0xdb;
    private final byte KISS_TFEND = (byte)0xdc;
    private final byte KISS_TFESC = (byte)0xdd;

    private final byte KISS_CMD_DATA = (byte)0x00;
    private final byte KISS_CMD_TX_DELAY = (byte)0x01;
    private final byte KISS_CMD_P = (byte)0x02;
    private final byte KISS_CMD_SLOT_TIME = (byte)0x03;
    private final byte KISS_CMD_DUPLEX = (byte)0x05;
    private final byte KISS_CMD_NOCMD = (byte)0x80;

    private final byte KISS_MODEM_STREAMING = (byte)0x20;   // This is the streaming modem ID.
    private final byte KISS_MODEM_BERT = (byte)0x30;   // This is the streaming modem ID.

    private enum KissState {
        VOID,
        GET_CMD,
        GET_DATA,
        ESCAPE
    };

    private KissState _kissState = KissState.VOID;
    private byte _kissCmd = KISS_CMD_NOCMD;

    private final byte _tncCsmaPersistence = 0x3f;          // 63 is recommended by M17 KISS Spec.
    private final byte _tncCsmaSlotTime = (byte) 0x04;      // Required by M17 KISS Spec.
    private final byte _tncTxDelay;                         // This is the only real tunable.
    private final byte _tncDuplex;                          // Controls BCL; defaults to BCL off.

    private final byte[] _outputKissBuffer;
    private final byte[] _inputKissBuffer;

    private final KissCallback _callback;

    private int _outputKissBufferPos;
    private int _inputKissBufferPos;

    public KissProcessor(KissCallback callback, byte txDelay) {
        _callback = callback;
        _outputKissBuffer = new byte[KISS_TX_FRAME_MAX_SIZE];
        _inputKissBuffer = new byte[100 * KISS_TX_FRAME_MAX_SIZE];
        _tncTxDelay = txDelay;
        _tncDuplex = 1;
        _outputKissBufferPos = 0;
        _inputKissBufferPos = 0;
    }

    public KissProcessor(KissCallback callback, byte txDelay, byte duplex) {
        _callback = callback;
        _outputKissBuffer = new byte[KISS_TX_FRAME_MAX_SIZE];
        _inputKissBuffer = new byte[100 * KISS_TX_FRAME_MAX_SIZE];
        _tncTxDelay = txDelay;
        _tncDuplex = duplex;
        _outputKissBufferPos = 0;
        _inputKissBufferPos = 0;
    }


    public void initialize() throws IOException {
/*
        startKissPacket(KISS_CMD_P);
        sendKissByte(_tncCsmaPersistence);
        completeKissPacket();

        startKissPacket(KISS_CMD_SLOT_TIME);
        sendKissByte(_tncCsmaSlotTime);
        completeKissPacket();

        startKissPacket(KISS_CMD_TX_DELAY);
        sendKissByte(_tncTxDelay);
        completeKissPacket();
*/
    }

    public void send(byte [] frame) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(frame.length * 2);
        output.write(KISS_FEND);
        output.write(KISS_MODEM_STREAMING);
        escape(frame, output);
        output.write(KISS_FEND);
        _callback.onSend(output.toByteArray());
    }

    public void receive(byte[] data) {
        for (byte b : data) {
            switch (_kissState) {
                case VOID:
                    if (b == KISS_FEND) {
                        _kissCmd = KISS_CMD_NOCMD;
                        _kissState = KissState.GET_CMD;
                    }
                    break;
                case GET_CMD:
                    if ((b & 7) == KISS_CMD_DATA) {
                        _kissCmd = b;
                        _kissState = KissState.GET_DATA;
                    } else if (b != KISS_FEND) {
                        resetState();
                    }
                    break;
                case GET_DATA:
                    if (b == KISS_FESC) {
                        _kissState = KissState.ESCAPE;
                    } else if (b == KISS_FEND) {
                        if (_kissCmd == KISS_MODEM_STREAMING) {
                            _callback.onReceive(Arrays.copyOf(_inputKissBuffer, _inputKissBufferPos));
                        } else if (_kissCmd == KISS_MODEM_BERT) {
                            _callback.onReceiveBERT(Arrays.copyOf(_inputKissBuffer, _inputKissBufferPos));
                        }
                        resetState();
                    } else {
                        receiveFrameByte(b);
                    }
                    break;
                case ESCAPE:
                    if (b == KISS_TFEND) {
                        receiveFrameByte(KISS_FEND);
                        _kissState = KissState.GET_DATA;
                    } else if (b == KISS_TFESC) {
                        receiveFrameByte(KISS_FESC);
                        _kissState = KissState.GET_DATA;
                    } else {
                        resetState();
                    }
                    break;
                default:
                    break;
            }
        }
    }

    public void flush() throws IOException{
        completeKissPacket();
    }

    private void sendKissByte(byte b) {
        _outputKissBuffer[_outputKissBufferPos++] = b;
    }

    private void receiveFrameByte(byte b) {
        _inputKissBuffer[_inputKissBufferPos++] = b;
        if (_inputKissBufferPos >= _inputKissBuffer.length) {
            Log.e(TAG, "Input KISS buffer overflow, discarding frame");
            resetState();
        }
    }

    private void resetState() {
        _kissCmd = KISS_CMD_NOCMD;
        _kissState = KissState.VOID;
        _inputKissBufferPos = 0;
    }

    private void startKissPacket(byte commandCode) throws IOException {
        sendKissByte(KISS_FEND);
        sendKissByte(commandCode);
    }

    private void completeKissPacket() throws IOException {
        if (_outputKissBufferPos > 0) {
            sendKissByte(KISS_FEND);
            _callback.onSend(Arrays.copyOf(_outputKissBuffer, _outputKissBufferPos));
            _outputKissBufferPos = 0;
        }
    }

    private void escape(byte[] inputBuffer, ByteArrayOutputStream output) {
        for (byte b : inputBuffer) {
            switch (b) {
                case KISS_FEND:
                    output.write(KISS_FESC);
                    output.write(KISS_TFEND);
                    break;
                case KISS_FESC:
                    output.write(KISS_FESC);
                    output.write(KISS_TFESC);
                    break;
                default:
                    output.write(b);
                    break;
            }
        }
    }
}
