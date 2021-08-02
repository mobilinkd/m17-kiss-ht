package com.mobilinkd.m17kissht.m17;

import java.io.IOException;

public abstract class M17Callback {
    abstract protected void onSend(byte[] data) throws IOException;
    abstract protected void onReceiveLinkSetup(String callsign);
    abstract protected void onReceiveAudio(byte[] frame);
    abstract protected void onReceiveBERT(byte[] frame);
}
