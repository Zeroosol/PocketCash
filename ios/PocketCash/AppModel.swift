import SwiftData
import SwiftUI

enum KeypadMode: String, Hashable {
    case spend, add
}

/// The four places in the bottom bar (docs/spec.md section 7).
enum Destination: String, CaseIterable, Identifiable, Hashable {
    case home, insights, envelopes, settings

    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .home: return LocalizedStringKey("Home")
        case .insights: return LocalizedStringKey("Insights")
        case .envelopes: return LocalizedStringKey("Envelopes")
        case .settings: return LocalizedStringKey("Settings")
        }
    }

    var icon: String {
        switch self {
        case .home: return "house"
        case .insights: return "chart.bar"
        case .envelopes: return "envelope"
        case .settings: return "gearshape"
        }
    }

    /// Insights and Envelopes show a blurred preview without Pro.
    var isPro: Bool { self == .insights || self == .envelopes }
}

/// Sheets shown over the current destination. The bottom bar hides while one is open.
enum Route: Identifiable, Equatable {
    case keypad(KeypadMode)
    case count
    case paywall
    case edit(UUID)
    case transfer
    case fill
    case newWallet
    case editButtons

    var id: String {
        switch self {
        case .keypad(let mode): return "keypad-\(mode.rawValue)"
        case .count: return "count"
        case .paywall: return "paywall"
        case .edit(let id): return "edit-\(id.uuidString)"
        case .transfer: return "transfer"
        case .fill: return "fill"
        case .newWallet: return "new-wallet"
        case .editButtons: return "edit-buttons"
        }
    }
}

struct UndoState: Equatable {
    let id = UUID()
    let transactionID: UUID
    let text: String
}

/// The app's state: the SwiftData context, the wallets, the selected wallet and destination, sheets and the undo
/// banner. Views never write to SwiftData themselves; everything goes through here and CashStore.
@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var wallets: [Wallet] = []
    /// Bumped after every change, so views that read relationships recompute.
    @Published private(set) var revision = 0
    @Published var destination: Destination = .home
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
            errorMessage = String(localized: "PocketCash couldn't open its data: \(error.localizedDescription)")
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
        let text = undoText(action.value, wallet: wallet)
        showUndo(UndoState(transactionID: action.transactionID, text: String(localized: "Widget: \(text)")))
    }

    private func consumePendingRoute() {
        guard let pending = AppSettings.pendingRoute else { return }
        AppSettings.pendingRoute = nil
        switch pending {
        case "keypad": route = .keypad(.spend)
        case "count": route = .count
        case "pro": route = .paywall
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

    /// Pro was bought, restored, refunded, or the debug switch moved.
    func proChanged() {
        if !isPro { AppSettings.proWasLost() }
        guard let context else { return }
        CashStore.refreshSnapshot(context)
        reload()
    }

    func settingsChanged() {
        guard let context else { return }
        CashStore.refreshSnapshot(context)
        reload()
    }

    /// Opens a Pro feature, or the paywall without Pro.
    func requirePro(_ action: () -> Void) {
        if isPro { action() } else { route = .paywall }
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
        if amount < 0 {
            let text = wallet.format(amount)
            return String(localized: "\(text) spent")
        }
        let text = wallet.format(amount, signed: true)
        return String(localized: "\(text) added")
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

    /// Fill envelopes (Pro): every non-zero amount becomes one transfer pair, all in one store write.
    func fill(from source: Wallet, moves: [(wallet: Wallet, amount: Decimal)]) {
        guard isPro else { route = .paywall; return }
        write { ctx in try CashStore.fill(from: source, moves: moves, in: ctx) }
        success()
    }

    @discardableResult
    func createWallet(name: String, currencyCode: String, symbol: String?, startingCash: Decimal,
                      target: Decimal? = nil) -> Wallet? {
        var created: Wallet?
        write { ctx in
            created = try CashStore.createWallet(name: name, currencyCode: currencyCode, symbol: symbol,
                                                 startingCash: startingCash, target: target, in: ctx)
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

    // MARK: Backup file (docs/spec.md section 11)

    /// Everything on this phone as a backup file.
    func makeBackup(now: Date = .now) -> Backup {
        let settings = Backup.Settings(
            plusButtonsAreATM: AppSettings.plusButtonsAreATM, privacyMode: AppSettings.privacyMode,
            lowCashAlerts: AppSettings.lowCashAlerts, quickSpendAmount: AppSettings.quickSpendAmount,
            quickSpendWalletID: AppSettings.quickSpendWalletID)
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        return Backup(exportedAt: now, platform: "ios", appVersion: version, settings: settings,
                      wallets: wallets.map(\.backup))
    }

    /// Replaces every wallet, transaction and backed-up setting with the file's. Returns false when the write failed
    /// (nothing changed then).
    @discardableResult
    func restore(_ backup: Backup) -> Bool {
        guard let context else { return false }
        writing = true
        defer { writing = false }
        do {
            try CashStore.replaceAll(with: backup, in: context)
        } catch {
            errorMessage = String(localized: "The backup couldn't be restored: \(error.localizedDescription)")
            context.rollback()
            reload()
            return false
        }
        undoTask?.cancel()
        undo = nil
        selectedWalletID = nil
        destination = .home
        AppSettings.onboarded = true
        reload()
        if AppSettings.lowCashAlerts {
            Task {
                if !(await LowCash.requestPermission()) { AppSettings.lowCashAlerts = false }
            }
        }
        success()
        return true
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
            route = nil
            destination = .home
            if let id = value("id").flatMap(UUID.init(uuidString:)), isPro { selectedWalletID = id }
        default: break
        }
    }
}
