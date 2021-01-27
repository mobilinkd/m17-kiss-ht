package com.mobilinkd.m17kissht.usb

import android.animation.ObjectAnimator
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.felhr.usbserial.UsbSerialDevice
import com.felhr.usbserial.UsbSerialInterface
import com.mobilinkd.m17kissht.R

class UsbConnectActivity : AppCompatActivity() {
    // Logging/Debugging
    var D = true
    var TAG = "UsbConnectActivity"

    // Broadcasts
    val USB_PERM_ACTION = "com.mobilinkd.m17kissht.UsbConnectActivity.USB_PERM_ACTION"
    private val ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
    private val ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"

    // Results
    val USB_NOT_FOUND = 1
    val USB_NO_PERMISSION = 2
    val USB_OPEN_FAILED = 3
    val USB_CONNECTED = 4
    val USB_DISCONNECTED = 5

    // Connection parameters
    private val USB_BAUD_RATE = 38400
    private val USB_DATA_BITS = 8
    private val USB_STOP_BITS = UsbSerialInterface.STOP_BITS_1
    private val USB_PARITY = UsbSerialInterface.PARITY_NONE

    // Device information
    private var mDeviceName: String? = null
    private var mUsbDevice: UsbDevice? = null
    private var mSerialDevice: UsbSerialDevice? = null
    private lateinit var manager: UsbManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usb_connect)
        val progressBarUsb = findViewById<ProgressBar>(R.id.progressBarUsb)
        progressBarUsb.visibility = View.VISIBLE
        ObjectAnimator.ofInt(progressBarUsb, "progress", 10)
                .setDuration(300)
                .start()
        manager = getSystemService(USB_SERVICE) as UsbManager
    }

    override fun onStart() {
        super.onStart()

        val filter = IntentFilter(USB_PERM_ACTION)
        filter.addAction(ACTION_USB_DETACHED)
        filter.addAction(ACTION_USB_ATTACHED)
        registerReceiver(receiver, filter)
        attachDevice()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(receiver)
    }

    private fun attachSupportedDevice(device : UsbDevice) : Boolean {
        if (UsbSerialDevice.isSupported(device)) {
            mUsbDevice = device
            mDeviceName = mUsbDevice!!.productName
            val intent = Intent(USB_PERM_ACTION)
            val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0)
            manager.requestPermission(mUsbDevice, pendingIntent)
            return true
        }
        return false
    }

    private fun attachDevice() {

        val resultMsg = Message()

        val deviceList: Map<String, UsbDevice?> = manager.deviceList
        for ((key, value) in deviceList) {
            if (value != null && attachSupportedDevice(value)) {
                return
            }
        }

        if (mUsbDevice == null) {
            Log.e(TAG, "No supported USB device found.")
            resultMsg.what = USB_NOT_FOUND
            onUsbStateChanged.sendMessage(resultMsg)
            return
        }
    }

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx : Context, intent : Intent) {
            Log.d(TAG, "onReceive: $intent")

            val resultMsg = Message()

            when(intent.action) {
                ACTION_USB_DETACHED -> {
                    if (D) Log.d(TAG,"USB device detached.")
                    resultMsg.what = USB_DISCONNECTED
                    onUsbStateChanged.sendMessage(resultMsg)
                    return
                }
                ACTION_USB_ATTACHED -> {
                    val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as UsbDevice?
                    if (device != null) {
                        if (D) Log.d(TAG, "USB attached device ${device.productName}")
                        if (attachSupportedDevice(device)) {
                            Log.i(TAG, "attaching ${device.productName}")
                        }
                    }
                    return
                }
                USB_PERM_ACTION -> {
                    val granted = intent.extras?.getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED)
                    if (granted != null && !granted) {
                        resultMsg.what = USB_NO_PERMISSION
                        onUsbStateChanged.sendMessage(resultMsg)
                    }
                    if (D) Log.d(TAG,"Obtained USB permissions.")
                    connectUsb()
                }
            }
        }
    }

    private fun connectUsb() {
        object : Thread() {
            override fun run() {

                val resultMsg = Message()

                val usbConnection = manager.openDevice(mUsbDevice)
                mSerialDevice = UsbSerialDevice.createUsbSerialDevice(mUsbDevice, usbConnection)
                if (mSerialDevice == null || !mSerialDevice!!.syncOpen()) {
                    Log.e(TAG, "failed to open $mDeviceName")
                    resultMsg.what = USB_OPEN_FAILED
                    onUsbStateChanged.sendMessage(resultMsg)
                    return
                }
                mSerialDevice!!.setBaudRate(USB_BAUD_RATE)
                mSerialDevice!!.setDataBits(USB_DATA_BITS)
                mSerialDevice!!.setStopBits(USB_STOP_BITS)
                mSerialDevice!!.setParity(USB_PARITY)
                mSerialDevice!!.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF)
                mSerialDevice!!.setDTR(true)
                mSerialDevice!!.setRTS(true)
                resultMsg.what = USB_CONNECTED
                onUsbStateChanged.sendMessage(resultMsg)
            }
        }.start()
    }

    private val onUsbStateChanged: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val toastMsg: String
            if (msg.what == USB_CONNECTED) {
                UsbPortHandler.setPort(mSerialDevice)
                toastMsg = String.format("USB connected %s", mDeviceName)
                Toast.makeText(baseContext, toastMsg, Toast.LENGTH_SHORT).show()
                val resultIntent = Intent()
                resultIntent.putExtra("name", mDeviceName)
                setResult(RESULT_OK, resultIntent)
            } else {
                setResult(RESULT_CANCELED)
            }
            finish()
        }
    }
}