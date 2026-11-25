import Foundation

/// The cross-platform backup file (docs/spec.md section 11): one JSON file that the iPhone and Android apps both
/// read and write. Pure Foundation, so the codec is tested on Linux against spec/backup/example-v1.json.
struct Backup: Equatable, Sendable {
    static let format = "pocketcash-backup"
    static let version = 1

    struct Settings: Equatable, Sendable {
        var plusButtonsAreATM: Bool
        var privacyMode: Bool
        var lowCashAlerts: Bool
        var quickSpendAmount: Decimal?
        var quickSpendWalletID: UUID?

        init(plusButtonsAreATM: Bool = true, privacyMode: Bool = false, lowCashAlerts: Bool = false,
             quickSpendAmount: Decimal? = nil, quickSpendWalletID: UUID? = nil) {
            self.plusButtonsAreATM = plusButtonsAreATM
            self.privacyMode = privacyMode
            self.lowCashAlerts = lowCashAlerts
            self.quickSpendAmount = quickSpendAmount
            self.quickSpendWalletID = quickSpendWalletID
        }
    }

    struct Transaction: Equatable, Sendable, Identifiable {
        var id: UUID
        var amount: Decimal
        var kind: CashKind
        var timestamp: Date
        var tag: String?
        var note: String?
        var transferID: UUID?

        init(id: UUID, amount: Decimal, kind: CashKind, timestamp: Date, tag: String? = nil, note: String? = nil,
             transferID: UUID? = nil) {
            self.id = id
            self.amount = amount
            self.kind = kind
            self.timestamp = timestamp
            self.tag = tag
            self.note = note
            self.transferID = transferID
        }
    }

    struct Wallet: Equatable, Sendable, Identifiable {
        var id: UUID
        var name: String
        var currencyCode: String
        var symbol: String?
        var addPresets: [Decimal]
        var spendPresets: [Decimal]
        var dailyBudget: Decimal?
        var lowCashThreshold: Decimal?
        var target: Decimal?
        var sortOrder: Int
        var createdAt: Date
        var transactions: [Transaction]

        init(id: UUID, name: String, currencyCode: String, symbol: String? = nil, addPresets: [Decimal],
             spendPresets: [Decimal], dailyBudget: Decimal? = nil, lowCashThreshold: Decimal? = nil,
             target: Decimal? = nil, sortOrder: Int, createdAt: Date, transactions: [Transaction] = []) {
            self.id = id
            self.name = name
            self.currencyCode = currencyCode
            self.symbol = symbol
            self.addPresets = addPresets
            self.spendPresets = spendPresets
            self.dailyBudget = dailyBudget
            self.lowCashThreshold = lowCashThreshold
            self.target = target
            self.sortOrder = sortOrder
            self.createdAt = createdAt
            self.transactions = transactions
        }

        var balance: Decimal { transactions.reduce(Decimal(0)) { $0 + $1.amount } }
    }

    var exportedAt: Date?
    var platform: String
    var appVersion: String
    var settings: Settings
    var wallets: [Wallet]

    init(exportedAt: Date?, platform: String = "ios", appVersion: String = "1.0", settings: Settings = Settings(),
         wallets: [Wallet]) {
        self.exportedAt = exportedAt
        self.platform = platform
        self.appVersion = appVersion
        self.settings = settings
        self.wallets = wallets
    }

    var transactionCount: Int { wallets.reduce(0) { $0 + $1.transactions.count } }

    /// Transfers whose two halves are both in the file.
    var transferPairCount: Int {
        var halves: [UUID: Int] = [:]
        for tx in wallets.flatMap(\.transactions) where tx.kind == .transfer {
            if let pair = tx.transferID { halves[pair, default: 0] += 1 }
        }
        return halves.values.filter { $0 >= 2 }.count
    }
}

/// Why a file can't be restored. A rejected file changes nothing.
enum BackupError: Error, Equatable {
    /// Not JSON, not an object, or `format` isn't "pocketcash-backup".
    case notABackup
    /// `version` is newer than this app understands.
    case newerVersion(Int)
    case badAmount(String)
    case badKind(String)
    case badCurrency(String)
    case badDate(String)
    case badID(String)
    case duplicateID(String)
    case missing(String)
}

