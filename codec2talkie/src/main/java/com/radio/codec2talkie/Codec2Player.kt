package com.radio.codec2talkie

import android.content.*
import android.media.*
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Process
import android.util.Log
import android.widget.Toast
import com.felhr.usbserial.UsbSerialDevice
import com.radio.codec2talkie.bluetooth.ACTION_DATA_AVAILABLE
import com.radio.codec2talkie.bluetooth.BluetoothLEService
import com.radio.codec2talkie.kiss.KissCallback
import com.radio.codec2talkie.kiss.KissProcessor
import com.radio.codec2talkie.m17.M17Callback
import com.radio.codec2talkie.m17.M17Processor
import com.ustadmobile.codec2.Codec2
import java.io.IOException
import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.LinkedTransferQueue

class Codec2Player(private val _onPlayerStateChanged: Handler, codec2Mode: Int, callsign: String) : Thread() {
    private val AUDIO_SAMPLE_RATE = 8000
    private val SLEEP_IDLE_DELAY_MS = 20
    private val POST_PLAY_DELAY_MS = 400
    private val RX_TIMEOUT = 100
    private val TX_TIMEOUT = 2000
    private val TX_DELAY_10MS_UNITS = 8.toByte()
    private val RX_BUFFER_SIZE = 8192
    private var _codec2Con: Long = 0
    private var _usbPort: UsbSerialDevice? = null
    private var _audioBufferSize = 0
    private var _audioEncodedBufferSize = 0
    private var _isRunning = true
    private var _isRecording = false
    private var _currentStatus = PLAYER_DISCONNECT
    private val _audioPlayer: AudioTrack
    private var _playbackAudioBuffer: ShortArray? = null
    private val _audioRecorder: AudioRecord
    private val _rxDataBuffer: ByteArray
    private var _recordAudioBuffer: ShortArray? = null
    private var _recordAudioEncodedBuffer: CharArray? = null

    // loopback mode
    private var _isLoopbackMode = false
    private var _loopbackBuffer: ByteBuffer? = null

    // callbacks
    private var _m17Processor: M17Processor? = null
    private var _kissProcessor: KissProcessor? = null
    private val _callsign: String
    fun setUsbPort(port: UsbSerialDevice?) {
        _usbPort = port
    }

    fun setLoopbackMode(isLoopbackMode: Boolean) {
        _isLoopbackMode = isLoopbackMode
    }

    fun setCodecMode(codecMode: Int) {
        Codec2.destroy(_codec2Con)
        setCodecModeInternal(codecMode)
    }

    fun setCallsign(callsign: String?) {
        _m17Processor!!.setCallsign(callsign)
    }

    fun startPlayback() {
        _isRecording = false
    }

    fun startRecording() {
        _isRecording = true
    }

    fun stopRunning() {
        _isRunning = false
    }


    private var mService: BluetoothLEService? = null

    fun setBleService(service: BluetoothLEService) {
        mService = service
    }

    private fun setCodecModeInternal(codecMode: Int) {
        _codec2Con = Codec2.create(codecMode)
        _audioBufferSize = Codec2.getSamplesPerFrame(_codec2Con)
        _audioEncodedBufferSize = Codec2.getBitsSize(_codec2Con) // returns number of bytes
        _recordAudioBuffer = ShortArray(_audioBufferSize)
        _recordAudioEncodedBuffer = CharArray(_audioEncodedBufferSize)
        _playbackAudioBuffer = ShortArray(_audioBufferSize)
        _loopbackBuffer = ByteBuffer.allocateDirect(1024 * _audioEncodedBufferSize)
        _m17Processor = M17Processor(_m17Callback, _callsign)
        _kissProcessor = KissProcessor(_kissCallback, TX_DELAY_10MS_UNITS)
    }

