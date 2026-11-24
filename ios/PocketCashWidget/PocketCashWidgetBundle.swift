import SwiftUI
import WidgetKit

@main
struct PocketCashWidgetBundle: WidgetBundle {
    var body: some Widget {
        WalletWidget()
        QuickSpendWidget()
        QuickSpendControl()
        OpenKeypadControl()
    }
}