/// Reads and writes backup files. Readers ignore unknown fields and accept dates with or without fractional
/// seconds; IDs compare case-insensitively; amounts are exact decimal strings.
enum BackupCodec {
    // MARK: Reading

    static func decode(_ data: Data) throws -> Backup {
        guard let root = try? JSONDecoder().decode(JSONValue.self, from: data), case .object(let file) = root,
              file["format"]?.string == Backup.format else {
            throw BackupError.notABackup
        }
        guard let version = file["version"]?.number, version >= 1, version == version.rounded() else {
            throw BackupError.notABackup
        }
        if version > Double(Backup.version) { throw BackupError.newerVersion(Int(version)) }

        let exportedAt = try optionalDate(file["exportedAt"], "exportedAt")
        let app = file["app"]?.object ?? [:]

        guard case .array(let rawWallets)? = file["wallets"] else { throw BackupError.missing("wallets") }
        var walletIDs = Set<UUID>()
        var transactionIDs = Set<UUID>()
        var wallets: [Backup.Wallet] = []
        for (index, raw) in rawWallets.enumerated() {
            guard let w = raw.object else { throw BackupError.missing("wallets[\(index)]") }
            let id = try uuid(w["id"], "wallet id")
            guard walletIDs.insert(id).inserted else { throw BackupError.duplicateID(id.uuidString) }
            guard let name = w["name"]?.string else { throw BackupError.missing("wallet name") }
            let code = try currency(w["currencyCode"])
            let defaults = DefaultPresets.forCurrency(code)

            var transactions: [Backup.Transaction] = []
            for rawTx in w["transactions"]?.array ?? [] {
                guard let t = rawTx.object else { throw BackupError.missing("transaction") }
                let txID = try uuid(t["id"], "transaction id")
                guard transactionIDs.insert(txID).inserted else { throw BackupError.duplicateID(txID.uuidString) }
                guard let kindText = t["kind"]?.string, let kind = CashKind(rawValue: kindText) else {
                    throw BackupError.badKind(t["kind"]?.string ?? "")
                }
                guard let timestamp = try optionalDate(t["timestamp"], "timestamp") else {
                    throw BackupError.missing("timestamp")
                }
                transactions.append(Backup.Transaction(
                    id: txID, amount: try amount(t["amount"]), kind: kind, timestamp: timestamp,
                    tag: text(t["tag"]), note: text(t["note"]), transferID: try optionalUUID(t["transferID"], "transferID")))
            }

            let add = try amounts(w["addPresets"])
            let spend = try amounts(w["spendPresets"])
            let created = try optionalDate(w["createdAt"], "createdAt")
            wallets.append(Backup.Wallet(
                id: id, name: name, currencyCode: code, symbol: text(w["symbol"]),
                addPresets: add.isEmpty ? defaults.add : add, spendPresets: spend.isEmpty ? defaults.spend : spend,
                dailyBudget: try optionalAmount(w["dailyBudget"]),
                lowCashThreshold: try optionalAmount(w["lowCashThreshold"]),
                target: try optionalAmount(w["target"]),
                sortOrder: w["sortOrder"]?.number.map { Int($0) } ?? index,
                createdAt: created ?? transactions.map(\.timestamp).min() ?? exportedAt ?? Date(),
                transactions: transactions))
        }

        let s = file["settings"]?.object ?? [:]
        let quickWallet = try optionalUUID(s["quickSpendWalletID"], "quickSpendWalletID")
        let settings = Backup.Settings(
            plusButtonsAreATM: s["plusButtonsAreATM"]?.bool ?? true,
            privacyMode: s["privacyMode"]?.bool ?? false,
            lowCashAlerts: s["lowCashAlerts"]?.bool ?? false,
            quickSpendAmount: try optionalAmount(s["quickSpendAmount"]),
            quickSpendWalletID: quickWallet.flatMap { walletIDs.contains($0) ? $0 : nil })

        return Backup(exportedAt: exportedAt, platform: app["platform"]?.string ?? "unknown",
                      appVersion: app["version"]?.string ?? "", settings: settings,
                      wallets: wallets.sorted { $0.sortOrder < $1.sortOrder })
    }

    private static func text(_ value: JSONValue?) -> String? {
        guard let s = value?.string, !s.isEmpty else { return nil }
        return s
    }

    private static func uuid(_ value: JSONValue?, _ field: String) throws -> UUID {
        guard let id = try optionalUUID(value, field) else { throw BackupError.missing(field) }
        return id
    }

