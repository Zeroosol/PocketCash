import AppIntents

/// Siri and Shortcuts phrases (docs/spec.md section 8).
struct PocketCashShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: SpendCashIntent(),
                    phrases: ["Spend cash in \(.applicationName)", "Log cash spending in \(.applicationName)"],
                    shortTitle: "Spend Cash", systemImageName: "banknote")
        AppShortcut(intent: AddCashIntent(),
                    phrases: ["Add cash in \(.applicationName)", "I withdrew cash in \(.applicationName)"],
                    shortTitle: "Add Cash", systemImageName: "plus.circle")
        AppShortcut(intent: QuickSpendIntent(),
                    phrases: ["Quick spend in \(.applicationName)"],
                    shortTitle: "Quick Spend", systemImageName: "bolt.fill")
        AppShortcut(intent: CashBalanceIntent(),
                    phrases: ["Cash balance in \(.applicationName)", "How much cash do I have in \(.applicationName)"],
                    shortTitle: "Cash Balance", systemImageName: "wallet.pass")
    }
}
