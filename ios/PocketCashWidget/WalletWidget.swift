import AppIntents
import SwiftUI
import WidgetKit

/// Lets each widget show a chosen wallet (Pro). Free widgets always show the default wallet.
struct SelectWalletIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "Wallet"
    static let description = IntentDescription("Choose the wallet this widget shows. Needs PocketCash Pro.")

    @Parameter(title: "Wallet") var wallet: WalletEntity?

    init() {}
}

struct WalletEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot?
    let walletID: UUID?
    /// True for the few seconds after a tap, while the Undo chip shows.
    let showUndo: Bool

    var wallet: WidgetSnapshot.WalletInfo? { snapshot?.wallet(walletID) }
}

struct WalletProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> WalletEntry {
        WalletEntry(date: .now, snapshot: .sample, walletID: nil, showUndo: false)
    }

    func snapshot(for configuration: SelectWalletIntent, in context: Context) async -> WalletEntry {
        let loaded = WidgetSnapshot.load()
        let useSample = context.isPreview && (loaded?.wallets.isEmpty ?? true)
        return WalletEntry(date: .now, snapshot: useSample ? .sample : loaded, walletID: configuration.wallet?.id, showUndo: false)
    }

    /// The app and the intents reload timelines after every write. Beyond that the widget only changes when the
    /// Undo chip expires and at midnight, when "today" starts over.
    func timeline(for configuration: SelectWalletIntent, in context: Context) async -> Timeline<WalletEntry> {
        let now = Date()
        let snapshot = WidgetSnapshot.load()
        let id = configuration.wallet?.id
        var entries: [WalletEntry] = []
        if let action = snapshot?.lastAction, snapshot?.wallet(id)?.id == action.walletID {
            let hideAt = action.at.addingTimeInterval(PocketCash.widgetUndoSeconds)
            if hideAt > now {
                entries.append(WalletEntry(date: now, snapshot: snapshot, walletID: id, showUndo: true))
                entries.append(WalletEntry(date: hideAt, snapshot: snapshot, walletID: id, showUndo: false))
            }
        }
        if entries.isEmpty {
            entries.append(WalletEntry(date: now, snapshot: snapshot, walletID: id, showUndo: false))
        }
        let calendar = Calendar.current
        let midnight = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: now)) ?? now.addingTimeInterval(86_400)
        entries.append(WalletEntry(date: midnight, snapshot: snapshot, walletID: id, showUndo: false))
        return Timeline(entries: entries, policy: .after(midnight))
    }
}

struct WalletWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: PocketCash.walletWidgetKind, intent: SelectWalletIntent.self, provider: WalletProvider()) { entry in
            WalletWidgetView(entry: entry)
        }
        .configurationDisplayName("Cash Wallet")
        .description("Your cash balance with 1-tap buttons. Tap −50 when you pay, no app needed.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

// MARK: - Views

struct WalletWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: WalletEntry

    /// Lock Screen widgets are Pro (docs/spec.md section 5); Free shows "PocketCash Pro" and opens the paywall.
    private var lockedForFree: Bool {
        guard let snapshot = entry.snapshot, !snapshot.isPro else { return false }
        return [.accessoryCircular, .accessoryRectangular, .accessoryInline].contains(family)
    }

    var body: some View {
        Group {
            if lockedForFree {
                ProLockedView(family: family)
            } else if let snapshot = entry.snapshot, let wallet = entry.wallet {
                let model = WidgetModel(snapshot: snapshot, wallet: wallet, date: entry.date, showUndo: entry.showUndo)
                switch family {
                case .systemMedium: MediumWalletView(model: model)
                case .accessoryCircular: CircularWalletView(model: model)
                case .accessoryRectangular: RectangularWalletView(model: model)
                case .accessoryInline: Label(model.balanceText, systemImage: "banknote")
                default: SmallWalletView(model: model)
                }
            } else {
                SetupView(family: family)
            }
        }
        .containerBackground(for: .widget) { Color(uiColor: .systemBackground) }
        .widgetURL(lockedForFree ? URL(string: "pocketcash://pro")
                   : URL(string: "pocketcash://wallet" + (entry.wallet.map { "?id=\($0.id.uuidString)" } ?? "")))
    }
}

/// Everything a widget layout needs, with privacy mode applied.
struct WidgetModel {
    let snapshot: WidgetSnapshot
    let wallet: WidgetSnapshot.WalletInfo
    let date: Date
    let showUndo: Bool

    var hidden: Bool { snapshot.privacyMode }
    var balance: Decimal { wallet.balanceValue }
    var today: Decimal { wallet.spentToday(at: date) }

    var balanceText: String { hidden ? "••••" : wallet.format(balance) }

    var compactBalance: String {
        if hidden { return "••" }
        let text = Money.compact(balance)
        return balance < 0 ? Money.minus + text : text
    }

    /// "−₱170 today", or with a budget (Pro) "₱230 left" / "Over by ₱30".
    var statusText: String {
        if hidden { return "••••" }
        if let budget = wallet.budgetValue {
            let left = budget - today
            if left >= 0 {
                let text = wallet.format(left)
                return String(localized: "\(text) left")
            }
            let text = wallet.format(-left)
            return String(localized: "Over by \(text)")
        }
        if today == 0 { return String(localized: "Nothing spent today") }
        let text = wallet.format(-today)
        return String(localized: "\(text) today")
    }

    var statusIsWarning: Bool {
        guard !hidden, let budget = wallet.budgetValue else { return false }
        return today > budget
    }

