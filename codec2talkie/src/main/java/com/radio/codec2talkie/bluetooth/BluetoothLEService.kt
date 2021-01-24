package com.radio.codec2talkie.bluetooth

import android.app.Service
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import java.util.*


const val ACTION_GATT_CONNECTED = "com.radio.codec2talkie.bluetooth.ACTION_GATT_CONNECTED"
const val ACTION_GATT_DISCONNECTED = "com.radio.codec2talkie.bluetooth.ACTION_GATT_DISCONNECTED"
const val ACTION_GATT_SERVICES_DISCOVERED = "com.radio.codec2talkie.bluetooth.ACTION_GATT_SERVICES_DISCOVERED"
const val ACTION_GATT_SERVICE_DISCOVERY_FAILED = "com.radio.codec2talkie.bluetooth.ACTION_GATT_SERVICE_DISCOVERY_FAILED"
const val ACTION_DATA_AVAILABLE = "com.radio.codec2talkie.bluetooth.ACTION_DATA_AVAILABLE"
const val EXTRA_DATA = "com.radio.codec2talkie.bluetooth.EXTRA_DATA"

private const val STATE_DISCONNECTED = 0
private const val STATE_CONNECTING = 1
private const val STATE_CONNECTED = 2

// A service that interacts with the BLE device via the Android BLE API.
class BluetoothLEService : Service() {

    private val TNC_SERVICE_UUID = UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb")
    private val TNC_SERVICE_TX_UUID = UUID.fromString("00000002-ba2a-46c9-ae49-01b0961f68bb")
    private val TNC_SERVICE_RX_UUID = UUID.fromString("00000003-ba2a-46c9-ae49-01b0961f68bb")
    private val CONFIG_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val D = true
    private val TAG = BluetoothLEService::class.java.simpleName

    private var mBluetoothManager: BluetoothManager? = null
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private val mBluetoothDeviceAddress: String? = null

    private var bluetoothDevice: BluetoothDevice? = null
    private var connectionState = STATE_DISCONNECTED
    private var tncService: BluetoothGattService? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    var enabled: Boolean = true
    private var bluetoothGatt: BluetoothGatt? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "Service started")
    }

    // Various callback methods defined by the BLE API.
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectionState = STATE_CONNECTED
                    broadcastUpdate(ACTION_GATT_CONNECTED)
                    Log.i(TAG, "Connected to GATT server.")
                    Log.i(TAG, "Attempting to start service discovery")
                    gatt?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectionState = STATE_DISCONNECTED
                    Log.i(TAG, "Disconnected from GATT server (status = $status).")
                    tncService = null;
                    bluetoothGatt = null
                    rxCharacteristic = null
                    txCharacteristic = null
                    broadcastUpdate(ACTION_GATT_DISCONNECTED)
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
                    broadcastUpdate(ACTION_GATT_SERVICE_DISCOVERY_FAILED)
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
                    broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic)
                }
            }
        }

        // Characteristic notification
        override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
        ) {
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.d("onCharacteristicWrite", "Failed write, retrying: $status")
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

            Log.i(TAG, "KISS TNC Service characteristics acquired")

            bluetoothGatt?.setCharacteristicNotification(rxCharacteristic, enabled)
            val descriptor = rxCharacteristic!!.getDescriptor(CONFIG_DESCRIPTOR_UUID).apply {
                value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            bluetoothGatt?.writeDescriptor(descriptor)
            Log.i(TAG, "KISS TNC Service RX notification enabled")
            broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED)

            txCharacteristic?.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        }
    }

    private fun broadcastUpdate(action: String) {
        var intent = Intent(action)
        sendBroadcast(intent)
    }

    fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

    private fun broadcastUpdate(action: String, characteristic: BluetoothGattCharacteristic) {
        val intent = Intent(action)

        when (characteristic.uuid) {
            TNC_SERVICE_RX_UUID -> {
                Log.d(TAG, "Received KISS data: " + characteristic.value.toHexString())
                intent.putExtra(EXTRA_DATA, characteristic.value)
            }
            else -> {
                // For all other profiles, writes the data formatted in HEX.
                Log.d(TAG, "Unexpected characteristic: " + characteristic.uuid)
            }
        }
        sendBroadcast(intent)
    }

    fun write(data: ByteArray) : Boolean
    {
        if (txCharacteristic != null && bluetoothGatt!= null) {
            txCharacteristic!!.value = data
            return bluetoothGatt!!.writeCharacteristic(txCharacteristic!!)
        } else {
            Log.w(TAG, "write called while not connected")
            return false
        }
    }

    fun close() {
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    inner class LocalBinder : Binder() {
        val service: BluetoothLEService
            get() = this@BluetoothLEService
    }

    override fun onBind(intent: Intent?): IBinder? {
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
    fun initialize(device: BluetoothDevice) {
        bluetoothDevice = device
        bluetoothGatt = bluetoothDevice!!.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        connectionState = STATE_CONNECTING
    }
}
