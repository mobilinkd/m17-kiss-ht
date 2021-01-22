package com.radio.codec2talkie.m17;

import java.io.IOException;

public abstract class M17Callback {
    abstract protected void onSend(byte[] data) throws IOException;
    abstract protected void onReceiveLinkSetup(String callsign);
    abstract protected void onReceiveAudio(byte[] frame);
}
