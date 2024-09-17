package com.mobilinkd.m17kissht.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.mobilinkd.m17kissht.MainActivity
import com.mobilinkd.m17kissht.R
import java.io.IOException
import java.util.*
import java.util.concurrent.Semaphore

const val EXTRA_DATA = "com.mobilinkd.m17kissht.bluetooth.EXTRA_DATA"

private const val STATE_DISCONNECTED = 0
private const val STATE_CONNECTING = 1
private const val STATE_CONNECTED = 2

@SuppressLint("MissingPermission")

// A service that interacts with the BLE device via the Android BLE API.
class BluetoothLEService : Service() {

    private var bluetoothDevice: BluetoothDevice? = null
    private var connectionState = STATE_DISCONNECTED
    private var tncService: BluetoothGattService? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var enabled: Boolean = true
    private var bluetoothGatt: BluetoothGatt? = null
    private var mHandler: Handler? = null
    private var writeSemaphore = Semaphore(1)
    private var retryCount: Int = 0;
    private var autoConnect = false;

    override fun onCreate() {
        if (D) Log.d(TAG, "onCreate()")
        return super.onCreate()
    }

    override fun onDestroy() {
        if (D) Log.d(TAG, "onDestroy()")
        super.onDestroy()
        Toast.makeText(this, "BLE service stopped", Toast.LENGTH_SHORT).show();
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started")
        return super.onStartCommand(intent, flags, startId)
    }

    // Various callback methods defined by the BLE API.
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
        ) {
            super.onConnectionStateChange(gatt, status, newState)
            if (D) Log.d(TAG, "$this -- gattCallback: gatt = $gatt, status = $status, state = $newState, bondState = ${bluetoothDevice?.bondState}")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // May receive multiple STATE_CONNECTED while negotiating auth.
                    if (connectionState != STATE_CONNECTED) {
                        Log.i(TAG, "GATT connected to ${gatt.device}")
                        connectionState = STATE_CONNECTED
                        mHandler?.obtainMessage(GATT_CONNECTED, status, if (autoConnect) 1 else 0)?.sendToTarget()
                    }
                    Log.i(TAG, "Connected to GATT server.")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (bluetoothDevice?.bondState == BluetoothDevice.BOND_BONDING) {
                        return // ignore disconnects during bonding
                    }
                    connectionState = STATE_DISCONNECTED
                    Log.i(TAG, "GATT disconnected from ${gatt.device} (status = $status).")
                    gatt.close()
                    bluetoothGatt = null
                    tncService = null
                    rxCharacteristic = null
                    txCharacteristic = null
                    mHandler?.obtainMessage(GATT_DISCONNECTED, status, if (autoConnect) 1 else 0)?.sendToTarget()
                }
            }
        }

        // New services discovered
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (D) Log.d(TAG, "onServicesDiscovered() status = $status")
            bluetoothGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.i(TAG, "Service discovery succeeded")
                    gatt.services.ifEmpty {
                        // This should never happen as we requested pairing only with devices
                        // that have a KISS TNC Service.
                        Log.e(TAG, "onServicesDiscovered: no services found")
                    }
                    gatt.services.forEach {
                        Log.i(TAG, "onServicesDiscovered UUID: ${it.uuid}")
                    }

                    tncService = gatt.getService(TNC_SERVICE_UUID)
                    if (tncService == null) {
                        if (false) {
                            val removeBond = gatt.device.javaClass.getMethod("removeBond")
                            removeBond.invoke(gatt.device)
                        }
                        Log.w(TAG, "KISS TNC Service not found")
                        mHandler?.obtainMessage(GATT_SERVICE_NOT_FOUND)?.sendToTarget()
                        return
                    }

                    Log.i(TAG, "KISS TNC Service found")
                    rxCharacteristic = tncService!!.getCharacteristic(TNC_SERVICE_RX_UUID)
                    txCharacteristic = tncService!!.getCharacteristic(TNC_SERVICE_TX_UUID)

                    if (rxCharacteristic == null) {
                        Log.e(TAG, "failed to get rxCharacteristic")
                        return
                    }

                    if (txCharacteristic == null) {
                        Log.e(TAG, "failed to get txCharacteristic")
                        return
                    }

                    mHandler?.obtainMessage(GATT_SERVICES_DISCOVERED)?.sendToTarget()

                    Log.i(TAG, "KISS TNC Service characteristics acquired")
                }
                else -> {
                    Log.e(TAG, "Service discovery failed: $status")
                    mHandler?.obtainMessage(GATT_SERVICE_DISCOVERY_FAILED)?.sendToTarget()
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (descriptor?.value == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) {
                    if (D) Log.d(TAG, "${descriptor?.uuid} = ${descriptor?.value}")
                    // Must use WRITE_TYPE_NO_RESPONSE to get the necessary throughput.
                    Log.i(TAG, "txCharacteristic properties = ${txCharacteristic?.properties}")
                    txCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    if (D) Log.d(TAG, "KISS TNC Service TX configured as WRITE_TYPE_NO_RESPONSE")
                    mHandler?.obtainMessage(GATT_READY)?.sendToTarget()
                } else {
                    bluetoothGatt?.disconnect()
                }
            } else {
                Log.e(TAG, "onDescriptorWrite failed: ${descriptor?.characteristic?.uuid}, status = ${status}")
                bluetoothGatt?.disconnect()
            }
        }

        // Result of a characteristic read operation
        override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
        ) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
