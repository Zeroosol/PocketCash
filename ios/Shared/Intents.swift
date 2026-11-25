import AppIntents
import Foundation
import SwiftData

// App Intents shared by the app (Siri, Shortcuts, in-app) and the widget extension (widget buttons, controls).
// Widget buttons run AdjustCashIntent inside the widget extension's process, against the same store file.

struct WalletEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Wallet"
    static let defaultQuery = WalletQuery()

    var id: UUID
    var name: String

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

/// Reads wallets from the widget snapshot, so the widget's wallet picker never opens the database.
struct WalletQuery: EntityQuery {
    private func all() -> [WalletEntity] {
        (WidgetSnapshot.load()?.wallets ?? []).map { WalletEntity(id: $0.id, name: $0.name) }
    }

    func entities(for identifiers: [UUID]) async throws -> [WalletEntity] {
        all().filter { identifiers.contains($0.id) }
    }

    func suggestedEntities() async throws -> [WalletEntity] { all() }

    func defaultResult() async -> WalletEntity? { all().first }
}

enum CategoryOption: String, AppEnum {
    case food, transit, parking, groceries, errand, tip, bills, fun, other

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Category"
    static let caseDisplayRepresentations: [CategoryOption: DisplayRepresentation] = [
        .food: DisplayRepresentation(title: "Food & drinks"),
        .transit: DisplayRepresentation(title: "Transit"),
        .parking: DisplayRepresentation(title: "Parking"),
        .groceries: DisplayRepresentation(title: "Groceries"),
        .errand: DisplayRepresentation(title: "Errands"),
        .tip: DisplayRepresentation(title: "Tips"),
        .bills: DisplayRepresentation(title: "Bills"),
        .fun: DisplayRepresentation(title: "Fun"),
        .other: DisplayRepresentation(title: "Other"),
    ]
}

enum PocketCashIntentError: Error, CustomLocalizedStringResourceConvertible {
    case noWallet
    case badAmount
    case needsPro

    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .noWallet: return LocalizedStringResource("Open PocketCash once to set up your wallet.")
        case .badAmount: return LocalizedStringResource("That amount doesn't look right.")
        case .needsPro: return LocalizedStringResource("Quick spend is part of PocketCash Pro. Open PocketCash to unlock it.")
        }
    }
}

@MainActor
private enum IntentWriter {
    struct Outcome {
        var amountText: String
        var balanceText: String
        var walletName: String
    }

    /// Free builds always write to the default wallet.
    static func target(_ id: UUID?, _ ctx: ModelContext) throws -> Wallet {
        let list = CashStore.wallets(ctx)
        let chosen = AppSettings.isPro ? list.first { $0.id == id } : nil
        guard let wallet = chosen ?? list.first else { throw PocketCashIntentError.noWallet }
        return wallet
    }

    static func add(_ amount: Decimal, kind: CashKind, walletID: UUID?, tag: String? = nil, note: String? = nil,
                    recordForUndo: Bool) throws -> Outcome {
        try CashStore.withFreshContext { ctx in
            let wallet = try target(walletID, ctx)
            let before = wallet.balance
            try CashStore.add(amount, kind: kind, to: wallet, tag: AppSettings.isPro ? tag : nil, note: note,
                              recordForUndo: recordForUndo, in: ctx)
            return Outcome(amountText: wallet.format(amount < 0 ? -amount : amount),
                           balanceText: wallet.format(before + amount), walletName: wallet.name)
        }
    }

    static func decimal(_ value: Double) throws -> Decimal {
        guard value.isFinite, value > 0, let d = Money.decimal(String(value)) else { throw PocketCashIntentError.badAmount }
        return Money.rounded(d, scale: 2)
    }

    /// Asks the app to show the paywall the next time it comes to the front.
    static func requestPaywall() {
        AppSettings.pendingRoute = "pro"
        NotificationCenter.default.post(name: .pocketCashOpenRoute, object: nil)
    }
}

// MARK: Widget buttons

/// A widget's 1-tap button. Not shown in Shortcuts.
struct AdjustCashIntent: AppIntent {
    static let title: LocalizedStringResource = "Adjust Cash"
    static let isDiscoverable = false

    @Parameter(title: "Wallet") var walletID: String
    /// Signed decimal string: "-50" spends, "1000" adds.
    @Parameter(title: "Amount") var amount: String

    init() {}

    init(walletID: UUID, amount: Decimal) {
        self.walletID = walletID.uuidString
        self.amount = Money.string(amount)
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        guard let value = Money.decimal(amount), value != 0 else { throw PocketCashIntentError.badAmount }
        let kind: CashKind = value > 0 ? (AppSettings.plusButtonsAreATM ? .atm : .in) : .spend
        _ = try IntentWriter.add(value, kind: kind, walletID: UUID(uuidString: walletID), recordForUndo: true)
        return .result()
    }
}

/// The Undo chip a widget shows for a few seconds after a tap.
struct UndoCashIntent: AppIntent {
    static let title: LocalizedStringResource = "Undo Cash Tap"
    static let isDiscoverable = false

