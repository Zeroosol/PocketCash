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

**Free** (immediate daily utility)
1. One wallet with starting cash, in the phone's currency.
2. Home Screen widgets, small and medium, with 1-tap buttons from the currency's default presets.
3. Lock Screen widgets (iOS) and a Control Center control / Quick Settings tile (Android) for the **quick spend**
   amount, plus an "Open keypad" control. The quick-spend amount can be set to any value, free.
4. 5-second Undo after every tap, with haptic confirmation.
5. 7-day rolling feed grouped by day, with swipe to delete and editable notes.
6. Custom amount keypad (Spend / Add cash, optional note).
7. **Count my cash** (reconcile): type the real amount, or count bills and coins per denomination; the difference is
   saved as an `adjust` ("Unrecorded spending" / "Found cash").
8. **Daily budget** with "left today" on the app and the medium widget.
9. **Low-cash reminder**: a local notification when a wallet drops below its threshold.
10. **Privacy mode** for widgets and the lock screen.

**Pro** ($3.99 once, `pocketcash.pro.lifetime`)
1. Custom denomination buttons per wallet (any amounts, 1-2 cash-in and 1-4 spend buttons).
2. Multiple wallets (Main Wallet, Coin Pouch, Emergency Stash, a Travel wallet with its own currency and symbol),
   transfers between wallets, and a wallet picker on each widget.
3. ATM cycle analytics: cycle-grouped feed, burn rate, runway forecast, top denomination and top category per cycle.
4. 1-tap category icons on any transaction after the fact (never slows down the first tap) and a category breakdown.
5. Full history beyond 7 days and CSV export.

Conversion trigger: never block the first-run experience. Show the paywall when the person tries a Pro action
(editing a button amount, adding a second wallet, tagging, opening insights, scrolling past 7 days, exporting), and
once, softly, on the third day of use.

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

Single canvas, no tab bar.

1. **Home**
   - Header: wallet switcher (top-left, "Main Wallet ▾"; Pro for more than one), Settings gear (top-right), a small
     PRO badge when unlocked.
   - Hero: oversized balance (tabular digits). Under it: `Today −₱170` and, with a budget, `₱230 left today` (red
     `Over by ₱30` when over). Tap the balance, or pull it down, to open **Count my cash**.
   - Action pad: top row cash-in presets in muted emerald, bottom row spend presets in monochrome, plus a
     `[ Custom ]` button that opens the keypad.
   - Insight card: Pro shows burn rate and runway ("₱61/day · lasts until about Thu, Dec 24"); Free shows a short
     teaser that opens the paywall.
   - Feed: Free = last 7 days by day ("Today", "Yesterday", "Mon, Nov 23"). Pro = full history by ATM cycle with
     collapsible headers. Rows: category icon (or kind icon), note or kind label, time, signed amount (cash in in
     emerald). Swipe to delete. Tap a row to edit note, tag (Pro) and amount.
   - Undo banner at the bottom for 5 seconds after every add: "−₱50 spent · Undo".
2. **Keypad sheet**: Spend / Add cash toggle, big amount, numeric keypad (1-9, decimal point when the currency has
   fraction digits, 0, delete), "From ATM" switch in Add cash mode, category chips (Pro), one-line note, full-width
   Save. Opens with the keypad already active.
3. **Count my cash**: "How much is in your wallet?" with two modes: type the total, or count per denomination
   (steppers for each bill and coin from `spec/presets.json`). Shows the difference against the app's balance and
   saves it as an `adjust`.
4. **Settings**: Wallets (add, rename, currency, symbol, starting cash, reorder, delete), Buttons (edit presets,
   Pro), Quick spend amount, Daily budget, Low-cash reminder, Count + buttons as ATM withdrawals, Privacy mode, Haptics,
   Export CSV (Pro), PocketCash Pro (buy / restore), About, and the debug Pro switch in debug builds.
5. **Insights** (Pro): burn rate and runway, ATM cycles (days lasted, avg/day, top denomination, top category),
   spending by category for the last 30 days.
6. **Paywall**: what Pro adds, the store's localized price, Buy and Restore.
7. **Onboarding** (first run only): pick the currency, enter (or count) the cash in your wallet now, then a hint for
   adding the widget.

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
