import Foundation

/// Identifiers shared by the app and the widget extension (docs/spec.md section 1).
enum PocketCash {
    static let appGroupID = "group.com.patternsystems.pocketcash"
    static let walletWidgetKind = "PocketCashWidget"
    static let quickSpendWidgetKind = "PocketCashQuickSpend"
    static let quickSpendControlKind = "com.patternsystems.pocketcash.quickspend"
    static let keypadControlKind = "com.patternsystems.pocketcash.keypad"
    static let proProductID = "pocketcash.pro.lifetime"
    /// How long a widget's Undo chip stays, and how long after a tap the undo still works.
    static let widgetUndoSeconds: TimeInterval = 8
    static let undoWindowSeconds: TimeInterval = 120

    /// The App Group container, or Application Support when this build has no App Group (e.g. some xtool builds).
    /// The SwiftData store and the widget snapshot live here, at explicit URLs.
    static var dataDirectory: URL {
        if let group = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupID) {
            return group
        }
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        return support
    }
}

/// Global settings (docs/spec.md section 2), in the App Group's defaults so the widget extension sees them.
enum AppSettings {
    static let defaults: UserDefaults = UserDefaults(suiteName: PocketCash.appGroupID) ?? .standard

    enum Key {
        static let quickSpendAmount = "quickSpendAmount"
        static let quickSpendWalletID = "quickSpendWalletID"
        static let plusButtonsAreATM = "plusButtonsAreATM"
        static let privacyMode = "privacyMode"
        static let lowCashAlerts = "lowCashAlerts"
        static let haptics = "haptics"
        static let appLock = "appLock"
        static let onboarded = "onboarded"
        static let isProCached = "isProCached"
        static let debugPro = "debugPro"
        static let selectedWalletID = "selectedWalletID"
        static let firstOpen = "firstOpen"
        static let softPaywallShown = "softPaywallShown"
        static let pendingRoute = "pendingRoute"
        static let lastOfferedActionID = "lastOfferedActionID"
    }

    private static func bool(_ key: String, default value: Bool) -> Bool {
        defaults.object(forKey: key) as? Bool ?? value
    }

    static var plusButtonsAreATM: Bool {
        get { bool(Key.plusButtonsAreATM, default: true) }
        set { defaults.set(newValue, forKey: Key.plusButtonsAreATM) }
    }
    static var privacyMode: Bool {
        get { bool(Key.privacyMode, default: false) }
        set { defaults.set(newValue, forKey: Key.privacyMode) }
    }
    static var lowCashAlerts: Bool {
        get { bool(Key.lowCashAlerts, default: false) }
        set { defaults.set(newValue, forKey: Key.lowCashAlerts) }
    }
    static var haptics: Bool {
        get { bool(Key.haptics, default: true) }
        set { defaults.set(newValue, forKey: Key.haptics) }
    }
    /// Lock the app with Face ID / Touch ID / the passcode (Pro, docs/spec.md section 12).
    static var appLock: Bool {
        get { bool(Key.appLock, default: false) }
        set { defaults.set(newValue, forKey: Key.appLock) }
    }
    static var onboarded: Bool {
        get { bool(Key.onboarded, default: false) }
        set { defaults.set(newValue, forKey: Key.onboarded) }
    }
    /// What StoreKit last said about the lifetime purchase. The widget reads this through the snapshot.
    static var isProCached: Bool {
        get { bool(Key.isProCached, default: false) }
        set { defaults.set(newValue, forKey: Key.isProCached) }
    }
    static var debugPro: Bool {
        get { bool(Key.debugPro, default: false) }
        set { defaults.set(newValue, forKey: Key.debugPro) }
    }

    /// Debug builds offer a switch that unlocks Pro without the App Store (docs/spec.md section 5).
    static var debugProAvailable: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    static var isPro: Bool { isProCached || (debugProAvailable && debugPro) }

    /// Privacy mode and the low-cash reminder are Pro: a Free install keeps the stored choice but ignores it.
    static var privacyModeActive: Bool { privacyMode && isPro }
    static var lowCashAlertsActive: Bool { lowCashAlerts && isPro }

    /// When Pro is lost (a refund), app lock and privacy mode switch off (docs/spec.md section 5).
    static func proWasLost() {
        appLock = false
        privacyMode = false
    }

    static var quickSpendAmount: Decimal? {
        get { defaults.string(forKey: Key.quickSpendAmount).flatMap(Money.decimal) }
        set { defaults.set(newValue.map(Money.string), forKey: Key.quickSpendAmount) }
    }
    static var quickSpendWalletID: UUID? {
        get { defaults.string(forKey: Key.quickSpendWalletID).flatMap(UUID.init(uuidString:)) }
        set { defaults.set(newValue?.uuidString, forKey: Key.quickSpendWalletID) }
    }
    static var selectedWalletID: UUID? {
        get { defaults.string(forKey: Key.selectedWalletID).flatMap(UUID.init(uuidString:)) }
        set { defaults.set(newValue?.uuidString, forKey: Key.selectedWalletID) }
    }
    static var firstOpen: Date {
        if let date = defaults.object(forKey: Key.firstOpen) as? Date { return date }
        let now = Date()
        defaults.set(now, forKey: Key.firstOpen)
        return now
    }
    static var softPaywallShown: Bool {
        get { bool(Key.softPaywallShown, default: false) }
        set { defaults.set(newValue, forKey: Key.softPaywallShown) }
    }
    /// A screen an intent asked the app to open ("keypad", "count"), consumed when the app becomes active.
    static var pendingRoute: String? {
        get { defaults.string(forKey: Key.pendingRoute) }
        set { defaults.set(newValue, forKey: Key.pendingRoute) }
    }
    static var lastOfferedActionID: String? {
        get { defaults.string(forKey: Key.lastOfferedActionID) }
        set { defaults.set(newValue, forKey: Key.lastOfferedActionID) }
    }
}
