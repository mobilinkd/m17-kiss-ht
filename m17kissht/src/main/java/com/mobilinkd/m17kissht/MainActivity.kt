package com.mobilinkd.m17kissht

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
import androidx.core.content.ContextCompat
import com.mobilinkd.m17kissht.bluetooth.*
import com.mobilinkd.m17kissht.usb.UsbConnectActivity
import com.mobilinkd.m17kissht.usb.UsbPortHandler
import com.ustadmobile.codec2.Codec2
import java.io.IOException
import java.lang.StringBuilder
import java.util.*


class MainActivity : AppCompatActivity() {
    private val _requiredPermissions = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.RECORD_AUDIO
    )
    private var mIsActive = false
    private var mDeviceTextView: TextView? = null
    private var mStatusTextView: TextView? = null
    private var mAudioLevelBar: ProgressBar? = null
    private var mEditCallsign: TextView? = null
    private var mReceivingCallsign: TextView? = null
    private var mConnectButton: ToggleButton? = null
    private var mTransmitButton: Button? = null
    private var mAudioPlayer: Codec2Player? = null
    private var mCallsign: String? = null


    private var mBluetoothDevice: BluetoothDevice? = null
    private var mService: BluetoothLEService? = null
    private var mBound: Boolean = false

    /** Defines callbacks for service binding, passed to bindService()  */
    private val connection = object : ServiceConnection {

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.i(TAG, "Service connected")
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            if (!mBound) {
                val binder = service as BluetoothLEService.LocalBinder
                mService = binder.service
                mBound = true
            }
            mService?.initialize(mBluetoothDevice!!)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mBound = false
        }
    }

    private val bluetoothReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when(intent?.action) {
                ACTION_GATT_CONNECTED -> {
                    Log.i(TAG, "GATT connected")
                    mDeviceTextView!!.text = mBluetoothDevice?.name
                    mConnectButton?.isActivated = true
                    mConnectButton?.isEnabled = true
                }
                ACTION_GATT_SERVICES_DISCOVERED -> {
                    Log.i(TAG, "KISS TNC Service connected")
                    if (mCallsign != null) mTransmitButton!!.isEnabled = true
                    try {
                        startPlayer(false)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
                ACTION_GATT_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected")
                    if (mAudioPlayer != null) {
                        Toast.makeText(this@MainActivity, "Bluetooth disconnected", Toast.LENGTH_SHORT).show()
                        mAudioPlayer!!.stopRunning()
                    }
                    mConnectButton?.isActivated = false
                    mConnectButton?.isEnabled = true
                    mDeviceTextView?.text = getString(R.string.not_connected_label)
                    mTransmitButton?.isEnabled = false
                }
                ACTION_DATA_AVAILABLE -> {
                    var data = intent.extras?.get(EXTRA_DATA) as ByteArray
                    mAudioPlayer?.onBluetoothData(data)
                }
            }
         }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mIsActive = true
        setContentView(R.layout.activity_main)
        mDeviceTextView = findViewById(R.id.textBtName)
        mStatusTextView = findViewById(R.id.textViewState)
        mAudioLevelBar = findViewById(R.id.progressTxRxLevel)
        mAudioLevelBar!!.setMax(-Codec2Player.audioMinLevel)
        mEditCallsign = findViewById(R.id.editTextCallSign)
        mEditCallsign!!.setOnEditorActionListener(onCallsignChanged)
        mReceivingCallsign = findViewById(R.id.textViewReceivedCallsign)
        mTransmitButton = findViewById(R.id.buttonTransmit)
        mTransmitButton!!.setOnTouchListener(onBtnPttTouchListener)
        mConnectButton = findViewById(R.id.connectButton)
        mConnectButton!!.setOnClickListener(onConnectListener)

        mCallsign = getLastCallsign()
        if (mCallsign != null) {
            mEditCallsign!!.text = mCallsign
        }

        registerReceiver(onUsbDetached, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        if (requestPermissions()) {
            startUsbConnectActivity()
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(bluetoothReceiver, makeGattUpdateIntentFilter())
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(bluetoothReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        mIsActive = false
        if (mAudioPlayer != null) {
            mAudioPlayer!!.stopRunning()
        }
    }

    protected fun startUsbConnectActivity() {
        val usbConnectIntent = Intent(this, UsbConnectActivity::class.java)
        startActivityForResult(usbConnectIntent, REQUEST_CONNECT_USB)
    }

    protected fun startBluetoothConnectActivity() {
        val bluetoothConnectIntent = Intent(this, BluetoothLEConnectActivity::class.java)
        startActivityForResult(bluetoothConnectIntent, REQUEST_CONNECT_BT)
    }

    protected fun requestPermissions(): Boolean {
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

    private val onLoopbackCheckedChangeListener = CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
        if (mAudioPlayer != null) {
            mAudioPlayer!!.setLoopbackMode(isChecked)
        }
    }
    private val onCallsignChanged = TextView.OnEditorActionListener { textView, actionId, keyEvent ->
        if (actionId == EditorInfo.IME_ACTION_DONE ||
                keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (keyEvent == null || !keyEvent.isShiftPressed) {
                mCallsign = validateCallsign(textView.text.toString())
                textView.text = mCallsign
                mAudioPlayer?.setCallsign(mCallsign)
                mTransmitButton!!.isEnabled = true
                textView.clearFocus()
                setLastCallsign(mCallsign!!)
                return@OnEditorActionListener false // hide keyboard.
            }
        }
        false
    }

    private val onUsbDetached: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (mAudioPlayer != null && UsbPortHandler.getPort() != null) {
                Toast.makeText(this@MainActivity, "USB detached", Toast.LENGTH_SHORT).show()
                mAudioPlayer!!.stopRunning()
                mTransmitButton!!.isEnabled = false
                mConnectButton?.isActivated = false
                mConnectButton?.text = getString(R.string.connect_label)
                mConnectButton?.isEnabled = true
                mDeviceTextView?.text = getString(R.string.not_connected_label)
            }
        }
    }

    private val onBtnPttTouchListener = View.OnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> if (mAudioPlayer != null && mCallsign != null) mAudioPlayer!!.startRecording()
            MotionEvent.ACTION_UP -> {
                v.performClick()
                if (mAudioPlayer != null) mAudioPlayer!!.startPlayback()
            }
        }
        false
    }

    private val onConnectListener = View.OnClickListener { _ ->
        if (mConnectButton!!.isChecked) {
            connectToBluetooth()
        } else {
            mService?.close()
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
                startUsbConnectActivity()
            } else {
                Toast.makeText(this@MainActivity, "Permissions Denied", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private val onPlayerStateChanged: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (mIsActive && msg.what == Codec2Player.PLAYER_DISCONNECT) {
                mStatusTextView!!.text = "STOP"
                Toast.makeText(baseContext, "Disconnected from modem", Toast.LENGTH_SHORT).show()
                startUsbConnectActivity()
            } else if (msg.what == Codec2Player.PLAYER_LISTENING) {
                mStatusTextView!!.setText(R.string.state_label_idle)
                mReceivingCallsign!!.text = ""
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
                val callsign = msg.obj as String
                mReceivingCallsign!!.text = callsign
            }
        }
    }

    @Throws(IOException::class)
    private fun startPlayer(isUsb: Boolean) {
        mAudioPlayer = Codec2Player(onPlayerStateChanged, CODEC2_DEFAULT_MODE, mCallsign ?: "")
        if (isUsb) {
            mAudioPlayer!!.setUsbPort(UsbPortHandler.getPort())
        } else {
            mAudioPlayer!!.setBleService(mService!!)
        }
        mAudioPlayer!!.start()
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
        var result = StringBuilder()
        var size = 0
        for (c in callsign) {
            if (c >= '0' && c <= '9') {
                result.append(c)
                size += 1
            } else if (c >= 'A' && c <= 'Z') {
                result.append(c)
                size += 1
            } else if (c == '-' || c == '/' || c == '.') {
                result.append(c)
                size += 1
            } else if (c >= 'a' && c <= 'z') {
                result.append(c - 64)
                size += 1
            }
            if (size == 9) break;
        }
        return result.toString()
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

    private fun bindBleService(device: BluetoothDevice) {
        mBluetoothDevice = device
        Log.i(TAG, "Bluetooth connect to " + mBluetoothDevice?.name)
        val gattServiceIntent = Intent(this, BluetoothLEService::class.java)
        if (!mBound) {
            bindService(gattServiceIntent, connection, BIND_AUTO_CREATE)
        } else {
            Log.i(TAG, "Re-initializing bound service")
            mService?.initialize(mBluetoothDevice!!)
        }
        mConnectButton?.isEnabled = false
    }

    private fun connectToBluetooth() {
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
        startBluetoothConnectActivity()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONNECT_BT) {
            if (resultCode == RESULT_CANCELED) {
                finish()
            } else if (resultCode == RESULT_OK) {
                if (data == null) {
                    Log.w(TAG, "REQUEST_CONNECT_BT: intent is null")
                }
                val device = data?.getParcelableExtra("device") as BluetoothDevice?
                if (device != null) {
                    setLastBleDevice(device.address)
                    bindBleService(device)
                }
            }
        }
        if (requestCode == REQUEST_CONNECT_USB) {
            if (resultCode == RESULT_OK) {
                if (mCallsign != null) mTransmitButton!!.isEnabled = true
                mDeviceTextView!!.text = data!!.getStringExtra("name")
                mConnectButton?.isActivated = true
                mConnectButton?.text = getString(R.string.disconnect_label)
                mConnectButton?.isEnabled = false
                try {
                    startPlayer(true)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun makeGattUpdateIntentFilter(): IntentFilter? {
        val intentFilter = IntentFilter()
        intentFilter.addAction(ACTION_GATT_CONNECTED)
        intentFilter.addAction(ACTION_GATT_DISCONNECTED)
        intentFilter.addAction(ACTION_GATT_SERVICES_DISCOVERED)
        intentFilter.addAction(ACTION_DATA_AVAILABLE)
        return intentFilter
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val REQUEST_CONNECT_BT = 1
        private const val REQUEST_CONNECT_USB = 2
        private const val REQUEST_PERMISSIONS = 3
        private const val CODEC2_DEFAULT_MODE = Codec2.CODEC2_MODE_3200
        private const val CODEC2_DEFAULT_MODE_POS = 0
    }
}