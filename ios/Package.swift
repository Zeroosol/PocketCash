// swift-tools-version: 6.0
// SwiftPM manifest for building PocketCash with xtool (https://xtool.sh) on Linux, WSL or macOS, without Xcode.
// The Xcode build comes from project.yml (XcodeGen); both read the same sources. xtool settings live in xtool.yml.
//
// SwiftPM can't compile one source folder into two targets, but the app and the widget both need Shared/,
// exactly like the Xcode targets do. So the manifest has three modes, picked with POCKETCASH_SPM:
//   (unset)  the app: PocketCash/ + Shared/. This is what `xtool dev` builds and installs.
//   widget   the widget extension's code: PocketCashWidget/ + Shared/ (compile check only; xtool can't ship
//            extensions yet, https://github.com/xtool-org/xtool/issues/313).
//   core     just Shared/Core and the tests, runnable on Linux: `POCKETCASH_SPM=core swift test`.
// Run `node ../tools/xtool_prepare.js` first: it writes Shared/Models+xtool.swift, the SwiftData @Model expansion
// that xtool can't generate itself (git-ignored and regenerated).

import PackageDescription
import Foundation

let mode = ProcessInfo.processInfo.environment["POCKETCASH_SPM"] ?? "app"

/// Everything under ios/ that isn't Swift source, so SwiftPM doesn't warn about unhandled files.
/// (xtool can't compile asset catalogs; the icon is copied in through xtool.yml instead. The translations in
/// Shared/Localization are copied into the bundle as-is through xtool.yml's `resources`, so iOS finds them in the
/// main bundle exactly like in the Xcode build.)
let notSources = ["PocketCash/Assets.xcassets", "PocketCashTests", "StoreKit", "xtool", "project.yml", "xtool.yml",
                  "Shared/Localization"]

func makePackage() -> Package {
    switch mode {
    case "core":
        return Package(
            name: "PocketCash",
            platforms: [.iOS(.v18), .macOS(.v14)],
            products: [.library(name: "PocketCashCore", targets: ["PocketCashCore"])],
            targets: [
                .target(name: "PocketCashCore", path: "Shared/Core"),
                .testTarget(name: "PocketCashTests", dependencies: ["PocketCashCore"], path: "PocketCashTests"),
            ],
            swiftLanguageModes: [.v5])
    case "widget":
        return Package(
            name: "PocketCash",
            platforms: [.iOS(.v18)],
            products: [.library(name: "PocketCashWidget", targets: ["PocketCashWidget"])],
            targets: [
                .target(name: "PocketCashWidget", path: ".", exclude: notSources + ["PocketCash"],
                        sources: ["PocketCashWidget", "Shared"]),
            ],
            swiftLanguageModes: [.v5])
    default:
        return Package(
            name: "PocketCash",
            platforms: [.iOS(.v18)],
            // xtool builds the first library as the app.
            products: [.library(name: "PocketCash", targets: ["PocketCash"])],
            targets: [
                // xtool can't compile asset catalogs; the icon is copied in through xtool.yml instead.
                .target(name: "PocketCash", path: ".", exclude: notSources + ["PocketCashWidget"],
                        sources: ["PocketCash", "Shared"]),
            ],
            swiftLanguageModes: [.v5])
    }
}

let package = makePackage()
