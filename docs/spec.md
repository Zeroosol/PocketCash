# PocketCash: product and behavior spec

The single source of truth for the iPhone app (`ios/`, Swift) and the Android app (`android/`, Kotlin). When the two
apps disagree, this file decides. Numbers that both apps must compute the same way are pinned by test vectors in
`spec/vectors/`, and the default buttons per currency come from `spec/presets.json` (generated into both apps by
`node tools/build_presets.js`).

PocketCash is a local-first physical-wallet tracker. Banking apps log card and QR payments on their own, but cash
disappears without a trace. PocketCash puts the wallet on the Home Screen: one tap on `−50` and the balance is right
again, without opening anything. No account, no network, no analytics SDKs. Everything stays on the phone.

## 1. Identifiers

| What | iOS | Android |
|---|---|---|
| App ID | `com.patternsystems.pocketcash` | `com.patternsystems.pocketcash` |
| Widget extension | `com.patternsystems.pocketcash.widget` | (same app) |
| App Group | `group.com.patternsystems.pocketcash` | n/a |
| Lifetime Pro product | `pocketcash.pro.lifetime` (non-consumable, StoreKit 2) | `pocketcash.pro.lifetime` (one-time in-app product, Play Billing) |
| Deep links | `pocketcash://keypad?mode=spend\|add`, `pocketcash://count`, `pocketcash://wallet?id=<uuid>`, `pocketcash://pro` | same URIs |
| Minimum OS | iOS 18 (Control Center controls), built with the iOS 26+ SDK (Liquid Glass when available) | Android 8.0 (API 26), target 36 |

Price: **$3.99 one time** (set the price tier in App Store Connect / Play Console, never hard-coded; the apps show
the store's localized price).

## 2. Data model

### Wallet
| Field | Type | Notes |
|---|---|---|
| `id` | UUID | |
| `name` | String | "Main Wallet" by default |
| `currencyCode` | String | ISO 4217, defaults to the phone's locale currency (fallback `USD`) |
| `symbol` | String? | Optional custom symbol (travel wallets, Pro). Nil = the currency's own symbol |
| `addPresets` | [Decimal] | Positive magnitudes for the cash-in buttons, e.g. `[1000, 500]` |
| `spendPresets` | [Decimal] | Positive magnitudes for the spend buttons, e.g. `[100, 50, 20, 10]` |
| `dailyBudget` | Decimal? | Optional daily cash allowance |
| `lowCashThreshold` | Decimal? | Optional; a reminder fires when the balance drops below it |
| `target` | Decimal? | Optional envelope target / savings goal (Pro, section 10). Nil = a plain wallet |
| `sortOrder` | Int | Wallet order in the switcher; the first wallet is the default |
| `createdAt` | Date | |

A wallet's **balance is always the sum of its transactions' amounts**. It is never stored separately, so it can't
drift. A new wallet's starting cash is saved as an `adjust` transaction.

### CashTransaction
| Field | Type | Notes |
|---|---|---|
| `id` | UUID | |
| `walletID` | UUID | (iOS: SwiftData relationship `wallet`) |
| `amount` | Decimal | Signed. Positive = cash in, negative = cash out |
| `timestamp` | Date | |
| `kind` | String | `spend`, `in`, `atm`, `adjust`, `transfer` (see below) |
| `tag` | String? | Category key (section 6), nil = untagged |
| `note` | String? | One line |
| `transferID` | UUID? | Links the two halves of a transfer between wallets |

iOS keeps the spec's `isATMWithdrawal: Bool` as a stored property (true exactly when `kind == "atm"`) so SwiftData
queries stay simple.

Kinds:
- `spend`: money left the wallet through a spend button, the keypad, a widget, the Control Center / Quick Settings
  tile, or Siri. Always negative.
- `atm`: an ATM withdrawal. Always positive. **Starts a new ATM cycle** (section 4).
- `in`: other cash received (change, a friend paying back, a cash salary). Always positive.
- `adjust`: a reconcile ("Count my cash") or a wallet's starting cash. Either sign.
- `transfer`: half of a move between two wallets (Pro). The two halves share `transferID`. Never counts as spending
  or income.

Widget `+` buttons and the keypad's "Add cash" save `atm` when the **"From ATM"** switch is on (the default for `+`
buttons, controlled in Settings by "Count + buttons as ATM withdrawals", default on), otherwise `in`.

