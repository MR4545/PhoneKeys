# PhoneKeys — Android Bluetooth HID keyboard prototype

This is a **test build**, not a verified working APK. It tests whether your Samsung Galaxy M56 5G / Android 16 firmware allows a normal app to register as a Bluetooth HID keyboard.

## Build an APK without installing Android Studio on your PC

1. Create/sign in to a GitHub account.
2. Create a new repository (for example, `PhoneKeys`).
3. Upload the contents of this ZIP to the repository, keeping the folder structure.
4. Open the repository's **Actions** tab and enable Actions if prompted.
5. Select **Build PhoneKeys APK** and click **Run workflow**.
6. When it finishes, open the workflow run and download the `PhoneKeys-debug-apk` artifact.
7. Extract the artifact ZIP; it contains `app-debug.apk`.
8. Transfer `app-debug.apk` to your phone and tap it to install. Android may ask you to allow your browser/file manager to install apps from that source.

No USB debugging is needed. Do not unlock the bootloader or root the phone.

## Test

1. Enable Bluetooth on the phone and Windows laptop.
2. Open PhoneKeys and grant the nearby Bluetooth permission.
3. Tap **Register keyboard** and look for the status message.
4. On Windows, open Settings → Bluetooth & devices → Add device → Bluetooth.
5. If PhoneKeys appears, pair it. Then tap letters in the app while a text field is focused on Windows.

## Important limitations

- The app uses Android's public `BluetoothHidDevice` API. Device firmware can prevent normal third-party apps from registering; this must be tested on the actual phone.
- This prototype focuses on basic keyboard HID testing. It is not yet a polished full PC keyboard.
- Two-way clipboard synchronization is not included. A standard Bluetooth keyboard connection does not provide clipboard sync.
- If registration fails or the phone never appears to Windows, the no-companion-app route may be blocked by this phone's firmware. The app displays status/errors where possible.
