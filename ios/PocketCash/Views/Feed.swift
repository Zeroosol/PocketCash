import SwiftUI

struct FeedGroup: Identifiable {
    let id: String
    let title: String
    let detail: String?
    let items: [CashTransaction]
}

/// Free: the last 7 days by day. Pro: everything by ATM cycle (docs/spec.md sections 4 and 7).
enum Feed {
    static func byDay(_ wallet: Wallet, now: Date, days: Int = 7, calendar: Calendar = .current) -> (groups: [FeedGroup], hasOlder: Bool) {
        let today = calendar.startOfDay(for: now)
        let cutoff = calendar.date(byAdding: .day, value: -(days - 1), to: today) ?? today
        let all = wallet.transactions.sorted { $0.timestamp > $1.timestamp }
        let recent = all.filter { $0.timestamp >= cutoff }
        let byDay = Dictionary(grouping: recent) { calendar.startOfDay(for: $0.timestamp) }
        let groups = byDay.keys.sorted(by: >).map { day -> FeedGroup in
            let items = byDay[day] ?? []
            let spent = items.reduce(Decimal(0)) { $0 + Ledger.spending($1.ledgerItem) }
            let title: String
            if calendar.isDateInToday(day) { title = "Today" }
            else if calendar.isDateInYesterday(day) { title = "Yesterday" }
            else { title = day.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day()) }
            return FeedGroup(id: "day-\(day.timeIntervalSince1970)", title: title,
                             detail: spent > 0 ? wallet.format(-spent) : nil, items: items)
        }
        return (groups, all.count > recent.count)
    }

    static func byCycle(_ wallet: Wallet, now: Date) -> (groups: [FeedGroup], hasOlder: Bool) {
        let byID = Dictionary(uniqueKeysWithValues: wallet.transactions.map { ($0.id.uuidString, $0) })
        let groups = Ledger.cycles(wallet.ledger, now: now).reversed().map { cycle -> FeedGroup in
            let items = cycle.itemIDs.compactMap { byID[$0] }.sorted { $0.timestamp > $1.timestamp }
            return FeedGroup(id: "cycle-\(cycle.id)", title: title(cycle, wallet: wallet), detail: nil, items: items)
        }
        return (groups, false)
    }

    /// "ATM Cycle · Nov 20 – Nov 25 · ₱1,000 lasted 4.5 days"
    static func title(_ cycle: CashCycle, wallet: Wallet) -> String {
        let day: (Date) -> String = { $0.formatted(.dateTime.month(.abbreviated).day()) }
        let days = String(format: "%.1f", cycle.days)
        guard cycle.isATM else { return "Before first ATM" }
        if let end = cycle.end {
            return "ATM Cycle · \(day(cycle.start)) – \(day(end)) · \(wallet.format(cycle.withdrawn)) lasted \(days) days"
        }
        return "Current cycle · since \(day(cycle.start)) · \(wallet.format(cycle.withdrawn)) · \(days) days so far"
    }
}

struct FeedHeader: View {
    let group: FeedGroup
    let collapsed: Bool
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            HStack(spacing: 6) {
                Text(group.title)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 4)
                if let detail = group.detail {
                    Text(detail).monospacedDigit()
                }
                Image(systemName: collapsed ? "chevron.right" : "chevron.down")
                    .font(.caption2.weight(.bold))
            }
            .font(.footnote.weight(.semibold))
            .foregroundStyle(.secondary)
            .textCase(nil)
        }
        .buttonStyle(.plain)
    }
}

struct TransactionRow: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore
    let tx: CashTransaction
    let wallet: Wallet

    var body: some View {
        HStack(spacing: 12) {
            icon
            Button { model.route = .edit(tx.id) } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(tx.title).font(.body).lineLimit(1)
                        Text(tx.timestamp, format: .dateTime.hour().minute())
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 8)
                    Text(wallet.format(tx.amount, signed: true))
                        .font(.body.weight(.semibold))
                        .monospacedDigit()
                        .foregroundStyle(Theme.amountColor(tx.amount))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button(role: .destructive) { model.delete(tx) } label: { Label("Delete", systemImage: "trash") }
        }
    }

    /// 1-tap category icons after the fact (Pro). Free shows the kind's icon, and tapping it explains Pro.
    @ViewBuilder
    private var icon: some View {
        let circle = Image(systemName: tx.symbol)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(tx.amount > 0 ? Theme.emerald : .primary)
            .frame(width: 36, height: 36)
            .background(Circle().fill(tx.amount > 0 ? Theme.emerald.opacity(0.15) : Color.primary.opacity(0.07)))
        if tx.kind == .spend || (tx.kind == .adjust && tx.amount < 0) {
            Menu {
                if pro.isPro {
                    ForEach(CashCategory.allCases) { category in
                        Button { model.setTag(category.rawValue, on: tx) } label: { Label(category.label, systemImage: category.symbol) }
                    }
                    if tx.tag != nil {
                        Divider()
                        Button(role: .destructive) { model.setTag(nil, on: tx) } label: { Label("Remove category", systemImage: "xmark") }
                    }
                } else {
                    Button { model.route = .paywall } label: { Label("Categories are in Pro", systemImage: "lock.fill") }
                }
            } label: {
                circle
            }
            .buttonStyle(.borderless)
        } else {
            circle
        }
    }
}
