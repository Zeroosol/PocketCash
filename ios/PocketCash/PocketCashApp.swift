import SwiftUI

@main
struct PocketCashApp: App {
    @StateObject private var model = AppModel()
    @StateObject private var pro = ProStore()
    @StateObject private var lock = AppLock()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(pro)
                .environmentObject(lock)
                .tint(Theme.emerald)
                .onOpenURL { model.handle($0) }
                .onChange(of: scenePhase, initial: true) { _, phase in
                    lock.scenePhaseChanged(phase)
                    if phase == .active { model.becameActive() }
                }
                .onChange(of: pro.isPro) { _, _ in
                    model.proChanged()
                    lock.refresh()
                }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore
    @EnvironmentObject private var lock: AppLock
    @AppStorage(AppSettings.Key.onboarded, store: AppSettings.defaults) private var onboarded = false

    var body: some View {
        Group {
            if !onboarded || model.wallets.isEmpty {
                OnboardingView()
            } else {
                MainShell()
            }
        }
        .sheet(item: $model.route) { route in
            switch route {
            case .keypad(let mode): KeypadSheet(mode: mode)
            case .count: CountCashSheet()
            case .paywall: NavigationStack { PaywallView(showsClose: true) }
            case .edit(let id): TransactionEditor(transactionID: id)
            case .transfer: TransferSheet()
            case .fill: FillSheet()
            case .newWallet: NavigationStack { WalletEditorView(wallet: nil, showsCancel: true) }
            case .editButtons:
                if let wallet = model.wallet {
                    NavigationStack { PresetEditorView(wallet: wallet, showsCancel: true) }
                }
            }
        }
        // The lock's own window covers sheets too; this also covers the very first frame at launch.
        .overlay {
            if lock.showsCover { LockScreenView(lock: lock) }
        }
        .alert("Something went wrong", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(verbatim: model.errorMessage ?? "")
        }
        .sensoryFeedback(.success, trigger: model.successTick)
        .sensoryFeedback(.impact(weight: .light), trigger: model.lightTick)
    }
}
