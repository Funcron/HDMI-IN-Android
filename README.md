# HDMI Monitor (Android)

Turns an Android phone into an HDMI monitor using a USB-C UVC capture card.
Plug the card into the phone, plug your HDMI source into the card, and the
picture appears full screen.

## Get the APK (no Android Studio needed)

1. Create a free GitHub repository and push this folder to it (`main` branch).
2. Open the repo's **Actions** tab. The **Build APK** workflow runs on every push
   (or press **Run workflow**).
3. When it finishes, open the run and download the **hdmi-monitor-debug-apk**
   artifact. Unzip it to get `app-debug.apk`.
4. Copy the APK to your phone and install it (allow "install unknown apps" for
   your file manager or browser when prompted).

To build locally instead: install JDK 17 and the Android SDK, then run
`./gradlew assembleDebug`. The APK lands in `app/build/outputs/apk/debug/`.

## Using it

- Plug the capture card into the phone's USB-C port, then open the app (or accept
  the "open with HDMI Monitor" prompt). Allow camera and USB access when asked.
- The app picks the best mode automatically: the largest picture up to 1080p that
  runs at 24 fps or more, then the highest frame rate (so 1080p60 is chosen if the
  card offers it, otherwise 1080p30).
- Tap the screen to show or hide the controls:
  - **Mode**: choose any resolution / frame rate / format the card reports.
  - **Audio**: plays the card's HDMI audio through the phone (needs microphone
    permission; many cards expose audio as a USB microphone).
- The top-left label shows the active mode.

## Limits you should know about

- **8-bit only.** The UVC library used here exposes MJPEG and 8-bit YUV. 10-bit
  capture is not supported by the library, and USB 2.0 cards cannot carry it anyway.
- **USB 2.0 cards** deliver 1080p30 (sometimes 1080p60) as MJPEG. Uncompressed
  video on USB 2.0 is limited to roughly 720p or lower.
- The phone needs USB host (OTG) support over USB-C.
- The card must be UVC-compliant. Cards that report a vendor-specific USB class
  (rather than the standard video class) will not be detected.
- Latency is typically around 100 ms or more, depending on the card and phone;
  it is a viewer, not a gaming monitor.
- This source was written without being compiled in the authoring environment, so
  the first build on GitHub is its first real test. If the workflow fails, the log
  will show the exact error.

## Credits

Camera access uses [UVCAndroid](https://github.com/shiyinghan/UVCAndroid)
(`com.herohan:UVCAndroid`), Apache 2.0.
