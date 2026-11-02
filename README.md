# HDMI In for Android

Turns an Android phone into an HDMI monitor using a generic USB 2.0 / USB-C UVC
HDMI capture card. Plug the card into the phone, plug your HDMI source into the
card, and the picture appears full screen. Resolution choices available.

![mage](https://github.com/Funcron/HDMI-IN-Android/blob/main/GenericCapCard.jpg)

In reality it is no different than many other options where you have to pay 
some amount to use the software (because you have to buy a $10 piece of hardware).
But it is different in that it's not tied to Windows needing to run companion 
software, or that it doesn't utilize something built into the Google or Samsung's
offerings on your phone. Headless Raspi needs a display just for setup? Use a
gaming console in a pinch? Grab files off that old laptop with a busted display for
your grandma without having to take the whole thing apart? Use your phone at work
as a second monitor without upsetting the IT department with some software they
didn't approve of? Turn an old phone into a monitor for your fancy digital
camera's HDMI output? I think you get it.

## Using it

- Plug the capture card into the phone's USB-C port, then open the app (or accept
  the "open with HDMI Monitor" prompt). Allow camera and USB access when asked.
- The app picks the best mode automatically: the largest picture up to 1080p that
  runs at 24 fps or more, then the highest frame rate (so 1080p60 is chosen if the
  card offers it, otherwise 1080p30).
- Tap the screen to show or hide the controls:
  - **Mode**: choose any resolution / frame rate / format the card reports.
  - **Audio**: plays the card's HDMI audio through the phone (needs microphone
    permission; many cards send audio as a USB microphone).
- The top-left label shows the active mode.
- The app technically starts up after the splash message is okay'd, so don't sit
  on that wpop-up and complain that it doesn't work.

## Limitations

- **8-bit only.** The UVC library used here used MJPEG and 8-bit YUV. 10-bit
  capture is not supported by the library, and USB 2.0 cards cannot carry it anyway.
- **USB 2.0 cards** deliver 1080p30 (sometimes 1080p60) as MJPEG. Uncompressed
  video on USB 2.0 is limited to roughly 720p or lower.
- The phone needs USB host (OTG) support over USB-C.
- The card must be UVC-compliant. Cards that report a vendor-specific USB class
  (rather than the standard video class) will not be detected.
- Latency is typically around 60-100 ms, depending on the card and phone;
  it is a viewer, you're not playing any FPS's with this. But, to each their own.
- It *should* work with USB 3.X Capture devices, but your mileage may vary. I
  had only tested it on a similarly cheap device, and it had some stuttering
  and horizontal sync issues.
  
## Credits

Camera access uses [UVCAndroid](https://github.com/shiyinghan/UVCAndroid), Apache 2.0.
