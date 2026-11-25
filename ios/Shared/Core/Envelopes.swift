import Foundation

/// Envelopes and savings goals (docs/spec.md section 10): an envelope is any wallet with a target.
enum Envelope {
    /// `clamp(balance / target, 0, 1)`, or nil when the wallet has no positive target.
    static func progress(balance: Decimal, target: Decimal?) -> Decimal? {
        guard let target, target > 0 else { return nil }
        return min(max(balance / target, 0), 1)
    }

    /// `max(target − balance, 0)`; 0 without a target.
    static func shortBy(balance: Decimal, target: Decimal?) -> Decimal {
        guard let target, target > 0 else { return 0 }
        return max(target - balance, 0)
    }

    static func isReached(balance: Decimal, target: Decimal?) -> Bool {
        guard let target, target > 0 else { return false }
        return balance >= target
    }

    /// The fill sheet's starting amounts: walk the envelopes in sort order and give each
    /// `min(short by, cash still left in the source)`. Wallets without a target get 0.
    static func suggestedFill(sourceBalance: Decimal, envelopes: [(balance: Decimal, target: Decimal?)]) -> [Decimal] {
        var left = max(sourceBalance, 0)
        return envelopes.map { envelope in
            let give = min(shortBy(balance: envelope.balance, target: envelope.target), left)
            left -= give
            return give
        }
    }
}

/// The keypad's change calculator (docs/spec.md section 7.2): "Paid ₱500, got ₱130 back" is one ₱370 spend.
enum ChangeMath {
    /// Quick "Paid" chips: the currency's largest bills that are at least the largest spend button, at most `limit`,
    /// largest first.
    static func paidChips(denominations: [Decimal], spendPresets: [Decimal], limit: Int = 4) -> [Decimal] {
        let floor = spendPresets.max() ?? 0
        return Array(Set(denominations.filter { $0 >= floor && $0 > 0 }).sorted(by: >).prefix(limit))
    }

    /// The spend to save: `paid − change`, or nil while that isn't a positive amount (change ≥ paid).
    static func spend(paid: Decimal, change: Decimal) -> Decimal? {
        guard paid > 0, change >= 0, change < paid else { return nil }
        return paid - change
    }
}
