package com.radio.codec2talkie.usb;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import com.radio.codec2talkie.R;

import java.util.Map;

public class UsbConnectActivity extends AppCompatActivity {

    boolean D = true;
    String TAG = "UsbConnectActivity";

    private final int USB_NOT_FOUND = 1;
    private final int USB_CONNECTED = 2;

    private final int USB_BAUD_RATE = 38400;
    private final int USB_DATA_BITS = 8;
    private final int USB_STOP_BITS = UsbSerialInterface.STOP_BITS_1;
    private final int USB_PARITY = UsbSerialInterface.PARITY_NONE;

    private String mDeviceName;
    private UsbDevice mUsbDevice;
    private UsbSerialDevice mSerialDevice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_connect);

        ProgressBar progressBarUsb = findViewById(R.id.progressBarUsb);
        progressBarUsb.setVisibility(View.VISIBLE);
        ObjectAnimator.ofInt(progressBarUsb, "progress", 10)
                .setDuration(300)
                .start();
        connectUsb();
    }

    private void connectUsb() {

        new Thread() {
            @Override
            public void run() {
                String USB_PERM_ACTION = "org.aprsdroid.app.UsbTnc.PERM";

                Message resultMsg = new Message();

                UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);

                Map<String, UsbDevice> deviceList = manager.getDeviceList();
                for (Map.Entry<String, UsbDevice> entry: deviceList.entrySet()) {
                    if (UsbSerialDevice.isSupported(entry.getValue())) {
                        mUsbDevice = entry.getValue();
                        mDeviceName = mUsbDevice.getProductName();
                        break;
                    } else {
                        if (D) Log.i(TAG, "Unsupported USB device " + entry.getKey());
                        return;
                    }
                }

                if (mUsbDevice == null) {
                    Log.e(TAG, "No supported USB device found.");
                    return;
                }

                UsbDeviceConnection usbConnection = manager.openDevice(mUsbDevice);
                mSerialDevice = UsbSerialDevice.createUsbSerialDevice(mUsbDevice, usbConnection);
                if (mSerialDevice == null || !mSerialDevice.syncOpen()) {
                    Log.e(TAG, "failed to open " + mDeviceName);
                    resultMsg.what = USB_NOT_FOUND;
                    onUsbStateChanged.sendMessage(resultMsg);
                    return;
                }

                mSerialDevice.setBaudRate(USB_BAUD_RATE);
                mSerialDevice.setDataBits(USB_DATA_BITS);
                mSerialDevice.setStopBits(USB_STOP_BITS);
                mSerialDevice.setParity(USB_PARITY);
                mSerialDevice.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
                mSerialDevice.setDTR(true);
                mSerialDevice.setRTS(true);
                resultMsg.what = USB_CONNECTED;
                onUsbStateChanged.sendMessage(resultMsg);
            }
        }.start();
    }

    private final Handler onUsbStateChanged = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            String toastMsg;
            if (msg.what == USB_CONNECTED) {
                UsbPortHandler.setPort(mSerialDevice);

                toastMsg = String.format("USB connected %s", mDeviceName);
                Toast.makeText(getBaseContext(), toastMsg, Toast.LENGTH_SHORT).show();

                Intent resultIntent = new Intent();
                resultIntent.putExtra("name", mDeviceName);
                setResult(Activity.RESULT_OK, resultIntent);
            } else {
                setResult(Activity.RESULT_CANCELED);
            }
            finish();
        }
    };
}
