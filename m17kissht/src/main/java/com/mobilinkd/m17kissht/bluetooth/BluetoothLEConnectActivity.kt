package com.mobilinkd.m17kissht.bluetooth

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import com.mobilinkd.m17kissht.R
import java.util.*

class BluetoothLEConnectActivity : Activity() {

    // Debugging
    private val TAG = "MainFragment"
    private val D = true

    private val BT_ENABLE = 1
    private val BT_CONNECT_SUCCESS = 2
    private val BT_PAIRING_FAILURE = 3
    private val BT_SOCKET_FAILURE = 4
    private val BT_ADAPTER_FAILURE = 5

    interface Listener {
        fun onBluetoothConnect(scanResult: ScanResult)
    }

    private lateinit var mListener: Listener
    private lateinit var bluetoothGatt: BluetoothGatt

    private val TNC_SERVICE_UUID = UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb")
    private val REQUEST_ENABLE_BLUETOOTH = 1
    private val REQUEST_CONNECT_DEVICE = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usb_connect)

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

    override fun onStart() {
        super.onStart()
        pairWithDevice()
    }

    private val deviceManager: CompanionDeviceManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(CompanionDeviceManager::class.java)
    }

    fun pairWithDevice() {
        // Connect to devices advertising KISS_TNC_SERVICE
        val deviceFilter: BluetoothLeDeviceFilter = BluetoothLeDeviceFilter.Builder()
                .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(TNC_SERVICE_UUID)).build())
                .build()

        if (D) Log.d(TAG, "deviceFilter construced with UUID " + TNC_SERVICE_UUID)

        // The argument provided in setSingleDevice() determines whether a single
        // device name or a list of device names is presented to the user as
        // pairing options.
        val pairingRequest: AssociationRequest = AssociationRequest.Builder()
                .addDeviceFilter(deviceFilter)
                .setSingleDevice(true)
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
                    // User has chosen to pair with the Bluetooth device.
                    val scanResult = data!!.getParcelableExtra<ScanResult>(CompanionDeviceManager.EXTRA_DEVICE)
                    if (D) Log.d(TAG, "onActivityResult REQUEST_CONNECT_DEVICE = " + scanResult!!.device.address)
                    val resultIntent = Intent()
                    resultIntent.putExtra("device", scanResult!!.device)
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
            }
        }
    }

}