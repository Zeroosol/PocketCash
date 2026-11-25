import AppIntents
import SwiftUI
import WidgetKit

/// Control Center / Lock Screen / Action button: subtract the quick-spend amount in one tap (iOS 18).
struct QuickSpendControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: PocketCash.quickSpendControlKind) {
            ControlWidgetButton(action: QuickSpendIntent()) {
                if ControlsAccess.isPro {
                    Label(Self.title, systemImage: "banknote")
                } else {
                    Label("PocketCash Pro", systemImage: "lock.fill")
                }
            }
        }
        .displayName("Quick Spend")
        .description("Subtracts your most common cash expense, like your commute fare.")
    }

    private static var title: String {
        guard let snapshot = WidgetSnapshot.load(), let quick = snapshot.quickSpend,
              let wallet = snapshot.wallets.first(where: { $0.id == quick.walletID }) else {
            return String(localized: "Quick Spend")
        }
        let amount = wallet.format(quick.value)
        return String(localized: "Spend \(amount)")
    }
}

/// Opens the app straight on the keypad for a custom amount.
struct OpenKeypadControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: PocketCash.keypadControlKind) {
            ControlWidgetButton(action: OpenKeypadIntent()) {
                if ControlsAccess.isPro {
                    Label("Cash Keypad", systemImage: "number.square")
                } else {
                    Label("PocketCash Pro", systemImage: "lock.fill")
                }
            }
        }
        .displayName("Cash Keypad")
        .description("Opens PocketCash on its keypad to log a custom amount.")
    }
}

/// Control Center controls are Pro (docs/spec.md section 5). Free installs see "PocketCash Pro" with a lock, and a tap
/// opens the paywall instead of spending (see QuickSpendIntent and OpenKeypadIntent).
enum ControlsAccess {
    static var isPro: Bool { WidgetSnapshot.load()?.isPro ?? AppSettings.isPro }
}
