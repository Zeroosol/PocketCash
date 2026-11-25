import SwiftUI
import UIKit

/// Colors and type shared by the app and the widgets (docs/spec.md section 7, "minimal"): one accent, emerald, only
/// for cash in, progress and the primary button. Everything else is monochrome on a plain background, separated by
/// space and hairlines rather than cards and shadows.
enum Theme {
    static func dynamic(light: (Double, Double, Double), dark: (Double, Double, Double)) -> Color {
        Color(uiColor: UIColor { traits in
            let c = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: c.0, green: c.1, blue: c.2, alpha: 1)
        })
    }

    /// #0E9F6E in light mode, #34D399 in dark mode.
    static let emerald = dynamic(light: (0.055, 0.624, 0.431), dark: (0.204, 0.827, 0.600))
    static let background = Color(uiColor: .systemBackground)
    /// A barely-there fill for touch targets (keys, chips, spend buttons).
    static let surface = Color.primary.opacity(0.05)
    static let hairline = Color.primary.opacity(0.12)
    static let danger = Color(uiColor: .systemRed)
    static let corner: CGFloat = 16

    /// Tabular digits, so a changing balance never jitters.
    static func balance(_ size: CGFloat) -> Font {
        .system(size: size, weight: .semibold, design: .rounded).monospacedDigit()
    }

    static func amountColor(_ amount: Decimal) -> Color {
        amount > 0 ? emerald : .primary
    }
}

/// A one-pixel line, the minimal style's only separator.
struct Hairline: View {
    @Environment(\.displayScale) private var scale

    var body: some View {
        Rectangle().fill(Theme.hairline).frame(height: 1 / max(scale, 1))
    }
}

extension View {
    /// A hairline under (or over) a row or group, instead of a card.
    func hairline(_ edge: VerticalEdge = .bottom) -> some View {
        overlay(alignment: edge == .bottom ? .bottom : .top) { Hairline() }
    }
}
