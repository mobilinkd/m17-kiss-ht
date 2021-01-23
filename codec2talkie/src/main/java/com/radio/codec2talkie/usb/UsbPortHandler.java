package com.radio.codec2talkie.usb;

import com.felhr.usbserial.UsbSerialDevice;

public class UsbPortHandler {
    private static UsbSerialDevice port;

    public static synchronized UsbSerialDevice getPort(){
        return port;
    }

    public static synchronized void setPort(UsbSerialDevice port){
        UsbPortHandler.port = port;
    }
}
