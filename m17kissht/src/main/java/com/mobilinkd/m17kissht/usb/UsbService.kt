package com.mobilinkd.m17kissht.usb

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.util.Log
import com.felhr.usbserial.UsbSerialDevice
import com.felhr.usbserial.UsbSerialInterface
import com.felhr.usbserial.UsbSerialInterface.*


class UsbService : Service() {
    private var mMainActivity: Activity? = null
    private var mHandler: Handler? = null
    private var mUsbManager: UsbManager? = null
    private var mUsbDevice: UsbDevice? = null
    private var mConnection: UsbDeviceConnection? = null
    private var mSerialDevice: UsbSerialDevice? = null
    private var mDeviceName: String? = null

    inner class LocalBinder : Binder() {
        val service: UsbService
            get() = this@UsbService
    }

    private val mBinder: IBinder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder? {
        Log.i(TAG, "onBind")
        return mBinder
    }

    override fun onCreate() {
        SERVICE_CONNECTED = true
        mUsbManager = getSystemService(USB_SERVICE) as UsbManager
        Log.i(TAG, "Service connected")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
        Log.i(TAG, "Service started")
    }

    override fun onDestroy() {
        super.onDestroy()
        mSerialDevice?.close()
        mSerialDevice = null
        SERVICE_CONNECTED = false
        Log.i(TAG, "Service destroyed")
    }


    private val readCallback = UsbReadCallback { data ->
        mHandler?.obtainMessage(DATA_RECEIVED, data)?.sendToTarget()
    }
    private val ctsCallback = UsbCTSCallback { mHandler?.obtainMessage(CTS_CHANGE)?.sendToTarget() }
    private val dsrCallback = UsbDSRCallback { mHandler?.obtainMessage(DSR_CHANGE)?.sendToTarget() }

    fun connect() {
        if (mConnection != null) return
        mConnection = mUsbManager!!.openDevice(mUsbDevice)
        connectUsb()
    }

    fun connected() : Boolean {
        return (mSerialDevice != null)
    }

    fun disconnect() {
        mSerialDevice?.close()
        mSerialDevice = null
        mConnection = null
    }

    fun write(data: ByteArray?) {
        mSerialDevice?.write(data)
    }

    fun read(data: ByteArray, timeout: Int) : Int {
        return mSerialDevice!!.syncRead(data, timeout)
    }

    fun setHandler(mHandler: Handler?) {
        this.mHandler = mHandler
    }

    fun setMainActivity(activity: Activity) {
        this.mMainActivity = activity
    }

    fun attachSupportedDevice(device : UsbDevice) : Boolean {
        if (UsbSerialDevice.isSupported(device)) {
            Log.d(TAG, "Requesting permission for USB access")
            mUsbDevice = device
            mDeviceName = mUsbDevice!!.productName
            val pendingIntent = PendingIntent.getBroadcast(mMainActivity, 0, Intent(ACTION_USB_PERMISSION), 0)
            mUsbManager!!.requestPermission(mUsbDevice!!, pendingIntent)
            return true
        }
        Log.w(TAG, "Device not supported")
        return false
    }

    private fun attachDevice() {
        val deviceList: Map<String, UsbDevice?> = mUsbManager!!.deviceList
        for ((key, value) in deviceList) {
            if (value != null && attachSupportedDevice(value)) {
                return
            }
        }

        if (mUsbDevice == null) {
            Log.e(TAG, "No supported USB device found.")
            val intent = Intent(ACTION_NO_USB)
            this.sendBroadcast(intent)
            return
        }
    }

    private fun setFilter() {
    }

    private fun requestUserPermission() {
        Log.d(TAG,"requestUserPermission(%X:%X)".format(
                mUsbDevice!!.getVendorId(),
                mUsbDevice!!.getProductId()
            )
        )
        val mPendingIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), 0)
        mUsbManager?.requestPermission(mUsbDevice, mPendingIntent)
    }

    private fun connectUsb() {
        Log.d(TAG, "connectUsb() invoked")
        object : Thread() {
            override fun run() {
                Log.d(TAG, "Connect USB thread running")
                val resultMsg = Message()
                mSerialDevice = UsbSerialDevice.createUsbSerialDevice(mUsbDevice, mConnection)
                if (mSerialDevice == null || !mSerialDevice!!.open()) {
                    Log.e(TAG, "failed to open $mDeviceName")
                    val intent = Intent(ACTION_USB_NOT_SUPPORTED)
                    this@UsbService.sendBroadcast(intent)
                    return
                }
                mSerialDevice!!.setBaudRate(USB_BAUD_RATE)
                mSerialDevice!!.setDataBits(USB_DATA_BITS)
                mSerialDevice!!.setStopBits(USB_STOP_BITS)
                mSerialDevice!!.setParity(USB_PARITY)
                mSerialDevice!!.setFlowControl(FLOW_CONTROL_OFF)
                mSerialDevice!!.setDTR(true)
                mSerialDevice!!.setRTS(true)

                mSerialDevice!!.read(readCallback)
                Log.d(TAG, "reading now...")
                mSerialDevice!!.getCTS(ctsCallback)
                mSerialDevice!!.getDSR(dsrCallback)

                Log.d(TAG, "Sending ACTION_USB_READY")
                val intent = Intent(ACTION_USB_READY)
                intent.putExtra(USB_DEVICE_NAME, mDeviceName!!)
                this@UsbService.sendBroadcast(intent)
            }
        }.start()
    }

    companion object {
        private val TAG = UsbService::class.java.name

        const val ACTION_USB_READY = "com.mobilinkd.m17kissht.USB_READY"
        const val ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
        const val ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"
        const val ACTION_USB_PERMISSION = "com.mobilinkd.m17kissht.USB_PERMISSION"
        const val ACTION_USB_NOT_SUPPORTED = "com.mobilinkd.m17kissht.USB_NOT_SUPPORTED"
        const val ACTION_NO_USB = "com.mobilinkd.m17kissht.NO_USB"
        const val ACTION_USB_PERMISSION_GRANTED = "com.mobilinkd.m17kissht.USB_PERMISSION_GRANTED"
        const val ACTION_USB_PERMISSION_NOT_GRANTED = "com.mobilinkd.m17kissht.USB_PERMISSION_NOT_GRANTED"
        const val ACTION_USB_DISCONNECTED = "com.mobilinkd.m17kissht.USB_DISCONNECTED"

        const val USB_DEVICE_NAME = "com.mobilinkd.m17kissht.USB_DEVICE_NAME"

        const val DATA_RECEIVED = 0
        const val CTS_CHANGE = 1
        const val DSR_CHANGE = 2

        // Connection parameters -- Required values for NucleoTNC
        private const val USB_BAUD_RATE = 38400
        private const val USB_DATA_BITS = 8
        private const val USB_STOP_BITS = UsbSerialInterface.STOP_BITS_1
        private const val USB_PARITY = UsbSerialInterface.PARITY_NONE

        var SERVICE_CONNECTED = false
    }
}