    @Parameter(title: "Transaction") var transactionID: String

    init() {}

    init(transactionID: UUID) {
        self.transactionID = transactionID.uuidString
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        try CashStore.withFreshContext { ctx in
            if let id = UUID(uuidString: transactionID), let tx = CashStore.transaction(id, ctx),
               Date().timeIntervalSince(tx.timestamp) < PocketCash.undoWindowSeconds {
                try CashStore.delete(tx, in: ctx)
            } else {
                CashStore.refreshSnapshot(ctx, lastAction: .clear)
            }
        }
        return .result()
    }
}

// MARK: Controls, Lock Screen and Siri

/// Subtracts the quick-spend amount (Control Center, Lock Screen, Siri). Quick spend is Pro (docs/spec.md section
/// 5): without Pro it spends nothing and sends the person to the paywall instead.
struct QuickSpendIntent: AppIntent {
    static let title: LocalizedStringResource = "Quick Spend"
    static let description = IntentDescription("Subtracts your quick-spend amount, like your daily commute fare, from your cash.")

    /// Runs in the background, and can bring the app forward (iOS 26) to show the paywall when Pro is missing.
    @available(iOS 26.0, *)
    static var supportedModes: IntentModes { [.background, .foreground(.dynamic)] }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        guard AppSettings.isPro else {
            IntentWriter.requestPaywall()
            if #available(iOS 26.0, *) {
                try await continueInForeground(nil, alwaysConfirm: false)
                return .result(dialog: IntentDialog(LocalizedStringResource("Quick spend is part of PocketCash Pro.")))
            }
            throw PocketCashIntentError.needsPro
        }
        let outcome: IntentWriter.Outcome = try CashStore.withFreshContext { ctx in
            let list = CashStore.wallets(ctx)
            guard let quick = CashStore.quickSpend(list, isPro: AppSettings.isPro) else { throw PocketCashIntentError.noWallet }
            return try IntentWriter.add(-quick.value, kind: .spend, walletID: quick.walletID, recordForUndo: true)
        }
        return .result(dialog: "Spent \(outcome.amountText). \(outcome.balanceText) left in \(outcome.walletName).")
    }
}

struct SpendCashIntent: AppIntent {
    static let title: LocalizedStringResource = "Spend Cash"
    static let description = IntentDescription("Subtracts cash you spent from a PocketCash wallet.")

    @Parameter(title: "Amount") var amount: Double
    @Parameter(title: "Wallet") var wallet: WalletEntity?
    @Parameter(title: "Category") var category: CategoryOption?
    @Parameter(title: "Note") var note: String?

    static var parameterSummary: some ParameterSummary {
        Summary("Spend \(\.$amount) from \(\.$wallet)") {
            \.$category
            \.$note
        }
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let value = try IntentWriter.decimal(amount)
        let outcome = try IntentWriter.add(-value, kind: .spend, walletID: wallet?.id, tag: category?.rawValue,
                                           note: note, recordForUndo: false)
        return .result(dialog: "Spent \(outcome.amountText). \(outcome.balanceText) left in \(outcome.walletName).")
    }
}

struct AddCashIntent: AppIntent {
    static let title: LocalizedStringResource = "Add Cash"
    static let description = IntentDescription("Adds cash to a PocketCash wallet, like an ATM withdrawal.")

    @Parameter(title: "Amount") var amount: Double
    @Parameter(title: "Wallet") var wallet: WalletEntity?
    @Parameter(title: "From ATM", default: true) var fromATM: Bool

    static var parameterSummary: some ParameterSummary {
        Summary("Add \(\.$amount) to \(\.$wallet)") {
            \.$fromATM
        }
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let value = try IntentWriter.decimal(amount)
        let outcome = try IntentWriter.add(value, kind: fromATM ? .atm : .in, walletID: wallet?.id, recordForUndo: false)
        return .result(dialog: "Added \(outcome.amountText). You have \(outcome.balanceText) in \(outcome.walletName).")
    }
}

struct CashBalanceIntent: AppIntent {
    static let title: LocalizedStringResource = "Cash Balance"
    static let description = IntentDescription("Tells you how much cash is in your wallet.")

    @Parameter(title: "Wallet") var wallet: WalletEntity?

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
        guard let snapshot = WidgetSnapshot.load(), let info = snapshot.wallet(wallet?.id) else {
            throw PocketCashIntentError.noWallet
        }
        let text = info.format(info.balanceValue)
        return .result(value: text, dialog: "You have \(text) in \(info.name).")
    }
}

/// Opens the app on its keypad (the Control Center "Cash Keypad" control, Pro). Without Pro it opens the paywall.
struct OpenKeypadIntent: AppIntent {
    static let title: LocalizedStringResource = "Open Cash Keypad"
    static let description = IntentDescription("Opens PocketCash on its keypad to log a custom amount.")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        AppSettings.pendingRoute = AppSettings.isPro ? "keypad" : "pro"
        NotificationCenter.default.post(name: .pocketCashOpenRoute, object: nil)
        return .result()
    }
}
