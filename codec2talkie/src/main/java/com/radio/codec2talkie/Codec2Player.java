package com.radio.codec2talkie;

import android.bluetooth.BluetoothSocket;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedTransferQueue;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.radio.codec2talkie.kiss.KissCallback;
import com.radio.codec2talkie.kiss.KissProcessor;
import com.radio.codec2talkie.m17.M17Callback;
import com.radio.codec2talkie.m17.M17Processor;
import com.ustadmobile.codec2.Codec2;

import static android.os.Process.THREAD_PRIORITY_AUDIO;
import static android.os.Process.setThreadPriority;

public class Codec2Player extends Thread {

    private static final String TAG = Codec2Player.class.getSimpleName();

    public static int PLAYER_DISCONNECT = 1;
    public static int PLAYER_LISTENING = 2;
    public static int PLAYER_RECORDING = 3;
    public static int PLAYER_PLAYING = 4;
    public static int PLAYER_RX_LEVEL = 5;
    public static int PLAYER_TX_LEVEL = 6;
    public static int PLAYER_CALLSIGN_RECEIVED = 7;

    private static int AUDIO_MIN_LEVEL = -70;
    private static int AUDIO_HIGH_LEVEL = -15;

    private final int AUDIO_SAMPLE_RATE = 8000;
    private final int SLEEP_IDLE_DELAY_MS = 20;
    private final int POST_PLAY_DELAY_MS = 400;

    private final int RX_TIMEOUT = 100;
    private final int TX_TIMEOUT = 2000;

    private final byte TX_DELAY_10MS_UNITS = (byte)8;

    private final int RX_BUFFER_SIZE = 8192;

    private long _codec2Con;

    private BluetoothSocket _btSocket;
    private UsbSerialPort _usbPort;

    private int _audioBufferSize;
    private int _audioEncodedBufferSize;

    private boolean _isRunning = true;
    private boolean _isRecording = false;
    private int _currentStatus = PLAYER_DISCONNECT;

    // input data, bt -> audio
    private InputStream _btInputStream;

    private final AudioTrack _audioPlayer;

    private short[] _playbackAudioBuffer;

    // output data., mic -> bt
    private OutputStream _btOutputStream;

    private final AudioRecord _audioRecorder;

    private final byte[] _rxDataBuffer;
    private short[] _recordAudioBuffer;
    private char[] _recordAudioEncodedBuffer;

    // loopback mode
    private boolean _isLoopbackMode;
    private ByteBuffer _loopbackBuffer;

    // callbacks
    private M17Processor _m17Processor;
    private KissProcessor _kissProcessor;
    private final Handler _onPlayerStateChanged;

    private String _callsign;

    public Codec2Player(Handler onPlayerStateChanged, int codec2Mode, String callsign) {
        _onPlayerStateChanged = onPlayerStateChanged;
        _isLoopbackMode = false;
        _rxDataBuffer = new byte[RX_BUFFER_SIZE];
        _callsign = callsign;

        setCodecModeInternal(codec2Mode);

        int _audioRecorderMinBufferSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        _audioRecorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(_audioRecorderMinBufferSize * 5, 320));
        _audioRecorder.startRecording();

