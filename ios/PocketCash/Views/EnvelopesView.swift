import SwiftUI

/// The Envelopes destination (Pro, docs/spec.md section 10): every wallet with its balance, and a progress bar for
/// the ones with a target. Free users see a blurred sample with an Unlock button.
struct EnvelopesScreen: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore

    var body: some View {
        NavigationStack {
            Group {
                if pro.isPro {
                    ScrollView { EnvelopesContent() }
                        .tracksNavBar(.envelopes)
                } else {
                    ProPreview(title: "Envelopes are part of Pro",
                               message: "Give a wallet a target, watch it fill up, and split cash into envelopes in one step.") {
                        EnvelopesSample(code: model.wallet?.currencyCode ?? CurrencyDefaults.localCode)
                    }
                }
            }
            .background(Theme.background)
            .navigationTitle("Envelopes")
            .navigationBarTitleDisplayMode(.large)
        }
    }
}

private struct EnvelopesContent: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            if model.wallets.count > 1 {
                Button { model.route = .fill } label: {
                    Label("Fill envelopes", systemImage: "arrow.down.to.line")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 34)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }

            VStack(spacing: 0) {
                ForEach(model.wallets) { wallet in
                    Button {
                        model.selectedWalletID = wallet.id
                        model.destination = .home
                    } label: {
                        EnvelopeRow(name: wallet.name, balance: wallet.balance, target: wallet.target,
                                    format: { wallet.format($0) })
                            .padding(.vertical, 16)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .hairline()
                }
            }

            VStack(alignment: .leading, spacing: 12) {
                if !model.wallets.contains(where: \.hasTarget) {
                    Text("Give a wallet a target in Settings → Wallets to see its progress here.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Button { model.route = .newWallet } label: {
                    Label("Add an envelope", systemImage: "plus")
                        .font(.subheadline.weight(.medium))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
    }
}

/// A wallet's name and balance, plus the progress bar and "₱2,300 of ₱5,000" when it has a target.
struct EnvelopeRow: View {
    let name: String
    let balance: Decimal
    let target: Decimal?
    let format: (Decimal) -> String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: name).font(.body.weight(.medium)).lineLimit(1)
                Spacer(minLength: 8)
                Text(verbatim: format(balance))
                    .font(.body.monospacedDigit())
                    .foregroundStyle(balance < 0 ? Theme.danger : .primary)
            }
            if let progress = Envelope.progress(balance: balance, target: target), let target {
                ProgressBar(value: Money.double(progress))
                if Envelope.isReached(balance: balance, target: target) {
                    Text("Goal reached")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    let have = format(max(balance, 0))
                    let goal = format(target)
                    Text("\(have) of \(goal)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

struct ProgressBar: View {
    let value: Double

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.surface)
                Capsule().fill(Theme.emerald)
                    .frame(width: max(proxy.size.width * min(max(value, 0), 1), value > 0 ? 6 : 0))
            }
        }
        .frame(height: 6)
        .animation(.snappy, value: value)
    }
}

/// What Free users see, blurred, behind the Unlock button.
private struct EnvelopesSample: View {
    let code: String

    var body: some View {
        let unit = DefaultPresets.forCurrency(code).add.first ?? 100
        let rows: [(String, Decimal, Decimal?)] = [
            (String(localized: "Rent"), unit * 3, unit * 5),
            (String(localized: "Food"), unit * 2, unit * 2),
            (String(localized: "Savings"), unit, unit * 10),
        ]
        VStack(spacing: 0) {
            ForEach(rows, id: \.0) { row in
                EnvelopeRow(name: row.0, balance: row.1, target: row.2, format: { Money.format($0, code: code) })
                    .padding(.vertical, 16)
                    .hairline()
            }
            Spacer()
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
    }
}

/// Fill envelopes (docs/spec.md section 10): move cash from one wallet into every other wallet with the same
/// currency, starting from the suggested fill, in one store write.
struct FillSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var sourceID: UUID?
    @State private var amounts: [UUID: String] = [:]

