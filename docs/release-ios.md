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
POCKETCASH_SPM=core swift test        # money rules, backup file, envelopes + spec vectors (Linux)
POCKETCASH_SPM=widget swift build --build-system native -j 2 --swift-sdk darwin --triple arm64-apple-ios   # widget code
swift build --build-system native -j 2 --swift-sdk darwin --triple arm64-apple-ios                         # app code
```

(Swift 6.4's default build system can't find the iPhoneOS platform in the xtool SDK, hence `--build-system native`;
`-j 2` keeps a WSL with little memory responsive.)

## 5. Translations

The app, the widgets, the controls and the App Intents ship in English plus Filipino, Spanish, Indonesian,
Portuguese (Brazilian wording), Hindi and Vietnamese (docs/spec.md section 13). The strings are classic
`ios/Shared/Localization/<lang>.lproj/Localizable.strings` files, keyed by the English text, plus
`InfoPlist.strings` for the Face ID prompt. `Shared/` is compiled into the app and the widget extension, so both get
the same tables; XcodeGen turns the `.lproj` folders into localized groups, and xtool copies them into the bundle
(`resources:` in `ios/xtool.yml`).

After adding or changing user-facing text in Swift:

```sh
node tools/check_ios_strings.js --write-en   # refresh en.lproj from the Swift sources
node tools/check_ios_strings.js              # lists the keys each language is missing
```

Add the new keys to every language (same key, translated value; keep each `%@`, or use `%1$@ %2$@` to reorder),
then `node tools/check_ios_strings.js --check` must pass; CI runs it next to the presets check. Rules that keep the
extraction exact: interpolate only `String` values into localized text (format numbers and dates first), use
`Text(verbatim:)` for text that must never be translated, and hand literals to custom views as `Text`,
`LocalizedStringKey` or `String(localized:)`. The translations are first drafts; have a native speaker review them
before a store launch. Siri's spoken phrases (AppShortcuts) are still English only.

To try a language: iPhone Settings → PocketCash → Language, or in Xcode edit the scheme's App Language.

## 6. Things to check on a device before a release

- **App lock** (Pro): turn it on in Settings (it asks for Face ID / the passcode once), send the app to the
  background, reopen it: the lock screen shows and Face ID starts by itself. The app switcher must show the plain
  cover, also while a sheet (keypad, count) is open.
- **Backup file**: Settings → Back up to a file, then Restore from a file on another phone (or after deleting the app);
  the confirmation shows the wallet and cash-move counts. `spec/backup/example-v1.json` restores too.
- **Free vs Pro**: with the debug switch off, Lock Screen widgets and the Control Center controls show
  "PocketCash Pro" and open the paywall; Insights and Envelopes show a blurred preview.

## 7. Test purchases in Xcode (on a Mac)

The `PocketCash` scheme uses `ios/StoreKit/Products.storekit`, so running from Xcode lets you buy Pro in the
simulator with no App Store Connect setup. Use Debug → StoreKit → Manage Transactions to refund and retest.

## 8. App Review notes

- The widget, Lock Screen widgets and Control Center controls are the main surface; mention them in the review notes
  and include a Home Screen screenshot with the widget.
- No account, no network, no data collection: the App Privacy answer is **Data Not Collected**.
- `ITSAppUsesNonExemptEncryption` is `false`.