### Settings (global)
| Setting | Default |
|---|---|
| `quickSpendAmount` | The default wallet's smallest spend preset (the "daily commute fare") |
| `quickSpendWalletID` | nil = default wallet |
| `plusButtonsAreATM` | true |
| `privacyMode` | false. When on, widgets, the lock screen and the tile show `••••` instead of amounts |
| `lowCashAlerts` | false. Needs notification permission, asked when switched on |
| `haptics` | true |
| `appLock` | false. Lock the app with Face ID / Touch ID / fingerprint / the phone's passcode (section 12) |
| `onboarded` | false |

## 3. Money rules

- `spending(tx)` = `−amount` when `kind == spend`, or when `kind == adjust` and `amount < 0`. Otherwise 0.
  (A negative reconcile is "unrecorded spending": the money did leave the wallet.)
- `inflow(tx)` = `amount` when `kind` is `in` or `atm`, or `adjust` with `amount > 0`. Otherwise 0.
- Transfers are neither spending nor inflow.
- **Today spent** = Σ spending of transactions in `[startOfDay(now), startOfDay(now) + 1 day)` in the phone's time zone.
- **Left today** = `dailyBudget − todaySpent` (negative = "over by").
- Amounts are exact decimals: Swift `Decimal`, Kotlin `BigDecimal`. Never `Double` for stored money.

Display:
- Full amounts use the currency's symbol (or the wallet's custom symbol) and grouping, with fraction digits **only
  when the amount isn't whole**: `₱680`, `$12.50`, `¥1,200`.
- Button labels are compact and unsigned-symbol: `+1k`, `−100`, `−50`, `+10k`, `+1.5k`, `−0.25`, `+1M`. Values
  ≥ 1,000 use `k`, ≥ 1,000,000 use `M`, with at most one decimal (dropped when zero). The minus sign is U+2212 `−`.
- The hero balance uses tabular (monospaced) digits so it never jitters while it changes.

## 4. ATM cycles and burn rate (Pro analytics)

Pinned by `spec/vectors/cycles.json`. Within one wallet, transactions are sorted by `timestamp` ascending.

- Each `atm` transaction starts a cycle that runs until the next `atm` (exclusive) or, for the newest, until now
  (the cycle is **open**).
- Transactions before the first `atm` form a **starting cycle** (`isATM = false`, `withdrawn = 0`) that starts at
  the first transaction. It is open when the wallet has no `atm` at all.
- Per cycle:
  - `start`: the ATM's timestamp (starting cycle: the first transaction's).
  - `end`: the next ATM's timestamp, or nil when open.
  - `withdrawn`: the ATM amount (0 for the starting cycle).
  - `spent`: Σ spending.
  - `inflow`: Σ inflow, **excluding** the cycle's own ATM withdrawal.
  - `days` = `((end ?? now) − start)` in seconds / 86,400, as a fractional number.
  - `avgDaily` = `spent / max(days, 1)`.
  - `topAmount`: among `spend` transactions only, group by amount magnitude; the group with the largest total wins,
    ties broken by the larger count, then the larger amount. Also report `topAmountCount` and `topAmountTotal`.
    Nil when the cycle has no spends. ("Which denomination drains the wallet fastest.")
  - `topTag`: the tag with the most spending (untagged excluded), ties broken alphabetically. Nil when none.
- **Burn rate** = Σ spending in `(now − 14 days, now]` ÷ `clamp(daysSinceFirstTransaction, 1, 14)`, where
  `daysSinceFirstTransaction` is fractional. Nil when the wallet has no transactions.
- **Runway** = `balance / burnRate` days, only when both are > 0. The UI shows "Cash lasts until about Fri, Nov 28".

Feed header for a closed cycle: `ATM Cycle · Nov 20 – Nov 25 · ₱1,000 lasted 4.5 days`.
Open cycle: `Current cycle · since Nov 20 · ₱1,000 · 2.3 days so far`. Starting cycle: `Before first ATM`.
Days are shown with one decimal.

