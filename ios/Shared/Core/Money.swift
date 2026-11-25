import Foundation

/// Transaction kinds (docs/spec.md section 2). Translated labels live in Shared/Labels.swift (they need the app's
/// string tables, which Core, built and tested on Linux too, doesn't have).
enum CashKind: String, Codable, CaseIterable, Sendable {
    case spend
    case `in`
    case atm
    case adjust
    case transfer

    var symbol: String {
        switch self {
        case .spend: return "banknote"
        case .in: return "arrow.down.circle.fill"
        case .atm: return "building.columns.fill"
        case .adjust: return "scalemass.fill"
        case .transfer: return "arrow.left.arrow.right"
        }
    }
}

/// Spending categories (docs/spec.md section 6). Stored by key, so renaming a label never breaks old data.
enum CashCategory: String, CaseIterable, Identifiable, Sendable {
    case food, transit, parking, groceries, errand, tip, bills, fun, other

    var id: String { rawValue }

    var symbol: String {
        switch self {
        case .food: return "fork.knife"
        case .transit: return "tram.fill"
        case .parking: return "parkingsign.circle.fill"
        case .groceries: return "cart.fill"
        case .errand: return "bag.fill"
        case .tip: return "hand.thumbsup.fill"
        case .bills: return "doc.text.fill"
        case .fun: return "sparkles"
        case .other: return "ellipsis.circle.fill"
        }
    }
}

/// Exact decimal money helpers and the display rules from docs/spec.md section 3.
enum Money {
    static let posix = Locale(identifier: "en_US_POSIX")
    /// U+2212, the typographic minus used on buttons and signed amounts.
    static let minus = "\u{2212}"

    static func decimal(_ string: String) -> Decimal? {
        Decimal(string: string.trimmingCharacters(in: .whitespaces), locale: posix)
    }

    /// Plain machine form: "1780", "141.75", "-50". Used for storage, CSV and the widget snapshot.
    static func string(_ value: Decimal) -> String {
        NSDecimalNumber(decimal: value).description(withLocale: posix)
    }

    static func double(_ value: Decimal) -> Double {
        NSDecimalNumber(decimal: value).doubleValue
    }

    static func rounded(_ value: Decimal, scale: Int) -> Decimal {
        var input = value, result = Decimal()
        NSDecimalRound(&result, &input, scale, .plain)
        return result
    }

    static func isWhole(_ value: Decimal) -> Bool {
        rounded(value, scale: 0) == value
    }

    /// Decimal places the currency normally uses (2 for USD, 0 for JPY).
    static func fractionDigits(for code: String) -> Int {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencyCode = code
        return f.maximumFractionDigits
    }

    /// The wallet's custom symbol, else the currency's symbol in this locale ("₱", "$", "€").
    static func symbol(for code: String, custom: String? = nil, locale: Locale = .current) -> String {
        if let custom, !custom.isEmpty { return custom }
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.locale = locale
        f.currencyCode = code
        return f.currencySymbol ?? code
    }

    /// "₱680", "$12.50", "¥1,200". Fraction digits only when the amount isn't whole.
    /// `signed` adds "+" or "−"; otherwise negative amounts are shown with "−" and positives bare.
    static func format(_ amount: Decimal, code: String, symbol: String? = nil, signed: Bool = false,
                       locale: Locale = .current) -> String {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.locale = locale
        f.currencyCode = code
        if let symbol, !symbol.isEmpty { f.currencySymbol = symbol }
        let digits = f.maximumFractionDigits
        let magnitude = amount < 0 ? -amount : amount
        if isWhole(magnitude) {
            f.minimumFractionDigits = 0
            f.maximumFractionDigits = 0
        } else {
            f.minimumFractionDigits = digits
            f.maximumFractionDigits = max(digits, 2)
        }
        let body = f.string(from: NSDecimalNumber(decimal: magnitude)) ?? "\(magnitude)"
        if amount < 0 { return minus + body }
        if signed && amount > 0 { return "+" + body }
        return body
    }

    /// Compact unsigned label: "1k", "100", "1.5k", "0.25", "1M" (docs/spec.md section 3).
    static func compact(_ value: Decimal) -> String {
        let v = value < 0 ? -value : value
        func short(_ scaled: Decimal, _ suffix: String) -> String {
            string(rounded(scaled, scale: 1)) + suffix
        }
        if v >= 1_000_000 { return short(v / 1_000_000, "M") }
        if v >= 1_000 { return short(v / 1_000, "k") }
        return string(v)
    }

    /// Button label: "+1k", "−50".
    static func buttonLabel(_ magnitude: Decimal, cashIn: Bool) -> String {
        (cashIn ? "+" : minus) + compact(magnitude)
    }
}
