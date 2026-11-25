import SwiftUI
import UIKit

/// The Settings destination (docs/spec.md section 7.4). Pro settings stay visible with a small lock and open the
/// paywall when used.
struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore
    @EnvironmentObject private var lock: AppLock
    @AppStorage(AppSettings.Key.plusButtonsAreATM, store: AppSettings.defaults) private var plusATM = true
    @AppStorage(AppSettings.Key.privacyMode, store: AppSettings.defaults) private var privacy = false
    @AppStorage(AppSettings.Key.lowCashAlerts, store: AppSettings.defaults) private var lowCash = false
    @AppStorage(AppSettings.Key.haptics, store: AppSettings.defaults) private var haptics = true
    @State private var share: ShareItem?
    @State private var exporting = false
    @State private var backupDocument: BackupDocument?
    @State private var restoring = false

    var body: some View {
        NavigationStack {
            Form {
                walletsSection
                if let wallet = model.wallet { walletSection(wallet) }
                quickSpendSection
                Section {
                    Toggle("Count + buttons as ATM withdrawals", isOn: $plusATM)
                    Toggle(isOn: proBinding($privacy)) { ProLabel(title: Text("Privacy mode"), locked: !pro.isPro) }
                } header: {
                    Text("Widgets")
                } footer: {
                    Text("ATM withdrawals start a new ATM cycle. Privacy mode hides amounts on widgets, the Lock Screen and controls.")
                }
                appLockSection
                Section {
                    Toggle("Haptics", isOn: $haptics)
                }
                dataSection
                Section {
                    Button { openSystemSettings() } label: {
                        LabeledContent { Text(verbatim: currentLanguage) } label: { Text("Language").foregroundStyle(Color.primary) }
                    }
                } footer: {
                    Text("Opens this app's page in the iPhone's Settings, where you can pick its language.")
                }
                proSection
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
            .scrollContentBackground(.hidden)
            .background(Theme.background)
            .tracksNavBar(.settings)
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.large)
            .onChange(of: plusATM) { _, _ in model.settingsChanged() }
            .onChange(of: privacy) { _, _ in model.settingsChanged() }
            .onChange(of: lowCash) { _, enabled in
                guard enabled else { return }
                Task {
                    if !(await LowCash.requestPermission()) { lowCash = false }
                }
            }
            .sheet(item: $share) { ActivityView(items: [$0.url]) }
            .fileExporter(isPresented: $exporting, document: backupDocument, contentType: .json,
                          defaultFilename: BackupDocument.defaultName()) { result in
                if case .failure(let error) = result { model.errorMessage = error.localizedDescription }
            }
            .restoresBackup(isPresented: $restoring)
        }
    }

    /// A Pro switch: off and locked without Pro, where turning it on opens the paywall.
    private func proBinding(_ value: Binding<Bool>) -> Binding<Bool> {
        Binding(get: { pro.isPro && value.wrappedValue }, set: { on in
            if pro.isPro { value.wrappedValue = on } else if on { model.route = .paywall }
        })
    }

    private var walletsSection: some View {
        Section {
            ForEach(model.wallets) { wallet in
                NavigationLink {
                    WalletEditorView(wallet: wallet)
                } label: {
                    LabeledContent {
                        Text(verbatim: wallet.format(wallet.balance)).monospacedDigit()
                    } label: {
                        Text(verbatim: wallet.name)
                    }
                }
            }
            .onMove { model.moveWallets(from: $0, to: $1) }
            Button { model.route = pro.isPro ? .newWallet : .paywall } label: {
                ProLabel(title: Text("Add a wallet"), locked: !pro.isPro)
            }
        } header: {
            Text("Wallets")
        } footer: {
            if model.wallets.count > 1 {
                Text("Touch and hold a wallet to reorder. The first one is the default.")
            }
        }
    }

    private func walletSection(_ wallet: Wallet) -> some View {
        Section {
            Button { model.route = pro.isPro ? .editButtons : .paywall } label: {
                ProLabel(title: Text("Buttons"), locked: !pro.isPro, chevron: true)
            }
            if pro.isPro {
                AmountField(title: "Daily budget", wallet: wallet, value: Binding(
                    get: { wallet.dailyBudget },
                    set: { wallet.dailyBudget = $0; model.saveEdits() }))
            } else {
                Button { model.route = .paywall } label: { ProLabel(title: Text("Daily budget"), locked: true) }
            }
            Toggle(isOn: proBinding($lowCash)) { ProLabel(title: Text("Low-cash reminder"), locked: !pro.isPro) }
            if lowCash && pro.isPro {
                AmountField(title: "Remind me below", wallet: wallet, value: Binding(
                    get: { wallet.lowCashThreshold },
                    set: { wallet.lowCashThreshold = $0; model.saveEdits() }))
            }
        } header: {
            Text(verbatim: wallet.name)
        } footer: {
            Text("The budget shows what's left today on the app and the medium widget.")
        }
    }

    private var quickSpendSection: some View {
        let smallest = model.wallet.flatMap { $0.spendButtons(isPro: pro.isPro).min() }
        return Section {
            if pro.isPro {
                if let wallet = quickWallet {
                    AmountField(title: "Amount", wallet: wallet,
                                placeholder: smallest.map { wallet.format($0) } ?? String(localized: "Off"),
                                value: Binding(
                                    get: { AppSettings.quickSpendAmount },
                                    set: { AppSettings.quickSpendAmount = $0; model.settingsChanged() }))
                }
                if model.wallets.count > 1 {
                    Picker("Wallet", selection: Binding(
                        get: { AppSettings.quickSpendWalletID ?? model.wallets.first?.id },
                        set: { AppSettings.quickSpendWalletID = $0; model.settingsChanged() })) {
                        ForEach(model.wallets) { Text(verbatim: $0.name).tag(Optional($0.id)) }
                    }
                }
            } else {
                Button { model.route = .paywall } label: { ProLabel(title: Text("Quick spend amount"), locked: true) }
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

    private var appLockSection: some View {
        let method = AppLock.method
        return Section {
            Toggle(isOn: Binding(get: { lock.isEnabled }, set: { on in
                guard pro.isPro else {
                    if on { model.route = .paywall }
                    return
                }
                Task { await lock.setEnabled(on) }
            })) {
                ProLabel(title: Text(method.title), locked: !pro.isPro)
            }
            .disabled(pro.isPro && method == .unavailable)
        } header: {
            Text("App lock")
        } footer: {
            if pro.isPro && method == .unavailable {
                Text("Set up a passcode on this iPhone to use App lock.")
            } else {
                Text("Widgets still show your balance. Turn on Privacy mode to hide it.")
            }
        }
    }

    private var dataSection: some View {
        Section {
            Button {
                backupDocument = BackupDocument(data: BackupCodec.encode(model.makeBackup()))
                exporting = true
            } label: {
                Label("Back up to a file", systemImage: "square.and.arrow.down")
            }
            Button { restoring = true } label: {
                Label("Restore from a file", systemImage: "arrow.counterclockwise")
            }
            Button {
                guard pro.isPro else { model.route = .paywall; return }
                if let url = model.exportCSV() { share = ShareItem(url: url) }
            } label: {
                ProLabel(title: Text("Export CSV"), systemImage: "tablecells", locked: !pro.isPro)
            }
        } header: {
            Text("Backup")
        } footer: {
            Text("A backup file holds every wallet, cash move and setting, and restores on any iPhone or Android phone.")
        }
    }

    private var proSection: some View {
        Section {
            if pro.isPro {
                Label("PocketCash Pro is unlocked", systemImage: "checkmark.seal")
            } else {
                Button { model.route = .paywall } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("PocketCash Pro").font(.headline).foregroundStyle(Color.primary)
                        Text("Envelopes, insights, more wallets, quick spend and more. One purchase.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Button("Restore purchase") { Task { await pro.restore() } }
            }
        } header: {
            Text("Pro")
        } footer: {
            if let message = pro.message, !pro.isPro { Text(verbatim: message) }
        }
    }

    private var currentLanguage: String {
        let code = Bundle.main.preferredLocalizations.first ?? "en"
        return Locale.current.localizedString(forIdentifier: code) ?? code
    }

    private func openSystemSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }
}

/// A row title with a small lock when the feature needs Pro.
struct ProLabel: View {
    let title: Text
    var systemImage: String?
    var locked: Bool
    var chevron = false

    var body: some View {
        HStack(spacing: 8) {
            if let systemImage {
                Image(systemName: systemImage).foregroundStyle(Color.primary).frame(width: 24)
            }
            title.foregroundStyle(Color.primary)
            Spacer(minLength: 4)
            if locked {
                Image(systemName: "lock").font(.footnote).foregroundStyle(.secondary).accessibilityLabel(Text("Pro"))
            } else if chevron {
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
            }
        }
    }
}

/// A decimal field that saves when it loses focus. Empty or zero means "off".
struct AmountField: View {
    let title: LocalizedStringKey
    let wallet: Wallet
    var placeholder = String(localized: "Off")
    @Binding var value: Decimal?
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            Text(verbatim: Money.symbol(for: wallet.currencyCode, custom: wallet.symbol)).foregroundStyle(.secondary)
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