    var body: some View {
        NavigationStack {
            if let source = model.wallets.first(where: { $0.id == sourceID }) ?? model.wallets.first {
                let targets = targets(for: source)
                let moves = targets.map { ($0, parsed(amounts[$0.id])) }
                let total = moves.reduce(Decimal(0)) { $0 + $1.1 }
                let tooMuch = total > source.balance
                Form {
                    Section {
                        Picker("From", selection: $sourceID) {
                            ForEach(model.wallets) { wallet in
                                Text(verbatim: wallet.name).tag(Optional(wallet.id))
                            }
                        }
                        LabeledContent("Available", value: source.format(source.balance))
                    }
                    if targets.isEmpty {
                        Section {
                            let code = source.currencyCode
                            Text("No other \(code) wallet to fill. Add one in Settings → Wallets.")
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        Section {
                            ForEach(targets) { wallet in
                                FillRow(wallet: wallet, text: Binding(
                                    get: { amounts[wallet.id] ?? "" },
                                    set: { amounts[wallet.id] = $0 }))
                            }
                        } footer: {
                            let moving = source.format(total)
                            let stays = source.format(source.balance - total)
                            let name = source.name
                            if tooMuch {
                                Text("That's more than \(name) has.").foregroundStyle(Theme.danger)
                            } else {
                                Text("Moving \(moving) · \(stays) stays in \(name)").monospacedDigit()
                            }
                        }
                        Section {
                            Button("Suggested amounts") { suggest(source) }
                            Button("Clear all") { amounts = [:] }
                        }
                    }
                }
                .scrollContentBackground(.hidden)
                .background(Theme.background)
                .navigationTitle("Fill envelopes")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") {
                            model.fill(from: source, moves: moves.map { (wallet: $0.0, amount: $0.1) })
                            dismiss()
                        }
                        .disabled(total <= 0 || tooMuch)
                    }
                }
                .onAppear {
                    if sourceID == nil {
                        sourceID = model.wallets.first?.id
                        suggest(source)
                    }
                }
                .onChange(of: sourceID) { _, _ in
                    if let newSource = model.wallets.first(where: { $0.id == sourceID }) { suggest(newSource) }
                }
            }
        }
    }

    /// Every other wallet with the source's currency, in sort order.
    private func targets(for source: Wallet) -> [Wallet] {
        model.wallets.filter { $0.id != source.id && $0.currencyCode == source.currencyCode }
    }

    private func suggest(_ source: Wallet) {
        let list = targets(for: source)
        let fill = Envelope.suggestedFill(sourceBalance: source.balance,
                                          envelopes: list.map { (balance: $0.balance, target: $0.target) })
        amounts = Dictionary(uniqueKeysWithValues: zip(list.map(\.id), fill.map { $0 > 0 ? Money.string($0) : "" }))
    }

    private func parsed(_ text: String?) -> Decimal {
        guard let text, let value = Money.decimal(text.replacingOccurrences(of: ",", with: ".")), value > 0 else { return 0 }
        return value
    }
}

private struct FillRow: View {
    let wallet: Wallet
    @Binding var text: String

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: wallet.name)
                if wallet.hasTarget, let target = wallet.target {
                    if wallet.shortBy > 0 {
                        let short = wallet.format(wallet.shortBy)
                        Text("Short by \(short)").font(.caption).foregroundStyle(.secondary)
                    } else {
                        let goal = wallet.format(target)
                        Text("Goal of \(goal) reached").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            Spacer(minLength: 8)
            Text(verbatim: Money.symbol(for: wallet.currencyCode, custom: wallet.symbol)).foregroundStyle(.secondary)
            TextField("0", text: $text)
                .keyboardType(wallet.allowsFractions ? .decimalPad : .numberPad)
                .multilineTextAlignment(.trailing)
                .monospacedDigit()
                .frame(maxWidth: 110)
        }
    }
}