    private static func optionalUUID(_ value: JSONValue?, _ field: String) throws -> UUID? {
        switch value {
        case nil, .null?: return nil
        case .string(let s)?:
            guard let id = UUID(uuidString: s.uppercased()) else { throw BackupError.badID(s) }
            return id
        default: throw BackupError.badID(field)
        }
    }

    private static func currency(_ value: JSONValue?) throws -> String {
        guard let code = value?.string else { throw BackupError.badCurrency("") }
        guard code.count == 3, code.unicodeScalars.allSatisfy({ ("A"..."Z").contains($0) || ("a"..."z").contains($0) }) else {
            throw BackupError.badCurrency(code)
        }
        return code.uppercased()
    }

    /// "1000", "-20.50", "0.25" (also "+5", "5." and ".5", like the Android reader). No grouping, no exponent,
    /// `.` as the decimal point.
    static func parseAmount(_ s: String) -> Decimal? {
        var digits = Substring(s)
        var negative = false
        if let sign = digits.first, sign == "-" || sign == "+" {
            negative = sign == "-"
            digits = digits.dropFirst()
        }
        let parts = digits.split(separator: ".", omittingEmptySubsequences: false)
        let isDigits: (Substring) -> Bool = { $0.unicodeScalars.allSatisfy { ("0"..."9").contains($0) } }
        guard (1...2).contains(parts.count), parts.allSatisfy(isDigits), parts.contains(where: { !$0.isEmpty }) else {
            return nil
        }
        let whole = parts[0].isEmpty ? "0" : String(parts[0])
        let fraction = parts.count == 2 && !parts[1].isEmpty ? "." + parts[1] : ""
        guard let value = Decimal(string: whole + fraction, locale: Locale(identifier: "en_US_POSIX")) else { return nil }
        return negative ? -value : value
    }

    private static func amount(_ value: JSONValue?) throws -> Decimal {
        guard let s = value?.string, let d = parseAmount(s) else { throw BackupError.badAmount(value?.string ?? "") }
        return d
    }

    private static func optionalAmount(_ value: JSONValue?) throws -> Decimal? {
        if value == nil || value == .null { return nil }
        return try amount(value)
    }

    private static func amounts(_ value: JSONValue?) throws -> [Decimal] {
        guard case .array(let list)? = value else { return [] }
        return try list.map(amount).filter { $0 > 0 }
    }

    private static func optionalDate(_ value: JSONValue?, _ field: String) throws -> Date? {
        switch value {
        case nil, .null?: return nil
        case .string(let s)?:
            guard let date = parseDate(s) else { throw BackupError.badDate(s) }
            return date
        default: throw BackupError.badDate(field)
        }
    }

    /// RFC 3339 in any offset, with or without fractional seconds (any number of digits).
    static func parseDate(_ s: String) -> Date? {
        let chars = Array(s)
        guard chars.count >= 20 else { return nil }
        var base = String(chars[0..<19])
        guard ["T", "t"].contains(chars[10]) else { return nil }
        base = String(chars[0..<10]) + "T" + String(chars[11..<19])
        var index = 19
        var fraction = 0.0
        if chars[index] == "." {
            index += 1
            let start = index
            while index < chars.count, chars[index].isASCII, chars[index].isNumber { index += 1 }
            guard index > start, let value = Double("0." + String(chars[start..<index])) else { return nil }
            fraction = value
        }
        var zone = String(chars[index...])
        if zone == "z" { zone = "Z" }
        guard zone == "Z" || (zone.count == 6 && ["+", "-"].contains(zone.first)) else { return nil }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        guard let date = formatter.date(from: base + zone) else { return nil }
        return date.addingTimeInterval(fraction)
    }

    // MARK: Writing

