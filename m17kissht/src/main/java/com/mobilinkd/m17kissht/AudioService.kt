package com.mobilinkd.m17kissht
/*
import android.app.Service
import android.content.Intent
import android.media.*
import android.os.*
import android.util.Log
import com.mobilinkd.m17kissht.kiss.KissProcessor
import com.mobilinkd.m17kissht.m17.M17Callback
import com.mobilinkd.m17kissht.m17.M17Processor
import com.ustadmobile.codec2.Codec2
import java.io.IOException
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.LinkedTransferQueue

object PacketReceiveQueue: LinkedBlockingQueue<ByteArray>()
object PacketTransmitQueue: LinkedBlockingQueue<ByteArray>()

class AudioService : Service() {
    private val AUDIO_SAMPLE_RATE = 8000
    private val SLEEP_IDLE_DELAY_MS = 20
    private val POST_PLAY_DELAY_MS = 400
    private val RX_TIMEOUT = 100
    private val TX_TIMEOUT = 2000
    private val TX_DELAY_10MS_UNITS: Byte = 8

    private var mCodec2Handle: Long = 0

    private lateinit var mAudioBuffer: ShortArray   // Audio sample stream
    private lateinit var mCodecBuffer: ByteArray    // Encoded byte stream

    private lateinit var mM17Processor: M17Processor
    private lateinit var mKissProcessor: KissProcessor

    private var mCurrentStatus = STATUS_IDLE

    private lateinit var mAudioPlayer: AudioTrack
    private lateinit var mAudioRecorder: AudioRecord

    private var mPacketReceiveQueue = LinkedBlockingQueue<ByteArray>()
    private var mPacketTransmitQueue = LinkedBlockingQueue<ByteArray>()

    override fun onBind(intent: Intent) : IBinder {
        // TODO Auto-generated method stub
        return null;
    }

    override fun onCreate()
    {
        mCodec2Handle = Codec2.create(Codec2.CODEC2_MODE_3200)
        mM17Processor = M17Processor(mM17Callback)
        mKissProcessor = KissProcessor(mKissCallback, TX_DELAY_10MS_UNITS)

        val minBuffersize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)

        mAudioRecorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuffersize, Codec2.getSamplesPerFrame(mCodec2Handle)))

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

    override fun onDestroy()
    {
        mAudioPlayer.stop()
        mAudioRecorder.stop()
    }

    override fun onStart(intent: Intent, id: Int){

        Log.d(TAG, "On start")
        mAudioRecorder.startRecording()
        mAudioPlayer.play()
    }

    private val mM17Callback: M17Callback = object : M17Callback() {
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
    }

    private val mThread = Thread {
        var mHandler: Handler?

        fun getQueue(): MessageQueue
        {
            return getQueue();
        }

        fun run() {
            Looper.prepare();

            mHandler = object : Handler(Looper.myLooper()!!) {
                override fun handleMessage(msg: Message) {
                    // process incoming messages here
                }
            };

            Looper.loop();
        }
    }

    companion object {
        private val D = true
        private val TAG = AudioService::class.java.simpleName

        var STATUS_IDLE = 1
        var STATUS_LISTENING = 2
        var STATUS_RECORDING = 3
        var STATUS_PLAYING = 4

        var PLAYER_RX_LEVEL = 5
        var PLAYER_TX_LEVEL = 6
        var PLAYER_CALLSIGN_RECEIVED = 7
        private const val CODEC2_DEFAULT_MODE = Codec2.CODEC2_MODE_3200
    }
}
*/
