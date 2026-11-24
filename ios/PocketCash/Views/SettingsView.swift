import SwiftUI
import UIKit

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore
    @Environment(\.dismiss) private var dismiss
    @AppStorage(AppSettings.Key.plusButtonsAreATM, store: AppSettings.defaults) private var plusATM = true
    @AppStorage(AppSettings.Key.privacyMode, store: AppSettings.defaults) private var privacy = false
    @AppStorage(AppSettings.Key.lowCashAlerts, store: AppSettings.defaults) private var lowCash = false
    @AppStorage(AppSettings.Key.haptics, store: AppSettings.defaults) private var haptics = true
    @State private var share: ShareItem?

    var body: some View {
        NavigationStack {
            Form {
                proSection
                if let wallet = model.wallet { walletSection(wallet) }
                walletsSection
                quickSpendSection
                Section {
                    Toggle("Count + buttons as ATM withdrawals", isOn: $plusATM)
                    Toggle("Privacy mode", isOn: $privacy)
                } header: {
                    Text("Widgets")
                } footer: {
                    Text("ATM withdrawals start a new ATM cycle. Privacy mode hides amounts on widgets, the Lock Screen and controls.")
                }
                Section {
                    Toggle("Haptics", isOn: $haptics)
                }
                dataSection
                Section {
                    LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                } header: {
                    Text("About")
                } footer: {
                    Text("PocketCash keeps everything on this iPhone. No account, no ads, no tracking.")
                }
                if AppSettings.debugProAvailable {
                    Section("Developer") {
                        Toggle("Unlock Pro (debug build)", isOn: $pro.debugUnlock)
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .onChange(of: plusATM) { _, _ in model.settingsChanged() }
            .onChange(of: privacy) { _, _ in model.settingsChanged() }
            .onChange(of: lowCash) { _, enabled in
                guard enabled else { return }
                Task {
                    if !(await LowCash.requestPermission()) { lowCash = false }
                }
            }
            .sheet(item: $share) { ActivityView(items: [$0.url]) }
        }
    }

    private var proSection: some View {
        Section {
            if pro.isPro {
                Label("PocketCash Pro is unlocked", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(Theme.emerald)
            } else {
                NavigationLink {
                    PaywallView()
                } label: {
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("PocketCash Pro").font(.headline)
                            Text("Custom buttons, wallets, ATM cycles, categories").font(.caption).foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "star.circle.fill").foregroundStyle(Theme.emerald)
                    }
                }
                Button("Restore purchase") { Task { await pro.restore() } }
            }
        }
    }

    private func walletSection(_ wallet: Wallet) -> some View {
        Section {
            AmountField(title: "Daily budget", wallet: wallet, value: Binding(
                get: { wallet.dailyBudget },
                set: { wallet.dailyBudget = $0; model.saveEdits() }))
            Toggle("Low-cash reminder", isOn: $lowCash)
            if lowCash {
                AmountField(title: "Remind me below", wallet: wallet, value: Binding(
                    get: { wallet.lowCashThreshold },
                    set: { wallet.lowCashThreshold = $0; model.saveEdits() }))
            }
            if pro.isPro {
                NavigationLink("Buttons") { PresetEditorView(wallet: wallet) }
            } else {
                NavigationLink { PaywallView() } label: { LockedLabel(title: "Buttons", detail: "Pick your own amounts") }
            }
        } header: {
            Text(wallet.name)
        } footer: {
            Text("The budget shows what's left today on the app and the medium widget.")
        }
    }

    private var walletsSection: some View {
        Section("Wallets") {
            ForEach(model.wallets) { wallet in
                NavigationLink {
                    WalletEditorView(wallet: wallet)
                } label: {
                    LabeledContent(wallet.name, value: wallet.format(wallet.balance))
                }
            }
            if pro.isPro {
                NavigationLink { WalletEditorView(wallet: nil) } label: { Label("Add a wallet", systemImage: "plus") }
            } else {
                NavigationLink { PaywallView() } label: { LockedLabel(title: "Add a wallet", detail: "Coin pouch, stash, travel") }
            }
        }
    }

    private var quickSpendSection: some View {
        let smallest = model.wallet.flatMap { $0.spendButtons(isPro: pro.isPro).min() }
        return Section {
            if let wallet = quickWallet {
                AmountField(title: "Amount", wallet: wallet, placeholder: smallest.map { wallet.format($0) } ?? "Off", value: Binding(
                    get: { AppSettings.quickSpendAmount },
                    set: { AppSettings.quickSpendAmount = $0; model.settingsChanged() }))
            }
            if pro.isPro && model.wallets.count > 1 {
                Picker("Wallet", selection: Binding(
                    get: { AppSettings.quickSpendWalletID ?? model.wallets.first?.id },
                    set: { AppSettings.quickSpendWalletID = $0; model.settingsChanged() })) {
                    ForEach(model.wallets) { Text($0.name).tag(Optional($0.id)) }
                }
            }
        } header: {
            Text("Quick spend")
        } footer: {
            Text("Your most common cash expense, like your commute fare. Add the Quick Spend control to Control Center or the Lock Screen, or say \"Quick spend in PocketCash\".")
        }
    }

    private var quickWallet: Wallet? {
        if pro.isPro, let id = AppSettings.quickSpendWalletID, let w = model.wallets.first(where: { $0.id == id }) { return w }
        return model.wallets.first
    }

    private var dataSection: some View {
        Section("Data") {
            if pro.isPro {
                Button { model.route = .insights } label: { Label("Insights", systemImage: "chart.bar.xaxis") }
                Button {
                    if let url = model.exportCSV() { share = ShareItem(url: url) }
                } label: {
                    Label("Export CSV", systemImage: "square.and.arrow.up")
                }
            } else {
                NavigationLink { PaywallView() } label: { LockedLabel(title: "Insights & CSV export", detail: "ATM cycles, categories, every entry") }
            }
        }
    }
}

struct LockedLabel: View {
    let title: String
    let detail: String

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            ProBadge()
        }
    }
}

/// A decimal field that saves when it loses focus. Empty or zero means "off".
struct AmountField: View {
    let title: String
    let wallet: Wallet
    var placeholder = "Off"
    @Binding var value: Decimal?
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            Text(Money.symbol(for: wallet.currencyCode, custom: wallet.symbol)).foregroundStyle(.secondary)
            TextField(placeholder, text: $text)
                .keyboardType(wallet.allowsFractions ? .decimalPad : .numberPad)
                .multilineTextAlignment(.trailing)
                .monospacedDigit()
                .focused($focused)
                .frame(maxWidth: 130)
        }
        .onAppear { text = value.map(Money.string) ?? "" }
        .onChange(of: focused) { _, isFocused in if !isFocused { commit() } }
        .onDisappear { if focused { commit() } }
        .toolbar {
            if focused {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { focused = false }
                }
            }
        }
    }

    private func commit() {
        let parsed = Money.decimal(text.replacingOccurrences(of: ",", with: "."))
        let newValue = (parsed ?? 0) > 0 ? parsed : nil
        if newValue != value { value = newValue }
        text = newValue.map(Money.string) ?? ""
    }
}

struct ShareItem: Identifiable {
    let id = UUID()
    let url: URL
}

struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