    /// Pretty-printed UTF-8 JSON with the spec's key order.
    static func encode(_ backup: Backup) -> Data {
        let dates = ISO8601DateFormatter()
        dates.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        func date(_ d: Date) -> OrderedJSON { .string(dates.string(from: d)) }
        func amount(_ d: Decimal) -> OrderedJSON { .string(Money.string(d)) }
        func optional<T>(_ value: T?, _ make: (T) -> OrderedJSON) -> OrderedJSON { value.map(make) ?? .null }
        func optionalText(_ value: String?) -> OrderedJSON { optional(value) { .string($0) } }

        let wallets: [OrderedJSON] = backup.wallets.map { w in
            .object([
                ("id", .string(w.id.uuidString)),
                ("name", .string(w.name)),
                ("currencyCode", .string(w.currencyCode)),
                ("symbol", optionalText(w.symbol)),
                ("addPresets", .array(w.addPresets.map(amount))),
                ("spendPresets", .array(w.spendPresets.map(amount))),
                ("dailyBudget", optional(w.dailyBudget, amount)),
                ("lowCashThreshold", optional(w.lowCashThreshold, amount)),
                ("target", optional(w.target, amount)),
                ("sortOrder", .integer(w.sortOrder)),
                ("createdAt", date(w.createdAt)),
                ("transactions", .array(w.transactions.map { t in
                    .object([
                        ("id", .string(t.id.uuidString)),
                        ("amount", amount(t.amount)),
                        ("kind", .string(t.kind.rawValue)),
                        ("timestamp", date(t.timestamp)),
                        ("tag", optionalText(t.tag)),
                        ("note", optionalText(t.note)),
                        ("transferID", optional(t.transferID) { .string($0.uuidString) }),
                    ])
                })),
            ])
        }
        let s = backup.settings
        let root = OrderedJSON.object([
            ("format", .string(Backup.format)),
            ("version", .integer(Backup.version)),
            ("exportedAt", optional(backup.exportedAt, date)),
            ("app", .object([("platform", .string(backup.platform)), ("version", .string(backup.appVersion))])),
            ("settings", .object([
                ("plusButtonsAreATM", .bool(s.plusButtonsAreATM)),
                ("privacyMode", .bool(s.privacyMode)),
                ("lowCashAlerts", .bool(s.lowCashAlerts)),
                ("quickSpendAmount", optional(s.quickSpendAmount, amount)),
                ("quickSpendWalletID", optional(s.quickSpendWalletID) { .string($0.uuidString) }),
            ])),
            ("wallets", .array(wallets)),
        ])
        var out = ""
        root.write(to: &out, indent: "")
        out += "\n"
        return Data(out.utf8)
    }

    /// "PocketCash-backup-2026-11-25.json", in the phone's time zone.
    static func fileName(for date: Date, timeZone: TimeZone = .current) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = timeZone
        f.dateFormat = "yyyy-MM-dd"
        return "PocketCash-backup-\(f.string(from: date)).json"
    }
}

/// Any JSON value, decoded the same way on Apple platforms and Linux.
enum JSONValue: Decodable, Equatable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    var string: String? { if case .string(let s) = self { return s }; return nil }
    var number: Double? { if case .number(let n) = self { return n }; return nil }
    var bool: Bool? { if case .bool(let b) = self { return b }; return nil }
    var array: [JSONValue]? { if case .array(let a) = self { return a }; return nil }
    var object: [String: JSONValue]? { if case .object(let o) = self { return o }; return nil }
}

/// A JSON writer that keeps key order, so backup files read top to bottom like the spec.
private indirect enum OrderedJSON {
    case null
    case bool(Bool)
    case integer(Int)
    case string(String)
    case array([OrderedJSON])
    case object([(String, OrderedJSON)])

    func write(to out: inout String, indent: String) {
        let inner = indent + "  "
        switch self {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .integer(let i): out += String(i)
        case .string(let s): Self.quote(s, into: &out)
        case .array(let items):
            if items.isEmpty { out += "[]"; return }
            out += "[\n"
            for (i, item) in items.enumerated() {
                out += inner
                item.write(to: &out, indent: inner)
                out += i + 1 < items.count ? ",\n" : "\n"
            }
            out += indent + "]"
        case .object(let pairs):
            if pairs.isEmpty { out += "{}"; return }
            out += "{\n"
            for (i, (key, value)) in pairs.enumerated() {
                out += inner
                Self.quote(key, into: &out)
                out += ": "
                value.write(to: &out, indent: inner)
                out += i + 1 < pairs.count ? ",\n" : "\n"
            }
            out += indent + "}"
        }
    }

    static func quote(_ s: String, into out: inout String) {
        out += "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    let hex = String(scalar.value, radix: 16)
                    out += "\\u" + String(repeating: "0", count: 4 - hex.count) + hex
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
    }
}
