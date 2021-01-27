package com.mobilinkd.m17kissht.kiss;

import java.io.IOException;

public abstract class KissCallback {
    abstract protected void onSend(byte[] data) throws IOException;
    abstract protected void onReceive(byte [] frame);
}
