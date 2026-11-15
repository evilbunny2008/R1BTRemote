# R1BTRemote

Turn a cheap [**Beauty-R1** Bluetooth remote](https://www.aliexpress.com/item/1005008089171343.html) into a media remote for Android.

Out of the box the Beauty-R1 pretends to be a touchscreen: every button press is a
fake swipe or tap, so Android shows a mouse cursor and the presses land on whatever
is under it. R1BTRemote catches those presses and turns them into media controls
instead, so you can play, pause, skip tracks and change the volume.

## What the buttons do

| Button      | Action             |
|-------------|--------------------|
| Centre      | Play / pause       |
| Right       | Next track         |
| Left        | Previous track     |
| Hold right  | Fast forward       |
| Hold left   | Rewind             |
| Up          | Volume up          |
| Down        | Volume down        |

The controls work with any app that responds to standard media buttons, such as
Spotify, YouTube Music, podcast apps and audiobook players.

## How it works

The app has two ways of reading the remote:

- **Shizuku grab service (recommended).** With [Shizuku](https://github.com/evilbunny2008/Shizuku/releases/latest)
  running, the app starts a small service with shell privileges that takes exclusive
  control of the remote's input device. Android never sees the presses, so no cursor
  appears. If the remote disconnects, the service waits for it and picks it up again
  when it reconnects.
- **Accessibility fallback.** When Shizuku isn't available, an accessibility service
  intercepts the remote's pointer events and turns them into media actions. This works
  without Shizuku, but Android still draws the cursor on screen.

The main screen shows the state of both, and only shows the setup buttons for the
steps you haven't done yet.

## Requirements

- Android 14 (API 34) or newer
- A Beauty-R1 Bluetooth remote, [available on AliExpress](https://www.aliexpress.com/item/1005008089171343.html) (it shows up as `Beauty-R1`)
- Optional but recommended: [Shizuku](https://github.com/evilbunny2008/Shizuku/releases/latest)

## Installation

1. Pair the Beauty-R1 remote with your phone in Bluetooth settings.
2. Install R1BTRemote. Either build it from source (see below) or install a release APK.
3. **Recommended:** install Shizuku from
   [github.com/evilbunny2008/Shizuku/releases/latest](https://github.com/evilbunny2008/Shizuku/releases/latest)
   and start it, following the instructions in the Shizuku app (wireless debugging
   on unrooted phones, or root).
4. Open R1 BT Remote:
   - Tap **Grant Shizuku permission** and allow it. The status should change to
     *Grab service: grabbed /dev/input/eventN* once the remote is connected.
   - If you're not using Shizuku, or want a fallback for when it isn't running, tap
     **Open accessibility settings** and turn on **R1 BT Remote**.
5. Press a button on the remote to check it works.

Shizuku has to be started again after each reboot on unrooted phones. While Shizuku starts,
the accessibility fallback (if it's turned on) keeps the remote working.

## Licence

Released into the public domain. See [LICENSE](LICENSE).