    var undoAction: WidgetSnapshot.LastAction? {
        guard showUndo, let action = snapshot.lastAction, action.walletID == wallet.id else { return nil }
        return action
    }

    var addButtons: [Decimal] { wallet.addValues }
    var spendButtons: [Decimal] { wallet.spendValues }
}

private struct WidgetPadButton: View {
    let walletID: UUID
    let magnitude: Decimal
    let cashIn: Bool

    var body: some View {
        Button(intent: AdjustCashIntent(walletID: walletID, amount: cashIn ? magnitude : -magnitude)) {
            Text(Money.buttonLabel(magnitude, cashIn: cashIn))
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .foregroundStyle(cashIn ? Theme.emerald : Color.primary)
                .frame(maxWidth: .infinity, minHeight: 34)
                .background(RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .fill(cashIn ? Theme.emerald.opacity(0.16) : Color.primary.opacity(0.08)))
        }
        .buttonStyle(.plain)
        .widgetAccentable(cashIn)
    }
}

private struct UndoChip: View {
    let action: WidgetSnapshot.LastAction
    let wallet: WidgetSnapshot.WalletInfo

    var body: some View {
        Button(intent: UndoCashIntent(transactionID: action.transactionID)) {
            let amount = wallet.format(action.value, signed: true)
            Label("Undo \(amount)", systemImage: "arrow.uturn.backward")
                .font(.caption.weight(.semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(Capsule().fill(Color.primary.opacity(0.1)))
        }
        .buttonStyle(.plain)
    }
}

private struct SmallWalletView: View {
    let model: WidgetModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let action = model.undoAction {
                UndoChip(action: action, wallet: model.wallet)
            } else {
                Text(model.wallet.name)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 2)
            Text(model.balanceText)
                .font(Theme.balance(30))
                .lineLimit(1)
                .minimumScaleFactor(0.4)
                .contentTransition(.numericText())
                .widgetAccentable()
            Text(model.statusText)
                .font(.caption2)
                .foregroundStyle(model.statusIsWarning ? Theme.danger : .secondary)
                .lineLimit(1)
            Spacer(minLength: 6)
            HStack(spacing: 6) {
                ForEach(Array(model.spendButtons.prefix(2).enumerated()), id: \.offset) { _, value in
                    WidgetPadButton(walletID: model.wallet.id, magnitude: value, cashIn: false)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}

private struct MediumWalletView: View {
    let model: WidgetModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                if let action = model.undoAction {
                    UndoChip(action: action, wallet: model.wallet)
                } else {
                    Label(model.wallet.name, systemImage: "wallet.pass.fill")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 4)
                Text(model.statusText)
                    .font(.caption.weight(.medium))
                    .monospacedDigit()
                    .foregroundStyle(model.statusIsWarning ? Theme.danger : .secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            Text(model.balanceText)
                .font(Theme.balance(40))
                .lineLimit(1)
                .minimumScaleFactor(0.4)
                .contentTransition(.numericText())
                .widgetAccentable()
            Spacer(minLength: 0)
            HStack(spacing: 7) {
                if let add = model.addButtons.first {
                    WidgetPadButton(walletID: model.wallet.id, magnitude: add, cashIn: true)
                }
                ForEach(Array(model.spendButtons.prefix(3).enumerated()), id: \.offset) { _, value in
                    WidgetPadButton(walletID: model.wallet.id, magnitude: value, cashIn: false)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}

private struct CircularWalletView: View {
    let model: WidgetModel

    var body: some View {
        ZStack {
            AccessoryWidgetBackground()
            VStack(spacing: 0) {
                Image(systemName: "banknote").font(.caption2)
                Text(model.compactBalance)
                    .font(.system(.body, design: .rounded, weight: .bold))
                    .monospacedDigit()
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                    .widgetAccentable()
            }
            .padding(4)
        }
    }
}

private struct RectangularWalletView: View {
    let model: WidgetModel

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Label(model.wallet.name, systemImage: "banknote")
                .font(.caption2.weight(.semibold))
                .lineLimit(1)
            Text(model.balanceText)
                .font(.system(.title3, design: .rounded, weight: .bold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .widgetAccentable()
            Text(model.statusText)
                .font(.caption2)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// What a Pro-only widget shows on a Free install: "PocketCash Pro" with a lock. Tapping opens the paywall.
struct ProLockedView: View {
    let family: WidgetFamily

    var body: some View {
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 1) {
                    Image(systemName: "lock.fill").font(.caption)
                    Text(verbatim: "Pro").font(.caption2.weight(.semibold))
                }
            }
        case .accessoryInline:
            Label("PocketCash Pro", systemImage: "lock.fill")
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 1) {
                Label("PocketCash Pro", systemImage: "lock.fill").font(.caption.weight(.semibold))
                Text("Tap to unlock").font(.caption2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        default:
            VStack(alignment: .leading, spacing: 6) {
                Image(systemName: "lock.fill").font(.title3)
                Spacer(minLength: 0)
                Text("PocketCash Pro").font(.footnote.weight(.semibold))
                Text("Tap to unlock").font(.caption2).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
    }
}

struct SetupView: View {
    let family: WidgetFamily

    var body: some View {
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: "banknote")
            }
        case .accessoryInline:
            Text("Open PocketCash")
        case .accessoryRectangular:
            Text("Open PocketCash to set up your wallet").font(.caption)
        default:
            VStack(alignment: .leading, spacing: 6) {
                Image(systemName: "wallet.pass.fill").font(.title2).foregroundStyle(Theme.emerald)
                Text("Open PocketCash to set up your wallet")
                    .font(.footnote.weight(.semibold))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
    }
}
