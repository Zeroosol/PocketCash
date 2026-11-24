import SwiftData
import SwiftUI

enum KeypadMode: String, Hashable {
    case spend, add
}

/// Screens shown as sheets over Home.
enum Route: Identifiable, Equatable {
    case keypad(KeypadMode)
    case count
    case settings
    case paywall
    case insights
    case edit(UUID)
    case transfer

    var id: String {
        switch self {
        case .keypad(let mode): return "keypad-\(mode.rawValue)"
        case .count: return "count"
        case .settings: return "settings"
        case .paywall: return "paywall"
        case .insights: return "insights"
        case .edit(let id): return "edit-\(id.uuidString)"
        case .transfer: return "transfer"
        }
    }
}

struct UndoState: Equatable {
    let id = UUID()
    let transactionID: UUID
    let text: String
}

/// The app's state: the SwiftData context, the wallets, the selected wallet, sheets and the undo banner.
/// Views never write to SwiftData themselves; everything goes through here and CashStore.
@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var wallets: [Wallet] = []
    /// Bumped after every change, so views that read relationships recompute.
    @Published private(set) var revision = 0
    @Published var route: Route?
    @Published var undo: UndoState?
    @Published var successTick = 0
    @Published var lightTick = 0
    @Published var errorMessage: String?
    @Published var selectedWalletID: UUID? {
        didSet { AppSettings.selectedWalletID = selectedWalletID }
    }

    private(set) var context: ModelContext?
    private var container: ModelContainer?
    private var undoTask: Task<Void, Never>?
    private var writing = false
    private var lastSnapshotSeen: Date = .distantPast
    private var observers: [NSObjectProtocol] = []

    init() {
        selectedWalletID = AppSettings.selectedWalletID
        _ = AppSettings.firstOpen
        openStore()
        // Posted synchronously on the main thread by CashStore (queue: nil).
        observers.append(NotificationCenter.default.addObserver(forName: .pocketCashDidChange, object: nil, queue: nil) { [weak self] _ in
            MainActor.assumeIsolated { self?.storeDidChange() }
        })
        observers.append(NotificationCenter.default.addObserver(forName: .pocketCashOpenRoute, object: nil, queue: nil) { [weak self] _ in
            MainActor.assumeIsolated { self?.consumePendingRoute() }
        })
    }

    var isPro: Bool { AppSettings.isPro }

    /// The wallet on screen. Free always shows the default (first) wallet.
    var wallet: Wallet? {
        if isPro, let id = selectedWalletID, let match = wallets.first(where: { $0.id == id }) { return match }
        return wallets.first
    }

    // MARK: Store

    private func openStore() {
        do {
            let container = try CashStore.makeContainer()
            CashStore.register(container)
            self.container = container
            let ctx = ModelContext(container)
            ctx.autosaveEnabled = false
            context = ctx
            reload()
            CashStore.refreshSnapshot(ctx)
            lastSnapshotSeen = WidgetSnapshot.load()?.updatedAt ?? .now
        } catch {
            errorMessage = "PocketCash couldn't open its data: \(error.localizedDescription)"
        }
    }

    func reload() {
        guard let context else { return }
        wallets = CashStore.wallets(context)
        revision += 1
    }

    /// Another process (a widget button, Siri, a control) wrote: start from a fresh container so nothing cached
    /// hides the new rows.
    private func reopenStore() {
        openStore()
    }

    private func storeDidChange() {
        lastSnapshotSeen = WidgetSnapshot.load()?.updatedAt ?? .now
        if writing {
            reload()
        } else {
            // A Siri intent in this process wrote through its own context.
            if let container { let ctx = ModelContext(container); ctx.autosaveEnabled = false; context = ctx }
            reload()
        }
    }

    func becameActive() {
        if let snapshot = WidgetSnapshot.load(), snapshot.updatedAt > lastSnapshotSeen.addingTimeInterval(0.5) {
            reopenStore()
            offerWidgetUndo(snapshot)
        } else if let context {
            CashStore.refreshSnapshot(context)
            lastSnapshotSeen = WidgetSnapshot.load()?.updatedAt ?? .now
        }
        consumePendingRoute()
        maybeShowSoftPaywall()
    }

    /// A widget tap from the last minute can still be undone from inside the app.
    private func offerWidgetUndo(_ snapshot: WidgetSnapshot) {
        guard let action = snapshot.lastAction, Date().timeIntervalSince(action.at) < 60,
              AppSettings.lastOfferedActionID != action.transactionID.uuidString,
              let wallet = wallets.first(where: { $0.id == action.walletID }) else { return }
        AppSettings.lastOfferedActionID = action.transactionID.uuidString
        showUndo(UndoState(transactionID: action.transactionID, text: undoText(action.value, wallet: wallet) + " from widget"))
    }

    private func consumePendingRoute() {
        guard let pending = AppSettings.pendingRoute else { return }
        AppSettings.pendingRoute = nil
        switch pending {
        case "keypad": route = .keypad(.spend)
        case "count": route = .count
        default: break
        }
    }

    /// Once, on the third day of use, show what Pro adds.
    private func maybeShowSoftPaywall() {
        guard !isPro, !AppSettings.softPaywallShown, route == nil, AppSettings.onboarded,
              Date().timeIntervalSince(AppSettings.firstOpen) > 2 * 86_400 else { return }
        AppSettings.softPaywallShown = true
        route = .paywall
    }

    func proChanged() {
        guard let context else { return }
        CashStore.refreshSnapshot(context)
        reload()
    }

    func settingsChanged() {
        guard let context else { return }
        CashStore.refreshSnapshot(context)
        reload()
    }

    // MARK: Writing

    private func write(_ body: (ModelContext) throws -> Void) {
        guard let context else { return }
        writing = true
        defer { writing = false }
        do {
            try body(context)
        } catch {
            errorMessage = error.localizedDescription
        }
        reload()
    }

    private func undoText(_ amount: Decimal, wallet: Wallet) -> String {
        amount < 0 ? "\(wallet.format(amount)) spent" : "\(wallet.format(amount, signed: true)) added"
    }

    private func showUndo(_ state: UndoState) {
        withAnimation(.snappy) { undo = state }
        undoTask?.cancel()
        undoTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled, let self, self.undo?.id == state.id else { return }
            withAnimation(.snappy) { self.undo = nil }
        }
    }

    private func success() {
        if AppSettings.haptics { successTick += 1 }
    }

    /// A pad button or the keypad. `magnitude` is positive.
    func add(_ magnitude: Decimal, cashIn: Bool, fromATM: Bool = AppSettings.plusButtonsAreATM,
             tag: String? = nil, note: String? = nil, to target: Wallet? = nil) {
        guard magnitude > 0, let wallet = target ?? wallet else { return }
        let amount = cashIn ? magnitude : -magnitude
        let kind: CashKind = cashIn ? (fromATM ? .atm : .in) : .spend
        write { ctx in
            let tx = try CashStore.add(amount, kind: kind, to: wallet, tag: isPro ? tag : nil, note: note, in: ctx)
            showUndo(UndoState(transactionID: tx.id, text: undoText(amount, wallet: wallet)))
        }
        success()
    }

    func undoLast() {
        guard let state = undo else { return }
        undoTask?.cancel()
        withAnimation(.snappy) { undo = nil }
        write { ctx in
            if let tx = CashStore.transaction(state.transactionID, ctx) { try CashStore.delete(tx, in: ctx) }
        }
        if AppSettings.haptics { lightTick += 1 }
    }

    func delete(_ tx: CashTransaction) {
        write { ctx in try CashStore.delete(tx, in: ctx) }
        if AppSettings.haptics { lightTick += 1 }
    }

    func setTag(_ tag: String?, on tx: CashTransaction) {
        guard isPro else { route = .paywall; return }
        tx.tag = tag
        saveEdits()
        if AppSettings.haptics { lightTick += 1 }
    }

    func reconcile(counted: Decimal) {
        guard let wallet else { return }
        write { ctx in try CashStore.reconcile(wallet, counted: counted, in: ctx) }
        success()
    }

    func transfer(_ amount: Decimal, to target: Wallet) {
        guard isPro, let wallet else { return }
        write { ctx in try CashStore.transfer(amount, from: wallet, to: target, in: ctx) }
        success()
    }

    @discardableResult
    func createWallet(name: String, currencyCode: String, symbol: String?, startingCash: Decimal) -> Wallet? {
        var created: Wallet?
        write { ctx in
            created = try CashStore.createWallet(name: name, currencyCode: currencyCode, symbol: symbol,
                                                 startingCash: startingCash, in: ctx)
        }
        if let created, wallets.count > 1 { selectedWalletID = created.id }
        return created
    }

    func deleteWallet(_ wallet: Wallet) {
        guard wallets.count > 1 else { return }
        if selectedWalletID == wallet.id { selectedWalletID = nil }
        write { ctx in try CashStore.deleteWallet(wallet, in: ctx) }
    }

    func moveWallets(from source: IndexSet, to destination: Int) {
        var list = wallets
        list.move(fromOffsets: source, toOffset: destination)
        for (index, wallet) in list.enumerated() { wallet.sortOrder = index }
        saveEdits()
    }

    /// Saves edits made directly on models (notes, amounts, tags, wallet settings).
    func saveEdits() {
        write { ctx in try CashStore.save(ctx) }
    }

    func transaction(_ id: UUID) -> CashTransaction? {
        guard let context else { return nil }
        return CashStore.transaction(id, context)
    }

    // MARK: Export and links

    func exportCSV() -> URL? {
        let rows = wallets.flatMap { wallet in
            wallet.transactions.map {
                CSVExport.Row(date: $0.timestamp, wallet: wallet.name, kind: $0.kind, amount: $0.amount,
                              currency: wallet.currencyCode, category: $0.tag, note: $0.note)
            }
        }
        let day = Date().formatted(.iso8601.year().month().day())
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("PocketCash-\(day).csv")
        do {
            try CSVExport.make(rows).write(to: url, atomically: true, encoding: .utf8)
            return url
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    /// pocketcash://keypad?mode=spend|add, pocketcash://count, pocketcash://wallet?id=…, pocketcash://pro
    func handle(_ url: URL) {
        guard url.scheme == "pocketcash" else { return }
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let value = { (name: String) in items.first { $0.name == name }?.value }
        switch url.host {
        case "keypad": route = .keypad(value("mode") == "add" ? .add : .spend)
        case "count": route = .count
        case "pro": route = .paywall
        case "wallet":
            if let id = value("id").flatMap(UUID.init(uuidString:)), isPro { selectedWalletID = id }
        default: break
        }
    }
}
