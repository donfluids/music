// swift-tools-version:5.9
// Command-line driver that feeds audio to KeyCatchCore's MusicAnalyzer the way the app does
// (mono, 1024-sample chunks, in real-time order) and prints the beat readout once a second.
// Build it from a copy outside the repo (see ../README.md); KCORE is the path to ios/KeyCatchCore.
import PackageDescription
import Foundation

let core = ProcessInfo.processInfo.environment["KCORE"] ?? "../../../ios/KeyCatchCore"
let package = Package(
    name: "kclive",
    platforms: [.macOS(.v14)],
    dependencies: [.package(path: core)],
    targets: [.executableTarget(name: "kclive", dependencies: [.product(name: "KeyCatchCore", package: "KeyCatchCore")])]
)
