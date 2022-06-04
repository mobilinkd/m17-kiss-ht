package com.mobilinkd.m17kissht

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.*
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mobilinkd.m17kissht.bluetooth.*
import com.mobilinkd.m17kissht.usb.UsbService
import com.ustadmobile.codec2.Codec2
import java.io.IOException
import java.util.*
import java.util.Locale.ROOT
import java.util.regex.Pattern
import kotlin.concurrent.schedule

class MainActivity : AppCompatActivity() {
    private val _requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.WAKE_LOCK
    )
    private var mIsActive = false
    private var mDeviceTextView: TextView? = null
    private var mStatusTextView: TextView? = null
    private var mBuildVersionTextView: TextView? = null
    private var mAudioLevelBar: ProgressBar? = null
    private var mEditCallsign: TextView? = null
    private var mReceivingCallsign: TextView? = null
    private var mConnectButton: ToggleButton? = null
    private var mTransmitButton: ToggleButton? = null
    private var mAudioPlayer: Codec2Player? = null
    private var mCallsign: String? = null

    private var mRfLevelBar: ProgressBar? = null
    private var mSquelchSeekBar: SeekBar? = null
    private var mSquelchTextView: TextView? = null
    private var mEditDestination: AutoCompleteTextView? = null
    private var mEditCan: TextView? = null
    private var mCan = 10
    private var mDestination = ""
    private var mSquelch = 50
    private var mPttPressTime = System.currentTimeMillis()
    private var mPttLocked = false
    private var mBluetoothConnected = false

    private var mBluetoothDevice: BluetoothDevice? = null
    private var mUsbDevice: UsbDevice? = null
    private var mBleService: BluetoothLEService? = null
    private var mUsbService: UsbService? = null

    private var mWakeLock: PowerManager.WakeLock? = null

    private var mBertReceiverTextView: TextView? = null
    private var mBerErrorCountTextView: TextView? = null
    private var mBerBitCountTextView: TextView? = null
    private var mBerFrameCountTextView: TextView? = null
    private var mBerRateTextView: TextView? = null
    private var mBerTimer: Timer? = null

    /** Defines callbacks for service binding, passed to bindService()  */
    private val bleConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.i(TAG, "onServiceConnected: className -> " + className.className)
            Log.i(TAG, "binding to: " + className.shortClassName)
            val binder = service as BluetoothLEService.LocalBinder
            mBleService = binder.service
            mBleService?.initialize(mBluetoothDevice!!, bleHandler)
        }

        override fun onServiceDisconnected(className: ComponentName) {
            mBleService = null
        }
    }

    /** Defines callbacks for service binding, passed to bindService()  */
    private val usbConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.i(TAG, "onServiceConnected: className -> " + className.className)
            val binder = service as UsbService.LocalBinder
            mUsbService = binder.service
            mUsbService?.setHandler(usbHandler)
            mUsbService?.setMainActivity(this@MainActivity)
            if (mUsbDevice != null) {
                if (!mUsbService!!.attachSupportedDevice(mUsbDevice!!))
                    Toast.makeText(this@MainActivity, "USB device not supported", Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "onServiceConnected: no device found")
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            Log.i(TAG, "usbConnection.onServiceDisconnected: className -> " + className.className)
            mUsbService = null
        }
    }

    private fun initBerWidgets() {
        mBertReceiverTextView = findViewById(R.id.bertReceiverTextView)
        mBerErrorCountTextView = findViewById(R.id.berErrorCountTextView)
        mBerBitCountTextView = findViewById(R.id.berBitCountTextView)
        mBerFrameCountTextView = findViewById(R.id.berFrameCountTextView)
        mBerRateTextView = findViewById(R.id.berRateTextView)

        hideBerWidgets()
    }

    private fun hideBerWidgets() {
        mBertReceiverTextView!!.visibility = View.INVISIBLE
        mBerErrorCountTextView!!.visibility = View.INVISIBLE
        mBerBitCountTextView!!.visibility = View.INVISIBLE
        mBerFrameCountTextView!!.visibility = View.INVISIBLE
        mBerRateTextView!!.visibility = View.INVISIBLE
    }

    private fun showBerWidgets() {
        mBertReceiverTextView!!.visibility = View.VISIBLE
        mBerErrorCountTextView!!.visibility = View.VISIBLE
        mBerBitCountTextView!!.visibility = View.VISIBLE
        mBerFrameCountTextView!!.visibility = View.VISIBLE
        mBerRateTextView!!.visibility = View.VISIBLE

        if (mBerTimer != null) {
            mBerTimer!!.cancel()
            mBerTimer = null
        }
        mBerTimer = Timer()
        mBerTimer!!.schedule(3000) { hideBerWidgets() }
    }

    private val bleHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                BluetoothLEService.DATA_RECEIVED -> {
                    mAudioPlayer?.onTncData(msg.obj as ByteArray)
                }
                BluetoothLEService.GATT_CONNECTED -> {
                    Log.i(TAG, "GATT connected")
                    mDeviceTextView!!.text = mBluetoothDevice!!.name
                    mConnectButton?.isActivated = true
                    mConnectButton?.isEnabled = true
                    mBluetoothConnected = true
                }
                BluetoothLEService.GATT_SERVICES_DISCOVERED -> {
                    Log.i(TAG, "KISS TNC Service connected")
                    if (mCallsign != null) mTransmitButton!!.isEnabled = true
                    if (mWakeLock != null) {
                        Log.w(TAG, "Wake lock already set: " + mWakeLock.toString())
                    }
                    mWakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                        newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "m17kissht:BleWakelockTag").apply {
                            acquire()
                        }
                    }
                    backgroundNotification(mBluetoothDevice!!.name)
                    try {
                        startPlayer(false)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
                BluetoothLEService.GATT_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected")
                    mWakeLock?.release()
                    backgroundCancelled()
                    mWakeLock = null
                    if (mAudioPlayer != null) {
                        Toast.makeText(this@MainActivity, "Bluetooth disconnected", Toast.LENGTH_SHORT).show()
                        mAudioPlayer!!.stopRunning()
                    }
                    mConnectButton?.isActivated = false
                    mConnectButton?.isEnabled = true
                    mDeviceTextView?.text = getString(R.string.not_connected_label)
                    mTransmitButton?.isEnabled = false
                    if (mConnectButton!!.isChecked) {
                        if (mBluetoothConnected) {
                            reconnectToBluetooth()
                        } else {
                            // Attempted to connect with last device and failed.
                            mConnectButton!!.isChecked = false
                            setLastBleDevice("NOT FOUND")
                            pairWithDevice()
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate() -> " + intent.action)

        mIsActive = true
        setContentView(R.layout.activity_main)
        mDeviceTextView = findViewById(R.id.textBtName)
        mStatusTextView = findViewById(R.id.textViewState)
        mAudioLevelBar = findViewById(R.id.progressTxRxLevel)
        mAudioLevelBar!!.max = -Codec2Player.audioMinLevel
        mEditCallsign = findViewById(R.id.editTextCallSign)
        mEditCallsign!!.setOnEditorActionListener(onCallsignChanged)

        mEditDestination = findViewById(R.id.editTextDestination)
        mEditDestination!!.setOnEditorActionListener(onDestinationChanged)
        mEditDestination!!.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, COMMANDS))
        mEditCan = findViewById(R.id.editChannelAccessNumber)
        mEditCan!!.setOnEditorActionListener(onChannelAccessNumberChanged)
        mRfLevelBar = findViewById(R.id.progressRfLevel)
        mRfLevelBar!!.min = 0
        mRfLevelBar!!.max = 255
        mSquelchSeekBar = findViewById(R.id.seekSquelchLevel)
        mSquelchSeekBar?.setOnSeekBarChangeListener(onSquelchLevelChanged)
        mSquelchSeekBar?.isEnabled = true
        mSquelchTextView = findViewById(R.id.textViewSquelch)

        mReceivingCallsign = findViewById(R.id.textViewReceivedCallsign)
        mTransmitButton = findViewById(R.id.buttonTransmit)
        mTransmitButton!!.setOnTouchListener(onBtnPttTouchListener)
        mConnectButton = findViewById(R.id.connectButton)
        mConnectButton!!.setOnClickListener(onConnectListener)
        mBuildVersionTextView = findViewById(R.id.buildVersionTextView)
        mBuildVersionTextView!!.text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
        initBerWidgets()

        mCallsign = getLastCallsign()
        if (mCallsign != null) {
            mEditCallsign!!.text = mCallsign
        }

        mCan = getLastCAN()
        mEditCan!!.text = mCan.toString()

        mSquelch = getLastSquelch()
        mSquelchSeekBar!!.progress = mSquelch
        mSquelchTextView!!.text = mSquelch.toString()
    }

    /*
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent() -> ${intent?.action}")
    }
    */

    override fun onStart() {
        Log.d(TAG, "onStart() -> " + intent.action)
        super.onStart()
        createNotificationChannel()
        registerReceiver(usbReceiver, makeUsbIntentFilter())
        // Initializes Bluetooth adapter.
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        // Ensures Bluetooth is available on the device and it is enabled. If not,
        // displays a dialog requesting user permission to enable Bluetooth.
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLUETOOTH)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume() -> " + intent.action)

        requestPermissions()

        if (intent.action == UsbService.ACTION_USB_ATTACHED) {
            val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as UsbDevice?
            bindUsbService(device!!)
        }
    }

    override fun onPause() {
        Log.d(TAG, "onPause() -> " + intent.action)
        super.onPause()
    }

    override fun onStop() {
        Log.d(TAG, "onStop()")
        super.onStop()
        unregisterReceiver(usbReceiver)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        super.onDestroy()
        mIsActive = false
        if (mAudioPlayer != null) {
            mAudioPlayer!!.stopRunning()
        }
        mWakeLock?.release()
        mWakeLock = null
    }

    private fun requestPermissions(): Boolean {
        val permissionsToRequest: MutableList<String> = LinkedList()
        for (permission in _requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this@MainActivity, permission) == PackageManager.PERMISSION_DENIED) {
                permissionsToRequest.add(permission)
            }
        }
        if (permissionsToRequest.size > 0) {
            ActivityCompat.requestPermissions(
                    this@MainActivity,
                    permissionsToRequest.toTypedArray(),
                    REQUEST_PERMISSIONS)
            return false
        }
        return true
    }

    private fun colorFromAudioLevel(audioLevel: Int): Int {
        var color = Color.GREEN
        if (audioLevel > Codec2Player.audioHighLevel) color = Color.RED else if (audioLevel == Codec2Player.audioMinLevel) color = Color.LTGRAY
        return color
    }

    private fun colorFromRSSILevel(level: Int): Int {
        var color = Color.LTGRAY
        when {
            level > 192 -> color = Color.GREEN
            level > 128 -> color = Color.YELLOW
            level > 0 -> color = Color.RED
        }
        return color
    }

    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.channel_name)
            val descriptionText = getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(M17_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private val onCallsignChanged = TextView.OnEditorActionListener { textView, actionId, keyEvent ->
        if (actionId == EditorInfo.IME_ACTION_DONE ||
                keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (keyEvent == null || !keyEvent.isShiftPressed) {
                mCallsign = validateCallsign(textView.text.toString())
                textView.text = mCallsign
                mAudioPlayer?.setCallsign(mCallsign!!)
                mTransmitButton!!.isEnabled = true
                textView.clearFocus()
                setLastCallsign(mCallsign!!)
                return@OnEditorActionListener false // hide keyboard.
            }
        }
        false
    }

    private val onDestinationChanged = TextView.OnEditorActionListener { textView, actionId, keyEvent ->
        if (actionId == EditorInfo.IME_ACTION_DONE ||
            keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (keyEvent == null || !keyEvent.isShiftPressed) {
                mDestination = validateCallsign(textView.text.toString())
                textView.text = mDestination
                mAudioPlayer?.setDestination(mDestination)
                textView.clearFocus()
                return@OnEditorActionListener false // hide keyboard.
            }
        }
        false
    }

    private val onChannelAccessNumberChanged = TextView.OnEditorActionListener { textView, actionId, keyEvent ->
        if (actionId == EditorInfo.IME_ACTION_DONE ||
            keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (keyEvent == null || !keyEvent.isShiftPressed) {
                mCan = validateCAN(textView.text.toString())
                textView.text = mCan.toString()
                mAudioPlayer?.setChannelAccessNumber(mCan)
                textView.clearFocus()
                setLastCAN(mCan)
                return@OnEditorActionListener false // hide keyboard.
            }
        }
        false
    }

    private val onSquelchLevelChanged = object: SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
            mSquelchTextView?.text = progress.toString()
        }
        override fun onStartTrackingTouch(seek: SeekBar?) {
            Log.d(TAG, "onStartTrackingTouch")
        }
        override fun onStopTrackingTouch(seek: SeekBar?) {
            Log.d(TAG, "onStopTrackingTouch")
            if (seek != null) {
                mSquelch = seek.progress
                mAudioPlayer?.setSquelch(mSquelch)
                setLastSquelch(mSquelch)
            }
        }
    }

    private val usbReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "usbReceiver.onReceive() -> " + intent.action)
            when(intent.action) {
                UsbService.ACTION_USB_ATTACHED -> {
                    val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as UsbDevice?
                    if (device != null) {
                        if (D) Log.d(TAG, "USB attached -> " + device.productName)
                        bindUsbService(device!!)
                    } else {
                        Toast.makeText(this@MainActivity, "No USB device available", Toast.LENGTH_SHORT).show()
                    }
                }
                UsbService.ACTION_USB_DETACHED -> {
                    Toast.makeText(this@MainActivity, "USB detached", Toast.LENGTH_SHORT).show()
                    mUsbService?.disconnect()
                    mAudioPlayer?.stopRunning()
                    mTransmitButton?.isEnabled = false
                    mConnectButton?.isActivated = false
                    mConnectButton?.text = getString(R.string.connect_label)
                    mConnectButton?.isEnabled = true
                    mConnectButton?.isChecked = false
                    mDeviceTextView?.text = getString(R.string.not_connected_label)
                    mWakeLock?.release()
                    mWakeLock = null
                    backgroundCancelled()
                    mUsbService?.disconnect()
                    finish() // finish this intent.
                }
                UsbService.ACTION_USB_PERMISSION -> {
                    val granted = intent.extras!!.getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED)
                    if (granted)
                    {
                        Log.i(TAG, "USB permission granted")
                        mUsbService?.connect()
                    } else {
                        Log.i(TAG, "USB permission denied")
                        Toast.makeText(context, "USB Permission not granted", Toast.LENGTH_SHORT).show()
                    }
                }
                UsbService.ACTION_NO_USB -> {
                    Toast.makeText(context, "No USB connected", Toast.LENGTH_SHORT).show()
                }
                UsbService.ACTION_USB_NOT_SUPPORTED -> {
                    Toast.makeText(context, "USB device not supported", Toast.LENGTH_SHORT).show()
                }
                UsbService.ACTION_USB_DISCONNECTED -> {
                }
                UsbService.ACTION_USB_READY -> {
                    Log.d(TAG, "ACTION_USB_READY")
                    if (mCallsign != null) mTransmitButton!!.isEnabled = true
                    val deviceName = intent.getStringExtra(UsbService.USB_DEVICE_NAME)
                    mDeviceTextView!!.text = deviceName
                    Log.d(TAG, "Connected to $deviceName")
                    mConnectButton?.isActivated = true
                    mConnectButton?.text = getString(R.string.disconnect_label)
                    mConnectButton?.isEnabled = false
                    mWakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                        newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "m17kissht:UsbWakelockTag").apply {
                            acquire()
                        }
                    }
                    backgroundNotification(deviceName!!)
                    try {
                        startPlayer(true)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private val usbHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                UsbService.DATA_RECEIVED -> {
                    mAudioPlayer?.onTncData(msg.obj as ByteArray)
                }
                UsbService.CTS_CHANGE -> {
                    // pass
                }
                UsbService.DSR_CHANGE -> {
                    // pass
                }
            }
        }
    }

    private val onBtnPttTouchListener = View.OnTouchListener { v, event ->
        var result = false
        when (event.action) {
            /*
            The iseChecked logic seems inverted from what I would expect.  I don't
            really understand why it needs to be inverted to work properly.  But it
            does.
             */
            MotionEvent.ACTION_DOWN -> {
                if (!mPttLocked && mAudioPlayer != null && mCallsign != null) {
                    mAudioPlayer!!.startRecording()
                }
                mPttPressTime = System.currentTimeMillis()
                v.isPressed = true
                result = true
            }
            MotionEvent.ACTION_UP -> {
                v.performClick()
                if (!mPttLocked && System.currentTimeMillis() - mPttPressTime < 500) {
                    mPttLocked = true
                    mTransmitButton!!.isChecked = true
                } else if (mAudioPlayer != null) {
                    mAudioPlayer!!.startPlayback()
                    mPttLocked = false
                    mTransmitButton!!.isChecked = false
                } else {
                    mPttLocked = false
                    mTransmitButton!!.isChecked = false
                }
                v.isPressed = false
                result = true
            }
        }
        result
    }

    private val onConnectListener = View.OnClickListener {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this@MainActivity, "Audio Permissions Denied", Toast.LENGTH_SHORT).show()
            finish()
        }

        if (mConnectButton!!.isChecked) {
            connectToBluetooth()
        } else {
            mBluetoothConnected = false
            mBleService?.close()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            var allGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    break
                }
            }
            if (allGranted) {
                Toast.makeText(this@MainActivity, "Permissions Granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Permissions Denied", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun receivedCallsign(callsign: String) {
        mReceivingCallsign!!.text = callsign

        val builder = NotificationCompat.Builder(this, M17_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(callsign)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setTimeoutAfter(10000)
            .setCategory(Notification.CATEGORY_CALL)

        with(NotificationManagerCompat.from(this)) {
            // notificationId is a unique int for each notification that you must define
            notify(CALLSIGN_NOTIFICATION_ID, builder.build())
        }
        Log.d(TAG, "RX: $callsign notification sent")
    }

    private fun receiveCancelled() {
        mReceivingCallsign!!.text = ""

        with(NotificationManagerCompat.from(this)) {
            // notificationId is a unique int for each notification that you must define
            cancel(CALLSIGN_NOTIFICATION_ID)
        }
    }

    private fun backgroundNotification(device: String) {

        val builder = NotificationCompat.Builder(this, M17_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle("Connected to $device")
            .setContentText("M17 is running in the background")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(Notification.CATEGORY_PROGRESS)

        with(NotificationManagerCompat.from(this)) {
            // notificationId is a unique int for each notification that you must define
            notify(RUNNING_NOTIFICATION_ID, builder.build())
        }
        Log.d(TAG, "background notification sent")
    }

    private fun backgroundCancelled() {
        with(NotificationManagerCompat.from(this)) {
            // notificationId is a unique int for each notification that you must define
            cancel(RUNNING_NOTIFICATION_ID)
        }
    }

    private val onPlayerStateChanged: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (mIsActive && msg.what == Codec2Player.PLAYER_DISCONNECT) {
                mStatusTextView!!.text = getString(R.string.state_label_stop)
                Toast.makeText(baseContext, "Disconnected from modem", Toast.LENGTH_SHORT).show()
//                startUsbConnectActivity()
            } else if (msg.what == Codec2Player.PLAYER_LISTENING) {
                mStatusTextView!!.setText(R.string.state_label_idle)
                mRfLevelBar!!.progressDrawable.colorFilter = PorterDuffColorFilter(colorFromRSSILevel(0), PorterDuff.Mode.SRC_IN)
                mRfLevelBar!!.progress = 0
                receiveCancelled()
            } else if (msg.what == Codec2Player.PLAYER_RECORDING) {
                mStatusTextView!!.setText(R.string.state_label_transmit)
            } else if (msg.what == Codec2Player.PLAYER_PLAYING) {
                mStatusTextView!!.setText(R.string.state_label_receive)
            } else if (msg.what == Codec2Player.PLAYER_RX_LEVEL) {
                mAudioLevelBar!!.progressDrawable.colorFilter = PorterDuffColorFilter(colorFromAudioLevel(msg.arg1), PorterDuff.Mode.SRC_IN)
                mAudioLevelBar!!.progress = msg.arg1 - Codec2Player.audioMinLevel
            } else if (msg.what == Codec2Player.PLAYER_TX_LEVEL) {
                mAudioLevelBar!!.progressDrawable.colorFilter = PorterDuffColorFilter(colorFromAudioLevel(msg.arg1), PorterDuff.Mode.SRC_IN)
                mAudioLevelBar!!.progress = msg.arg1 - Codec2Player.audioMinLevel
            } else if (msg.what == Codec2Player.PLAYER_CALLSIGN_RECEIVED) {
                receivedCallsign(msg.obj as String)
            } else if (msg.what == Codec2Player.PLAYER_BERT_RECEIVED) {
                receivedBERT(msg.arg1, msg.arg2, msg.obj as Int)
            } else if (msg.what == Codec2Player.PLAYER_RSSI_RECEIVED) {
                mRfLevelBar!!.progressDrawable.colorFilter = PorterDuffColorFilter(colorFromRSSILevel(msg.arg1), PorterDuff.Mode.SRC_IN)
                mRfLevelBar!!.progress = msg.arg1
            }
        }
    }

    private fun receivedBERT(receivedBits: Int, errorBits: Int, frameCount: Int) {
        if (receivedBits == 0) return
        mBerBitCountTextView!!.text = getString(R.string.bert_bits, receivedBits)
        mBerErrorCountTextView!!.text = getString(R.string.bert_errors, errorBits)
        mBerFrameCountTextView!!.text = getString(R.string.bert_frames, frameCount)
        mBerRateTextView!!.text = String.format("%,.9f", errorBits.toFloat() / receivedBits.toFloat())
        showBerWidgets()
    }

    @Throws(IOException::class)
    private fun startPlayer(isUsb: Boolean) {
        mAudioPlayer = Codec2Player(onPlayerStateChanged, CODEC2_DEFAULT_MODE, mCallsign ?: "")
        mAudioPlayer?.setChannelAccessNumber(mCan)
        mAudioPlayer?.setDestination(mDestination)
        if (isUsb) {
            mAudioPlayer?.setUsbService(mUsbService)
        } else {
            mAudioPlayer?.setBleService(mBleService!!)
        }
        mAudioPlayer?.start()
    }

    private fun getLastBleDevice() : String? {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val address = sharedPref.getString(getString(R.string.ble_device_key), "NOT FOUND")
        if (address == "NOT FOUND") return null
        return address
    }

    private fun setLastBleDevice(address: String) {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString(getString(R.string.ble_device_key), address)
            apply()
        }
    }

    private fun validateCallsign(callsign: String) : String {
        val result = StringBuilder()
        var size = 0
        for (c in callsign.toUpperCase(ROOT)) {
            if (c in '0'..'9') {
                result.append(c)
                size += 1
            } else if (c in 'A'..'Z') {
                result.append(c)
                size += 1
            } else if (c == '-' || c == '/' || c == '.' || c == ' ') {
                result.append(c)
                size += 1
            }
            if (size == 9) break
        }
        return result.toString()
    }

    private fun validateCAN(canString: String) : Int {
        canString.toInt().also { val can = it
            if (can < 0) return 0
            if (can > 15) return 15
            return can
        }
    }

    private fun getLastCallsign() : String? {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val address = sharedPref.getString(getString(R.string.call_sign), "")
        if (address == "") return null
        return address
    }

    private fun setLastCallsign(callsign: String) {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString(getString(R.string.call_sign), callsign)
            apply()
        }
    }

    private fun getLastCAN(): Int {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        return sharedPref.getInt(getString(R.string.channel_access_number), 10)
    }

    private fun setLastCAN(can: Int) {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putInt(getString(R.string.channel_access_number), can)
            apply()
        }
    }

    private fun getLastSquelch(): Int {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        return sharedPref.getInt(getString(R.string.squelch), 50)
    }

    private fun setLastSquelch(sql: Int) {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putInt(getString(R.string.squelch), sql)
            apply()
        }
    }

    private fun bindBleService(device: BluetoothDevice) {
        mBluetoothDevice = device
        Log.i(TAG, "Bluetooth connect to ${device.name}, type = ${device.type}, bonded = ${device.bondState}")
        val gattServiceIntent = Intent(this, BluetoothLEService::class.java)
        if (mBleService == null) {
            bindService(gattServiceIntent, bleConnection, BIND_AUTO_CREATE)
        } else {
            Log.i(TAG, "Re-initializing bound service")
            mBleService?.initialize(mBluetoothDevice!!, bleHandler)
        }
        mConnectButton?.isEnabled = false
    }

    private fun reconnectToBluetooth() {
        val address = getLastBleDevice()
        if (address != null) {
            Log.i(TAG, "Bluetooth connecting to last device @ $address")
            val bluetoothManager: BluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val device = bluetoothManager.adapter.getRemoteDevice(address)
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                bindBleService(device)
                return
            }
        }
    }

    private fun connectToBluetooth() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_DENIED)
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_CONNECT),
                    2);
                return;
            }
        }

        val address = getLastBleDevice()
        if (address != null) {
            Log.i(TAG, "Bluetooth connecting to last device @ " + address);
            val bluetoothManager: BluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val device = bluetoothManager.getAdapter().getRemoteDevice(address)
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                bindBleService(device)
                return
            }
        }
        pairWithDevice()
    }

    private fun bindUsbService(device: UsbDevice) {
        Log.i(TAG, "Bind to USB Service")
        mUsbDevice = device
        if (mUsbService == null) {
            val intent = Intent(this, UsbService::class.java)
            bindService(intent, usbConnection, BIND_AUTO_CREATE)
        } else {
            if (!mUsbService!!.attachSupportedDevice(device)) {
                Toast.makeText(this@MainActivity, "USB device not supported", Toast.LENGTH_SHORT).show()
                finish()
            }

            Log.i(TAG, "Re-initializing bound service")
        }
    }


    private val deviceManager: CompanionDeviceManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(CompanionDeviceManager::class.java)
    }

    fun pairWithDevice() {
        // Connect to devices advertising KISS_TNC_SERVICE
        val deviceFilter: BluetoothLeDeviceFilter = BluetoothLeDeviceFilter.Builder()
            // .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(TNC_SERVICE_UUID)).build())
            .setNamePattern(Pattern.compile(".*TNC.*|.*Mobilinkd.*"))
            .build()

        if (D) Log.d(TAG, "deviceFilter construced with UUID " + TNC_SERVICE_UUID)

        // The argument provided in setSingleDevice() determines whether a single
        // device name or a list of device names is presented to the user as
        // pairing options.
        val pairingRequest: AssociationRequest = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false)
            .build()

        if (D) Log.d(TAG, "pairingRequest construced with deviceFilter")

        // When the app tries to pair with the Bluetooth device, show the
        // appropriate pairing request dialog to the user.
        deviceManager.associate(pairingRequest, object : CompanionDeviceManager.Callback() {

            override fun onDeviceFound(chooserLauncher: IntentSender) {
                if (D) Log.d(TAG, "device found for pairing")
                startIntentSenderForResult(chooserLauncher,
                    REQUEST_CONNECT_DEVICE, null, 0, 0, 0, null)
            }

            override fun onFailure(error: CharSequence?) {
                if (D) Log.d(TAG, "pairingRequest failed: " + error)
            }

        }, null)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_ENABLE_BLUETOOTH -> when (resultCode) {
                Activity.RESULT_OK -> {
                    if (D) Log.d(TAG, "BT enabled")
                }
                else -> {
                    // User did not enable Bluetooth or an error occured
                    if (D) Log.d(TAG, "BT not enabled")
                    Toast.makeText(this, R.string.bt_not_enabled, Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_CONNECT_DEVICE -> when (resultCode) {
                Activity.RESULT_OK -> {
                    if (data == null) {
                        Log.w(TAG, "REQUEST_CONNECT_DEVICE: intent is null")
                        return;
                    }
                    // User has chosen to pair with the Bluetooth device.
                    val scanResult = data!!.getParcelableExtra<ScanResult>(CompanionDeviceManager.EXTRA_DEVICE)
                    val device = scanResult?.device;
                    if (device != null) {
                        if (D) Log.d(TAG, "onActivityResult REQUEST_CONNECT_DEVICE = " + device.address)
                        setLastBleDevice(device.address)
                        bindBleService(device)
                    }
                }
            }
        }
    }

    private fun makeUsbIntentFilter(): IntentFilter {
        val intentFilter = IntentFilter()
        intentFilter.addAction(UsbService.ACTION_USB_READY)
        intentFilter.addAction(UsbService.ACTION_NO_USB)
        intentFilter.addAction(UsbService.ACTION_USB_NOT_SUPPORTED)
        intentFilter.addAction(UsbService.ACTION_USB_PERMISSION)
        intentFilter.addAction(UsbService.ACTION_USB_DETACHED)
        intentFilter.addAction(UsbService.ACTION_USB_ATTACHED)
        return intentFilter
    }

    companion object {
        private val TAG = MainActivity::class.java.name
        private val D = true

        private const val REQUEST_CONNECT_BT = 1
//        private const val REQUEST_CONNECT_USB = 2
        private const val REQUEST_PERMISSIONS = 3
        private const val CODEC2_DEFAULT_MODE = Codec2.CODEC2_MODE_3200

        private val M17_CHANNEL_ID = MainActivity::class.java.`package`!!.toString()

        private const val CALLSIGN_NOTIFICATION_ID = 1
        private const val RUNNING_NOTIFICATION_ID = 2

        private val COMMANDS = arrayOf(
            "BROADCAST", "ECHO", "INFO", "UNLINK", "M17-M17 A", "M17-M17 C", "M17-M17 E"
        )

        private val BT_ENABLE = 1
        private val BT_CONNECT_SUCCESS = 2
        private val BT_PAIRING_FAILURE = 3
        private val BT_SOCKET_FAILURE = 4
        private val BT_ADAPTER_FAILURE = 5

        private val TNC_SERVICE_UUID = UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb")
        private val REQUEST_ENABLE_BLUETOOTH = 1
        private val REQUEST_CONNECT_DEVICE = 2

    }
}