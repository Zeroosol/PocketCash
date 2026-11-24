import Foundation

/// The small JSON summary every write leaves in the App Group (docs/spec.md section 8), so widgets, controls and
/// the wallet picker never open the SQLite store just to draw. Amounts are decimal strings to stay exact.
struct WidgetSnapshot: Codable, Equatable {
    struct WalletInfo: Codable, Equatable, Identifiable {
        var id: UUID
        var name: String
        var currencyCode: String
        var symbol: String?
        var balance: String
        var todaySpent: String
        /// The day `todaySpent` belongs to.
        var dayStart: Date
        var dailyBudget: String?
        var addPresets: [String]
        var spendPresets: [String]

        var balanceValue: Decimal { Money.decimal(balance) ?? 0 }
        var budgetValue: Decimal? { dailyBudget.flatMap(Money.decimal) }
        var addValues: [Decimal] { addPresets.compactMap(Money.decimal) }
        var spendValues: [Decimal] { spendPresets.compactMap(Money.decimal) }

        /// Today's spending, or 0 once the day it was computed for is over.
        func spentToday(at date: Date, calendar: Calendar = .current) -> Decimal {
            calendar.isDate(dayStart, inSameDayAs: date) ? (Money.decimal(todaySpent) ?? 0) : 0
        }

        func format(_ amount: Decimal, signed: Bool = false) -> String {
            Money.format(amount, code: currencyCode, symbol: symbol, signed: signed)
        }
    }

    struct QuickSpend: Codable, Equatable {
        var walletID: UUID
        var amount: String
        var value: Decimal { Money.decimal(amount) ?? 0 }
    }

    struct LastAction: Codable, Equatable {
        var walletID: UUID
        var transactionID: UUID
        var amount: String
        var at: Date
        var value: Decimal { Money.decimal(amount) ?? 0 }
    }

    var version = 1
    var updatedAt: Date
    var isPro: Bool
    var privacyMode: Bool
    var plusButtonsAreATM: Bool
    var defaultWalletID: UUID?
    var quickSpend: QuickSpend?
    var lastAction: LastAction?
    var wallets: [WalletInfo]

    /// The wallet a widget shows: the chosen one with Pro, otherwise the default wallet.
    func wallet(_ id: UUID?) -> WalletInfo? {
        if isPro, let id, let match = wallets.first(where: { $0.id == id }) { return match }
        return wallets.first(where: { $0.id == defaultWalletID }) ?? wallets.first
    }

    // MARK: Storage

    static var fileURL: URL { PocketCash.dataDirectory.appendingPathComponent("widget_snapshot.json") }

    static func load() -> WidgetSnapshot? {
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(WidgetSnapshot.self, from: data)
    }

    func save() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(self) else { return }
        try? data.write(to: Self.fileURL, options: .atomic)
    }

    // MARK: Previews

    static let sample: WidgetSnapshot = {
        let id = UUID()
        return WidgetSnapshot(
            updatedAt: .now, isPro: false, privacyMode: false, plusButtonsAreATM: true, defaultWalletID: id,
            quickSpend: QuickSpend(walletID: id, amount: "20"), lastAction: nil,
            wallets: [WalletInfo(id: id, name: "Main Wallet", currencyCode: "PHP", symbol: nil, balance: "680",
                                 todaySpent: "170", dayStart: Calendar.current.startOfDay(for: .now), dailyBudget: nil,
                                 addPresets: ["1000", "500"], spendPresets: ["100", "50", "20", "10"])])
    }()
}
