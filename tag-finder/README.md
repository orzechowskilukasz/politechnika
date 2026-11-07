# Tag Finder

An Android app for finding a misplaced Apple AirTag (or other Find My accessory) in your
immediate surroundings, using only the phone's own sensors. It can't use the iPhone's
Ultra-Wideband "Precision Finding", because AirTags only do UWB with their owner's Apple
devices. Instead it combines Bluetooth signal strength with gyro, camera (ARCore) and
magnetometer data.

The physics, the maths, what was tried and rejected (Doppler, interferometry, predicting
multipath from room shape) and benchmark results are in **[docs/DESIGN.md](docs/DESIGN.md)**.

## Modes

| Mode | Sensors | What you do | What you get |
|---|---|---|---|
| **Hot/cold** | BLE | Walk; at each spot wave the phone in small circles | Smoothed signal, warmer/colder trend, rough distance |
| **Direction** | BLE + gyro | Phone flat on your chest, turn 1.5 times on the spot | Arrow + uncertainty from your body's radio shadow (~10° median in simulation) |
| **AR room scan** | BLE + camera + IMU | Walk the room with the camera on, scribbling every step or two, then walk toward the marker | Probability heat map and a marker over the camera view (~1 m median in simulation) |
| **Magnet** | Magnetometer | Calibrate, then sweep the phone slowly over likely spots | Detects the tag's speaker magnet within ~5–10 cm |

Typical search: **AR scan** or **Direction** to get to the right part of the room → **Hot/cold** for the
last metre → **Magnet** for the cushion or bag.

Tips:
- Take your iPhone out of Bluetooth range (or switch its Bluetooth off). An AirTag away from
  its owner advertises a fuller, more frequent signal that's easier to pick out.
- If the tag has been away from its owner for a while, Android's built-in **Settings →
  Safety & emergency → Unknown tracker alerts → Scan now** can make it beep. That's often
  the fastest way to find it.

## Project layout

```
core/   Plain Kotlin/JVM library, no Android dependency, fully unit-tested
          FindMyAdvertisement  parse Apple 0x004C / type 0x12 payloads
          TargetTracker        follow one tag across BLE address rotations
          Rssi, RssiKalman     fading statistics (trigamma), distance model, smoothing
          SiteAggregator       scribble scanning: λ/2-cell spatial-diversity averaging
          BearingEstimator     body-shadow cosine fit with delta-method uncertainty
          Localizer            Bayesian 3-D grid: path loss + body shadow + walls + surface prior
          MagnetAnomaly        world-frame magnetic anomaly detector
        src/test: radio-channel simulator (shadowing field, Rayleigh fading, body shadow),
                  unit tests and a Monte-Carlo benchmark
app/    Android app (Activities + custom Views, ARCore for the AR scan)
docs/   DESIGN.md: research and design notes
```

## Building

Requirements: Android Studio (Ladybug or newer) or the Android SDK with JDK 17+.

```bash
./gradlew :app:assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew :app:installDebug           # install on a connected phone (USB debugging on)
```

Core library only (no Android SDK needed):

```bash
./gradlew -PcoreOnly :core:test       # unit tests
./gradlew -PcoreOnly :core:simulate   # accuracy benchmark table
```

The phone needs Android 8.0+ and Bluetooth LE. The AR scan needs a phone that supports
[Google Play Services for AR](https://developers.google.com/ar/devices). The other modes work without it.

Permissions: *Nearby devices* (Bluetooth scan) and *Location*. Android treats BLE scanning
as location-sensitive, and the app deliberately doesn't use the `neverForLocation` flag,
which would hide beacon-style advertisements. *Camera* is only used for the AR scan.

## Status and limitations

- The core library is unit-tested and benchmarked against a simulated channel. The Android
  app builds (`assembleDebug`) and passes Android lint with no errors, but it **has not yet
  been run on a device**, so expect some tuning (body-shadow strength, advertisement rate,
  thresholds).
- The Find My status-byte "AirTag" type bits come from public reverse engineering and
  are shown as "probably".
- RSSI positioning indoors is fundamentally limited by shadowing (≈ ±40% range error per
  measurement). The app gets a room-scale answer, not a centimetre one.
- Intended for finding **your own** belongings. If you think an unknown tracker is following
  you, use Android's built-in Unknown tracker alerts.
