import AppIntents
import SwiftUI
import WidgetKit

/// Control Center / Lock Screen / Action button: subtract the quick-spend amount in one tap (iOS 18).
struct QuickSpendControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: PocketCash.quickSpendControlKind) {
            ControlWidgetButton(action: QuickSpendIntent()) {
                Label(Self.title, systemImage: "banknote")
            }
        }
        .displayName("Quick Spend")
        .description("Subtracts your most common cash expense, like your commute fare.")
    }

    private static var title: String {
        guard let snapshot = WidgetSnapshot.load(), let quick = snapshot.quickSpend,
              let wallet = snapshot.wallets.first(where: { $0.id == quick.walletID }) else { return "Quick Spend" }
        return "Spend \(wallet.format(quick.value))"
    }
}

/// Opens the app straight on the keypad for a custom amount.
struct OpenKeypadControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: PocketCash.keypadControlKind) {
            ControlWidgetButton(action: OpenKeypadIntent()) {
                Label("Cash Keypad", systemImage: "number.square.fill")
            }
        }
        .displayName("Cash Keypad")
        .description("Opens PocketCash on its keypad to log a custom amount.")
    }
}
