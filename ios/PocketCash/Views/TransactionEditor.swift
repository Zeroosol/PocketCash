import SwiftUI

/// Edit a transaction's amount, note, category (Pro) and time, or delete it.
struct TransactionEditor: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore
    @Environment(\.dismiss) private var dismiss
    let transactionID: UUID

    @State private var amountText = ""
    @State private var note = ""
    @State private var tag: String?
    @State private var date = Date()
    @State private var loaded = false
    @State private var showPaywall = false

    var body: some View {
        NavigationStack {
            if let tx = model.transaction(transactionID), let wallet = tx.wallet {
                Form {
                    Section {
                        HStack {
                            Text(tx.amount < 0 ? Money.minus : "+").font(.title2.weight(.bold))
                            TextField("Amount", text: $amountText)
                                .keyboardType(wallet.allowsFractions ? .decimalPad : .numberPad)
                                .font(.title2.weight(.bold).monospacedDigit())
                        }
                        LabeledContent("Type", value: tx.kind == .adjust ? tx.title : tx.kind.label)
                        DatePicker("When", selection: $date)
                    }
                    Section("Note") {
                        TextField("Note", text: $note)
                    }
                    if tx.amount < 0 && tx.kind != .transfer {
                        Section {
                            CategoryChips(selection: $tag, locked: !pro.isPro) { showPaywall = true }
                                .listRowInsets(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))
                        } header: {
                            Text("Category")
                        }
                    }
                    Section {
                        Button("Delete", role: .destructive) {
                            model.delete(tx)
                            dismiss()
                        }
                    }
                }
                .navigationTitle(tx.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") { save(tx) }
                            .disabled((Money.decimal(normalized) ?? 0) <= 0)
                    }
                }
                .onAppear {
                    guard !loaded else { return }
                    loaded = true
                    let magnitude = tx.amount < 0 ? -tx.amount : tx.amount
                    amountText = Money.string(magnitude)
                    note = tx.note ?? ""
                    tag = tx.tag
                    date = tx.timestamp
                }
            } else {
                ContentUnavailableView("This entry was deleted", systemImage: "trash")
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            }
        }
        .sheet(isPresented: $showPaywall) { NavigationStack { PaywallView(showsClose: true) } }
    }

    /// Accepts "12,50" as well as "12.50".
    private var normalized: String { amountText.replacingOccurrences(of: ",", with: ".") }

    private func save(_ tx: CashTransaction) {
        guard let magnitude = Money.decimal(normalized), magnitude > 0 else { return }
        tx.amount = tx.amount < 0 ? -magnitude : magnitude
        tx.note = note.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        if pro.isPro { tx.tag = tag }
        tx.timestamp = date
        model.saveEdits()
        dismiss()
    }
}

/// Move cash between two wallets with the same currency (Pro).
struct TransferSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var targetID: UUID?
    @State private var entry = ""

    var body: some View {
        NavigationStack {
            if let source = model.wallet {
                let targets = model.wallets.filter { $0.id != source.id && $0.currencyCode == source.currencyCode }
                let amount = Money.decimal(entry) ?? 0
                VStack(spacing: 14) {
                    if targets.isEmpty {
                        ContentUnavailableView("No other \(source.currencyCode) wallet",
                                               systemImage: "arrow.left.arrow.right",
                                               description: Text("Add another wallet with the same currency in Settings."))
                    } else {
                        Picker("To", selection: $targetID) {
                            ForEach(targets) { Text($0.name).tag(Optional($0.id)) }
                        }
                        .pickerStyle(.segmented)
                        AmountDisplay(entry: entry, wallet: source)
                        Text("From \(source.name) · \(source.format(source.balance)) available")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        Spacer(minLength: 0)
                        KeypadView(entry: $entry, fractionDigits: Money.fractionDigits(for: source.currencyCode))
                        Button {
                            if let target = targets.first(where: { $0.id == targetID }) {
                                model.transfer(amount, to: target)
                            }
                            dismiss()
                        } label: {
                            Text("Move cash").font(.headline).frame(maxWidth: .infinity, minHeight: 34)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .disabled(amount <= 0 || targetID == nil)
                    }
                }
                .padding(16)
                .background(Theme.background)
                .navigationTitle("Move cash")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
                .onAppear { if targetID == nil { targetID = targets.first?.id } }
            }
        }
    }
}
