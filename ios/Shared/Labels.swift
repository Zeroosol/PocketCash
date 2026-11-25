import Foundation

// Translated names for the Core enums. They live outside Shared/Core because Core is also built and tested on Linux,
// without the app's Localizable.strings tables (docs/spec.md section 13).

extension CashKind {
    var label: String {
        switch self {
        case .spend: return String(localized: "Spent")
        case .in: return String(localized: "Cash in")
        case .atm: return String(localized: "ATM withdrawal")
        case .adjust: return String(localized: "Cash count")
        case .transfer: return String(localized: "Transfer")
        }
    }
}

extension CashCategory {
    var label: String {
        switch self {
        case .food: return String(localized: "Food & drinks")
        case .transit: return String(localized: "Transit")
        case .parking: return String(localized: "Parking")
        case .groceries: return String(localized: "Groceries")
        case .errand: return String(localized: "Errands")
        case .tip: return String(localized: "Tips")
        case .bills: return String(localized: "Bills")
        case .fun: return String(localized: "Fun")
        case .other: return String(localized: "Other")
        }
    }
}
