package com.mobilinkd.m17kissht.bluetooth

import android.app.Service
import android.bluetooth.*
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.util.Log
import java.util.*

const val EXTRA_DATA = "com.mobilinkd.m17kissht.bluetooth.EXTRA_DATA"

private const val STATE_DISCONNECTED = 0
private const val STATE_CONNECTING = 1
private const val STATE_CONNECTED = 2


// A service that interacts with the BLE device via the Android BLE API.
class BluetoothLEService : Service() {

    private var bluetoothDevice: BluetoothDevice? = null
    private var connectionState = STATE_DISCONNECTED
    private var tncService: BluetoothGattService? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    var enabled: Boolean = true
    private var bluetoothGatt: BluetoothGatt? = null
    private var mHandler: Handler? = null

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
            if (D) Log.d(TAG, "gattCallback: status = $status, state = $newState, bondState = ${bluetoothDevice?.bondState}")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectionState = STATE_CONNECTED
                    mHandler?.obtainMessage(GATT_CONNECTED)?.sendToTarget()
                    Log.i(TAG, "Connected to GATT server.")
                    Log.i(TAG, "Attempting to start service discovery")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectionState = STATE_DISCONNECTED
                    Log.i(TAG, "Disconnected from GATT server (status = $status).")
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    tncService = null
                    rxCharacteristic = null
                    txCharacteristic = null
                    mHandler?.obtainMessage(GATT_DISCONNECTED)?.sendToTarget()
                }
            }
        }

        // New services discovered
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.w(TAG, "onServicesDiscovered received: $gatt")
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.i(TAG, "Service discovery succeeded")
                    bluetoothGatt = gatt
                    bluetoothGatt?.requestMtu(160)
                }
                else -> {
                    Log.e(TAG, "Service discovery failed: $status")
                    mHandler?.obtainMessage(GATT_SERVICE_DISCOVERY_FAILED)?.sendToTarget()
                }
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
                    sendUpdate(characteristic)
                }
            }
        }

        // Characteristic notification
        override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
        ) {
            sendUpdate(characteristic)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w("onCharacteristicWrite", "Failed write, retrying: $status")
                gatt!!.writeCharacteristic(characteristic)
            }
            super.onCharacteristicWrite(gatt, characteristic, status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (D) Log.i(TAG, "MTU changed to $mtu")

            tncService = bluetoothGatt?.getService(TNC_SERVICE_UUID)
            if (tncService == null) {
                Log.w(TAG, "KISS TNC Service not found")
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

            Log.i(TAG, "KISS TNC Service characteristics acquired")

            // This enables notification from the RX characteristic.
            bluetoothGatt?.setCharacteristicNotification(rxCharacteristic, enabled)
            val descriptor = rxCharacteristic!!.getDescriptor(CONFIG_DESCRIPTOR_UUID).apply {
                value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            bluetoothGatt?.writeDescriptor(descriptor)
            if (D) Log.d(TAG, "KISS TNC Service RX notification enabled")

            // Must use WRITE_TYPE_NO_RESPONSE to get the necessary throughput.
            txCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (D) Log.d(TAG, "KISS TNC Service TX configured as WRITE_TYPE_NO_RESPONSE")

            mHandler?.obtainMessage(GATT_SERVICES_DISCOVERED)?.sendToTarget()
        }
    }

//    fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

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

    fun write(data: ByteArray) : Boolean
    {
        return if (txCharacteristic != null && bluetoothGatt!= null) {
            txCharacteristic!!.value = data
            val result = bluetoothGatt!!.writeCharacteristic(txCharacteristic!!)
            if (!result) {
                Log.w(TAG, "write failed")
            }
            result
        } else {
            Log.w(TAG, "write called while not connected")
            false
        }
    }

    fun close() {
        bluetoothGatt?.disconnect()
    }

    inner class LocalBinder : Binder() {
        val service: BluetoothLEService
            get() = this@BluetoothLEService
    }

    override fun onBind(intent: Intent?): IBinder {
        return mBinder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
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
        bluetoothDevice = device
        mHandler = handler
        bluetoothGatt = bluetoothDevice!!.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
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
    }
}
