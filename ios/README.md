# KeyCatch for iPhone

The iPhone version of KeyCatch. It needs iOS 17 or newer.

- `KeyCatchCore/` is the listening engine, a Swift package. It is a line-by-line port of the
  Android engine (`analysis/src/main/kotlin/com/donfluids/music/dsp` and `music`). It keeps the same
  names, constants and tests, so a fix on one side can be copied to the other.
- `KeyCatchCore/Sources/KeyCatchCore/Resources/` holds the learned models as Core ML
  packages:
  - `CrepeTiny.mlpackage` is built from the same weights as the Android app by
    `tools/coreml/crepe_coreml.py`.
  - `BasicPitchNotes.mlpackage` is Spotify's own Core ML release of Basic Pitch.
  - `BeatThisSmall.mlpackage` (Band, 4.4 MB) and `BeatThisFull.mlpackage` (Guitar, 41 MB) are
    the small0 and final0 checkpoints of Beat This!, converted to FP16 for an 8-second window by
    `tools/beatthis/convert_coreml.py`. `tools/beatthis/README.md` is their spec and evaluation.

  The app compiles each one on the phone the first time it runs, and keeps the result.
- `KeyCatch/` is the SwiftUI app. It has the same screens, wording and colours as the Android
  app.
- `project.yml` describes the Xcode project for [XcodeGen](https://github.com/yonaskolb/XcodeGen).
  The `.xcodeproj` is generated from it and is not kept in git.

## Building

On a Mac with Xcode 16:

```sh
brew install xcodegen
cd ios
xcodegen generate
open KeyCatch.xcodeproj
```

The engine's tests also run on Linux, with the Swift toolchain:

```sh
cd ios/KeyCatchCore
swift test
```

The Core ML tests compare the models with the original networks' outputs. They only run on a
Mac, because Linux has no Core ML. Everything around the models (Beat This!'s front end,
postprocessing and reading included) is tested on Linux too, against reference data from
`tools/beatthis/make_fixtures.py`.

Without a Mac, GitHub Actions does the Mac part (`.github/workflows/ios.yml`). On every push
that touches `ios/`, it:

- runs all the engine tests, Core ML included (the step fails if a Beat This! Core ML test did
  not run);
- generates the project;
- builds the app for the simulator and, unsigned, for iPhones;
- on the KeyCatch branch, puts that unsigned build as `KeyCatch.ipa` on the pre-release
  `keycatch-ios-latest` (the repository's Releases page), replaced on every push.

## Putting it on an iPhone

An iPhone only runs apps signed for it with an Apple ID.

### With a free Apple ID

The IPA from the pre-release is unsigned, so a sideloading tool signs it with your Apple ID
on a Windows PC or a Mac. These steps use Sideloadly; AltStore works the same way.

1. Download `KeyCatch.ipa` from the `keycatch-ios-latest` pre-release.
2. Install Sideloadly; its site says what Windows needs besides it.
3. Connect the iPhone by cable and tap **Trust** on the phone.
4. Drop the IPA into Sideloadly, enter the Apple ID and start. The password goes to Apple to
   make the signing certificate; a separate Apple ID for this is fine.
5. On the iPhone:
   - Turn on **Settings → Privacy & Security → Developer Mode** and restart when asked.
   - Trust your Apple ID under **Settings → General → VPN & Device Management**.
6. Open KeyCatch and allow the microphone.

Limits of a free Apple ID:
- The app stops opening after 7 days. Sign it again from the computer.
- At most three apps signed this way at a time.

### With a paid Apple Developer account

Without a Mac the practical route is TestFlight:

1. Join the Apple Developer Program (paid, yearly).
2. In App Store Connect:
   - Create the app with the bundle ID `com.donfluids.keycatch`.
   - Make an App Store Connect API key.
3. Add the key and a signing certificate to the repository's GitHub secrets. A release job in
   the workflow can then sign the build and upload it to TestFlight.
4. Install the TestFlight app on the iPhone and accept the invitation.

The other route is a rented cloud Mac, such as MacinCloud or an AWS Mac instance. There, Xcode
can install the app straight onto a phone connected to it, or upload it to TestFlight the same
way.

## Not the same as Android

- Clips are shared as separate files (a WAV and a JSON per clip) instead of one zip.
- The learned models run through Core ML, so they can use the iPhone's Neural Engine.
- Beat This! (Settings, "Tempo and time signature": Accurate, the default, on both phones)
  follows Guitar with the full model on the iPhone; Android uses the small one for Band and
  Guitar alike, on the CPU through LiteRT. Android also times its first windows and, on a slow
  phone, runs one every two seconds instead of every second, or goes back to Standard and says
  so in Details; the iPhone always runs one a second.
- The Settings screen opens as a sheet instead of a dialog.
