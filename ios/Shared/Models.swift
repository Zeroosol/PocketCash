import Foundation
import SwiftData

// SwiftData models (docs/spec.md section 2). Keep each class body to stored properties and init only, and never
// declare a local `var` inside them: tools/expand_models.js rewrites every `var` line into SwiftData's @Model
// expansion for the xtool build, which has no SwiftData macro plugin. Computed helpers live in ModelHelpers.swift.

#if !SWIFT_PACKAGE
@Model
final class Wallet {
    @Attribute(.unique) var id: UUID
    var name: String
    var currencyCode: String
    var symbol: String?
    var addPresets: [Decimal]
    var spendPresets: [Decimal]
    var dailyBudget: Decimal?
    var lowCashThreshold: Decimal?
    /// Envelope target or savings goal (Pro, docs/spec.md section 10). Optional, so older stores migrate on their own.
    var target: Decimal?
    var sortOrder: Int
    var createdAt: Date
    @Relationship(deleteRule: .cascade, inverse: \CashTransaction.wallet) var transactions: [CashTransaction] = []

    init(id: UUID = UUID(), name: String, currencyCode: String, symbol: String? = nil,
         addPresets: [Decimal], spendPresets: [Decimal], sortOrder: Int, createdAt: Date = .now) {
        self.id = id
        self.name = name
        self.currencyCode = currencyCode
        self.symbol = symbol
        self.addPresets = addPresets
        self.spendPresets = spendPresets
        self.dailyBudget = nil
        self.lowCashThreshold = nil
        self.target = nil
        self.sortOrder = sortOrder
        self.createdAt = createdAt
    }
}

@Model
final class CashTransaction {
    @Attribute(.unique) var id: UUID
    var amount: Decimal
    var timestamp: Date
    var kindRaw: String
    var isATMWithdrawal: Bool
    var tag: String?
    var note: String?
    var transferID: UUID?
    var wallet: Wallet?

    init(id: UUID = UUID(), amount: Decimal, kindRaw: String, timestamp: Date = .now,
         tag: String? = nil, note: String? = nil, transferID: UUID? = nil) {
        self.id = id
        self.amount = amount
        self.timestamp = timestamp
        self.kindRaw = kindRaw
        self.isATMWithdrawal = kindRaw == "atm"
        self.tag = tag
        self.note = note
        self.transferID = transferID
    }
}
#endif
