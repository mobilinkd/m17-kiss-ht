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
import android.bluetooth.le.ScanFilter
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
        Manifest.permission.WAKE_LOCK,
    )

    private val _blePermissionsOld = arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private val _blePermissionsNew = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    private var mHaveRequiredPermissions = false
    private var mHaveBlePermissions = false
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
    private var mUsbConnected = false

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
    private var mBluetoothReconnecting = false
    private var mBluetoothRefreshed = false

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
            Log.i(TAG, "onServiceDisconnected: className -> " + className.className)
            mBleService = null
        }
    }

    /** Defines callbacks for service binding, passed to bindService()  */
    private val usbConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.i(TAG, "onServiceConnected: className -> " + className.className)
            if (mUsbService == null) {
                val binder = service as UsbService.LocalBinder
                mUsbService = binder.service
                mUsbService?.setHandler(usbHandler)
                mUsbService?.setMainActivity(this@MainActivity)
            }
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
                    mBluetoothRefreshed = false
                    mBleService?.startDiscovery()
                }
                BluetoothLEService.GATT_READY -> {
                    Log.i(TAG, "GATT ready")
                    mDeviceTextView!!.text = mBluetoothDevice!!.name
                    mConnectButton?.isActivated = true
                    mConnectButton?.isEnabled = true
                    mBluetoothConnected = true
                    mBluetoothReconnecting = false
                    if (mCallsign != null) mTransmitButton!!.isEnabled = true
                    if (mWakeLock != null) {
                        Log.w(TAG, "Wake lock already set: " + mWakeLock.toString())
                    } else {
                        mWakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                            newWakeLock(
                                PowerManager.PARTIAL_WAKE_LOCK,
                                "m17kissht:BleWakelockTag"
                            ).apply {
                                acquire()
                            }
                        }
                    }
                    backgroundNotification(mBluetoothDevice!!.name)
                    try {
                        startPlayer(false)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
                BluetoothLEService.GATT_SERVICE_DISCOVERY_FAILED-> {
                    // This occurs if the TNC is no longer available, either turned off or out of
                    // range when the connection was attempted. Assume that there is another device
                    // nearby that the user wishes to connect to.
                    Log.w(TAG, "GATT discovery failed.")
                    Toast.makeText(this@MainActivity, "KISS TNC service not found", Toast.LENGTH_SHORT).show()
                    mBleService?.close()
                    resetLastBleDevice()
                    pairWithDevice()
                }
                BluetoothLEService.GATT_SERVICES_DISCOVERED -> {
                    Log.i(TAG, "KISS TNC Service discovered")
                    mBleService?.setMTU()
                }
                BluetoothLEService.GATT_MTU_CHANGED -> {
                    Log.i(TAG, "MTU changed")
                    mBleService?.updateCharacteristics()
                }
                BluetoothLEService.GATT_MTU_FAILED -> {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to increase MTU",
                        Toast.LENGTH_LONG
                    ).show()
                    // This will never work.
                    mBleService?.close()
                }
                BluetoothLEService.GATT_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected")
                    val is_connected = mBluetoothConnected
                    val should_be_connected = mConnectButton!!.isChecked
                    if (should_be_connected && mHaveBlePermissions) {
                        if (!is_connected) {
                            // Never GATT_CONNECTED, meaning the device was not found. Attempted to
                            // connect with last device and failed. Try to re-pair.
                            reconnecting()
                            Toast.makeText(
                                this@MainActivity,
                                "Bluetooth device not found",
                                Toast.LENGTH_SHORT
                            ).show()
                            mDeviceTextView?.text = getString(R.string.connecting_label)
                            mConnectButton?.isEnabled = false
                            mConnectButton?.isChecked = true
                            resetLastBleDevice()
                            pairWithDevice()
                        } else if (mBluetoothReconnecting) {
                            // Retried a connection.
                            disconnected()
                            Toast.makeText(
                                this@MainActivity,
                                "Bluetooth disconnected",
                                Toast.LENGTH_LONG
                            ).show()
                            mBluetoothReconnecting = false
                        } else {
                            // Got GATT_CONNECTED and then a disconnect which was not initiated by
                            // the user.
                            disconnected()
                            Toast.makeText(
                                this@MainActivity,
                                "Bluetooth disconnected",
                                Toast.LENGTH_LONG
                            ).show()
                            // There are two options here. One is to just fail and let the user
                            // reconnect. The other is to attempt to reconnect while the "Connect"
                            // button is pressed. This is good if you go in and out of range of
                            // the TNC frequently. But it can be a poor user experience if there
                            // are odd connection problems. We retry once.
                            mBluetoothReconnecting = true
                            Log.i(TAG, "Reconnecting")
                            reconnecting()
                            connectToBluetooth()
                        }
                    } else {
                        disconnected()
                    }
                }
                BluetoothLEService.GATT_SERVICE_NOT_FOUND -> {
                    Log.w(TAG, "TNC Service not found")
                    if (!mBluetoothRefreshed) {
                        mBleService?.refresh()
                        mBleService?.startDiscovery()
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
        } else {
            mTransmitButton!!.text = getString(R.string.set_callsign_label)
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
        registerReceiver(bleReceiver, makeBleIntentFilter())
        requestRequiredPermissions()
        requestBlePermissions()
    }

    @SuppressLint("MissingPermission")
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume() -> " + intent.action)

        if (intent.action == UsbService.ACTION_USB_ATTACHED) {
            mUsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as UsbDevice?
            mUsbConnected = true
        }

        if (mUsbConnected) {
            bindUsbService(mUsbDevice!!)
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
        unregisterReceiver(bleReceiver)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        super.onDestroy()
        mIsActive = false
        if (mAudioPlayer != null) {
            mAudioPlayer!!.stopRunning()
        }
        mBleService?.close()
        mWakeLock?.release()
        mWakeLock = null
    }

    private fun requestRequiredPermissions(): Boolean {
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
                    REQUEST_REQUIRED_PERMISSIONS)
            return false
        }
        if (D) Log.d(TAG, "Have required permissions")
        mHaveRequiredPermissions = true
        return true
    }

    private fun requestBlePermissions(): Boolean {
        val permissionsToRequest: MutableList<String> = LinkedList()
        val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)  _blePermissionsNew else  _blePermissionsOld

        for (permission in blePermissions) {
            if (ContextCompat.checkSelfPermission(this@MainActivity, permission ) == PackageManager.PERMISSION_DENIED) {
                permissionsToRequest.add(permission)
            }
        }

        if (permissionsToRequest.size > 0) {
            ActivityCompat.requestPermissions(
                this@MainActivity,
                permissionsToRequest.toTypedArray(),
                REQUEST_BLE_PERMISSIONS)
            return false
        }
        if (D) Log.d(TAG, "Have BLE permissions")
        mHaveBlePermissions = true
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

    private val onCallsignChanged = TextView.OnEditorActionListener { textView, actionId, keyEvent ->
        if (actionId == EditorInfo.IME_ACTION_DONE ||
                keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (keyEvent == null || !keyEvent.isShiftPressed) {
                mCallsign = validateCallsign(textView.text.toString())
                textView.text = mCallsign
                textView.clearFocus()
                if (mCallsign == "") {
                    mCallsign = null
                    mTransmitButton!!.isEnabled = false
                    mTransmitButton!!.text = getString(R.string.set_callsign_label)
                    resetLastCallsign()
                } else {
                    mAudioPlayer?.setCallsign(mCallsign!!)
                    if (mBluetoothConnected or mUsbConnected) {
                        mTransmitButton!!.isEnabled = true
                        mTransmitButton!!.text = getString(R.string.push_to_talk)
                    }
                    setLastCallsign(mCallsign!!)
                    return@OnEditorActionListener false // hide keyboard.
                }
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
                    // We will also the MAIN intent in onResume() right after this. We can only
                    // get permissions handled properly if bindUsbService() is called from
                    // onResume(). So we store the device here and wait for the intent.
                    // https://stackoverflow.com/a/9814826/854133
                    val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as UsbDevice?
                    if (device != null) {
                        if (D) Log.d(TAG, "USB attached -> " + device.productName)
                        mUsbConnected = true
                        mUsbDevice = device
                    } else {
                        Toast.makeText(this@MainActivity, "No USB device available", Toast.LENGTH_SHORT).show()
                    }
                }
                UsbService.ACTION_USB_DETACHED -> {
                    Toast.makeText(this@MainActivity, "USB detached", Toast.LENGTH_SHORT).show()
                    mUsbService?.disconnect()
                    mUsbConnected = false
                    disconnected()
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
        if (!mHaveRequiredPermissions) {
            Toast.makeText(this@MainActivity, "Audio Permissions Denied", Toast.LENGTH_SHORT).show()
            finish()
        }

        if (requestBlePermissions()) {
            if (!mBluetoothConnected) {
                // Initializes Bluetooth adapter.
                val bluetoothManager =
                    getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val bluetoothAdapter = bluetoothManager.adapter

                // Ensures Bluetooth is available on the device and it is enabled. If not,
                // displays a dialog requesting user permission to enable Bluetooth.
                if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLUETOOTH)
                }
            }
        }

        if (mConnectButton!!.isChecked) {
            mConnectButton?.isEnabled = false
            mDeviceTextView?.text = getString(R.string.connecting_label)
            connectToBluetooth()
        } else {
            mBluetoothConnected = false
            mConnectButton?.isEnabled = false
            mBleService?.close()
        }
    }

    /**
     * Do the grant results indicate that all needed permissions have been granted?
     */
    private fun isAllGranted(grantResults: IntArray): Boolean {
        for (result in grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_REQUIRED_PERMISSIONS -> {
                if (isAllGranted(grantResults)) {
                    if (D) Log.d(TAG, "Have required permissions")
                    mHaveRequiredPermissions = true
                } else {
                    permissions.zip(grantResults.toTypedArray()).forEach {
                        if (it.component2() != PackageManager.PERMISSION_GRANTED) {
                            Log.e(TAG, "Missing ${it.component1()} permission")
                        }
                    }
                    Toast.makeText(this@MainActivity, "Required Permissions Denied", Toast.LENGTH_LONG).show()
                    mHaveRequiredPermissions = false
                }
            }
            REQUEST_BLE_PERMISSIONS -> {
                if (isAllGranted(grantResults)) {
                    if (D) Log.d(TAG, "Have BLE permissions")
                    mHaveBlePermissions = true
                } else {
                    Toast.makeText(this@MainActivity, "Bluetooth Permissions Denied", Toast.LENGTH_LONG).show()
                    mHaveRequiredPermissions = false
                }
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

    private fun guiDisconnected() {
        mTransmitButton?.isEnabled = false
        mConnectButton?.isActivated = false
        mConnectButton?.text = if (mHaveBlePermissions) getString(R.string.connect_label) else getString(R.string.ble_denied_label)
        mConnectButton?.isEnabled = mHaveBlePermissions
        mConnectButton?.isChecked = false
        mDeviceTextView?.text = getString(R.string.not_connected_label)
    }

    private fun guiReconnecting() {
        mTransmitButton?.isEnabled = false
        mConnectButton?.isActivated = false
        mConnectButton?.text = getString(R.string.disconnect_label)
        mConnectButton?.isEnabled = true
        mConnectButton?.isChecked = true
        mDeviceTextView?.text = getString(R.string.connecting_label)
    }

    private fun disable_background_threads() {
        mWakeLock?.release()
        backgroundCancelled()
        mWakeLock = null
        mAudioPlayer?.stopRunning()
    }

    /**
     * The TNC is disconnected and will stay disconnected. Release the wakelock, reset the
     * Bluetooth device variables, and indicate that the GUI is in a disconnected state.
     */
    private fun disconnected() {
        disable_background_threads()
        mBluetoothDevice = null
        mBluetoothConnected = false
        guiDisconnected()
    }

    /**
     * The TNC was disconnected and will attempt to reconnect. Release the wakelock, and indicate
     * that the GUI is in a reconnecting state.
     */
    private fun reconnecting() {
        disable_background_threads()
        mBluetoothDevice = null
        mBluetoothConnected = false
        guiReconnecting()
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

    private fun resetLastBleDevice() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            remove(getString(R.string.ble_device_key))
            apply()
        }
    }

    private fun validateCallsign(callsign: String) : String {
        val result = StringBuilder()
        var size = 0
        for (c in callsign.uppercase(ROOT)) {
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

    private fun resetLastCallsign() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            remove(getString(R.string.call_sign))
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
        if (mBleService == null) {
            Log.i(TAG, "Binding BleService to ${device.name}, type = ${device.type}, bonded = ${device.bondState}")
            val gattServiceIntent = Intent(this, BluetoothLEService::class.java)
            bindService(gattServiceIntent, bleConnection, BIND_AUTO_CREATE)
        } else {
            Log.i(TAG, "Re-initializing BleService with ${device.name}, type = ${device.type}, bonded = ${device.bondState}")
            mBleService?.initialize(mBluetoothDevice!!, bleHandler)
        }
        mConnectButton?.isEnabled = false
        setLastBleDevice(device.address)
    }

    private fun connectToBluetooth() {

        val address = getLastBleDevice()
        if (address != null) {
            Log.i(TAG, "Bluetooth connecting to last device @ " + address);
            val bluetoothManager: BluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val device = bluetoothManager.getAdapter().getRemoteDevice(address)
            Log.i(TAG, "Bluetooth connect to: ${device.name}, type = ${device.type}, bonded = ${device.bondState}")
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                // The device will have no UUIDs associated with it if last bonded via BLE.  If it
                // has any UUIDs associated with it then it was bonded via BR/EDR and will need to
                // be re-bonded via BLE to work properly.
                if (device.uuids != null) {
                    device.uuids.forEach {
                        if (D) Log.d(TAG, "Bonded with UUID ${it.uuid}")
                    }
                }
                bindBleService(device)
                return
            } else {
                Log.e(TAG, "${device.name} (${device.address}) is no longer bonded")
                resetLastBleDevice()
            }
        }
        pairWithDevice()
    }

    private fun removeBond(device: BluetoothDevice) {
        val removeBond = device.javaClass.getMethod("removeBond")
        removeBond.invoke(device)
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
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(TNC_SERVICE_UUID)).build())
//            .setNamePattern(Pattern.compile(".*TNC.*|.*Mobilinkd.*"))
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
                disconnected()
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
                    val scanResult = data.getParcelableExtra<ScanResult>(CompanionDeviceManager.EXTRA_DEVICE)
                    val device = scanResult?.device;
                    if (device != null) {
                        if (D) Log.d(TAG, "onActivityResult REQUEST_CONNECT_DEVICE = ${device.address}, bondState = ${device.bondState}")
                        if (mBleService?.isConnected() == true) {
                            if (D) Log.d(TAG, "Already connected")
                        } else {
                            when (device.bondState) {
                                BluetoothDevice.BOND_NONE -> {
//                                    bindBleService(device)
                                    mBluetoothDevice = device
                                    device.createBond()
                                }
                                BluetoothDevice.BOND_BONDING -> {
                                    removeBond(device)
                                    connectToBluetooth()
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Bad BLE state -- need device restart?",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                BluetoothDevice.BOND_BONDED -> {
                                    if (mBleService?.isConnected() == true) {
                                        Log.e(TAG, "Already connected to ${device.name}")
                                    } else {
                                        bindBleService(device)
                                    }
                                }
                            }
                        }
                    } else {
                        if (D) Log.d(TAG, "onActivityResult Pairing rejected")
                        Toast.makeText(this@MainActivity, R.string.pairing_rejected, Toast.LENGTH_LONG).show()
                        disconnected();
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

    private fun makeBleIntentFilter(): IntentFilter {
        val intentFilter = IntentFilter()
        intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        return intentFilter
    }

    private val bleReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "bleReceiver.onReceive() -> " + intent.action)
            when(intent.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as BluetoothDevice?
                    val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                    if (device != null) {
                        Log.i(TAG, "BT bond state changing ${prevState} -> ${newState} for " + device.name)
                        if (device.address == mBluetoothDevice?.address) {
                            when(newState) {
                                BluetoothDevice.BOND_BONDED -> {
                                    if (mBleService?.isConnected() == true) {
                                        Log.i(TAG, "Already connected")
                                    } else {
                                        bindBleService(device)
                                    }
                                }
                                BluetoothDevice.BOND_BONDING -> {
                                    if (D) Log.d(TAG, "Bonding to ${device.name}")
                                }
                                BluetoothDevice.BOND_NONE -> {
                                    when (prevState) {
                                        BluetoothDevice.BOND_BONDING -> {
                                            // User pressed cancel to bonding request.
                                            if (D) Log.d(TAG, "User denied bonding request to ${device.name}")
                                            Toast.makeText(this@MainActivity, R.string.pairing_rejected, Toast.LENGTH_SHORT).show()
                                            mBluetoothDevice = null
                                            mBleService?.close()
                                            resetLastBleDevice()
                                            disconnected()
                                        }
                                        BluetoothDevice.BOND_BONDED -> {
                                            // Explicitly unbonded device. This should only occur when
                                            // we need to re-bond the device via BLE.
                                            if (D) Log.d(TAG, "Explicitly unbonded ${device.name}")
                                            device.createBond()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    companion object {
        private val TAG = MainActivity::class.java.name
        private val D = true

        private const val REQUEST_REQUIRED_PERMISSIONS = 1
        private const val REQUEST_BLE_PERMISSIONS = 1

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
        private val REQUEST_DISCONNECT_DEVICE = 3
    }
}