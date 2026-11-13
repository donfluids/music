# KeyCatch for iPhone

The iPhone version of KeyCatch. It needs iOS 17 or newer.

- `KeyCatchCore/` is the listening engine, a Swift package. It is a line-by-line port of the
  Android engine (`app/src/main/kotlin/com/donfluids/music/dsp` and `music`). It keeps the same
  names, constants and tests, so a fix on one side can be copied to the other.
- `KeyCatchCore/Sources/KeyCatchCore/Resources/` holds the two learned models as Core ML
  packages:
  - `CrepeTiny.mlpackage` is built from the same weights as the Android app by
    `tools/coreml/crepe_coreml.py`.
  - `BasicPitchNotes.mlpackage` is Spotify's own Core ML release of Basic Pitch.

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

The Core ML tests compare both models with the original networks' outputs. They only run on a
Mac, because Linux has no Core ML.

Without a Mac, GitHub Actions does the Mac part (`.github/workflows/ios.yml`). On every push
that touches `ios/`, it:

- runs all the engine tests, Core ML included;
- generates the project;
- builds the app for the simulator and, unsigned, for iPhones.

## Putting it on an iPhone

An iPhone only runs apps signed by Apple's tools. Every route needs an Apple Developer account,
and without a Mac the practical route is TestFlight:

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
- The Settings screen opens as a sheet instead of a dialog.
