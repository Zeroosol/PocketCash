import SwiftUI

/// Create a wallet (Pro) or edit one: name, currency, custom symbol, starting cash, target, default, delete.
struct WalletEditorView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore
    @Environment(\.dismiss) private var dismiss
    let wallet: Wallet?
    /// Shown as a sheet (from Home or Envelopes) rather than pushed from Settings.
    var showsCancel = false

    @State private var name = ""
    @State private var currency = CurrencyDefaults.localCode
    @State private var symbol = ""
    @State private var startingCash = ""
    @State private var target = ""
    @State private var loaded = false
    @State private var confirmDelete = false

    var body: some View {
        Form {
            Section {
                TextField("Name", text: $name)
                NavigationLink {
                    CurrencyPicker(selection: $currency)
                } label: {
                    LabeledContent("Currency", value: "\(currency) · \(CurrencyDefaults.name(currency))")
                }
                TextField("Custom symbol (optional)", text: $symbol)
                    .disabled(!pro.isPro)
            } footer: {
                if pro.isPro {
                    Text("A custom symbol is handy for a travel wallet.")
                } else {
                    Text("Custom symbols are part of Pro.")
                }
            }

            if wallet == nil {
                Section {
                    TextField("0", text: $startingCash)
                        .keyboardType(Money.fractionDigits(for: currency) > 0 ? .decimalPad : .numberPad)
                } header: {
                    Text("Cash in it right now")
                }
            }

            Section {
                HStack {
                    Text(verbatim: Money.symbol(for: currency, custom: symbol.nilIfEmpty)).foregroundStyle(.secondary)
                    TextField("No target", text: $target)
                        .keyboardType(Money.fractionDigits(for: currency) > 0 ? .decimalPad : .numberPad)
                        .monospacedDigit()
                        .disabled(!pro.isPro)
                    if !pro.isPro { Image(systemName: "lock").font(.footnote).foregroundStyle(.secondary) }
                }
            } header: {
                Text("Target")
            } footer: {
                if pro.isPro {
                    Text("Envelope target or savings goal. Its progress shows in Envelopes.")
                } else {
                    Text("Envelope targets and savings goals are part of Pro.")
                }
            }

            if wallet == nil {
                Section {
                    ForEach(suggestions, id: \.self) { suggestion in
                        Button(suggestion) { name = suggestion }
                    }
                } header: {
                    Text("Ideas")
                }
            }

            if let wallet {
                if model.wallets.first?.id != wallet.id {
                    Section {
                        Button("Make this the default wallet") {
                            wallet.sortOrder = (model.wallets.map(\.sortOrder).min() ?? 0) - 1
                            model.saveEdits()
                        }
                    } footer: {
                        Text("Widgets, the free version and Siri use the default wallet unless you pick another.")
                    }
                }
                if model.wallets.count > 1 {
                    Section {
                        Button("Delete wallet", role: .destructive) { confirmDelete = true }
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle(wallet == nil ? Text("New wallet") : Text("Edit wallet"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if showsCancel {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") { save() }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .confirmationDialog("Delete \(name)?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete wallet and its history", role: .destructive) {
                if let wallet { model.deleteWallet(wallet) }
                dismiss()
            }
        } message: {
            Text("This can't be undone.")
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            if let wallet {
                name = wallet.name
                currency = wallet.currencyCode
                symbol = wallet.symbol ?? ""
                target = wallet.target.map(Money.string) ?? ""
            } else {
                name = ""
                currency = model.wallet?.currencyCode ?? CurrencyDefaults.localCode
            }
        }
    }

    private var suggestions: [String] {
        [String(localized: "Coin Pouch"), String(localized: "Emergency Stash"), String(localized: "Travel Wallet"),
         String(localized: "Rent"), String(localized: "Savings")]
    }

    private func decimal(_ text: String) -> Decimal? {
        Money.decimal(text.replacingOccurrences(of: ",", with: "."))
    }

    private func save() {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        let customSymbol = symbol.trimmingCharacters(in: .whitespaces).nilIfEmpty
        let newTarget = decimal(target).flatMap { $0 > 0 ? $0 : nil }
        if let wallet {
            if wallet.currencyCode != currency {
                // New currency, new sensible buttons.
                let presets = DefaultPresets.forCurrency(currency)
                wallet.currencyCode = currency
                wallet.addPresets = presets.add
                wallet.spendPresets = presets.spend
            }
            wallet.name = trimmed
            if pro.isPro {
                wallet.symbol = customSymbol
                wallet.target = newTarget
            }
            model.saveEdits()
        } else {
            let cash = decimal(startingCash) ?? 0
            model.createWallet(name: trimmed, currencyCode: currency, symbol: pro.isPro ? customSymbol : nil,
                               startingCash: max(cash, 0), target: pro.isPro ? newTarget : nil)
        }
        dismiss()
    }
}

struct CurrencyPicker: View {
    @Binding var selection: String
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var codes: [String] {
        let all = CurrencyDefaults.pickerCodes
        guard !query.isEmpty else { return all }
        return all.filter { $0.localizedCaseInsensitiveContains(query) || CurrencyDefaults.name($0).localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        List(codes, id: \.self) { code in
            Button {
                selection = code
                dismiss()
            } label: {
                HStack {
                    Text(verbatim: code).font(.body.monospaced().weight(.medium)).frame(width: 52, alignment: .leading)
                    Text(verbatim: CurrencyDefaults.name(code)).lineLimit(1)
                    Spacer()
                    if code == selection { Image(systemName: "checkmark") }
                }
            }
            .foregroundStyle(.primary)
        }
        .listStyle(.plain)
        .searchable(text: $query, prompt: "Search currencies")
        .navigationTitle("Currency")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Custom denomination buttons (Pro): 1-2 cash-in and 1-4 spend amounts per wallet.
struct PresetEditorView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let wallet: Wallet
    /// Shown as a sheet from Home or Settings.
    var showsCancel = false

    struct Row: Identifiable {
        let id = UUID()
        var text: String
    }

    @State private var adds: [Row] = []
    @State private var spends: [Row] = []
    @State private var loaded = false

    var body: some View {
        Form {
            Section {
                ForEach($adds) { $row in
                    presetField(sign: "+", text: $row.text)
                }
                .onDelete { if adds.count > 1 { adds.remove(atOffsets: $0) } }
                if adds.count < 2 { Button("Add a cash-in button") { adds.append(Row(text: "")) } }
            } header: {
                Text("Cash in")
            } footer: {
                Text("The first one is the widget's + button.")
            }
            Section {
                ForEach($spends) { $row in
                    presetField(sign: Money.minus, text: $row.text)
                }
                .onDelete { if spends.count > 1 { spends.remove(atOffsets: $0) } }
                .onMove { spends.move(fromOffsets: $0, toOffset: $1) }
                if spends.count < 4 { Button("Add a spend button") { spends.append(Row(text: "")) } }
            } header: {
                Text("Spend")
            } footer: {
                Text("The small widget shows the first two, the medium widget the first three.")
            }
            Section {
                Button("Reset to \(wallet.currencyCode) defaults") {
                    adds = wallet.defaults.add.map { Row(text: Money.string($0)) }
                    spends = wallet.defaults.spend.map { Row(text: Money.string($0)) }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle("Buttons")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if showsCancel {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") { save() }.disabled(parsed(adds).isEmpty || parsed(spends).isEmpty)
            }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            adds = wallet.addButtons(isPro: true).map { Row(text: Money.string($0)) }
            spends = wallet.spendButtons(isPro: true).map { Row(text: Money.string($0)) }
        }
    }

    private func presetField(sign: String, text: Binding<String>) -> some View {
        HStack {
            Text(verbatim: sign).font(.headline).foregroundStyle(.secondary)
            TextField("Amount", text: text)
                .keyboardType(wallet.allowsFractions ? .decimalPad : .numberPad)
                .monospacedDigit()
            if let value = Money.decimal(text.wrappedValue.replacingOccurrences(of: ",", with: ".")), value > 0 {
                Text(verbatim: Money.buttonLabel(value, cashIn: sign == "+"))
                    .font(.caption.weight(.medium).monospacedDigit())
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Theme.surface, in: Capsule())
            }
        }
    }

    private func parsed(_ rows: [Row]) -> [Decimal] {
        rows.compactMap { Money.decimal($0.text.replacingOccurrences(of: ",", with: ".")) }.filter { $0 > 0 }
    }

    private func save() {
        wallet.addPresets = parsed(adds)
        wallet.spendPresets = parsed(spends)
        model.saveEdits()
        dismiss()
    }
}
