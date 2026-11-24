import SwiftUI

/// "Count my cash": count bills and coins (or type the total) and save the difference as an adjustment.
struct CountCashSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var countBills = true
    @State private var counts: [Decimal: Int] = [:]
    @State private var entry = ""

    var body: some View {
        NavigationStack {
            if let wallet = model.wallet {
                let balance = wallet.balance
                let counted = countBills
                    ? counts.reduce(Decimal(0)) { $0 + $1.key * Decimal($1.value) }
                    : (Money.decimal(entry) ?? 0)
                let difference = counted - balance
                let touched = countBills ? counts.values.contains { $0 > 0 } : !entry.isEmpty

                VStack(spacing: 12) {
                    VStack(spacing: 4) {
                        Text("How much is in your wallet?")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(.secondary)
                        Text(wallet.format(counted))
                            .font(Theme.balance(52))
                            .lineLimit(1)
                            .minimumScaleFactor(0.4)
                            .contentTransition(.numericText(value: Money.double(counted)))
                            .animation(.snappy, value: counted)
                        Group {
                            if !touched {
                                Text("PocketCash says \(wallet.format(balance))")
                            } else if difference == 0 {
                                Label("Matches PocketCash exactly", systemImage: "checkmark.circle.fill")
                                    .foregroundStyle(Theme.emerald)
                            } else if difference < 0 {
                                Text("\(wallet.format(-difference)) unrecorded spending")
                                    .foregroundStyle(.orange)
                            } else {
                                Text("\(wallet.format(difference)) more than PocketCash says")
                                    .foregroundStyle(Theme.emerald)
                            }
                        }
                        .font(.footnote.weight(.medium))
                        .monospacedDigit()
                    }
                    .padding(.top, 8)

                    Picker("Method", selection: $countBills) {
                        Text("Count bills & coins").tag(true)
                        Text("Type the total").tag(false)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 16)

                    if countBills {
                        List {
                            ForEach(wallet.defaults.denominations, id: \.self) { value in
                                DenominationRow(value: value, wallet: wallet, count: Binding(
                                    get: { counts[value] ?? 0 },
                                    set: { counts[value] = $0 }))
                            }
                        }
                        .listStyle(.insetGrouped)
                    } else {
                        Spacer(minLength: 0)
                        KeypadView(entry: $entry, fractionDigits: Money.fractionDigits(for: wallet.currencyCode))
                            .padding(.horizontal, 16)
                    }

                    Button {
                        model.reconcile(counted: counted)
                        dismiss()
                    } label: {
                        Text(difference == 0 || !touched ? "Done" : "Save count")
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 34)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(!touched)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
                }
                .background(Theme.background)
                .navigationTitle("Count my cash")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    if countBills && counts.values.contains(where: { $0 > 0 }) {
                        ToolbarItem(placement: .primaryAction) { Button("Clear") { counts = [:] } }
                    }
                }
            }
        }
    }
}

private struct DenominationRow: View {
    let value: Decimal
    let wallet: Wallet
    @Binding var count: Int

    var body: some View {
        HStack(spacing: 10) {
            Text(wallet.format(value))
                .font(.body.weight(.semibold))
                .monospacedDigit()
                .frame(minWidth: 70, alignment: .leading)
            Spacer(minLength: 4)
            if count > 0 {
                Text(wallet.format(value * Decimal(count)))
                    .font(.footnote)
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
            Button { if count > 0 { count -= 1 } } label: { Image(systemName: "minus.circle.fill") }
                .buttonStyle(.borderless)
                .foregroundStyle(count > 0 ? Color.primary : Color.secondary.opacity(0.4))
                .font(.title2)
            Text("\(count)")
                .font(.body.weight(.semibold))
                .monospacedDigit()
                .frame(minWidth: 28)
            Button { count += 1 } label: { Image(systemName: "plus.circle.fill") }
                .buttonStyle(.borderless)
                .foregroundStyle(Theme.emerald)
                .font(.title2)
        }
        .sensoryFeedback(.selection, trigger: count)
    }
}
