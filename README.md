![CI](https://github.com/sh123/codec2_talkie/workflows/CI/badge.svg)

# M17 KISS HT
This is a fork of the [Android Codec2 Walkie-Talkie](https://github.com/sh123/codec2_talkie),
updated to support M17 streaming KISS protocol over Bluetooth & USB.  This is an Amateur Radio DV
(digital voice) communication protocol using the using open source vocoder,
[Codec2](https://github.com/drowe67/codec2).

<img alt="Screenshot of main Activity" src="https://raw.githubusercontent.com/mobilinkd/m17-kiss-ht/master/images/screenshot.png" width="216" />

# Introduction
This Android application is a digital voice frontend for your radio. It connects to your M17
KISS Bluetooth/USB modem, sends and receives M17 link setup and audio frames, which are
encapsulated inside KISS frames. It does not deal with radio management, modulation, etc.
The modem and the radio handle the M17 data link layer and physical layer.

# Requirements
- Android 8.0 (API 26) or higher
- Modem or transceiver which supports
  [M17 KISS protocol](https://m17-protocol-specification.readthedocs.io/en/latest/kiss_protocol.html)
  over Bluetooth or USB

# Features
- **PTT button**, push and talk, an M17 digital voice stream will be initiated and maintained with
  the modem.
- **USB serial connectivity** (38400 bps, 8 data bits, 1 stop bit, no parity), just select this app
  after connecting to USB and it will use given connection.
- **BluetoothLE connectivity** use the Connect/Disconnect button to connect/disconnect BLE devices.
  It will attempt to connect to the last connected device.  Otherwise it will scan for devices.
- **Callsign Identification** enter your callsign once connected in order to be able to transmit.
- **Caller Identification** received callsign is clearly displayed when an M17 DV call is received.
- **User Preferences** BLE device preference and user callsign will be saved for later.

# Usability Notes

- Android seems to have issues connecting to the same Bluetooth device over both BLE and "classic".
  The TNC3 may need to be re-paired when switching modes, such as when switching between the
  Mobilinkd Config app (classic) and the M17 KISS HT app (BLE).
- Switching between BLE devices will require that the currently bonded device be powered off,
  initiating a connection, and then waiting for the connection to time out, resulting in a re-scan
  of available BLE devices.  The new device should appear in the connection dialog.

# Suitable radios and modems

Any radio which is advertised as 9600-baud capable should be usable for M17.  However, M17 seems
to place more demands on a flat TX and RX response, and relies on good low-frequency response of
the modulator.  Radios should also key up very quickly, with key-up times below 10ms the ideal.

- Tested, works:
  - Mobilinkd TNC3 (BLE & USB) using experimental firmware
  - Mobilinkd NucleoTNC (USB) using stock firmware
  - Kenwood TM-V71A - This is a well-balance radio with reasonable TX and RX performance.  This
    requires a fairly long TX Delay (12/120ms). Requires reverse TX polarity.
  - Kenwood TK-790 - This has reasonable TX performance.  The modulator seems to have poor low
    frequency response, resulting in higher symbol errors.  However, it seems seems to have
    quite good RX performance.  This requires a short TX Delay; 6/60ms works reliably.
  - Yaesu FT-991 - This has the best TX performance, with good low-frequency respons and the lowest
    symbol offset error, but has the least flat RX response. This requires a short TX Delay; 6/60ms
    works reliably.

# Related Projects
- Codec2 codec: https://github.com/drowe67/codec2
- Android Codec2 wrapper code: https://github.com/UstadMobile/Codec2-Android
- Android USB serial: https://github.com/felHR85/UsbSerial (only Android USB serial package that works properly with CDC devices)
- iOS Codec2 wrapper: https://github.com/Beartooth/codec2-ios
- M17 Project: https://m17project.org/
- Mobilinkd NucleoTNC: http://www.mobilinkd.com/2019/06/24/nucleotnc/ 
- Mobilinkd TNC3: https://store.mobilinkd.com/collections/frontpage/products/mobilinkd-tnc3

# FAQ
- What does this do?
  - This app is part of a complete system, which includes an Android device, an M17 modem,
    and an FM transceiver to do M17 digital voice communication.  It is a half-duplex transceiver.
- Why would I want to use this?
  - This is designed to get people experimenting with M17 digital voice using equipment that
    they may already have available.
  - It is designed to allow M17 developers and early adopters a tool for research and development.
- What is the current state of this project?
  - It is very new, has bugs, but is the best thing at the moment to do bi-directional voice
    communication with M17.
- What equipment do I need?
  - You will need a fairly recent (Android 8.0 or newer) Android device to run the app.
  - You will need an M17 KISS TNC (Mobilinkd devices currently).
  - You will need to install experimental firmware on the Mobilinkd TNC3.
  - You will need to configure the Mobilinkd TNC using the Python config app.
  - You will need an FM radio capable of 9600 baud modulation (direct access to modulator and
    FM discriminator).

# Developer Notes

Bluetooth support was dropped in favor of BLE because latency/jitter was significantly improved.

A change was needed to the TNC3 BLE module configuration in order to cleanly stream frames
across the BLE link without significant latency/jitter.

https://www.b4x.com/android/forum/threads/uart-data-comms-using-bm78-dual-mode-ble-module.68113/

## BLE on Android

*BLE on Android is a mess. BLE on Android with dual-mode devices is an even greater mess.*

There are three main issues when using BLE on on Android.

 1. Pairing and bonding
 2. Sequencing operations
 3. Threading

### Pairing and Bonding

BLE on Android is supposed to bond automatically when an operation which requires authentication
is attempted. However, with a dual-mode device (or at least the BM78), Android seems to require
that the  device be paired first. Failing to first pair the device will result in a
`GATT_AUTHORIZATION_ERROR` when doing GATT discovery and bonding is not attempted. If the device
is paired first, GATT discovery proceeds successfully and a second BLE pairing attempt is made
when a privileged operation is attempted.

On the TNC3 & TNC4, the privileged operation is the request to enable notification from the
read characteristic.

Once the BLE pairing is completed, BLE operations will work as expected. But the device will
no longer work for BR/EDR (classic mode). To use the device in BR/EDR mode, the device must be
re-paired with Android. This presents the biggest usability issue with using dual-mode devices
for both BR/EDR and BLE on Android.

When developing the app, bear in mind that there are three potential scenarios for pairing,
and a similar number of failure paths which must be dealt with.

 1. Device is not bonded.
 2. Device is bonded for BR/EDR.
 3. Device is bonded for BLE.

Note: there is no way to tell whether a bonded device is bonded for BR/EDR or BLE.

#### Device is not bonded

If the device is not bonded, a call to `device.createBond()` should be made to establish the
BR/EDR bond. The user will asked to approve pairing of the device. If that is successful, then
the steps for *Device is bonded for BR/EDR* can be followed.

Attempting to use the BLE GATT without first creating a BR/EDR bond will result in a
`GATT_AUTHORIZATION_ERROR` during service discovery.

One important distinction here is that there will be **two** sets of notifications for
`BluetoothDevice.ACTION_BOND_STATE_CHANGED` when the device is not bonded. The GATT connection
attempt should only be started once.

#### Device is bonded for BR/EDR

If the device is bonded for BR/EDR, pairing will happen automatically for BLE one the request
to enable notifications is sent. The user will (potentially again) be presented with a pairing
request.  If that is successful, then the steps for *Device is bonded for BLE* can be followed.
Otherwise, the GATT will be disconnected with an error.

#### Other Pairing and Bonding Issues

Android may become confused and fail to ever bond to a device. There are two things which may
fix the issue if the Android device enters this state.

 1. Restart the Bluetooth adapter by turning Bluetooth off and on.
 2. Restart the Android device. This has always worked to return to sane Bluetooth behavior.

When Android starts reporting the undocumented GATT_ERROR (133) when attempting to enable
notificatons, the Android device needs to be restarted.

    onDescriptorWrite failed: 00000003-ba2a-46c9-ae49-01b0961f68bb, status = 133


### Sequencing Operations

In order to pair the TNC under BLE 

# TODO
- Configuration screen for USB serial parameters, callsign, etc.
- Received callsign history.
