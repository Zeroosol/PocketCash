import Foundation
import SwiftData
import UserNotifications
import WidgetKit

extension Notification.Name {
    /// Posted after every write. `object` is the ModelContainer that wrote.
    static let pocketCashDidChange = Notification.Name("PocketCashDidChange")
    /// An intent asked the running app to open a screen (see AppSettings.pendingRoute).
    static let pocketCashOpenRoute = Notification.Name("PocketCashOpenRoute")
}

/// The one write path for the app, the widget buttons, the controls and Siri (docs/spec.md section 8).
/// Every write: insert, save synchronously, then rewrite the widget snapshot, and only then reload timelines.
@MainActor
enum CashStore {
    static let storeFileName = "PocketCash.store"
    static var storeURL: URL { PocketCash.dataDirectory.appendingPathComponent(storeFileName) }

    /// Always opened at the explicit App Group URL, never by automatic detection, so the app and the widget
    /// extension share one file and an App Group change can't silently relocate the database.
    static func makeContainer() throws -> ModelContainer {
        let config = ModelConfiguration(url: storeURL, cloudKitDatabase: .none)
        return try ModelContainer(for: Wallet.self, CashTransaction.self, configurations: config)
    }

    private static var processContainer: ModelContainer?

    /// One container per process. The app registers its own at launch, so Siri intents running inside the app
    /// write through the same container instead of opening a second one.
    static func sharedContainer() throws -> ModelContainer {
        if let processContainer { return processContainer }
        let container = try makeContainer()
        processContainer = container
        return container
    }

    static func register(_ container: ModelContainer) { processContainer = container }

    /// A fresh context for intents, so a long-lived widget process never reads stale cached rows.
    static func withFreshContext<T>(_ body: (ModelContext) throws -> T) throws -> T {
        let ctx = ModelContext(try sharedContainer())
        ctx.autosaveEnabled = false
        return try body(ctx)
    }

    // MARK: Reading

    static func wallets(_ ctx: ModelContext) -> [Wallet] {
        let all = (try? ctx.fetch(FetchDescriptor<Wallet>())) ?? []
        return all.sorted { ($0.sortOrder, $0.createdAt) < ($1.sortOrder, $1.createdAt) }
    }

    static func wallet(_ id: UUID?, _ ctx: ModelContext) -> Wallet? {
        guard let id else { return nil }
        return wallets(ctx).first { $0.id == id }
    }

    /// Recent transactions first. Undo and transfers only ever look at recent ones.
    static func recentTransactions(_ ctx: ModelContext, limit: Int = 500) -> [CashTransaction] {
        var d = FetchDescriptor<CashTransaction>(sortBy: [SortDescriptor(\.timestamp, order: .reverse)])
        d.fetchLimit = limit
        return (try? ctx.fetch(d)) ?? []
    }

    static func transaction(_ id: UUID, _ ctx: ModelContext) -> CashTransaction? {
        recentTransactions(ctx).first { $0.id == id }
    }

    // MARK: Writing

    enum LastActionChange {
        case keep
        case set(WidgetSnapshot.LastAction)
        case clear
    }

    /// Adds a transaction. `recordForUndo` marks it as the widgets' undoable last action.
    @discardableResult
    static func add(_ amount: Decimal, kind: CashKind, to wallet: Wallet, tag: String? = nil, note: String? = nil,
                    at date: Date = .now, recordForUndo: Bool = false, in ctx: ModelContext) throws -> CashTransaction {
        let before = wallet.balance
        let tx = CashTransaction(amount: amount, kindRaw: kind.rawValue, timestamp: date, tag: tag,
                                 note: note?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty)
        ctx.insert(tx)
        tx.wallet = wallet
        try ctx.save()
        LowCash.notifyIfCrossed(wallet: wallet, before: before, after: before + amount)
        let action = WidgetSnapshot.LastAction(walletID: wallet.id, transactionID: tx.id,
                                               amount: Money.string(amount), at: date)
        didChange(ctx, lastAction: recordForUndo ? .set(action) : .keep)
        return tx
    }

    /// Deletes a transaction (both halves of a transfer).
    static func delete(_ tx: CashTransaction, in ctx: ModelContext) throws {
        var doomed = [tx]
        if let pair = tx.transferID {
            doomed += recentTransactions(ctx, limit: 5_000).filter { $0.transferID == pair && $0.id != tx.id }
        }
        let ids = Set(doomed.map(\.id))
        for item in doomed { ctx.delete(item) }
        try ctx.save()
        let wasLastAction = WidgetSnapshot.load()?.lastAction.map { ids.contains($0.transactionID) } ?? false
        didChange(ctx, lastAction: wasLastAction ? .clear : .keep)
    }

    /// "Count my cash": saves the difference between the counted cash and the app's balance as an adjustment.
    @discardableResult
    static func reconcile(_ wallet: Wallet, counted: Decimal, in ctx: ModelContext) throws -> CashTransaction? {
        let difference = counted - wallet.balance
        guard difference != 0 else { return nil }
        return try add(difference, kind: .adjust, to: wallet, in: ctx)
    }