## 5. Free and Pro

Free is the basics: one wallet you can keep accurate every day. Pro is everything that makes it faster, smarter and
safer, so the $3.99 is an easy yes.

**Free** (the basics)
1. One wallet with starting cash, in the phone's currency.
2. Home Screen widgets, small and medium, with 1-tap buttons from the currency's default presets.
3. 5-second Undo after every tap (and on the widget), with haptic confirmation.
4. Custom amount keypad (Spend / Add cash, optional note).
5. 7-day rolling feed grouped by day, with swipe to delete and editable notes.
6. **Count my cash** (reconcile): type the real amount, or count bills and coins per denomination; the difference is
   saved as an `adjust` ("Unrecorded spending" / "Found cash").
7. **Backup file**: export everything to one JSON file and restore it on any iPhone or Android phone (section 11).
   Always free: a person's data is never held hostage.
8. The whole app in **English, Filipino, Spanish, Indonesian, Portuguese, Hindi and Vietnamese** (section 13).
9. Siri / Shortcuts and Android launcher shortcuts (they only open the app or log what you say).

**Pro** ($3.99 once, `pocketcash.pro.lifetime`)
1. **Envelopes and savings goals**: give any wallet a target, see progress bars, and split cash from one wallet into
   several envelopes in one step (section 10).
2. **Insights**: ATM cycle analytics (cycle-grouped feed, "₱1,000 lasted 4.5 days"), burn rate, runway forecast, top
   denomination and top category per cycle, spending by category.
3. **Multiple wallets** (Coin Pouch, Emergency Stash, a Travel wallet with its own currency and symbol), transfers
   between wallets, and a wallet picker on each widget.
4. **Quick spend everywhere**: Lock Screen widgets and the Control Center controls (iOS), the Quick Settings tile
   (Android), with a quick-spend amount you choose.
5. **Change calculator** in the keypad: "Paid ₱500, got ₱130 back" saves one ₱370 spend (section 7.2).
6. **Daily budget** with "left today" on the app and the medium widget.
7. **Categories**: 1-tap category icons on any transaction after the fact (never slows down the first tap).
8. **Custom buttons**: any amounts per wallet (1-2 cash-in and 1-4 spend buttons).
9. **App lock** with Face ID, Touch ID, fingerprint or the phone's passcode (section 12), and **privacy mode** for
   widgets and the lock screen.
10. **Low-cash reminder**: a local notification when a wallet drops below its threshold.
11. **Full history** beyond 7 days and **CSV export**.

How Pro features look to Free users: they stay visible with a small lock, and using one opens the paywall (never an
error). Free widgets and the medium widget's top-right show `−₱170 today` (no budget). A Lock Screen widget, Control
Center control or Quick Settings tile added while Free shows "PocketCash Pro" with a lock and opens the paywall when
tapped instead of spending. If Pro is ever lost (refund), Pro data stays stored and visible read-only where it makes
sense, but Pro actions show the paywall again; app lock and privacy mode switch off.

Conversion trigger: never block the first-run experience. Show the paywall when the person tries a Pro action
(any of the above: editing a button amount, adding a second wallet, tagging, opening Insights or Envelopes, scrolling
past 7 days, exporting CSV, "Got change?", setting a daily budget, turning on app lock, privacy mode or the low-cash
reminder, tapping a Pro quick-spend surface), and once, softly, on the third day of use. The paywall lists Pro's
features in the order above.

Debug builds have a "Unlock Pro (debug build)" switch in Settings for testing without the stores.

## 6. Categories

| Key | Label | iOS SF Symbol | Android icon idea |
|---|---|---|---|
| `food` | Food & drinks | `fork.knife` | restaurant |
| `transit` | Transit | `tram.fill` | bus |
| `parking` | Parking | `parkingsign.circle.fill` | parking |
| `groceries` | Groceries | `cart.fill` | cart |
| `errand` | Errands | `bag.fill` | bag |
| `tip` | Tips | `hand.thumbsup.fill` | thumb up |
| `bills` | Bills | `doc.text.fill` | receipt |
| `fun` | Fun | `sparkles` | sparkle |
| `other` | Other | `ellipsis.circle.fill` | more |

