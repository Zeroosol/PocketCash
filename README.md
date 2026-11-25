# PocketCash

A local-first, widget-driven **physical wallet tracker** for iPhone (Swift) and Android (Kotlin).

Banking apps log card and QR payments on their own, but cash disappears without a trace. PocketCash puts your
wallet on the Home Screen: tap `−50` on the widget when you pay and the balance is right again, without opening
anything. No account, no network, no third-party SDKs.

The full product and behavior spec, shared by both apps, is [docs/spec.md](docs/spec.md).

## What's in it

A minimal design with a dynamic bottom bar (Home, Insights, Envelopes, Settings, plus a + button for the keypad)
that shrinks while you scroll. The whole app, widgets included, is in English, Filipino, Spanish, Indonesian,
Portuguese, Hindi and Vietnamese.

**Free**: the basics
- One wallet in your currency
- Home Screen widgets (small and medium) with live balance and 1-tap buttons, auto-scaled to your currency
  (`+1k −100 −50 −20` for pesos, `+100 −20 −10 −5` for dollars; 30 currencies tuned in `spec/presets.json`)
- Undo after every tap (in the app for 5 seconds, and on the widget itself), with haptics
- 7-day cash feed, custom amount keypad, Siri / Shortcuts and launcher shortcuts
- **Count my cash**: count bills and coins per denomination and save the difference as "unrecorded spending"
- **Backup file** that restores on any iPhone or Android phone (always free)

**Pro** ($3.99 once, `pocketcash.pro.lifetime`): the best features
- **Envelopes and savings goals**: give wallets a target, see progress, and fill them from one wallet in one step
- **Insights**: ATM cycles ("₱1,000 lasted 4.5 days"), burn rate, runway forecast, top denomination and category
- **More wallets** (coin pouch, emergency stash, a travel wallet with its own currency), transfers, widget wallet picker
- **Quick spend everywhere**: Lock Screen widgets and Control Center (iPhone), Quick Settings tile (Android)
- **Change calculator**: "paid ₱500, got ₱130 back" saves one ₱370 spend
- **Daily budget** ("₱230 left today" on the app and widget), **categories** after the fact, **custom buttons**
- **App lock** (Face ID / Touch ID / fingerprint / passcode), **privacy mode**, **low-cash reminder**
- Full history and CSV export

## Layout

| Path | What |
|---|---|
| `docs/spec.md` | The contract both apps implement: data model, money rules, ATM cycles, Free/Pro, screens, widgets |
| `spec/presets.json` | Default buttons and countable denominations per currency |
| `spec/vectors/cycles.json` | Test vectors for balance, today spent, ATM cycles, burn rate and runway, checked by both apps' tests |
| `spec/backup/example-v1.json` | A sample backup file; both apps' tests decode it, so an iPhone backup restores on Android and back |
| `ios/` | SwiftUI + SwiftData app, WidgetKit extension (Home Screen, Lock Screen, Control Center), App Intents, StoreKit 2 |
| `ios/Shared/Core` | Pure-Foundation money rules (`Ledger`, `Money`, CSV), testable on Linux |
| `ios/Shared` | Code compiled into both the app and the widget: SwiftData models, `CashStore` (the one write path), widget snapshot, intents |
| `android/` | Kotlin + Jetpack Compose app: `core` (plain Kotlin rules and tests) and `app` (UI, Glance widgets, Quick Settings tile, Play Billing) |
| `site/` | Preview site: a landing page with a working, clickable demo of the app, its widgets and quick-spend buttons. Static files, no build: open `site/index.html`, or serve the folder. `ledger.js` follows the same money rules and matches `spec/vectors` |
| `tools/` | Node scripts, no dependencies: `build_presets.js` (presets → Swift, Kotlin and `site/presets.js`), `make_icon.js` (app icons), `xtool_prepare.js` / `expand_models.js` (SwiftData expansion for xtool), `png.js` |
| `.github/workflows` | `ios.yml` (test + TestFlight on GitHub's Macs) and `android.yml` (test, lint, APK, optional Play upload) |
| `art/` | Store icons rendered by `tools/make_icon.js` |

## Building

Both apps build on GitHub Actions, so no Mac is needed (the same setup as Splitty).

**iPhone**: see [docs/release-ios.md](docs/release-ios.md). On a Mac: `cd ios && xcodegen generate && open
PocketCash.xcodeproj`. From Windows/WSL with xtool: `node tools/xtool_prepare.js`, then `cd ios && xtool dev`
(the app without the widget extension; xtool can't ship extensions yet).

**Android**: every push runs `.github/workflows/android.yml` (core tests including `spec/vectors`, lint, and a
debug APK under the run's Artifacts). Locally, with JDK 17, Gradle 8.14.6 and Android SDK 36 (`sdk.dir` in
`android/local.properties`): `cd android && gradle :core:test :app:assembleDebug :app:lintDebug`. On a small WSL VM
add `--no-daemon -Dorg.gradle.jvmargs=-Xmx1800m -Pkotlin.compiler.execution.strategy=in-process`, and never run it
at the same time as a Swift build. Play Console setup, the `pocketcash.pro.lifetime` product and signing secrets:
[docs/release-android.md](docs/release-android.md).

Debug builds of both apps have an "Unlock Pro (debug build)" switch in Settings for testing without the stores.

**Shared checks** (any OS with Node and Swift):

```sh
node tools/build_presets.js --check         # generated presets are up to date
cd ios && POCKETCASH_SPM=core swift test    # money rules against spec/vectors (runs on Linux too)
```

After editing `spec/presets.json`, run `node tools/build_presets.js`. After changing `ios/Shared/Models.swift`,
run `node tools/xtool_prepare.js` before an xtool build. `node tools/make_icon.js` redraws every icon.

## How the widget stays correct

Widget buttons run `AdjustCashIntent` in the widget extension's own process. It opens the SwiftData store at the
explicit App Group URL (`PocketCash.store`, never automatic detection), inserts the transaction, saves
synchronously, rewrites `widget_snapshot.json`, and only then reloads the timelines. Widgets draw from that small
JSON snapshot, so they never hold the SQLite file open (no `0xdead10cc` terminations), and the app rebuilds its
model container when it comes back to the foreground after another process wrote. A wallet's balance is always
the sum of its transactions, so it can't drift.