//                    sendUpdate(characteristic)
                    updateCharacteristics()
                }
            }
        }

        // Characteristic notification
        override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
        ) {
            if (D) Log.d(TAG, "onCharacteristicChanged()")
            sendUpdate(characteristic)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
             if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w("onCharacteristicWrite", "Failed write, retrying: $status")
            } else {
                if (D) Log.d(TAG, "onCharacteristicWrite: GATT_SUCCESS")
                 writeSemaphore.release()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (D) Log.i(TAG, "MTU changed to $mtu")
            if (mtu >= 100) {
                mHandler?.obtainMessage(GATT_MTU_CHANGED)?.sendToTarget()
            } else {
                mHandler?.obtainMessage(GATT_MTU_FAILED)?.sendToTarget()
            }
        }
    }

//    fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

    fun startDiscovery() {
        Log.i(TAG, "startDiscovery()")
        bluetoothGatt?.discoverServices()
    }

    fun setMTU() {
        Log.i(TAG, "setMTU()")
        bluetoothGatt?.requestMtu(160)
    }

    fun bondDevice() {
        Log.i(TAG, "bondDevice()")
        bluetoothGatt?.let { gatt ->
            gatt.readCharacteristic(rxCharacteristic!!)
        }
    }

    fun updateCharacteristics() {
        retryCount = 5;
        bluetoothGatt?.let { gatt ->
            if (rxCharacteristic != null) {
                bluetoothGatt?.setCharacteristicNotification(rxCharacteristic, true)
                val descriptor = rxCharacteristic!!.getDescriptor(CONFIG_DESCRIPTOR_UUID).apply {
                    value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
                if (descriptor != null && gatt.writeDescriptor(descriptor)) {
                    Log.d(TAG, "KISS TNC Service RX notification requested")
                } else {
                    Log.e(TAG, "KISS TNC Service RX notification failed")
                }
            } else {
                Log.e(TAG, "RX Characteristic not set")
            }
        }

//            val result = gatt?.writeDescriptor(rxCharacteristic!!.getDescriptor(CONFIG_DESCRIPTOR_UUID), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
//            if (result == BluetoothGatt.GATT_SUCCESS) {
//                if (D) Log.d(TAG, "KISS TNC Service RX notification enabled")
//            } else {
//                Log.e(TAG, "KISS TNC Service RX notification failed: ${result}")
//            }

    }
    private fun sendUpdate(characteristic: BluetoothGattCharacteristic) {

        when (characteristic.uuid) {
            TNC_SERVICE_RX_UUID -> {
                mHandler?.obtainMessage(DATA_RECEIVED, characteristic.value)?.sendToTarget()
            }
            else -> {
                // For all other profiles, writes the data formatted in HEX.
                Log.w(TAG, "Unexpected characteristic: " + characteristic.uuid)
            }
        }
    }

    fun refresh() {
        val refreshCache = bluetoothGatt?.javaClass?.getMethod("refresh")
        refreshCache?.invoke(bluetoothGatt!!)
        bluetoothGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
    }

    fun write(data: ByteArray) : Boolean
    {
        return if (txCharacteristic != null && bluetoothGatt!= null) {
            writeSemaphore.acquire()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                txCharacteristic
                val result = bluetoothGatt!!.writeCharacteristic(
                    txCharacteristic!!,
                    data,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
                if (result != BluetoothStatusCodes.SUCCESS) {
                    Log.e(TAG, "write failed; result = ${result}")
                    writeSemaphore.release()
                    close()
                    false
                } else {
                    true
                }
            } else {
                txCharacteristic!!.value = data
                val result = bluetoothGatt!!.writeCharacteristic(txCharacteristic!!)
                if (!result) {
                    writeSemaphore.release()
                    Log.e(TAG, "write failed; result = ${result}")
                }
                result
            }
        } else {
            Log.w(TAG, "write called while not connected")
            false
        }
    }

    fun close() {
        if (D) Log.d(TAG, "close() - gatt = ${bluetoothGatt}")
        retryCount = 0
        bluetoothGatt?.let { gatt ->
            if (rxCharacteristic != null) {
                gatt.setCharacteristicNotification(rxCharacteristic, false)
                val descriptor = rxCharacteristic!!.getDescriptor(CONFIG_DESCRIPTOR_UUID).apply {
                    value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                if (gatt.writeDescriptor(descriptor)) {
                    Log.d(TAG, "KISS TNC Service RX notification disabled")
                } else {
                    Log.e(TAG, "KISS TNC Service RX notification failed")
                    gatt.disconnect()
                    gatt.close()
                    bluetoothGatt = null
                    tncService = null
                    rxCharacteristic = null
                    txCharacteristic = null               }
            } else {
                gatt.disconnect()
                gatt.close()
                bluetoothGatt = null
                tncService = null
                rxCharacteristic = null
                txCharacteristic = null
            }
        }
    }

    fun isConnected() : Boolean {
        return bluetoothGatt != null
    }

    inner class LocalBinder : Binder() {
        val service: BluetoothLEService
            get() = this@BluetoothLEService
    }

    override fun onBind(intent: Intent?): IBinder {
//        registerReceiver(bleReceiver, makeBleIntentFilter())
        return mBinder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        Log.i(TAG, "onUnbind: ${intent?.action}")
        close()
        return super.onUnbind(intent)
    }

    private val mBinder: IBinder = LocalBinder()

    /**
     * Initializes a reference to the local Bluetooth device.
     *
     * @return Return true if the initialization is successful.
     */
    fun initialize(device: BluetoothDevice, handler: Handler) {
        if (D) Log.d(TAG, "initialize() ${device.name}, address = ${device.address}")
        bluetoothDevice = device
        mHandler = handler

        if (false && device.type != BluetoothDevice.DEVICE_TYPE_UNKNOWN && device.bondState == BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "BLE auto-connect enabled")
            retryCount = 0
            autoConnect = true
        } else {
            Log.i(TAG, "BLE auto-connect disabled")
            retryCount = 3
            autoConnect = false
        }
        bluetoothGatt?.close()

        bluetoothGatt =
            device.connectGatt(this, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        connectionState = STATE_CONNECTING
    }

    companion object {
        private const val D = true
        private val TAG = BluetoothLEService::class.java.name

        private val TNC_SERVICE_UUID = UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb")
        private val TNC_SERVICE_TX_UUID = UUID.fromString("00000002-ba2a-46c9-ae49-01b0961f68bb")
        private val TNC_SERVICE_RX_UUID = UUID.fromString("00000003-ba2a-46c9-ae49-01b0961f68bb")
        private val CONFIG_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val DATA_RECEIVED = 1
        const val GATT_CONNECTED = 2
        const val GATT_SERVICES_DISCOVERED = 3
        const val GATT_SERVICE_DISCOVERY_FAILED = 4
        const val GATT_DISCONNECTED = 5
        const val GATT_SERVICE_NOT_FOUND = 6
        const val GATT_MTU_CHANGED = 7
        const val GATT_MTU_FAILED = 8
        const val GATT_READY = 9
        const val GATT_PHY_FAILED = 10

        fun getServiceUUID() : UUID {
            return TNC_SERVICE_UUID
        }
    }
}
