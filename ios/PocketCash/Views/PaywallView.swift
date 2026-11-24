import SwiftUI

/// What Pro adds, the App Store's localized price, Buy and Restore. Never shown before the first run is done.
struct PaywallView: View {
    @EnvironmentObject private var pro: ProStore
    @Environment(\.dismiss) private var dismiss
    var showsClose = false

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Image(systemName: "wallet.pass.fill")
                    .font(.system(size: 56))
                    .foregroundStyle(Theme.emerald)
                    .padding(.top, 8)
                VStack(spacing: 6) {
                    Text("PocketCash Pro").font(.largeTitle.bold())
                    Text("One purchase. Yours forever. No subscription.")
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                VStack(alignment: .leading, spacing: 16) {
                    FeatureRow(symbol: "slider.horizontal.3", title: "Your own buttons",
                               detail: "Exact bills and coins, like −45 for your commute or −15 for parking.")
                    FeatureRow(symbol: "wallet.pass", title: "Multiple wallets",
                               detail: "Main wallet, coin pouch, emergency stash, a travel wallet in any currency.")
                    FeatureRow(symbol: "flame", title: "ATM cycles & burn rate",
                               detail: "How long each withdrawal lasts, your daily burn, and when the cash runs out.")
                    FeatureRow(symbol: "tag", title: "1-tap categories",
                               detail: "Tag spending after the fact, then see where your cash goes.")
                    FeatureRow(symbol: "clock.arrow.circlepath", title: "Full history & CSV export",
                               detail: "Every cash move beyond 7 days, exportable to any spreadsheet.")
                }
                .padding(18)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))

                if pro.isPro {
                    Label("Pro is unlocked. Thank you!", systemImage: "checkmark.seal.fill")
                        .font(.headline)
                        .foregroundStyle(Theme.emerald)
                } else {
                    Button {
                        Task { await pro.buy() }
                    } label: {
                        Group {
                            if pro.purchasing {
                                ProgressView()
                            } else {
                                Text(pro.product.map { "Unlock for \($0.displayPrice)" } ?? "Unlock Pro")
                            }
                        }
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 36)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(pro.product == nil || pro.purchasing)

                    if pro.loadState == .unavailable {
                        Text("The App Store isn't reachable right now. Try again later.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    Button("Restore purchase") { Task { await pro.restore() } }
                        .font(.subheadline)
                }
                if let message = pro.message {
                    Text(message).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }
            }
            .padding(20)
        }
        .background(Theme.background)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if showsClose {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
        }
        .task { if pro.product == nil { await pro.refresh() } }
    }
}

private struct FeatureRow: View {
    let symbol: String
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(Theme.emerald)
                .frame(width: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(detail).font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
}