        int _audioPlayerMinBufferSize = AudioTrack.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        _audioPlayer = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(10 * _audioPlayerMinBufferSize)
                .build();
        _audioPlayer.play();
    }

    public void setSocket(BluetoothSocket btSocket) throws IOException {
        _btSocket = btSocket;
        _btInputStream = _btSocket.getInputStream();
        _btOutputStream = _btSocket.getOutputStream();
    }

    public void setUsbPort(UsbSerialPort port) {
        _usbPort = port;
    }

    public void setLoopbackMode(boolean isLoopbackMode) {
        _isLoopbackMode = isLoopbackMode;
    }

    public void setCodecMode(int codecMode) {
        Codec2.destroy(_codec2Con);
        setCodecModeInternal(codecMode);
    }

    public static int getAudioMinLevel() {
        return AUDIO_MIN_LEVEL;
    }

    public static int getAudioHighLevel() {
        return AUDIO_HIGH_LEVEL;
    }

    public void startPlayback() {
        _isRecording = false;
    }

    public void startRecording() {
        _isRecording = true;
    }

    public void stopRunning() {
        _isRunning = false;
    }

    private void setCodecModeInternal(int codecMode) {
        _codec2Con = Codec2.create(codecMode);

        _audioBufferSize = Codec2.getSamplesPerFrame(_codec2Con);
        _audioEncodedBufferSize = Codec2.getBitsSize(_codec2Con); // returns number of bytes

        _recordAudioBuffer = new short[_audioBufferSize];
        _recordAudioEncodedBuffer = new char[_audioEncodedBufferSize];

        _playbackAudioBuffer = new short[_audioBufferSize];

        _loopbackBuffer = ByteBuffer.allocateDirect(1024 * _audioEncodedBufferSize);

        _m17Processor = new M17Processor(_m17Callback, _callsign);
        _kissProcessor = new KissProcessor(_kissCallback, TX_DELAY_10MS_UNITS);
    }

    private final M17Callback _m17Callback = new M17Callback() {
        Timer timer = null;
        LinkedTransferQueue<byte[]> queue = new LinkedTransferQueue<byte[]>();
        boolean timer_active = false;
        boolean primed = false;

        @Override
        protected void onSend(byte[] data) throws IOException {
            synchronized (queue) {
                if (!timer_active) {
                    primed = false;
                    timer = new Timer();
                    TimerTask task = new TimerTask() {
                        @Override
                        public void run() {
                            int count = 1;
                            if (!primed) {
                                count = 7;
                                primed = true;
                            }
                            for (int i = 0; i != count; ++i) {
                                byte[] data = queue.poll();
                                if (data == null) {
                                    Log.d("M17Callback", String.format("queue empty; cancelled."));
                                    timer.cancel();
                                    timer_active = false;
                                } else {
                                    try {
                                        _kissProcessor.send(data);
//                                    Log.d("M17Callback", String.format("Sent %d bytes", data.length));
                                    } catch (IOException ex) {
                                        Log.e("M17Callback", "Exception " + ex.toString());
                                    }
                                }
                            }
                        }
                    };

                    timer_active = true;
                    timer.schedule(task, 400, 40);
                }
            }
            queue.add(data);
        }

        @Override
        protected void onReceiveAudio(byte[] data) {
            // split by audio frame and play
            byte [] audioFrame = new byte[_audioEncodedBufferSize];
            for (int i = 0; i < data.length; i += _audioEncodedBufferSize) {
                for (int j = 0; j < _audioEncodedBufferSize && (j + i) < data.length; j++)
                    audioFrame[j] = data[i + j];
                decodeAndPlayAudio(audioFrame);
            }
        }

        @Override
        protected void onReceiveLinkSetup(String callsign) {
            _onPlayerStateChanged.obtainMessage(PLAYER_CALLSIGN_RECEIVED, 0, 0, callsign).sendToTarget();
        }

    };

    private final KissCallback _kissCallback = new KissCallback() {
        @Override
        protected void onSend(byte[] data) throws IOException {
            sendRawDataToModem(data);
        }

        @Override
        protected void onReceive(byte[] data) {
            _m17Processor.receive(data);
        }
    };

    private void sendRawDataToModem(byte[] data) throws IOException {
        if (_isLoopbackMode) {
            try {
                _loopbackBuffer.put(data);
            } catch (BufferOverflowException e) {
                e.printStackTrace();
            }
        } else {
            if (_btOutputStream != null) {
                _btOutputStream.write(data);
                _btOutputStream.flush();
            } else if (_usbPort != null) {
                _usbPort.write(data, TX_TIMEOUT);
            }
        }
    }

    private void decodeAndPlayAudio(byte[] data) {
        Codec2.decode(_codec2Con, _playbackAudioBuffer, data);
        _audioPlayer.write(_playbackAudioBuffer, 0, _audioBufferSize);
        notifyAudioLevel(_playbackAudioBuffer, false);
    }

    private void notifyAudioLevel(short [] pcmAudioSamples, boolean isTx) {
        double db = getAudioMinLevel();
        if (pcmAudioSamples != null) {
            double acc = 0;
            for (short v : pcmAudioSamples) {
                acc += Math.abs(v);
            }
            double avg = acc / pcmAudioSamples.length;
            db = (20.0 * Math.log10(avg / 32768.0));
        }
        Message msg = Message.obtain();
        if (isTx)
            msg.what = PLAYER_TX_LEVEL;
        else
            msg.what = PLAYER_RX_LEVEL;
        msg.arg1 = (int)db;
        _onPlayerStateChanged.sendMessage(msg);
    }

    private boolean processLoopbackPlayback() {
        try {
            byte [] ba  = new byte[1];
            _loopbackBuffer.get(ba);
            _kissProcessor.receive(ba);
            return true;
        } catch (BufferUnderflowException e) {
            return false;
        }
    }

    private void recordAudio() throws IOException {
        boolean sendLSF = _currentStatus != PLAYER_RECORDING;
        setStatus(PLAYER_RECORDING, 0);
        notifyAudioLevel(_recordAudioBuffer, true);

        char[] temp = new char[16];

        _audioRecorder.read(_recordAudioBuffer, 0, _audioBufferSize);
        Codec2.encode(_codec2Con, _recordAudioBuffer, _recordAudioEncodedBuffer);
        System.arraycopy(_recordAudioEncodedBuffer, 0, temp, 0, _audioEncodedBufferSize);

        _audioRecorder.read(_recordAudioBuffer, 0, _audioBufferSize);
        Codec2.encode(_codec2Con, _recordAudioBuffer, _recordAudioEncodedBuffer);
        System.arraycopy(_recordAudioEncodedBuffer, 0, temp, _audioEncodedBufferSize, _audioEncodedBufferSize);

        byte[] frame = new byte[16];
        for (int i = 0; i != frame.length; i++) frame[i] = (byte) temp[i];

        if (sendLSF) _m17Processor.startTransmit();

        _m17Processor.send(frame);
    }

    private boolean playAudio() throws IOException {
        if (_isLoopbackMode) {
            return processLoopbackPlayback();
        }
        int bytesRead = 0;
        if (_btInputStream != null) {
            bytesRead = _btInputStream.available();
            if (bytesRead > 0) {
                bytesRead = _btInputStream.read(_rxDataBuffer);
            }
        }
        else if (_usbPort != null) {
            bytesRead = _usbPort.read(_rxDataBuffer, RX_TIMEOUT);
        }
        if (bytesRead > 0) {
            setStatus(PLAYER_PLAYING, 0);
            _kissProcessor.receive(Arrays.copyOf(_rxDataBuffer, bytesRead));
            return true;
        }
        return false;
    }

    private void toggleRecording() {
        _audioRecorder.startRecording();
        _audioPlayer.stop();
        _loopbackBuffer.clear();
        notifyAudioLevel(null, false);
    }

    private void togglePlayback() throws IOException {
        _m17Processor.stopTransmit();
        _audioRecorder.stop();
        _audioPlayer.play();
        _kissProcessor.flush();
        _loopbackBuffer.flip();
        notifyAudioLevel(null, true);
    }

    private void processRecordPlaybackToggle() throws IOException {
        // playback -> recording
        if (_isRecording && _audioRecorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            toggleRecording();
        }
        // recording -> playback
        if (!_isRecording && _audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            togglePlayback();
        }
    }

    private void cleanup() {
        try {
            _kissProcessor.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

        _audioRecorder.stop();
        _audioRecorder.release();

        _audioPlayer.stop();
        _audioPlayer.release();

        Codec2.destroy(_codec2Con);

        if (_btSocket != null) {
            try {
                _btSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (_usbPort != null) {
            try {
                _usbPort.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void setStatus(int status, int delayMs) {
        if (status != _currentStatus) {
            _currentStatus = status;
            Message msg = Message.obtain();
            msg.what = status;
            _onPlayerStateChanged.sendMessageDelayed(msg, delayMs);
        }
    }

    @Override
    public void run() {
        setThreadPriority(THREAD_PRIORITY_AUDIO);
        try {
            setStatus(PLAYER_LISTENING, 0);
            if (!_isLoopbackMode) {
                _kissProcessor.initialize();
            }
            while (_isRunning) {
                processRecordPlaybackToggle();

                // recording
                if (_audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    recordAudio();
                } else {
                    // playback
                    if (!playAudio()) {
                        // idling
                        try {
                            if (_currentStatus != PLAYER_LISTENING) {
                                notifyAudioLevel(null, false);
                                notifyAudioLevel(null, true);
                            }
                            setStatus(PLAYER_LISTENING, POST_PLAY_DELAY_MS);
                            Thread.sleep(SLEEP_IDLE_DELAY_MS);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        setStatus(PLAYER_DISCONNECT, 0);
        cleanup();
    }
}
