// swift-tools-version:5.9
import PackageDescription

// KeyCatch's listening engine: plain Swift, so it builds and tests on Linux as well as on
// Apple platforms. The Core ML models (CREPE, Basic Pitch) are used only where Core ML exists.
let package = Package(
    name: "KeyCatchCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "KeyCatchCore", targets: ["KeyCatchCore"])],
    targets: [
        // The models are copied as they are and compiled on the device the first time they are used.
        .target(name: "KeyCatchCore", resources: [.copy("Resources/CrepeTiny.mlpackage"), .copy("Resources/BasicPitch.mlpackage")]),
        .testTarget(name: "KeyCatchCoreTests", dependencies: ["KeyCatchCore"], resources: [.copy("Resources")]),
    ]
)
