# No-root Android mode (USB Bluetooth adapter)

Contributed by Ganeshan Arumuganainar ([@intragalactic-stranger](https://github.com/intragalactic-stranger)).

The upstream Android Bridge talks to the phone's **built-in** Bluetooth chip
through the vendor `IBluetoothHci` HAL. Android only lets the system Bluetooth
process reach that HAL. Upstream gets around this with Magisk: a root
`app_process` helper and SELinux rules. The build is also locked to a OnePlus
CPH2653.

Shizuku can't replace that. Shizuku gives you the `shell` (adb) identity, and
SELinux blocks `shell` from looking up or calling the Bluetooth HAL. No
unprivileged route reaches the built-in controller's raw HCI.

This mode moves the same protocol engine (`HalTransportSession`, which is
unchanged) onto an **external USB Bluetooth adapter**. The adapter is driven
directly through the public Android USB Host API, the same way the Windows
build drives a dongle with WinUSB. You don't need root, Magisk, Shizuku, or a
specific phone model, and the phone's own Bluetooth is left alone. Your
earbuds and car keep working.

It is **additive**: when a USB Bluetooth adapter is attached, Bridge uses it;
otherwise the existing Magisk/HAL path runs exactly as before. Rooted
installations keep camera pairing, Wi-Fi credential sync and the stock-bond
tools.

## What you need

* An Android 13+ phone (arm64) with USB-OTG (USB host) support.
* A USB Bluetooth adapter (plus a USB-C OTG adapter if needed). Supported
  chipsets (not yet hardware-verified in this mode):
  * **Realtek RTL8761B/BU** (TP-Link UB500, ASUS USB-BT500, UGREEN CM390, and
    most "Bluetooth 5.0/5.1" dongles). The firmware is bundled and loaded
    automatically.
  * Realtek RTL8821C / RTL8822B / RTL8723B / RTL8761A / RTL8761C (bundled
    firmware).
  * Adapters that need no firmware download (many Barrot/CSR-style dongles)
    work as is.
  * Not supported: adapters that need Intel, Broadcom, MediaTek or Realtek
    "v2" (`RTBTCore`, e.g. RTL8852/RTL8822CU) firmware.

## Install

1. Install **both** APKs. They are signed with the same key, which the
   signature-protected IPC between them requires:
   * `AppleWatchBridge-noroot.apk` (backend, package `dev.applewatchandroid.bridge`)
   * `AppleWatchCompanion-noroot.apk` (UI)

   Both must come from the same build and signing key. If you install over an
   existing build signed with a different key, uninstall that build first.
2. Plug in the USB Bluetooth adapter. Android asks *"Open Apple Watch Bridge
   to handle …?"*. Tick **Always** and accept, which grants USB access for
   good. Opening the Bridge icon also asks for USB access.
3. Open **Apple Watch Companion**, tap **Allow Bluetooth access**, and allow
   notifications.
4. Put the watch in pairing mode (erased watch showing the pairing
   animation). Tap the "i" button on the watch to see its name.
5. In Companion tap **Pair Apple Watch** and pick that watch. The watch then
   shows a **six-digit code**; enter it in Companion. Keep the dongle within
   about 1 m of the watch during setup.

Reconnecting later only needs the dongle plugged in. **Connect Watch** reuses
the saved pair.

## Differences from the Magisk path

| Feature | Magisk path | No-root USB mode |
| --- | --- | --- |
| Bluetooth controller | built-in (HAL) | external USB adapter |
| Stock Bluetooth | must be OFF | untouched |
| Pairing | camera or PIN | PIN (six-digit code) |
| Initial Wi-Fi credential sync | reads `WifiConfigStore.xml` as root | not available; join Wi-Fi on the watch |
| Stock bond audit / import / identity tools | yes | root only (they rewrite system Bluetooth config) |
| CarPlay cooperative hand-off | yes | not needed |

Camera pairing needs an asset taken from Apple's iOS firmware
(`VisualPairing`), which isn't in this repository. When
`firmware/ios-26.6-23G71/extracted/VisualPairing` is absent, the Companion
Gradle build now skips the optical asset and its native reader instead of
failing. Build Companion with `--dart-define=OPTICAL_PAIRING=false` so the
Android UI offers six-digit-code pairing instead of the camera.

## How it works

```
Companion (Flutter) ──signature IPC──▶ Bridge app process
                                         │ UsbHostProcess (java.lang.Process facade)
                                         │   stdin/stdout/status pipes over Binder
                                         ▼
                                   Bridge ":hci" process  (UsbHciHostService)
                                         UsbHciProtocolHost  ← USB counterpart of RootBluetoothHalHost
                                         HalTransportSession (unchanged protocol engine)
                                         UsbBluetoothController ← USB counterpart of AndroidBluetoothController
                                           control EP0 = HCI commands, interrupt IN = events,
                                           bulk IN/OUT = ACL, Realtek RAM-patch loader
```

Each session runs in a fresh `:hci` process, which is then discarded. This
keeps the same isolation and static-state lifetime as the `su` child
process. The stdin/stdout line protocol, Keystore storage and pairing records
are unchanged.

## Build

```sh
# Bridge. Optional: place a prebuilt arm64 libwatch_replicator_quic.so in
# app/prebuilt-jni/arm64-v8a/ (git-ignored) to skip the Rust/NDK QUIC build.
cd apple-watch-bridge
WATCH_RELEASE_SIGNING_PROPERTIES=/path/signing.properties ./gradlew :app:assembleRelease

# Companion (Flutter 3.47.4)
cd apple-watch-companion
WATCH_RELEASE_SIGNING_PROPERTIES=/path/signing.properties \
  flutter build apk --release --target-platform android-arm64 \
  --dart-define=OPTICAL_PAIRING=false
```

The bundled Realtek firmware comes from linux-firmware (`rtl_bt/`) and is
redistributed under `assets/rtl_bt/LICENCE.rtlwifi_firmware.txt`.