    private val _m17Callback: M17Callback = object : M17Callback() {
        var timer: Timer? = null
        var queue = LinkedTransferQueue<ByteArray>()
        var timer_active = false
        var primed = false
        @Throws(IOException::class)
        override fun onSend(data: ByteArray) {
            synchronized(queue) {
                if (!timer_active) {
                    primed = false
                    timer = Timer()
                    val task: TimerTask = object : TimerTask() {
                        override fun run() {
                            var count = 1
                            if (!primed) {
                                if (mService != null) count = 7
                                primed = true
                            }
                            for (i in 0 until count) {
                                val data = queue.poll()
                                if (data == null) {
                                    Log.d("M17Callback", String.format("queue empty; cancelled."))
                                    timer!!.cancel()
                                    timer_active = false
                                } else {
                                    try {
                                        _kissProcessor!!.send(data)
                                        //                                    Log.d("M17Callback", String.format("Sent %d bytes", data.length));
                                    } catch (ex: IOException) {
                                        Log.e("M17Callback", "Exception $ex")
                                    }
                                }
                            }
                        }
                    }
                    timer_active = true
                    timer!!.schedule(task, 400, 40)
                }
            }
            queue.add(data)
        }

        override fun onReceiveAudio(data: ByteArray) {
            // split by audio frame and play
            val audioFrame = ByteArray(_audioEncodedBufferSize)
            var i = 0
            while (i < data.size) {
                var j = 0
                while (j < _audioEncodedBufferSize && j + i < data.size) {
                    audioFrame[j] = data[i + j]
                    j++
                }
                decodeAndPlayAudio(audioFrame)
                i += _audioEncodedBufferSize
            }
        }

        override fun onReceiveLinkSetup(callsign: String) {
            _onPlayerStateChanged.obtainMessage(PLAYER_CALLSIGN_RECEIVED, 0, 0, callsign).sendToTarget()
        }
    }
    private val _kissCallback: KissCallback = object : KissCallback() {
        @Throws(IOException::class)
        override fun onSend(data: ByteArray) {
            sendRawDataToModem(data)
        }

        override fun onReceive(data: ByteArray) {
            _m17Processor!!.receive(data)
        }
    }

    @Throws(IOException::class)
    private fun sendRawDataToModem(data: ByteArray) {
        if (_isLoopbackMode) {
            try {
                _loopbackBuffer!!.put(data)
            } catch (e: BufferOverflowException) {
                e.printStackTrace()
            }
        } else {
            if (mService != null) {
                if (!mService!!.write(data)) {
                    Log.e(TAG, "BLE write failed")
                }
            } else if (_usbPort != null) {
                _usbPort!!.syncWrite(data, TX_TIMEOUT)
            } else {
                if (D) Log.d(TAG, "Dropping sent data")
            }
        }
    }

    private fun decodeAndPlayAudio(data: ByteArray) {
        Codec2.decode(_codec2Con, _playbackAudioBuffer, data)
        _audioPlayer.write(_playbackAudioBuffer!!, 0, _audioBufferSize)
        notifyAudioLevel(_playbackAudioBuffer, false)
    }

    private fun notifyAudioLevel(pcmAudioSamples: ShortArray?, isTx: Boolean) {
        var db = audioMinLevel.toDouble()
        if (pcmAudioSamples != null) {
            var acc = 0.0
            for (v in pcmAudioSamples) {
                acc += Math.abs(v.toInt()).toDouble()
            }
            val avg = acc / pcmAudioSamples.size
            db = 20.0 * Math.log10(avg / 32768.0)
        }
        val msg = Message.obtain()
        if (isTx) msg.what = PLAYER_TX_LEVEL else msg.what = PLAYER_RX_LEVEL
        msg.arg1 = db.toInt()
        _onPlayerStateChanged.sendMessage(msg)
    }

    private fun processLoopbackPlayback(): Boolean {
        return try {
            val ba = ByteArray(1)
            _loopbackBuffer!![ba]
            _kissProcessor!!.receive(ba)
            true
        } catch (e: BufferUnderflowException) {
            false
        }
    }

    @Throws(IOException::class)
    private fun recordAudio() {
        val sendLSF = _currentStatus != PLAYER_RECORDING
        setStatus(PLAYER_RECORDING, 0)
        notifyAudioLevel(_recordAudioBuffer, true)
        val temp = CharArray(16)
        _audioRecorder.read(_recordAudioBuffer!!, 0, _audioBufferSize)
        Codec2.encode(_codec2Con, _recordAudioBuffer, _recordAudioEncodedBuffer)
        System.arraycopy(_recordAudioEncodedBuffer!!, 0, temp, 0, _audioEncodedBufferSize)
        _audioRecorder.read(_recordAudioBuffer!!, 0, _audioBufferSize)
        Codec2.encode(_codec2Con, _recordAudioBuffer, _recordAudioEncodedBuffer)
        System.arraycopy(_recordAudioEncodedBuffer, 0, temp, _audioEncodedBufferSize, _audioEncodedBufferSize)
        val frame = ByteArray(16)
        for (i in frame.indices) frame[i] = temp[i].toByte()
        if (sendLSF) _m17Processor!!.startTransmit()
        _m17Processor!!.send(frame)
    }

