import SwiftUI

@main
struct PocketCashApp: App {
    @StateObject private var model = AppModel()
    @StateObject private var pro = ProStore()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(pro)
                .tint(Theme.emerald)
                .onOpenURL { model.handle($0) }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { model.becameActive() }
                }
                .onChange(of: pro.isPro) { _, _ in model.proChanged() }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore
    @AppStorage(AppSettings.Key.onboarded, store: AppSettings.defaults) private var onboarded = false

    var body: some View {
        Group {
            if !onboarded || model.wallets.isEmpty {
                OnboardingView()
            } else {
                HomeView()
            }
        }
        .sheet(item: $model.route) { route in
            switch route {
            case .keypad(let mode): KeypadSheet(mode: mode)
            case .count: CountCashSheet()
            case .settings: SettingsView()
            case .paywall: NavigationStack { PaywallView(showsClose: true) }
            case .insights: InsightsView()
            case .edit(let id): TransactionEditor(transactionID: id)
            case .transfer: TransferSheet()
            }
        }
        .alert("Something went wrong", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.errorMessage ?? "")
        }
        .sensoryFeedback(.success, trigger: model.successTick)
        .sensoryFeedback(.impact(weight: .light), trigger: model.lightTick)
    }
}
