import SwiftUI
import UIKit

/// Colors and type shared by the app and the widgets. Cash in is muted emerald, spending is monochrome.
enum Theme {
    static func dynamic(light: (Double, Double, Double), dark: (Double, Double, Double)) -> Color {
        Color(uiColor: UIColor { traits in
            let c = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: c.0, green: c.1, blue: c.2, alpha: 1)
        })
    }

    /// #0E9F6E in light mode, #34D399 in dark mode.
    static let emerald = dynamic(light: (0.055, 0.624, 0.431), dark: (0.204, 0.827, 0.600))
    static let background = Color(uiColor: .systemGroupedBackground)
    static let surface = Color(uiColor: .secondarySystemGroupedBackground)
    static let danger = Color(uiColor: .systemRed)
    static let corner: CGFloat = 18

    /// Tabular digits, so a changing balance never jitters.
    static func balance(_ size: CGFloat) -> Font {
        .system(size: size, weight: .bold, design: .rounded).monospacedDigit()
    }

    static func amountColor(_ amount: Decimal) -> Color {
        amount > 0 ? emerald : .primary
    }
}