    fun onBluetoothData(data: ByteArray) {
        setStatus(PLAYER_PLAYING, 0)
        _kissProcessor!!.receive(data)
    }

    @Throws(IOException::class)
    private fun playAudio(): Boolean {
        if (_isLoopbackMode) {
            return processLoopbackPlayback()
        }
        var bytesRead = 0
        if (_usbPort != null) {
            bytesRead = _usbPort!!.syncRead(_rxDataBuffer, RX_TIMEOUT)
        }
        if (bytesRead > 0) {
            setStatus(PLAYER_PLAYING, 0)
            _kissProcessor!!.receive(Arrays.copyOf(_rxDataBuffer, bytesRead))
            return true
        }
        return false
    }

    private fun toggleRecording() {
        _audioRecorder.startRecording()
        _audioPlayer.stop()
        _loopbackBuffer!!.clear()
        notifyAudioLevel(null, false)
    }

    @Throws(IOException::class)
    private fun togglePlayback() {
        _m17Processor!!.stopTransmit()
        _audioRecorder.stop()
        _audioPlayer.play()
        _kissProcessor!!.flush()
        _loopbackBuffer!!.flip()
        notifyAudioLevel(null, true)
    }

    @Throws(IOException::class)
    private fun processRecordPlaybackToggle() {
        // playback -> recording
        if (_isRecording && _audioRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            toggleRecording()
        }
        // recording -> playback
        if (!_isRecording && _audioRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            togglePlayback()
        }
    }

    private fun cleanup() {
        try {
            _kissProcessor!!.flush()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        _audioRecorder.stop()
        _audioRecorder.release()
        _audioPlayer.stop()
        _audioPlayer.release()
        Codec2.destroy(_codec2Con)

        if (_usbPort != null) {
            _usbPort!!.close()
            _usbPort = null
        }
        if (mService != null) {
            mService = null
        }
    }

    private fun setStatus(status: Int, delayMs: Int) {
        if (status != _currentStatus) {
            _currentStatus = status
            val msg = Message.obtain()
            msg.what = status
            _onPlayerStateChanged.sendMessageDelayed(msg, delayMs.toLong())
        }
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        try {
            setStatus(PLAYER_LISTENING, 0)
            if (!_isLoopbackMode) {
                _kissProcessor!!.initialize()
            }
            while (_isRunning) {
                processRecordPlaybackToggle()

                // recording
                if (_audioRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recordAudio()
                } else {
                    // playback
                    if (!playAudio()) {
                        // idling
                        try {
                            if (_currentStatus != PLAYER_LISTENING) {
                                notifyAudioLevel(null, false)
                                notifyAudioLevel(null, true)
                            }
                            setStatus(PLAYER_LISTENING, POST_PLAY_DELAY_MS)
                            sleep(SLEEP_IDLE_DELAY_MS.toLong())
                        } catch (e: InterruptedException) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        setStatus(PLAYER_DISCONNECT, 0)
        cleanup()
    }

    companion object {
        private val TAG = Codec2Player::class.java.simpleName
        private val D = true
        var PLAYER_DISCONNECT = 1
        var PLAYER_LISTENING = 2
        var PLAYER_RECORDING = 3
        var PLAYER_PLAYING = 4
        var PLAYER_RX_LEVEL = 5
        var PLAYER_TX_LEVEL = 6
        var PLAYER_CALLSIGN_RECEIVED = 7
        const val audioMinLevel = -70
        const val audioHighLevel = -15
    }

    init {
        _rxDataBuffer = ByteArray(RX_BUFFER_SIZE)
        _callsign = callsign
        setCodecModeInternal(codec2Mode)
        val _audioRecorderMinBufferSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
        _audioRecorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(_audioRecorderMinBufferSize, 320))
        _audioRecorder.startRecording()
        val _audioPlayerMinBufferSize = AudioTrack.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
        _audioPlayer = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(10 * _audioPlayerMinBufferSize)
                .build()
        _audioPlayer.play()
    }
}