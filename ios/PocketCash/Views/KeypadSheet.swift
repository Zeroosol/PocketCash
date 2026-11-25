import SwiftUI

/// The custom amount sheet: opens with the keypad already active (docs/spec.md section 7.2). In Spend mode the
/// change calculator (Pro) splits the entry into Paid and Change and saves one spend of the difference.
struct KeypadSheet: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore
    @Environment(\.dismiss) private var dismiss
    @State var mode: KeypadMode
    /// The amount, or what was paid while the change calculator is on.
    @State private var entry = ""
    @State private var change = ""
    @State private var splitting = false
    /// Which field the keypad types into while splitting.
    @State private var editingChange = false
    @State private var fromATM = AppSettings.plusButtonsAreATM
    @State private var tag: String?
    @State private var note = ""
    @State private var showPaywall = false

    var body: some View {
        NavigationStack {
            if let wallet = model.wallet {
                let paid = Money.decimal(entry) ?? 0
                let changeValue = Money.decimal(change) ?? 0
                let changeMode = splitting && mode == .spend
                let amount: Decimal? = changeMode && !change.isEmpty
                    ? ChangeMath.spend(paid: paid, change: changeValue)
                    : (paid > 0 ? paid : nil)
                let changeTooBig = changeMode && !change.isEmpty && changeValue > 0 && changeValue >= paid

                VStack(spacing: 16) {
                    Picker("Type", selection: $mode) {
                        Text("Spend").tag(KeypadMode.spend)
                        Text("Add cash").tag(KeypadMode.add)
                    }
                    .pickerStyle(.segmented)

                    if changeMode {
                        ChangeFields(wallet: wallet, paid: $entry, change: $change, editingChange: $editingChange,
                                     result: amount, tooBig: changeTooBig) {
                            withAnimation(.snappy) {
                                splitting = false
                                editingChange = false
                                change = ""
                            }
                        }
                    } else {
                        AmountDisplay(entry: entry, wallet: wallet, sign: mode == .spend ? Money.minus : "+",
                                      color: mode == .add ? Theme.emerald : .primary)
                        if mode == .spend {
                            Button {
                                if pro.isPro {
                                    withAnimation(.snappy) {
                                        splitting = true
                                        editingChange = true
                                    }
                                } else {
                                    showPaywall = true
                                }
                            } label: {
                                Label("Got change?", systemImage: pro.isPro ? "arrow.uturn.backward" : "lock")
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(.secondary)
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    if mode == .add {
                        Toggle("From ATM", isOn: $fromATM)
                    } else {
                        CategoryChips(selection: $tag, locked: !pro.isPro) { showPaywall = true }
                    }

                    TextField("Note (optional)", text: $note)
                        .submitLabel(.done)
                        .padding(.vertical, 10)
                        .hairline()

                    Spacer(minLength: 0)

                    KeypadView(entry: changeMode && editingChange ? $change : $entry,
                               fractionDigits: Money.fractionDigits(for: wallet.currencyCode))

                    Button {
                        if let amount {
                            model.add(amount, cashIn: mode == .add, fromATM: fromATM, tag: tag, note: note)
                        }
                        dismiss()
                    } label: {
                        Text("Save").font(.headline).frame(maxWidth: .infinity, minHeight: 34)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(amount == nil)
                }
                .padding(20)
                .background(Theme.background)
                .navigationTitle(wallet.name)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                }
            }
        }
        .sheet(isPresented: $showPaywall) { NavigationStack { PaywallView(showsClose: true) } }
    }
}

/// Paid and Change, the quick "Paid" bills, and `Paid ₱500 − Change ₱130 = ₱370`.
private struct ChangeFields: View {
    @EnvironmentObject private var pro: ProStore
    let wallet: Wallet
    @Binding var paid: String
    @Binding var change: String
    @Binding var editingChange: Bool
    let result: Decimal?
    let tooBig: Bool
    let close: () -> Void

    var body: some View {
        let chips = ChangeMath.paidChips(denominations: wallet.defaults.denominations,
                                         spendPresets: wallet.spendButtons(isPro: pro.isPro))
        VStack(spacing: 10) {
            field(title: Text("Paid"), entry: paid, selected: !editingChange) { editingChange = false }
            if !chips.isEmpty {
                HStack(spacing: 8) {
                    ForEach(chips, id: \.self) { bill in
                        Button {
                            paid = Money.string(bill)
                            editingChange = true
                        } label: {
                            Text(verbatim: wallet.format(bill))
                                .font(.subheadline.weight(.medium))
                                .monospacedDigit()
                                .lineLimit(1)
                                .minimumScaleFactor(0.7)
                                .frame(maxWidth: .infinity, minHeight: 34)
                                .background(Theme.surface, in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            field(title: Text("Change"), entry: change, selected: editingChange) { editingChange = true }
            HStack {
                if tooBig {
                    Text("Change must be less than what you paid")
                        .foregroundStyle(Theme.danger)
                } else {
                    let paidText = AmountDisplay.text(entry: paid, code: wallet.currencyCode, symbol: wallet.symbol)
                    let changeText = AmountDisplay.text(entry: change, code: wallet.currencyCode, symbol: wallet.symbol)
                    let resultText = wallet.format(result ?? 0)
                    Text("Paid \(paidText) − Change \(changeText) = \(resultText)")
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                Button(action: close) {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Remove change"))
            }
            .font(.footnote)
            .lineLimit(2)
        }
    }

    private func field(title: Text, entry: String, selected: Bool, select: @escaping () -> Void) -> some View {
        Button(action: select) {
            HStack {
                title
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(selected ? Color.primary : Color.secondary)
                Spacer()
                Text(verbatim: AmountDisplay.text(entry: entry, code: wallet.currencyCode, symbol: wallet.symbol))
                    .font(Theme.balance(34))
                    .foregroundStyle(entry.isEmpty ? Color.secondary : Color.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .contentTransition(.numericText())
                    .animation(.snappy(duration: 0.15), value: entry)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(selected ? Theme.surface : Color.clear, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// The big amount while typing: "−₱120.5".
struct AmountDisplay: View {
    let entry: String
    let code: String
    var symbol: String?
    var sign = ""
    var color: Color = .primary

    init(entry: String, code: String, symbol: String? = nil, sign: String = "", color: Color = .primary) {
        self.entry = entry
        self.code = code
        self.symbol = symbol
        self.sign = sign
        self.color = color
    }

    init(entry: String, wallet: Wallet, sign: String = "", color: Color = .primary) {
        self.init(entry: entry, code: wallet.currencyCode, symbol: wallet.symbol, sign: sign, color: color)
    }

    /// "₱1,200" for whole amounts, "₱12.5" while a fraction is being typed.
    static func text(entry: String, code: String, symbol: String?) -> String {
        let typed = entry.isEmpty ? "0" : entry
        return Money.isWhole(Money.decimal(typed) ?? 0) && !typed.contains(".")
            ? Money.format(Money.decimal(typed) ?? 0, code: code, symbol: symbol)
            : Money.symbol(for: code, custom: symbol) + typed
    }

    var body: some View {
        Text(verbatim: (entry.isEmpty ? "" : sign) + Self.text(entry: entry, code: code, symbol: symbol))
            .font(Theme.balance(60))
            .foregroundStyle(entry.isEmpty ? .secondary : color)
            .lineLimit(1)
            .minimumScaleFactor(0.4)
            .contentTransition(.numericText())
            .animation(.snappy(duration: 0.15), value: entry)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)
    }
}

/// A calculator-style number pad that edits a plain decimal string.
struct KeypadView: View {
    @Binding var entry: String
    /// 0 hides the decimal point (JPY, KRW, ...).
    var fractionDigits: Int

    private let keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "del"]

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 10) {
            ForEach(keys, id: \.self) { key in
                let hidden = key == "." && fractionDigits == 0
                Button { tap(key) } label: {
                    Group {
                        if key == "del" { Image(systemName: "delete.left") } else { Text(verbatim: key) }
                    }
                    .font(.system(.title, design: .rounded, weight: .regular))
                    .foregroundStyle(.primary)
                    .frame(maxWidth: .infinity, minHeight: 54)
                    .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(PressableStyle())
                .opacity(hidden ? 0 : 1)
                .disabled(hidden)
                .accessibilityLabel(key == "del" ? Text("Delete") : Text(verbatim: key))
            }
        }
        .sensoryFeedback(.selection, trigger: entry)
    }

    private func tap(_ key: String) {
        switch key {
        case "del":
            if !entry.isEmpty { entry.removeLast() }
        case ".":
            guard fractionDigits > 0, !entry.contains(".") else { return }
            entry = entry.isEmpty ? "0." : entry + "."
        default:
            if let dot = entry.firstIndex(of: ".") {
                guard entry[entry.index(after: dot)...].count < fractionDigits else { return }
            } else if entry.count >= 9 {
                return
            }
            entry = entry == "0" ? key : entry + key
        }
    }
}

/// Optional category chips (Pro). Locked chips open the paywall instead.
struct CategoryChips: View {
    @Binding var selection: String?
    var locked: Bool
    var onLocked: () -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if locked {
                    Button(action: onLocked) {
                        Label("Categories", systemImage: "lock")
                            .font(.subheadline)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
                ForEach(CashCategory.allCases) { category in
                    let selected = selection == category.rawValue
                    Button {
                        if locked { onLocked() } else { selection = selected ? nil : category.rawValue }
                    } label: {
                        Label(category.label, systemImage: category.symbol)
                            .font(.subheadline)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .foregroundStyle(selected ? Color(uiColor: .systemBackground) : .primary)
                            .background(selected ? Color.primary : Theme.surface, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .opacity(locked ? 0.4 : 1)
                }
            }
        }
    }
}
