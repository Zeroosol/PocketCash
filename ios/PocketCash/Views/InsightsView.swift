import Charts
import SwiftUI

/// The Insights destination (Pro, docs/spec.md sections 4 and 7.5): burn rate, runway, ATM cycles and spending by
/// category. Free users see a blurred preview with an Unlock button.
struct InsightsScreen: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore

    var body: some View {
        NavigationStack {
            Group {
                if let wallet = model.wallet {
                    if pro.isPro {
                        ScrollView { InsightsContent(wallet: wallet) }
                            .tracksNavBar(.insights)
                    } else {
                        ProPreview(title: "Insights are part of Pro",
                                   message: "See how long each ATM withdrawal lasts, your daily burn rate, when the cash runs out, and where it goes.") {
                            ScrollView { InsightsContent(wallet: wallet) }
                                .scrollDisabled(true)
                        }
                    }
                }
            }
            .background(Theme.background)
            .navigationTitle("Insights")
            .navigationBarTitleDisplayMode(.large)
        }
    }
}

private struct InsightsContent: View {
    let wallet: Wallet

    var body: some View {
        let now = Date()
        let items = wallet.ledger
        let cycles = Ledger.cycles(items, now: now)
        let summary = BurnSummary(wallet: wallet, now: now)
        let byTag = Ledger.spendingByTag(items, since: now.addingTimeInterval(-30 * Ledger.secondsPerDay))

        VStack(alignment: .leading, spacing: 32) {
            VStack(alignment: .leading, spacing: 6) {
                Text(summary.headline)
                    .font(.title2.weight(.semibold))
                    .monospacedDigit()
                Text(summary.detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let current = cycles.last {
                    let spent = wallet.format(current.spent)
                    let days = Feed.days(current.days)
                    Group {
                        if current.isATM {
                            Text("This ATM cycle: \(spent) in \(days) days")
                        } else {
                            Text("So far: \(spent) in \(days) days")
                        }
                    }
                    .font(.subheadline)
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
                }
            }

            if !byTag.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    SectionTitle(text: Text("Last 30 days by category"))
                    Chart(Array(byTag.enumerated()), id: \.offset) { item in
                        BarMark(x: .value("Spent", Money.double(item.element.total)),
                                y: .value("Category", label(item.element.tag)))
                            .foregroundStyle(Theme.emerald)
                            .cornerRadius(4)
                            .annotation(position: .trailing) {
                                Text(verbatim: wallet.format(item.element.total))
                                    .font(.caption2.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                    }
                    .chartXAxis(.hidden)
                    .frame(height: CGFloat(byTag.count) * 32 + 12)
                }
            }

            VStack(alignment: .leading, spacing: 0) {
                SectionTitle(text: Text("ATM cycles"))
                    .padding(.bottom, 4)
                if cycles.isEmpty {
                    Text("Cycles appear after your first ATM withdrawal.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .padding(.vertical, 12)
                }
                ForEach(cycles.reversed()) { cycle in
                    CycleRow(cycle: cycle, wallet: wallet)
                        .padding(.vertical, 14)
                        .hairline()
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
    }

    private func label(_ tag: String?) -> String {
        tag.flatMap(CashCategory.init(rawValue:))?.label ?? String(localized: "Untagged")
    }
}

/// A small uppercase-free group title.
struct SectionTitle: View {
    let text: Text

    var body: some View {
        text
            .font(.footnote.weight(.medium))
            .foregroundStyle(.secondary)
    }
}

private struct CycleRow: View {
    let cycle: CashCycle
    let wallet: Wallet

    var body: some View {
        let day: (Date) -> String = { $0.formatted(.dateTime.month(.abbreviated).day()) }
        let start = day(cycle.start)
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Group {
                    if !cycle.isATM {
                        Text("Before first ATM")
                    } else if let end = cycle.end.map(day) {
                        Text(verbatim: "\(start) – \(end)")
                    } else {
                        Text("Current · since \(start)")
                    }
                }
                .font(.subheadline.weight(.semibold))
                Spacer()
                if cycle.isATM {
                    Text(verbatim: wallet.format(cycle.withdrawn, signed: true))
                        .font(.subheadline.weight(.medium).monospacedDigit())
                        .foregroundStyle(Theme.emerald)
                }
            }
            HStack(spacing: 0) {
                let days = Feed.days(cycle.days)
                if cycle.isOpen {
                    stat(Text("So far"), Text("\(days) d"))
                } else {
                    stat(Text("Lasted"), Text("\(days) d"))
                }
                stat(Text("Spent"), Text(verbatim: wallet.format(cycle.spent)))
                stat(Text("Per day"), Text(verbatim: wallet.format(Money.rounded(Decimal(cycle.avgDaily),
                                                                                  scale: wallet.allowsFractions ? 2 : 0))))
            }
            if let top = cycle.topAmount {
                let topText = wallet.format(top)
                let count = String(cycle.topAmountCount)
                Label("Top denomination: \(topText) × \(count)", systemImage: "banknote")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let category = cycle.topTag.flatMap(CashCategory.init(rawValue:)) {
                let name = category.label
                Label("Most went to \(name)", systemImage: category.symbol)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func stat(_ title: Text, _ value: Text) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            title.font(.caption2).foregroundStyle(.secondary)
            value.font(.callout.monospacedDigit()).lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
