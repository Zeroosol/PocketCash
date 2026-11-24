import Foundation

/// One transaction as the money rules see it. The SwiftData model maps to this, and so do the test vectors.
struct LedgerItem: Sendable, Equatable, Identifiable {
    var id: String
    var amount: Decimal
    var kind: CashKind
    var timestamp: Date
    var tag: String?

    init(id: String, amount: Decimal, kind: CashKind, timestamp: Date, tag: String? = nil) {
        self.id = id
        self.amount = amount
        self.kind = kind
        self.timestamp = timestamp
        self.tag = tag
    }
}

/// One ATM cycle (docs/spec.md section 4).
struct CashCycle: Sendable, Equatable, Identifiable {
    var id: String
    /// False for the "Before first ATM" cycle.
    var isATM: Bool
    var start: Date
    /// Nil while the cycle is still running.
    var end: Date?
    var withdrawn: Decimal
    var spent: Decimal
    var inflow: Decimal
    var days: Double
    var avgDaily: Double
    var topAmount: Decimal?
    var topAmountCount: Int
    var topAmountTotal: Decimal
    var topTag: String?
    /// Transactions in this cycle, oldest first.
    var itemIDs: [String]

    var isOpen: Bool { end == nil }
}

/// The money rules shared by the app, the widgets and the tests. Pinned by spec/vectors/cycles.json.
enum Ledger {
    static let secondsPerDay: TimeInterval = 86_400

    static func spending(_ item: LedgerItem) -> Decimal {
        switch item.kind {
        case .spend: return -item.amount
        case .adjust: return item.amount < 0 ? -item.amount : 0
        case .in, .atm, .transfer: return 0
        }
    }

    static func inflow(_ item: LedgerItem) -> Decimal {
        switch item.kind {
        case .in, .atm: return item.amount
        case .adjust: return item.amount > 0 ? item.amount : 0
        case .spend, .transfer: return 0
        }
    }

    static func balance(_ items: [LedgerItem]) -> Decimal {
        items.reduce(Decimal(0)) { $0 + $1.amount }
    }

    static func sorted(_ items: [LedgerItem]) -> [LedgerItem] {
        items.sorted { ($0.timestamp, $0.id) < ($1.timestamp, $1.id) }
    }

    /// Spending in [from, to).
    static func spent(_ items: [LedgerItem], from: Date, to: Date) -> Decimal {
        items.reduce(Decimal(0)) { sum, item in
            item.timestamp >= from && item.timestamp < to ? sum + spending(item) : sum
        }
    }

    static func todaySpent(_ items: [LedgerItem], now: Date, calendar: Calendar = .current) -> Decimal {
        let start = calendar.startOfDay(for: now)
        let end = calendar.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(secondsPerDay)
        return spent(items, from: start, to: end)
    }

    /// ATM cycles, oldest first.
    static func cycles(_ items: [LedgerItem], now: Date) -> [CashCycle] {
        let all = sorted(items)
        guard !all.isEmpty else { return [] }

        // Split at every ATM withdrawal. A group without an ATM is the starting cycle.
        var groups: [(atm: LedgerItem?, items: [LedgerItem])] = []
        for item in all {
            if item.kind == .atm {
                groups.append((item, [item]))
            } else if groups.isEmpty {
                groups.append((nil, [item]))
            } else {
                groups[groups.count - 1].items.append(item)
            }
        }

        return groups.enumerated().map { index, group in
            let start = group.atm?.timestamp ?? group.items[0].timestamp
            let end = index + 1 < groups.count ? groups[index + 1].items[0].timestamp : nil
            let days = max(0, (end ?? now).timeIntervalSince(start) / secondsPerDay)
            let spent = group.items.reduce(Decimal(0)) { $0 + spending($1) }
            let inflowTotal = group.items.reduce(Decimal(0)) { sum, item in
                item.id == group.atm?.id ? sum : sum + inflow(item)
            }

            // Which denomination drains the wallet fastest: spends grouped by amount.
            var byAmount: [Decimal: (count: Int, total: Decimal)] = [:]
            for item in group.items where item.kind == .spend {
                let magnitude = -item.amount
                let current = byAmount[magnitude] ?? (0, 0)
                byAmount[magnitude] = (current.count + 1, current.total + magnitude)
            }
            let top = byAmount.max { a, b in
                if a.value.total != b.value.total { return a.value.total < b.value.total }
                if a.value.count != b.value.count { return a.value.count < b.value.count }
                return a.key < b.key
            }

            var byTag: [String: Decimal] = [:]
            for item in group.items {
                let s = spending(item)
                if let tag = item.tag, s > 0 { byTag[tag, default: 0] += s }
            }
            let topTag = byTag.max { a, b in
                a.value != b.value ? a.value < b.value : a.key > b.key
            }?.key

            return CashCycle(
                id: group.items[0].id,
                isATM: group.atm != nil,
                start: start,
                end: end,
                withdrawn: group.atm?.amount ?? 0,
                spent: spent,
                inflow: inflowTotal,
                days: days,
                avgDaily: Money.double(spent) / max(days, 1),
                topAmount: top?.key,
                topAmountCount: top?.value.count ?? 0,
                topAmountTotal: top?.value.total ?? 0,
                topTag: topTag,
                itemIDs: group.items.map(\.id))
        }
    }

    /// Average daily spending over the last 14 days (fewer for a young wallet). Nil without transactions.
    static func burnRatePerDay(_ items: [LedgerItem], now: Date) -> Double? {
        guard let first = items.map(\.timestamp).min() else { return nil }
        let windowStart = now.addingTimeInterval(-14 * secondsPerDay)
        let spent = items.reduce(Decimal(0)) { sum, item in
            item.timestamp > windowStart && item.timestamp <= now ? sum + spending(item) : sum
        }
        let age = now.timeIntervalSince(first) / secondsPerDay
        return Money.double(spent) / min(max(age, 1), 14)
    }

    /// Days the cash lasts at the current burn rate. Nil unless both are positive.
    static func runwayDays(balance: Decimal, burnRate: Double?) -> Double? {
        guard let burnRate, burnRate > 0, balance > 0 else { return nil }
        return Money.double(balance) / burnRate
    }

    /// Spending per tag since a date, largest first. Untagged spending is reported with a nil tag.
    static func spendingByTag(_ items: [LedgerItem], since: Date) -> [(tag: String?, total: Decimal)] {
        var totals: [String: Decimal] = [:]
        var untagged = Decimal(0)
        for item in items where item.timestamp >= since {
            let s = spending(item)
            guard s > 0 else { continue }
            if let tag = item.tag { totals[tag, default: 0] += s } else { untagged += s }
        }
        var rows: [(tag: String?, total: Decimal)] = totals.map { (tag: Optional($0.key), total: $0.value) }
        if untagged > 0 { rows.append((tag: nil, total: untagged)) }
        return rows.sorted { $0.total > $1.total }
    }
}
