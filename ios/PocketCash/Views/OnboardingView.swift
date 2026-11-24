import SwiftUI

/// First run only: currency, the cash in your wallet now, then how to add the widget.
struct OnboardingView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage(AppSettings.Key.onboarded, store: AppSettings.defaults) private var onboarded = false
    @State private var step = 0
    @State private var currency = CurrencyDefaults.localCode
    @State private var entry = ""

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
    }

    private var welcome: some View {
        VStack(spacing: 18) {
            Spacer()
            Image(systemName: "wallet.pass.fill")
                .font(.system(size: 72))
                .foregroundStyle(Theme.emerald)
            Text("Your cash, finally tracked")
                .font(.largeTitle.bold())
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
                    Text("\(currency) · \(CurrencyDefaults.name(currency))").foregroundStyle(.secondary).lineLimit(1)
                    Image(systemName: "chevron.right").font(.caption.weight(.bold)).foregroundStyle(.tertiary)
                }
                .padding(16)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .foregroundStyle(.primary)
            Button { step = 1 } label: {
                Text("Continue").font(.headline).frame(maxWidth: .infinity, minHeight: 34)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
    }

    private var startingCash: some View {
        VStack(spacing: 16) {
            Text("How much cash is in your wallet right now?")
                .font(.title2.bold())
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
                model.createWallet(name: "Main Wallet", currencyCode: currency, symbol: nil,
                                   startingCash: Money.decimal(entry) ?? 0)
                step = 2
            } label: {
                Text(entry.isEmpty ? "Start at zero" : "Start tracking").font(.headline).frame(maxWidth: .infinity, minHeight: 34)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
    }

    private var widgetTip: some View {
        VStack(alignment: .leading, spacing: 18) {
            Spacer()
            Image(systemName: "apps.iphone.badge.plus")
                .font(.system(size: 60))
                .foregroundStyle(Theme.emerald)
                .frame(maxWidth: .infinity)
            Text("Put your wallet on your Home Screen")
                .font(.title2.bold())
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
            VStack(alignment: .leading, spacing: 12) {
                tip("1.circle.fill", "Touch and hold an empty spot on your Home Screen.")
                tip("2.circle.fill", "Tap Edit, then Add Widget.")
                tip("3.circle.fill", "Search PocketCash and pick the medium size.")
                tip("bolt.circle.fill", "For your commute fare, add the Quick Spend control to Control Center or your Lock Screen.")
            }
            Spacer()
            Button { onboarded = true } label: {
                Text("Done").font(.headline).frame(maxWidth: .infinity, minHeight: 34)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
    }

    private func tip(_ symbol: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol).font(.title3).foregroundStyle(Theme.emerald)
            Text(text).font(.body)
        }
    }
}
