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
    private var _isActive = false
    private var _textConnInfo: TextView? = null
    private var _textStatus: TextView? = null
    private var _progressTxRxLevel: ProgressBar? = null
    private var _editTextCallSign: TextView? = null
    private var _receivedCallSign: TextView? = null
    private var _transmitButton: Button? = null
    private var _codec2Player: Codec2Player? = null
    private var _callsign: String? = null


    private var mBluetoothDevice: BluetoothDevice? = null
    private var mService: BluetoothLEService? = null
    private var mBound: Boolean = false

    /** Defines callbacks for service binding, passed to bindService()  */
    private val connection = object : ServiceConnection {

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.i(TAG, "Service connected")
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            val binder = service as BluetoothLEService.LocalBinder
            mService = binder.service
            mBound = true
            binder.service.initialize(mBluetoothDevice!!)
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
                }
                ACTION_GATT_SERVICES_DISCOVERED -> {
                    Log.i(TAG, "KISS TNC Service connected")
                    if (_callsign != null) _transmitButton!!.isEnabled = true
                    try {
                        startPlayer(false)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
                ACTION_GATT_DISCONNECTED -> {
                    if (_codec2Player != null) {
                        Toast.makeText(this@MainActivity, "Bluetooth disconnected", Toast.LENGTH_SHORT).show()
                        _codec2Player!!.stopRunning()
                    }
                    _transmitButton?.isEnabled = false
                    startBluetoothConnectActivity()
                }
                ACTION_DATA_AVAILABLE -> {
                    var data = intent.extras?.get(EXTRA_DATA) as ByteArray
                    _codec2Player?.onBluetoothData(data)
                }
            }
         }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _isActive = true
        setContentView(R.layout.activity_main)
        _textConnInfo = findViewById(R.id.textBtName)
        _textStatus = findViewById(R.id.textViewState)
        _progressTxRxLevel = findViewById(R.id.progressTxRxLevel)
        _progressTxRxLevel!!.setMax(-Codec2Player.audioMinLevel)
        _editTextCallSign = findViewById(R.id.editTextCallSign)
        _editTextCallSign!!.setOnEditorActionListener(onCallsignChanged)
        _receivedCallSign = findViewById(R.id.textViewReceivedCallsign)
        _transmitButton = findViewById(R.id.buttonTransmit)
        _transmitButton!!.setOnTouchListener(onBtnPttTouchListener)

        _callsign = getLastCallsign()
        if (_callsign != null) {
            _editTextCallSign!!.text = _callsign
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
        _isActive = false
        if (_codec2Player != null) {
            _codec2Player!!.stopRunning()
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
        if (_codec2Player != null) {
            _codec2Player!!.setLoopbackMode(isChecked)
        }
    }
    private val onCallsignChanged = TextView.OnEditorActionListener { textView, actionId, keyEvent ->
        if (actionId == EditorInfo.IME_ACTION_DONE ||
                keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (keyEvent == null || !keyEvent.isShiftPressed) {
                _callsign = validateCallsign(textView.text.toString())
                textView.text = _callsign
                _codec2Player?.setCallsign(_callsign)
                _transmitButton!!.isEnabled = true
                textView.clearFocus()
                setLastCallsign(_callsign!!)
                return@OnEditorActionListener false // hide keyboard.
            }
        }
        false
    }

    private val onUsbDetached: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (_codec2Player != null && UsbPortHandler.getPort() != null) {
                Toast.makeText(this@MainActivity, "USB detached", Toast.LENGTH_SHORT).show()
                _codec2Player!!.stopRunning()
                _transmitButton!!.isEnabled = false
            }
        }
    }
    private val onBtnPttTouchListener = View.OnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> if (_codec2Player != null && _callsign != null) _codec2Player!!.startRecording()
            MotionEvent.ACTION_UP -> {
                v.performClick()
                if (_codec2Player != null) _codec2Player!!.startPlayback()
            }
        }
        false
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
            if (_isActive && msg.what == Codec2Player.PLAYER_DISCONNECT) {
                _textStatus!!.text = "STOP"
                Toast.makeText(baseContext, "Disconnected from modem", Toast.LENGTH_SHORT).show()
                startUsbConnectActivity()
            } else if (msg.what == Codec2Player.PLAYER_LISTENING) {
                _textStatus!!.setText(R.string.state_label_idle)
                _receivedCallSign!!.text = ""
            } else if (msg.what == Codec2Player.PLAYER_RECORDING) {
                _textStatus!!.setText(R.string.state_label_transmit)
            } else if (msg.what == Codec2Player.PLAYER_PLAYING) {
                _textStatus!!.setText(R.string.state_label_receive)
            } else if (msg.what == Codec2Player.PLAYER_RX_LEVEL) {
                _progressTxRxLevel!!.progressDrawable.colorFilter = PorterDuffColorFilter(colorFromAudioLevel(msg.arg1), PorterDuff.Mode.SRC_IN)
                _progressTxRxLevel!!.progress = msg.arg1 - Codec2Player.audioMinLevel
            } else if (msg.what == Codec2Player.PLAYER_TX_LEVEL) {
                _progressTxRxLevel!!.progressDrawable.colorFilter = PorterDuffColorFilter(colorFromAudioLevel(msg.arg1), PorterDuff.Mode.SRC_IN)
                _progressTxRxLevel!!.progress = msg.arg1 - Codec2Player.audioMinLevel
            } else if (msg.what == Codec2Player.PLAYER_CALLSIGN_RECEIVED) {
                val callsign = msg.obj as String
                _receivedCallSign!!.text = callsign
            }
        }
    }

    @Throws(IOException::class)
    private fun startPlayer(isUsb: Boolean) {
        _codec2Player = Codec2Player(onPlayerStateChanged, CODEC2_DEFAULT_MODE, _callsign ?: "")
        if (isUsb) {
            _codec2Player!!.setUsbPort(UsbPortHandler.getPort())
        } else {
            _codec2Player!!.setBleService(mService!!)
        }
        _codec2Player!!.start()
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
        Log.i(TAG, "Bluetooth connect to " + mBluetoothDevice?.name);
        val gattServiceIntent = Intent(this, BluetoothLEService::class.java)
        bindService(gattServiceIntent, connection, BIND_AUTO_CREATE)
        _textConnInfo!!.text = mBluetoothDevice?.name
    }

    private fun connectToBluetooth() {
        val address = getLastBleDevice()
        if (address != null) {
            Log.i(TAG, "Bluetooth connecting to last device @ " + address);
            val bluetoothManager: BluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val device = bluetoothManager.getAdapter().getRemoteDevice(address)
            bindBleService(device)
        } else {
            startBluetoothConnectActivity()
        }
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
            if (resultCode == RESULT_CANCELED) {
                connectToBluetooth()
            } else if (resultCode == RESULT_OK) {
                if (_callsign != null) _transmitButton!!.isEnabled = true
                _textConnInfo!!.text = data!!.getStringExtra("name")
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