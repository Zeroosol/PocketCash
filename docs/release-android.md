# Releasing PocketCash for Android

The Android app lives in `android/` (Kotlin, Jetpack Compose, Glance widgets). It builds on GitHub's Linux runners
(`.github/workflows/android.yml`) or on your own machine. No Mac and no Android Studio needed.

## 1. Build on GitHub and install the APK

1. Push the repository to GitHub. Every push to `main`/`master` that touches `android/`, `spec/` or
   `tools/build_presets.js` runs the **Android** workflow: the core tests (including the shared vectors in
   `spec/vectors/`), lint, and a debug build.
2. Open the run under **Actions → Android**, download **pocketcash-debug-<n>** under Artifacts, unzip it and copy
   `app-debug.apk` to your phone.
3. On the phone, open the APK and allow installing from that app when Android asks. Later builds install over the
   old one as long as the debug key stays cached (7 days without a build clears it: uninstall once).
4. Debug builds have **Settings → Debug → Unlock Pro (debug build)** to try every Pro feature without Google Play.

## 2. Build locally (Linux, WSL or macOS)

You need JDK 17, Gradle 8.14.6 and the Android SDK (platform 36, build-tools 36). In `android/`, create
`local.properties` with `sdk.dir=/path/to/android/sdk`, then:

```sh
node tools/build_presets.js          # only after editing spec/presets.json
cd android
gradle :core:test :app:assembleDebug :app:lintDebug
```

On a small machine (or a WSL VM with ~4 GB), add
`--no-daemon -Dorg.gradle.jvmargs=-Xmx1800m -Pkotlin.compiler.execution.strategy=in-process`.
The APK is `android/app/build/outputs/apk/debug/app-debug.apk`; `adb install -r` it, or copy it to the phone.

## 3. Upload key (one time)

Google Play signs the app for users; you sign uploads with your own **upload key**. Create one (keep the file and
passwords somewhere safe, never in the repository):

```sh
keytool -genkeypair -v -keystore pocketcash-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 pocketcash-upload.jks > pocketcash-upload.b64   # macOS: base64 -i pocketcash-upload.jks
```

In the GitHub repository, **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `POCKETCASH_UPLOAD_KEYSTORE` | the contents of `pocketcash-upload.b64` |
| `POCKETCASH_UPLOAD_STORE_PASSWORD` | the keystore password |
| `POCKETCASH_UPLOAD_KEY_ALIAS` | `upload` (or the alias you chose) |
| `POCKETCASH_UPLOAD_KEY_PASSWORD` | the key password |

## 4. Play Console setup (one time)

1. Create the app in [Play Console](https://play.google.com/console): name **PocketCash**, package
   `com.patternsystems.pocketcash`, free app with in-app products.
2. Run the workflow by hand (**Actions → Android → Run workflow**, tick **Upload to Google Play**). Without the Play
   key from step 5 it saves **pocketcash-release-<n>** under Artifacts: upload that `.aab` yourself in
   **Test and release → Internal testing → Create new release** (Play needs the first upload by hand). Accept Play App
   Signing when asked.
3. **Create the Pro product**: **Monetize with Play → Products → In-app products → Create product**
   - Product ID: `pocketcash.pro.lifetime` (must match exactly; it can never be reused once deleted)
   - Name: PocketCash Pro. Description: Envelopes and savings goals, insights, more wallets, the Quick spend tile, the
     change calculator, daily budget, categories, custom buttons, app lock and privacy mode, low-cash reminders, full
     history and CSV export.
   - Price: $3.99 (Play converts it per country). **Activate** it.
4. **License testing**: Settings → License testing, add your Google account, so test purchases are free and can be
   refunded instantly. Install the app from the internal testing link to test buying and restoring.
5. App content: privacy policy URL (PocketCash collects nothing; say so), Data safety form (**No data collected, no data
   shared**), ads: none, target audience, and content rating.

## 5. Automatic uploads (optional)

1. In Google Cloud Console, create a service account in a project linked to Play Console, and a JSON key for it.
2. In Play Console, **Users and permissions → Invite new users**, add the service account's email with permission to
   release to testing tracks.
3. Add the JSON as the repository secret `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`.
4. Until a first release has been rolled out by hand, Play only accepts drafts: set the repository variable
   `PLAY_RELEASE_STATUS` to `draft`, and remove it afterwards.

Now **Run workflow → Upload to Google Play** builds, signs and uploads to the internal track in one go. The version
code is 100 + the run number, so it always goes up.

## Notes

- The app has no internet permission (the manifest removes it even if a library asks). Play Billing talks to the Play
  Store app on the phone, so purchases still work.
- Pro is cached on the phone after Play confirms it; Play's answer on each app launch is authoritative, so a refund turns
  Pro off again.
- The launcher icon comes from `tools/make_icon.js` (`ic_launcher_fg.png`, `ic_launcher_mono.png` on `#059669`); the
  default buttons per currency come from `spec/presets.json` via `tools/build_presets.js`. Don't edit the generated
  files by hand.
- Languages: English plus Filipino (`res/values-b+fil`, which a phone set to Filipino matches), Spanish,
  Indonesian (`res/values-in`, the code Android looks up), Brazilian Portuguese, Hindi and Vietnamese. Android 13+ lists
  them under Settings → Apps → PocketCash → Language (`res/xml/locales_config.xml`). The translations are first drafts:
  have a native speaker review them before a store launch. Lint fails the build on a missing translation, so add every
  new string to all seven `strings.xml` files.
- App lock uses androidx.biometric 1.1.0 (the newest stable release): fingerprint or face where the phone has them,
  else the screen lock PIN, pattern or password. To try it, the test phone needs a screen lock; on an emulator set a PIN
  in Settings → Security first.
- Backup files (`PocketCash-backup-YYYY-MM-DD.json`) use the shared format of docs/spec.md section 11, so a backup
  made on an iPhone restores on Android and the other way. The core tests decode `spec/backup/example-v1.json`.
