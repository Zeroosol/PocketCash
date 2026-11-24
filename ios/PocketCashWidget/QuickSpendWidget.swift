import AppIntents
import SwiftUI
import WidgetKit

/// A single big button for the quick-spend amount: a Lock Screen circle or a small Home Screen tile.
struct QuickSpendEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot?

    var quick: (wallet: WidgetSnapshot.WalletInfo, amount: Decimal)? {
        guard let snapshot, let q = snapshot.quickSpend,
              let wallet = snapshot.wallets.first(where: { $0.id == q.walletID }) else { return nil }
        return (wallet, q.value)
    }
}

struct QuickSpendProvider: TimelineProvider {
    func placeholder(in context: Context) -> QuickSpendEntry { QuickSpendEntry(date: .now, snapshot: .sample) }

    func getSnapshot(in context: Context, completion: @escaping (QuickSpendEntry) -> Void) {
        let loaded = WidgetSnapshot.load()
        completion(QuickSpendEntry(date: .now, snapshot: context.isPreview && loaded == nil ? .sample : loaded))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<QuickSpendEntry>) -> Void) {
        completion(Timeline(entries: [QuickSpendEntry(date: .now, snapshot: WidgetSnapshot.load())], policy: .never))
    }
}

struct QuickSpendWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: PocketCash.quickSpendWidgetKind, provider: QuickSpendProvider()) { entry in
            QuickSpendView(entry: entry)
        }
        .configurationDisplayName("Quick Spend")
        .description("One tap subtracts your most common cash expense, like your commute fare.")
        .supportedFamilies([.accessoryCircular, .systemSmall])
    }
}

struct QuickSpendView: View {
    @Environment(\.widgetFamily) private var family
    let entry: QuickSpendEntry

    var body: some View {
        Group {
            if let quick = entry.quick {
                Button(intent: QuickSpendIntent()) {
                    if family == .accessoryCircular {
                        ZStack {
                            AccessoryWidgetBackground()
                            VStack(spacing: 0) {
                                Image(systemName: "minus").font(.caption2.weight(.bold))
                                Text(Money.compact(quick.amount))
                                    .font(.system(.title3, design: .rounded, weight: .bold))
                                    .monospacedDigit()
                                    .minimumScaleFactor(0.5)
                                    .widgetAccentable()
                            }
                        }
                    } else {
                        VStack(alignment: .leading, spacing: 4) {
                            Label("Quick spend", systemImage: "bolt.fill")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                            Spacer(minLength: 0)
                            Text(Money.buttonLabel(quick.amount, cashIn: false))
                                .font(Theme.balance(40))
                                .minimumScaleFactor(0.5)
                                .lineLimit(1)
                                .widgetAccentable()
                            Text(entry.snapshot?.privacyMode == true ? quick.wallet.name
                                 : "\(quick.wallet.format(quick.wallet.balanceValue)) in \(quick.wallet.name)")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                    }
                }
                .buttonStyle(.plain)
            } else {
                SetupView(family: family)
            }
        }
        .containerBackground(for: .widget) { Color(uiColor: .systemBackground) }
    }
}