## 7. Screens

**Style: minimal.** One accent color (emerald) used only for cash in, progress bars and the primary button; everything
else is monochrome on a plain background. No drop shadows and no cards inside cards: groups are separated by space and
hairline dividers. One big number per screen (the balance), small secondary text, thin line icons, generous margins.
Motion is short and functional: the balance rolls to its new value, the nav bar morphs.

**Navigation: a dynamic bottom bar.** Four destinations, **Home, Insights, Envelopes, Settings**, in a floating
capsule at the bottom, with a separate round **+** button beside it that opens the keypad from anywhere (long-press:
Count my cash).
- The selected destination shows its icon and label; the others show only their icons. Switching slides the highlight
  and expands the new label.
- Scrolling down collapses the bar into a small capsule holding just the selected icon (the + button stays).
  Scrolling up, reaching the top, or tapping the small capsule expands it again.
- iOS 26+: Liquid Glass (`glassEffect`); iOS 18-25: thin material. Android: a Material 3 surface-container capsule,
  same layout. A light haptic tick on each switch.
- Insights and Envelopes are Pro. Free users can open them and see a quiet, blurred preview with an Unlock button that
  opens the paywall.
- **Top bar.** Home has no title: wallet switcher on the left ("Main Wallet ▾", PRO badge when unlocked). Once the big
  balance scrolls under the top bar, the balance appears small in the top bar's center (fade and slide). The other
  destinations use a large title that shrinks into the bar on scroll.
- Sheets (keypad, count, edit, paywall, fill envelopes) open over the current destination; the bar hides while one is
  open.

