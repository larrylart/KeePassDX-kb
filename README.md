# KeePassDX with Bluetooth HID Dongle Support

This is a modified clone of the [KeePassDX Android app](https://github.com/Kunzisoft/KeePassDX).  
The purpose of this modification is to extend KeePassDX with the ability to send passwords directly to a remote machine **without having to type them in manually**.

## How It Works

I built a small companion project: a **USB HID keyboard emulator** running on an ESP32-S3 dongle.  
The dongle receives key events from KeePassDX over Bluetooth and injects them into the target machine as if typed from a physical keyboard.

## 🔄 Update

### **v1.1 – KeePassDX-kb Android App**

**Release Highlights:**

- 🔐 **Improved BLE Communication & Pairing**
  - Enhanced connection stability and device handshake with the Blue Keyboard Dongle v1.1.
  - Fixed cases where the layout dropdown or settings screen failed to refresh on connect/disconnect.

- 🧩 **Dynamic Layout Switching**
  - Implemented support for the new micro-command protocol (`C:` / `S:`).  
  - The app can now send layout change commands (e.g., `C:SET:LAYOUT_US_WINLIN`) directly to the dongle.  
  - On connection wakeboard layout is now automatically echoed back (`CONNECTED=<layout>`) and synchronized with app preferences.

- 🔒 **String/Password MD5 Verification**
  - Added MD5 signature verification for sent strings and passwords to confirm successful delivery and integrity on the dongle side.  
  - Prevents transmission errors and ensures that the received password matches the original sent value.

- 🧰 **Compatibility**
  - Requires **Blue Keyboard Dongle firmware v1.1** or later.
  - Tested with **ESP32 Board Library v3.3.1** and the latest dongle protocol.

---

> ⚠️ **Note:**  
> Make sure you use the latest version dongle firmware version (v1.1+) [blue_keyboard repository](https://github.com/larrylart/blue_keyboard/) 


### Hardware

The hardware used is the **LILYGO T-Dongle-S3 ESP32-S3 TTGO Development Board**, which features:

| Features | Image |
|---|---|
| <ul><li>0.96-inch ST7735 LCD display</li><li>TF card slot</li><li>USB HID capable ESP32-S3</li></ul><br><strong>You can find this board on:</strong><br>• Amazon ($30)<br>• AliExpress ($17) | <img src="doc/lilygo_usb_s3_dongle_.jpg" alt="LILYGO T-Dongle-S3" width="260"> |


### Dongle Software

The dongle firmware and instructions can be found here:  
👉 [blue_keyboard repository](https://github.com/larrylart/blue_keyboard/)  

You’ll need to **flash the dongle** with that software before using this modified KeePassDX build.

---

## How to Install

You have two options:

1. **Install the unsigned APK**  
   - Download the release APK from the [Releases](./releases) section.  
   - Transfer it to your Android device.  
   - Enable “Install from Unknown Sources” in system settings.  
   - Manually install the APK to test the app.  

2. **Build from source**  
   - Clone this repository:  
     ```bash
     git clone https://github.com/larrylart/keepassdx-bluetooth.git
     cd keepassdx-bluetooth
     ```
   - Open the project in **Android Studio**.  
   - Compile a debug or release APK.  
   - Install it on your Android device.  

---

## Notes & Disclaimer

- This is a **few-days hack**, tested only briefly. Expect bugs and rough edges.  
- My **Android development experience is limited**, so some implementation details may not be ideal.  
- Contributions and improvements are welcome!

---

## Modifications to KeePassDX

The following changes were made to KeePassDX:

- Added a **Bluetooth interface singleton** to:
  - Scan for dongles  
  - Pair/unpair  
  - Send password data  

- Added a new **settings option: “Output Devices”**, where you can:  
  - Enable/disable dongle use (toggles the send-button next to password fields)  
  - Select and pair with a dongle (default name: `KPKB_SRV01`)
  - Select the dongle keyboard layout to match the host keyboard layout, so special character are "typed" accordingly.
  - Configure whether to **append a newline (`\n`)** when sending passwords  

![Settings](doc/KeePassDX_settings.jpg)
![Settings Output](doc/KeePassDX_settings_output.jpg)
![Send Password](doc/KeePassDX_sendpass.jpg)

---

## App Behaviour

- On startup:  
  - If “Output Device” is enabled and a BLE dongle is selected, KeePassDX will **auto-connect**.  
  - The dongle’s LED will turn **green** when connected.  
  - Connection persists while the app is open (to avoid reconnecting on every send).  

- On password send:  
  - Dongle LED blinks **red** for ~1 second  
  - Screen displays: `RECV: <counter>`  

- If it fails to connect/send:  
  - This may happen occasionally due to the quick implementation  
  - Workaround: simply **restart KeePassDX**  

---

## License

This project follows the same licensing as [KeePassDX](https://github.com/Kunzisoft/KeePassDX).  
Please check their repository for details.
