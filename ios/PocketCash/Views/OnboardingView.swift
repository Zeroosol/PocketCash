import SwiftUI

/// First run only: currency (or restore a backup), the cash in your wallet now, then how to add the widget.
struct OnboardingView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage(AppSettings.Key.onboarded, store: AppSettings.defaults) private var onboarded = false
    @State private var step = 0
    @State private var currency = CurrencyDefaults.localCode
    @State private var entry = ""
    @State private var restoring = false

    var body: some View {
        NavigationStack {
            Group {
                if step == 0 && model.wallets.isEmpty {
                    welcome
                } else if step <= 1 && model.wallets.isEmpty {
                    startingCash
                } else {
                    widgetTip
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.background)
            .animation(.snappy, value: step)
        }
        .restoresBackup(isPresented: $restoring)
    }

    private var welcome: some View {
        VStack(spacing: 18) {
            Spacer()
            Image(systemName: "wallet.pass")
                .font(.system(size: 64, weight: .light))
                .foregroundStyle(Theme.emerald)
            Text("Your cash, finally tracked")
                .font(.largeTitle.weight(.bold))
                .multilineTextAlignment(.center)
            Text("Banking apps log your cards. PocketCash puts your physical wallet on your Home Screen: tap −50 when you pay, and you're done.")
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Spacer()
            NavigationLink {
                CurrencyPicker(selection: $currency)
            } label: {
                HStack {
                    Text("Currency")
                    Spacer()
                    Text(verbatim: "\(currency) · \(CurrencyDefaults.name(currency))").foregroundStyle(.secondary).lineLimit(1)
                    Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
                }
                .padding(.vertical, 14)
                .hairline(.top)
                .hairline()
            }
            .foregroundStyle(.primary)
            Button { step = 1 } label: {
                Text("Continue").font(.headline).frame(maxWidth: .infinity, minHeight: 34)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            Button { restoring = true } label: {
                Text("Restore from a backup file")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
        }
    }

    private var startingCash: some View {
        VStack(spacing: 16) {
            Text("How much cash is in your wallet right now?")
                .font(.title2.weight(.bold))
                .multilineTextAlignment(.center)
                .padding(.top, 12)
            Text("Count it roughly. You can always fix it later by tapping the balance.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            AmountDisplay(entry: entry, code: currency)
            Spacer(minLength: 0)
            KeypadView(entry: $entry, fractionDigits: Money.fractionDigits(for: currency))
            Button {
                model.createWallet(name: String(localized: "Main Wallet"), currencyCode: currency, symbol: nil,
                                   startingCash: Money.decimal(entry) ?? 0)
                step = 2
            } label: {
                Group {
                    if entry.isEmpty { Text("Start at zero") } else { Text("Start tracking") }
                }
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 34)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
    }

    private var widgetTip: some View {
        VStack(alignment: .leading, spacing: 18) {
            Spacer()
            Image(systemName: "apps.iphone.badge.plus")
                .font(.system(size: 56, weight: .light))
                .foregroundStyle(.primary)
                .frame(maxWidth: .infinity)
            Text("Put your wallet on your Home Screen")
                .font(.title2.weight(.bold))
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
            VStack(alignment: .leading, spacing: 14) {
                tip("1.circle", Text("Touch and hold an empty spot on your Home Screen."))
                tip("2.circle", Text("Tap Edit, then Add Widget."))
                tip("3.circle", Text("Search PocketCash and pick the medium size."))
                tip("bolt.circle", Text("With Pro, add the Quick Spend control to Control Center or your Lock Screen for your commute fare."))
            }
            Spacer()
            Button { onboarded = true } label: {
                Text("Done").font(.headline).frame(maxWidth: .infinity, minHeight: 34)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
    }

    private func tip(_ symbol: String, _ text: Text) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol).font(.title3.weight(.light)).foregroundStyle(.secondary)
            text.font(.body)
        }
    }
}