1. **Home**
   - Top bar as above. There's no Settings gear: Settings is a destination.
   - Hero: oversized balance (tabular digits). Under it: `Today −₱170` and, with a budget, `₱230 left today` (red
     `Over by ₱30` when over). Tap the balance, or pull it down, to open **Count my cash**.
   - Action pad: top row cash-in presets in muted emerald, bottom row spend presets in monochrome, plus a
     `[ Custom ]` button that opens the keypad.
   - Insight line: Pro shows one quiet line under the action pad with burn rate and runway ("₱61/day · lasts until
     about Thu, Dec 24"), tapping it opens Insights. Free shows nothing here (the Insights destination has the teaser).
   - Feed: Free = last 7 days by day ("Today", "Yesterday", "Mon, Nov 23"). Pro = full history by ATM cycle with
     collapsible headers. Rows: category icon (or kind icon), note or kind label, time, signed amount (cash in in
     emerald). Swipe to delete. Tap a row to edit note, tag (Pro) and amount.
   - Undo banner at the bottom for 5 seconds after every add: "−₱50 spent · Undo".
2. **Keypad sheet**: Spend / Add cash toggle, big amount, numeric keypad (1-9, decimal point when the currency has
   fraction digits, 0, delete), "From ATM" switch in Add cash mode, category chips (Pro), one-line note, full-width
   Save. Opens with the keypad already active.
   **Change calculator** (Spend mode, Pro): a "Got change?" button under the amount splits the entry into two
   fields, **Paid** and **Change**. The keypad types into whichever field is selected (Paid first; tapping "Got
   change?" selects Change). Under Paid, quick chips offer the currency's four largest bills from `presets.json`
   denominations that are at least the largest spend preset (fewer if the currency has fewer), so "Paid ₱1,000" is
   one tap. The display reads `Paid ₱500 − Change ₱130 = ₱370`. Save records **one** `spend` of `paid − change`;
   it's disabled while `change ≥ paid` (show "Change must be less than what you paid"). With no change entered it
   behaves like the plain keypad. The user's note is kept as typed; nothing about the change is stored.
3. **Count my cash**: "How much is in your wallet?" with two modes: type the total, or count per denomination
   (steppers for each bill and coin from `spec/presets.json`). Shows the difference against the app's balance and
   saves it as an `adjust`.
4. **Settings**: Wallets (add, rename, currency, symbol, starting cash, target (Pro), reorder, delete), Buttons
   (edit presets, Pro), Quick spend amount, Daily budget, Low-cash reminder, Count + buttons as ATM withdrawals,
   Privacy mode, App lock, Haptics, Back up to a file / Restore from a file, Export CSV (Pro), Language (opens the
   system's per-app language setting where the OS has one), PocketCash Pro (buy / restore), About, and the debug Pro
   switch in debug builds.
5. **Insights** (Pro): burn rate and runway, ATM cycles (days lasted, avg/day, top denomination, top category),
   spending by category for the last 30 days.
6. **Paywall**: what Pro adds, the store's localized price, Buy and Restore.
7. **Onboarding** (first run only): pick the currency, enter (or count) the cash in your wallet now, then a hint for
   adding the widget. A "Restore from a backup file" link on the first page.
8. **Envelopes** (Pro, section 10): a destination in the nav bar.

## 8. Widgets and quick actions

Every write goes through one store function that saves, then refreshes a small **widget snapshot** so widgets never
need the database to draw.

Snapshot (JSON, `widget_snapshot.json`, iOS in the App Group container):
```
{ "version": 1, "updatedAt": <date>, "isPro": bool, "privacyMode": bool, "plusButtonsAreATM": bool,
  "defaultWalletID": <uuid>, "quickSpend": { "walletID": <uuid>, "amount": "20" },
  "lastAction": { "walletID": <uuid>, "transactionID": <uuid>, "amount": "-50", "at": <date> } | null,
  "wallets": [ { "id", "name", "currencyCode", "symbol", "balance", "todaySpent", "dayStart", "dailyBudget",
                 "addPresets", "spendPresets" } ] }
```
`todaySpent` is valid for `dayStart`; a widget drawing on a later day shows 0. Free builds' widgets always show the
default wallet with its default presets.

**Small widget**: wallet name, balance, two spend buttons (`spendPresets[0]`, `spendPresets[1]`).
**Medium widget**: top row wallet name (left) and `−₱170 today` (right); big tabular balance in the middle; a bottom
row of four buttons `+1k  −100  −50  −20` (`addPresets[0]`, `spendPresets[0...2]`). With a daily budget, the top-right
shows `₱230 left` instead.
After a tap the widget shows `Undo −₱50` in place of the top row for a few seconds; tapping it deletes that
transaction (only within 2 minutes of the tap).

iOS:
- `AdjustCashIntent(walletID, amount)` runs in the widget extension process. It opens the SwiftData store at the
  explicit App Group URL `PocketCash.store`, inserts the transaction, **saves synchronously**, rewrites the snapshot,
  and only then calls `WidgetCenter.shared.reloadTimelines(ofKind:)`.
- Lock Screen: `accessoryCircular` (balance), `accessoryRectangular` (balance + today), `accessoryInline`, and a
  circular **Quick spend** button.
- Control Center (iOS 18): **Quick spend** (`ControlWidgetButton` running the quick-spend intent) and **Open keypad**.
- Siri / Shortcuts: "Spend cash in PocketCash", "Add cash in PocketCash", "Cash balance in PocketCash".
- Widgets support the accented / tinted rendering modes and Liquid Glass (`widgetAccentable`).

Android:
- Glance app widget with responsive small (2×2) and medium (4×2) layouts; buttons run an action callback that writes
  through the same store, vibrates briefly, and updates every widget. A one-time WorkManager job clears the Undo chip.
- Quick Settings tile **Quick spend** (one tap deducts the quick-spend amount; subtitle shows the balance or `••••`).
- Launcher shortcuts: "Spend", "Add cash", "Count my cash".
- Low-cash notifications on a "Low cash" channel (asks `POST_NOTIFICATIONS` on Android 13+ when switched on).

## 9. Privacy and storage

- No network permission on Android and no network calls on iOS. No accounts. No third-party SDKs.
- iOS: SwiftData store in the App Group container, plus the snapshot JSON. Included in the iPhone's own backups.
- Android: a JSON file in app storage (kotlinx.serialization), included in Google's device backup.
- CSV export columns: `date,time,wallet,kind,amount,currency,category,note` (ISO date, 24h time, amounts as plain
  decimals with a minus sign for cash out).
- A backup file (section 11) only leaves the phone when the person saves or shares it somewhere themselves.

## 10. Envelopes and savings goals (Pro)

"Cash stuffing": you split cash into envelopes (Rent, Food, Savings) and spend each one only on its purpose. In
PocketCash an envelope is an ordinary wallet with a `target`. Nothing else about wallets changes: envelopes have
buttons, widgets, cycles and transfers like any wallet.

- **Progress** = `clamp(balance / target, 0, 1)` when `target > 0`. **Short by** = `max(target − balance, 0)`.
  Shown as a bar plus `₱2,300 of ₱5,000` (or `Goal reached` when `balance ≥ target`).
- **Envelopes screen**: every wallet in `sortOrder`, each with its balance, and the progress bar and target when it
  has one. Tapping one selects it on Home. A **Fill envelopes** button opens the fill sheet.
- **Fill sheet**: pick a source wallet (default: the default wallet). It lists every other wallet **with the same
  currency** as the source, each with an amount field. Fields start with the suggested fill: walk the envelopes in
  `sortOrder` and give each `min(short by, cash still left in the source)` (wallets without a target get 0). Footer:
  `Moving ₱4,000 · ₱1,200 stays in Main Wallet`. Save is disabled when the total is 0 or more than the source's
  balance. Save creates one transfer pair per envelope with a non-zero amount (source `−x`, envelope `+x`, shared
  `transferID`, kind `transfer`, the same timestamp), in one store write.
- Wallet editor: an optional **Target** amount field ("Envelope target or savings goal"), Pro.

## 11. Backup file

A single JSON file that either app can read, so a backup made on an iPhone restores on Android and the other way.
Free. Example: `spec/backup/example-v1.json` (both apps' tests decode it and check the totals listed in its
`_expected` field, which readers ignore).

```
{ "format": "pocketcash-backup", "version": 1, "exportedAt": "2026-11-25T12:00:00Z",
  "app": { "platform": "ios" | "android", "version": "1.0" },
  "settings": { "plusButtonsAreATM": true, "privacyMode": false, "lowCashAlerts": false, "quickSpendAmount": "20",
                "quickSpendWalletID": "<uuid>" | null },
  "wallets": [ { "id", "name", "currencyCode", "symbol", "addPresets": ["1000","500"], "spendPresets": [...],
                 "dailyBudget": "300" | null, "lowCashThreshold": null, "target": null, "sortOrder": 0,
                 "createdAt": "<date>",
                 "transactions": [ { "id", "amount": "-50", "kind": "spend", "timestamp": "<date>",
                                     "tag": "food" | null, "note": null, "transferID": null } ] } ] }
```

- File name `PocketCash-backup-YYYY-MM-DD.json`, UTF-8, pretty-printed. MIME type `application/json`.
- Amounts are decimal **strings** (exact, `.` as the decimal point, no grouping). Dates are RFC 3339 in UTC;
  writers may include fractional seconds and readers must accept dates with or without them. IDs are UUID strings
  (compare case-insensitively).
- Readers ignore unknown fields (so `version: 1` files can grow), and reject the file when `format` is wrong,
  `version` is greater than 1, a kind or currency code is invalid, an amount doesn't parse, or an ID repeats.
- Settings that aren't in this list (Pro unlock, app lock, notification permission, haptics) are never in a backup.
- **Restore replaces everything.** Before restoring, the app shows what the file holds (`2 wallets · 143 cash moves ·
  backed up Nov 25, 2026`) and asks to confirm "Replace all data on this phone". Then it writes everything in one
  store transaction, refreshes widgets, and selects the default wallet. A file that fails validation changes nothing
  and shows why ("This file isn't a PocketCash backup", "This backup is from a newer version of PocketCash").
- Export: Settings → "Back up to a file" saves through the system's file picker / share sheet. Restore: Settings →
  "Restore from a file", and the onboarding link.

## 12. App lock (Pro)

- Settings → **App lock** ("Lock with Face ID", "Lock with Touch ID", "Lock with fingerprint" or "Lock with
  passcode", whichever the phone offers). Turning it on asks for one successful unlock first; if the phone has no
  passcode or biometrics set up, the switch stays off and says so.
- While on, the app locks every time it goes to the background. When it opens, it shows a plain lock screen (app
  icon, "PocketCash is locked", an **Unlock** button) and asks for authentication right away. Biometrics fall back
  to the phone's passcode / PIN / pattern. Content must not be visible behind the lock screen or in the app
  switcher.
- iOS: LocalAuthentication `deviceOwnerAuthentication`, `NSFaceIDUsageDescription` in Info.plist. Android:
  androidx.biometric `BiometricPrompt` allowing biometrics or device credential; hide the Recents thumbnail
  (`setRecentsScreenshotEnabled(false)` on API 33+). Screenshots stay allowed.
- Widgets, the Lock Screen widgets, Control Center controls, the Quick Settings tile and Siri keep working while the
  app is locked (they show only what privacy mode allows). The lock screen mentions this once, under the switch:
  "Widgets still show your balance. Turn on Privacy mode to hide it."

## 13. Languages

Both apps ship **English** (base) plus **Filipino** (`fil`), **Spanish** (`es`), **Indonesian** (`id`),
**Portuguese** (`pt`, Brazilian wording), **Hindi** (`hi`) and **Vietnamese** (`vi`). Every user-facing string is
translated, including widgets, notifications, the tile and shortcuts. Amounts, dates and times always follow the
phone's region formatting. The phone's per-app language setting picks the language (iOS Settings → PocketCash →
Language; Android 13+ App languages, declared with `localeConfig`).

The translations are first drafts and need a native speaker's review before a store launch. Keep these terms the same
everywhere so both apps read alike:

| English | Filipino | Spanish | Indonesian | Portuguese | Hindi | Vietnamese |
|---|---|---|---|---|---|---|
| Wallet | Pitaka | Cartera | Dompet | Carteira | बटुआ | Ví |
| Cash | Cash | Efectivo | Uang tunai | Dinheiro | नकद | Tiền mặt |
| Balance | Balanse | Saldo | Saldo | Saldo | शेष राशि | Số dư |
| Spend / Spent | Gastos / Nagastos | Gastar / Gastado | Keluar / Terpakai | Gastar / Gasto | खर्च / खर्च हुआ | Chi / Đã chi |
| Add cash | Magdagdag ng cash | Añadir efectivo | Tambah uang | Adicionar dinheiro | नकद जोड़ें | Thêm tiền |
| Undo | I-undo | Deshacer | Urungkan | Desfazer | पूर्ववत करें | Hoàn tác |
| ATM withdrawal | Withdraw sa ATM | Retiro en cajero | Tarik tunai ATM | Saque no caixa eletrônico | एटीएम निकासी | Rút tiền ATM |
| Count my cash | Bilangin ang cash ko | Contar mi efectivo | Hitung uangku | Contar meu dinheiro | मेरा नकद गिनें | Đếm tiền của tôi |
| Daily budget | Budget kada araw | Presupuesto diario | Anggaran harian | Orçamento diário | दैनिक बजट | Ngân sách mỗi ngày |
| Quick spend | Mabilisang gastos | Gasto rápido | Keluar cepat | Gasto rápido | झटपट खर्च | Chi nhanh |
| Change (money back) | Sukli | Cambio | Kembalian | Troco | बाकी पैसे | Tiền thừa |
| Envelope | Sobre | Sobre | Amplop | Envelope | लिफ़ाफ़ा | Phong bì |
| Target / goal | Target | Meta | Target | Meta | लक्ष्य | Mục tiêu |
| Backup | Backup | Copia de seguridad | Cadangan | Backup | बैकअप | Bản sao lưu |
| App lock | Lock ng app | Bloqueo de la app | Kunci aplikasi | Bloqueio do app | ऐप लॉक | Khóa ứng dụng |
| Today | Ngayon | Hoy | Hari ini | Hoje | आज | Hôm nay |
| Pro | Pro | Pro | Pro | Pro | Pro | Pro |

"PocketCash" is never translated. Category names follow section 6's keys with translated labels.
