import SwiftUI

/// The custom amount sheet: opens with the keypad already active (docs/spec.md section 7).
struct KeypadSheet: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore
    @Environment(\.dismiss) private var dismiss
    @State var mode: KeypadMode
    @State private var entry = ""
    @State private var fromATM = AppSettings.plusButtonsAreATM
    @State private var tag: String?
    @State private var note = ""
    @State private var showPaywall = false

    var body: some View {
        NavigationStack {
            if let wallet = model.wallet {
                let amount = Money.decimal(entry) ?? 0
                VStack(spacing: 14) {
                    Picker("Type", selection: $mode) {
                        Text("Spend").tag(KeypadMode.spend)
                        Text("Add cash").tag(KeypadMode.add)
                    }
                    .pickerStyle(.segmented)

                    AmountDisplay(entry: entry, wallet: wallet, sign: mode == .spend ? Money.minus : "+",
                                  color: mode == .add ? Theme.emerald : .primary)

                    if mode == .add {
                        Toggle("From ATM", isOn: $fromATM)
                            .padding(.horizontal, 4)
                    } else {
                        CategoryChips(selection: $tag, locked: !pro.isPro) { showPaywall = true }
                    }

                    TextField("Note (optional)", text: $note)
                        .submitLabel(.done)
                        .padding(12)
                        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))

                    Spacer(minLength: 0)

                    KeypadView(entry: $entry, fractionDigits: Money.fractionDigits(for: wallet.currencyCode))

                    Button {
                        model.add(amount, cashIn: mode == .add, fromATM: fromATM, tag: tag, note: note)
                        dismiss()
                    } label: {
                        Text("Save").font(.headline).frame(maxWidth: .infinity, minHeight: 34)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(amount <= 0)
                }
                .padding(16)
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

    var body: some View {
        let typed = entry.isEmpty ? "0" : entry
        let shown = Money.isWhole(Money.decimal(typed) ?? 0) && !typed.contains(".")
            ? Money.format(Money.decimal(typed) ?? 0, code: code, symbol: symbol)
            : Money.symbol(for: code, custom: symbol) + typed
        Text((entry.isEmpty ? "" : sign) + shown)
            .font(Theme.balance(56))
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
                        if key == "del" { Image(systemName: "delete.left") } else { Text(key) }
                    }
                    .font(.system(.title, design: .rounded, weight: .medium))
                    .foregroundStyle(.primary)
                    .frame(maxWidth: .infinity, minHeight: 54)
                    .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(PressableStyle())
                .opacity(hidden ? 0 : 1)
                .disabled(hidden)
                .accessibilityLabel(key == "del" ? "Delete" : key)
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
                        Label("Categories", systemImage: "lock.fill")
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(Theme.surface, in: Capsule())
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
                            .foregroundStyle(selected ? Theme.emerald : .primary)
                            .background(selected ? Theme.emerald.opacity(0.18) : Theme.surface, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .opacity(locked ? 0.45 : 1)
                }
            }
        }
    }
}
