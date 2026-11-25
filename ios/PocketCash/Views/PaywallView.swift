import SwiftUI

/// What Pro adds (in the order of docs/spec.md section 5), the App Store's localized price, Buy and Restore.
/// Never shown before the first run is done.
struct PaywallView: View {
    @EnvironmentObject private var pro: ProStore
    @Environment(\.dismiss) private var dismiss
    var showsClose = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("PocketCash Pro").font(.largeTitle.weight(.bold))
                    Text("One purchase. Yours forever. No subscription.")
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 8)

                VStack(alignment: .leading, spacing: 0) {
                    FeatureRow(symbol: "envelope", title: "Envelopes and savings goals",
                               detail: "Give any wallet a target, watch it fill, and split cash into envelopes in one step.")
                    FeatureRow(symbol: "chart.bar", title: "Insights",
                               detail: "How long each ATM withdrawal lasts, your burn rate, when the cash runs out, and where it goes.")
                    FeatureRow(symbol: "wallet.pass", title: "Multiple wallets",
                               detail: "Coin pouch, emergency stash, a travel wallet in any currency, with moves between them.")
                    FeatureRow(symbol: "bolt", title: "Quick spend everywhere",
                               detail: "Lock Screen widgets and Control Center buttons for the amount you spend most.")
                    FeatureRow(symbol: "arrow.uturn.backward", title: "Change calculator",
                               detail: "Paid with a big bill? Enter what you got back and PocketCash saves the difference.")
                    FeatureRow(symbol: "calendar", title: "Daily budget",
                               detail: "See what's left today in the app and on the medium widget.")
                    FeatureRow(symbol: "tag", title: "Categories",
                               detail: "Tag spending after the fact with one tap, without slowing down the first tap.")
                    FeatureRow(symbol: "slider.horizontal.3", title: "Custom buttons",
                               detail: "Your exact amounts, like −45 for your commute or −15 for parking.")
                    FeatureRow(symbol: "faceid", title: "App lock and privacy mode",
                               detail: "Lock PocketCash with Face ID or your passcode, and hide amounts on widgets.")
                    FeatureRow(symbol: "bell", title: "Low-cash reminder",
                               detail: "A nudge when a wallet drops below the amount you pick.")
                    FeatureRow(symbol: "clock.arrow.circlepath", title: "Full history and CSV export",
                               detail: "Every cash move beyond 7 days, exportable to any spreadsheet.")
                }

                VStack(spacing: 12) {
                    if pro.isPro {
                        Label("Pro is unlocked. Thank you!", systemImage: "checkmark.seal")
                            .font(.headline)
                    } else {
                        Button {
                            Task { await pro.buy() }
                        } label: {
                            Group {
                                if pro.purchasing {
                                    ProgressView()
                                } else if let product = pro.product {
                                    let price = product.displayPrice
                                    Text("Unlock for \(price)")
                                } else {
                                    Text("Unlock Pro")
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
                            .foregroundStyle(.primary)
                    }
                    if let message = pro.message {
                        Text(verbatim: message).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }
                }
                .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
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
    let title: LocalizedStringKey
    let detail: LocalizedStringKey

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 18, weight: .regular))
                .foregroundStyle(.primary)
                .frame(width: 26)
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body.weight(.semibold))
                Text(detail).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hairline()
    }
}
