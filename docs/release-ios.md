# Releasing PocketCash for iPhone

No Mac needed: `.github/workflows/ios.yml` generates the Xcode project with XcodeGen, runs the tests and uploads to
TestFlight on a GitHub-hosted Mac, the same way Splitty ships.

## 1. One-time Apple setup

1. In [Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/identifiers/list):
   - Register the App Group **`group.com.patternsystems.pocketcash`**.
   - Register the App IDs **`com.patternsystems.pocketcash`** and **`com.patternsystems.pocketcash.widget`**, and
     enable **App Groups** on both with that group. (Automatic signing on CI can create these too, but registering
     the App Group by hand avoids a failed first run.)
   - To use another prefix, change `bundleIdPrefix`, the bundle IDs and the group in `ios/project.yml`,
     `PocketCash.appGroupID` in `ios/Shared/AppSettings.swift`, and `ios/xtool/*`.
2. In [App Store Connect](https://appstoreconnect.apple.com): create the app with bundle ID
   `com.patternsystems.pocketcash`.
3. Under the app → **Monetization → In-App Purchases**, create a **Non-Consumable**:
   - Product ID **`pocketcash.pro.lifetime`**, reference name "PocketCash Pro (lifetime)".
   - Price: $3.99 (tier of your choice), display name "PocketCash Pro", a description, and the review screenshot
     (the paywall). Submit it with the first app version.
4. Create an App Store Connect API key (Users and Access → Integrations → App Store Connect API, role
   **App Manager**). Download the `.p8` once.

## 2. GitHub secrets

Repository → Settings → Secrets and variables → Actions → **Secrets**:

| Secret | Value |
|---|---|
| `APP_STORE_CONNECT_API_KEY` | The whole `.p8` file, including the BEGIN/END lines |
| `APP_STORE_CONNECT_KEY_ID` | The key's ID |
| `APP_STORE_CONNECT_ISSUER_ID` | The issuer ID shown above the keys list |
| `APPLE_TEAM_ID` | Your 10-character Team ID |

If `macos-latest` doesn't have Xcode 26 yet, add the repository **variable** `MAC_RUNNER` = `macos-26`.

## 3. Ship a build

Actions → **iOS** → Run workflow (leave "Upload to TestFlight" ticked), or push a tag like `ios-v1.0.0`.
Pull requests that touch `ios/` or `spec/` only build and test. The build number is `100 + run number`; raise
`MARKETING_VERSION` in `ios/project.yml` for a new version.

## 4. Test on your own iPhone from Windows (xtool, optional)

In WSL, with [xtool](https://xtool.sh) set up (`xtool setup`):

```sh
node tools/xtool_prepare.js      # on Windows or anywhere with Node: writes the SwiftData model expansion
cd ios && xtool dev              # builds, signs with your Apple ID and installs on the connected iPhone
```

xtool builds the app without the widget extension (xtool issue #313), and StoreKit purchases need the App Store,
so use **Settings → Developer → Unlock Pro (debug build)** to try Pro features.

Compile checks without a device, from `ios/`:

```sh
POCKETCASH_SPM=core swift test                                                # money rules + spec vectors (Linux)
POCKETCASH_SPM=widget swift build --swift-sdk darwin --triple arm64-apple-ios # widget extension code
```

## 5. Test purchases in Xcode (on a Mac)

The `PocketCash` scheme uses `ios/StoreKit/Products.storekit`, so running from Xcode lets you buy Pro in the
simulator with no App Store Connect setup. Use Debug → StoreKit → Manage Transactions to refund and retest.

## 6. App Review notes

- The widget, Lock Screen widgets and Control Center controls are the main surface; mention them in the review notes
  and include a Home Screen screenshot with the widget.
- No account, no network, no data collection: the App Privacy answer is **Data Not Collected**.
- `ITSAppUsesNonExemptEncryption` is `false`.
