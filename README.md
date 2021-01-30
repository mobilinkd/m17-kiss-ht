![CI](https://github.com/sh123/codec2_talkie/workflows/CI/badge.svg)

# M17 KISS HT
This is a fork of the [Android Codec2 Walkie-Talkie](https://github.com/sh123/codec2_talkie),
updated to support M17 streaming KISS protocol over Bluetooth & USB.  This is an Amateur Radio DV
(digital voice) communication protocol using the using open source vocoder,
[Codec2](https://github.com/drowe67/codec2).

<img alt="Screenshot of main Activity" url="https://github.com/mobilinkd/m17-kiss-ht/raw/master/images/screenshot.png" width="216" />

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
- **BluetoothLE connectivity** on startup, lists paired devices, so you can choose your modem and
  connect.
- **Callsign Identification** enter your callsign once connected in order to be able to transmit.
- **Caller Identification** received callsign is clearly displayed when an M17 DV call is received.
- **User Preferences** BLE device preference and user callsign will be saved for later.

# Suitable radios and modems
- Tested, works:
  - Mobilinkd TNC3 (BLE & USB) using experimental firmware
  - Mobilinkd NucleoTNC (USB) using stock firmware
  - Kenwood TM-V71A
  - Kenwood TK-790

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

# TODO
- Improved BLE/USB connection management.
- Audio issues when reconnecting BLE.
- Improved UI layout.
- Configuration screen for USB serial parameters, callsign, etc.
- Received callsign history.

