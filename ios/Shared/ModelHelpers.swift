import Foundation
import SwiftData

extension CashTransaction {
    var kind: CashKind {
        get { CashKind(rawValue: kindRaw) ?? .spend }
        set {
            kindRaw = newValue.rawValue
            isATMWithdrawal = newValue == .atm
        }
    }

    var category: CashCategory? { tag.flatMap(CashCategory.init(rawValue:)) }

    var ledgerItem: LedgerItem {
        LedgerItem(id: id.uuidString, amount: amount, kind: kind, timestamp: timestamp, tag: tag)
    }

    /// What the feed row says: the note, else the category, else the kind.
    var title: String {
        if let note, !note.isEmpty { return note }
        if let category { return category.label }
        if kind == .adjust {
            return amount < 0 ? String(localized: "Unrecorded spending") : String(localized: "Found cash")
        }
        return kind.label
    }

    var symbol: String { category?.symbol ?? kind.symbol }
}

extension Wallet {
    var ledger: [LedgerItem] { transactions.map(\.ledgerItem) }
    var balance: Decimal { Ledger.balance(ledger) }
    var defaults: CurrencyPresets { DefaultPresets.forCurrency(currencyCode) }

    /// Free builds always use the currency's default buttons; Pro uses the wallet's own.
    func addButtons(isPro: Bool) -> [Decimal] {
        let own = isPro ? addPresets.filter { $0 > 0 } : []
        return own.isEmpty ? defaults.add : own
    }

    func spendButtons(isPro: Bool) -> [Decimal] {
        let own = isPro ? spendPresets.filter { $0 > 0 } : []
        return own.isEmpty ? defaults.spend : own
    }

    func format(_ amount: Decimal, signed: Bool = false) -> String {
        Money.format(amount, code: currencyCode, symbol: symbol, signed: signed)
    }

    var allowsFractions: Bool { Money.fractionDigits(for: currencyCode) > 0 }

    /// The daily budget is Pro; a Free wallet keeps a stored budget but doesn't show it (docs/spec.md section 5).
    func activeBudget(isPro: Bool) -> Decimal? {
        guard isPro, let dailyBudget, dailyBudget > 0 else { return nil }
        return dailyBudget
    }

    // MARK: Envelopes (docs/spec.md section 10)

    var hasTarget: Bool { (target ?? 0) > 0 }
    var progress: Decimal? { Envelope.progress(balance: balance, target: target) }
    var shortBy: Decimal { Envelope.shortBy(balance: balance, target: target) }

    // MARK: Backup (docs/spec.md section 11)

    var backup: Backup.Wallet {
        Backup.Wallet(
            id: id, name: name, currencyCode: currencyCode, symbol: symbol, addPresets: addPresets,
            spendPresets: spendPresets, dailyBudget: dailyBudget, lowCashThreshold: lowCashThreshold, target: target,
            sortOrder: sortOrder, createdAt: createdAt,
            transactions: transactions.sorted { $0.timestamp < $1.timestamp }.map {
                Backup.Transaction(id: $0.id, amount: $0.amount, kind: $0.kind, timestamp: $0.timestamp, tag: $0.tag,
                                   note: $0.note, transferID: $0.transferID)
            })
    }

    static func make(name: String, currencyCode: String, symbol: String? = nil, sortOrder: Int) -> Wallet {
        let p = DefaultPresets.forCurrency(currencyCode)
        return Wallet(name: name, currencyCode: currencyCode, symbol: symbol,
                      addPresets: p.add, spendPresets: p.spend, sortOrder: sortOrder)
    }
}

enum CurrencyDefaults {
    /// The phone's currency, or USD.
    static var localCode: String { Locale.current.currency?.identifier ?? "USD" }

    /// Codes offered in pickers: the ones with tuned presets first, then every other ISO code.
    static var pickerCodes: [String] {
        let tuned = DefaultPresets.table.keys.sorted()
        let rest = Locale.commonISOCurrencyCodes.filter { !DefaultPresets.table.keys.contains($0) }.sorted()
        var codes = tuned + rest
        if !codes.contains(localCode) { codes.insert(localCode, at: 0) }
        return codes
    }

    static func name(_ code: String) -> String {
        Locale.current.localizedString(forCurrencyCode: code) ?? code
    }
}