    /// Moves cash between two wallets of the same currency (Pro).
    static func transfer(_ amount: Decimal, from source: Wallet, to target: Wallet, in ctx: ModelContext) throws {
        guard amount > 0, source.id != target.id else { return }
        let pair = UUID(), now = Date()
        let out = CashTransaction(amount: -amount, kindRaw: CashKind.transfer.rawValue, timestamp: now,
                                  note: "To \(target.name)", transferID: pair)
        let into = CashTransaction(amount: amount, kindRaw: CashKind.transfer.rawValue, timestamp: now,
                                   note: "From \(source.name)", transferID: pair)
        ctx.insert(out)
        ctx.insert(into)
        out.wallet = source
        into.wallet = target
        try ctx.save()
        didChange(ctx, lastAction: .keep)
    }

    @discardableResult
    static func createWallet(name: String, currencyCode: String, symbol: String?, startingCash: Decimal,
                             in ctx: ModelContext) throws -> Wallet {
        let order = (wallets(ctx).map(\.sortOrder).max() ?? -1) + 1
        let wallet = Wallet.make(name: name, currencyCode: currencyCode, symbol: symbol, sortOrder: order)
        ctx.insert(wallet)
        if startingCash != 0 {
            let tx = CashTransaction(amount: startingCash, kindRaw: CashKind.adjust.rawValue, note: "Starting cash")
            ctx.insert(tx)
            tx.wallet = wallet
        }
        try ctx.save()
        didChange(ctx, lastAction: .keep)
        return wallet
    }

    static func deleteWallet(_ wallet: Wallet, in ctx: ModelContext) throws {
        ctx.delete(wallet)
        try ctx.save()
        didChange(ctx, lastAction: .clear)
    }

    /// Saves edits made directly on models (notes, tags, wallet settings, presets).
    static func save(_ ctx: ModelContext) throws {
        try ctx.save()
        didChange(ctx, lastAction: .keep)
    }

    // MARK: Snapshot

    static func didChange(_ ctx: ModelContext, lastAction: LastActionChange) {
        refreshSnapshot(ctx, lastAction: lastAction)
        NotificationCenter.default.post(name: .pocketCashDidChange, object: ctx.container)
    }

    /// Rewrites widget_snapshot.json from the saved data, then reloads widgets and controls.
    static func refreshSnapshot(_ ctx: ModelContext, lastAction: LastActionChange = .keep) {
        makeSnapshot(ctx, previous: WidgetSnapshot.load(), lastAction: lastAction).save()
        WidgetCenter.shared.reloadAllTimelines()
        ControlCenter.shared.reloadAllControls()
    }

    static func makeSnapshot(_ ctx: ModelContext, previous: WidgetSnapshot?, lastAction change: LastActionChange,
                             now: Date = .now) -> WidgetSnapshot {
        let isPro = AppSettings.isPro
        let list = wallets(ctx)
        let calendar = Calendar.current
        let dayStart = calendar.startOfDay(for: now)
        let infos = list.map { wallet -> WidgetSnapshot.WalletInfo in
            let items = wallet.ledger
            return WidgetSnapshot.WalletInfo(
                id: wallet.id, name: wallet.name, currencyCode: wallet.currencyCode, symbol: wallet.symbol,
                balance: Money.string(Ledger.balance(items)),
                todaySpent: Money.string(Ledger.todaySpent(items, now: now, calendar: calendar)),
                dayStart: dayStart, dailyBudget: wallet.dailyBudget.map(Money.string),
                addPresets: wallet.addButtons(isPro: isPro).map(Money.string),
                spendPresets: wallet.spendButtons(isPro: isPro).map(Money.string))
        }
        let lastAction: WidgetSnapshot.LastAction?
        switch change {
        case .keep: lastAction = previous?.lastAction
        case .set(let action): lastAction = action
        case .clear: lastAction = nil
        }
        return WidgetSnapshot(updatedAt: now, isPro: isPro, privacyMode: AppSettings.privacyMode,
                              plusButtonsAreATM: AppSettings.plusButtonsAreATM, defaultWalletID: list.first?.id,
                              quickSpend: quickSpend(list, isPro: isPro), lastAction: lastAction, wallets: infos)
    }

    /// The Control Center / Lock Screen quick-spend action: the chosen amount, else the default wallet's smallest
    /// spend button (the "daily commute fare").
    static func quickSpend(_ list: [Wallet], isPro: Bool) -> WidgetSnapshot.QuickSpend? {
        let chosen = isPro ? list.first { $0.id == AppSettings.quickSpendWalletID } : nil
        guard let wallet = chosen ?? list.first else { return nil }
        let amount = AppSettings.quickSpendAmount ?? wallet.spendButtons(isPro: isPro).min() ?? 0
        guard amount > 0 else { return nil }
        return WidgetSnapshot.QuickSpend(walletID: wallet.id, amount: Money.string(amount))
    }
}

/// The low-cash reminder (docs/spec.md section 5, Free 9).
enum LowCash {
    static func notifyIfCrossed(wallet: Wallet, before: Decimal, after: Decimal) {
        guard AppSettings.lowCashAlerts, let threshold = wallet.lowCashThreshold, threshold > 0,
              before >= threshold, after < threshold else { return }
        let content = UNMutableNotificationContent()
        content.title = "Running low on cash"
        content.body = "Only \(wallet.format(after)) left in \(wallet.name)."
        content.sound = .default
        let request = UNNotificationRequest(identifier: "lowcash-\(wallet.id.uuidString)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    static func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
