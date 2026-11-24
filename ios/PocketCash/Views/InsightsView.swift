import Charts
import SwiftUI

/// Pro analytics (docs/spec.md section 4): burn rate, runway, ATM cycles and spending by category.
struct InsightsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            if let wallet = model.wallet {
                let now = Date()
                let items = wallet.ledger
                let cycles = Ledger.cycles(items, now: now)
                let summary = BurnSummary(wallet: wallet, now: now)
                let byTag = Ledger.spendingByTag(items, since: now.addingTimeInterval(-30 * Ledger.secondsPerDay))
                List {
                    Section {
                        VStack(alignment: .leading, spacing: 4) {
                            Label(summary.headline, systemImage: "flame.fill")
                                .font(.headline)
                                .foregroundStyle(.primary)
                            Text(summary.detail).font(.subheadline).foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 4)
                        if let current = cycles.last {
                            LabeledContent(current.isATM ? "This ATM cycle" : "So far",
                                           value: "\(wallet.format(current.spent)) in \(String(format: "%.1f", current.days)) days")
                        }
                    }

                    if !byTag.isEmpty {
                        Section("Last 30 days by category") {
                            Chart(Array(byTag.enumerated()), id: \.offset) { item in
                                BarMark(x: .value("Spent", Money.double(item.element.total)),
                                        y: .value("Category", label(item.element.tag)))
                                    .foregroundStyle(Theme.emerald.gradient)
                                    .cornerRadius(5)
                                    .annotation(position: .trailing) {
                                        Text(wallet.format(item.element.total))
                                            .font(.caption2.monospacedDigit())
                                            .foregroundStyle(.secondary)
                                    }
                            }
                            .chartXAxis(.hidden)
                            .frame(height: CGFloat(byTag.count) * 34 + 12)
                            .padding(.vertical, 6)
                        }
                    }

                    Section("ATM cycles") {
                        if cycles.isEmpty {
                            Text("Cycles appear after your first ATM withdrawal.").foregroundStyle(.secondary)
                        }
                        ForEach(cycles.reversed()) { cycle in
                            CycleRow(cycle: cycle, wallet: wallet)
                        }
                    }
                }
                .navigationTitle("Insights")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            }
        }
    }

    private func label(_ tag: String?) -> String {
        tag.flatMap(CashCategory.init(rawValue:))?.label ?? "Untagged"
    }
}

private struct CycleRow: View {
    let cycle: CashCycle
    let wallet: Wallet

    var body: some View {
        let day: (Date) -> String = { $0.formatted(.dateTime.month(.abbreviated).day()) }
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(!cycle.isATM ? "Before first ATM"
                     : cycle.end.map { "\(day(cycle.start)) – \(day($0))" } ?? "Current · since \(day(cycle.start))")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                if cycle.isATM {
                    Text(wallet.format(cycle.withdrawn, signed: true))
                        .font(.subheadline.weight(.semibold).monospacedDigit())
                        .foregroundStyle(Theme.emerald)
                }
            }
            HStack(spacing: 0) {
                stat(cycle.isOpen ? "So far" : "Lasted", "\(String(format: "%.1f", cycle.days)) d")
                stat("Spent", wallet.format(cycle.spent))
                stat("Per day", wallet.format(Money.rounded(Decimal(cycle.avgDaily), scale: wallet.allowsFractions ? 2 : 0)))
            }
            if let top = cycle.topAmount {
                Label("Top denomination: \(wallet.format(top)) × \(cycle.topAmountCount)", systemImage: "banknote")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let category = cycle.topTag.flatMap(CashCategory.init(rawValue:)) {
                Label("Most went to \(category.label)", systemImage: category.symbol)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }

    private func stat(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption2).foregroundStyle(.secondary)
            Text(value).font(.callout.weight(.medium).monospacedDigit()).lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
