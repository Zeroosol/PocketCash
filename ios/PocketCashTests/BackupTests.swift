import XCTest
#if canImport(PocketCashCore)
@testable import PocketCashCore
#else
@testable import PocketCash
#endif

/// The backup file (docs/spec.md section 11), checked against spec/backup/example-v1.json, the same file the Android
/// tests decode. Runs in Xcode and on Linux: `POCKETCASH_SPM=core swift test` in ios/.
final class BackupTests: XCTestCase {
    private static var exampleURL: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // PocketCashTests
            .deletingLastPathComponent()   // ios
            .deletingLastPathComponent()   // repository root
            .appendingPathComponent("spec/backup/example-v1.json")
    }

    private func exampleData() throws -> Data { try Data(contentsOf: Self.exampleURL) }

    private func expected() throws -> [String: Any] {
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: exampleData()) as? [String: Any])
        return try XCTUnwrap(root["_expected"] as? [String: Any])
    }

    private func uuid(_ s: String) -> UUID { UUID(uuidString: s.uppercased())! }

    func testDecodesTheSpecExample() throws {
        let backup = try BackupCodec.decode(exampleData())
        let want = try expected()

        XCTAssertEqual(backup.wallets.count, want["wallets"] as? Int)
        XCTAssertEqual(backup.transactionCount, want["transactions"] as? Int)
        XCTAssertEqual(backup.transferPairCount, want["transferPairs"] as? Int)
        XCTAssertEqual(backup.platform, "ios")
        XCTAssertEqual(backup.exportedAt, Date(timeIntervalSince1970: 1_795_608_000)) // 2026-11-25T12:00:00Z

        // Balances, keyed by IDs written in either case.
        let balances = try XCTUnwrap(want["balances"] as? [String: String])
        for (id, value) in balances {
            let wallet = try XCTUnwrap(backup.wallets.first { $0.id == uuid(id) }, id)
            XCTAssertEqual(wallet.balance, Money.decimal(value), "balance of \(wallet.name)")
        }

        // The transfer's halves carry the same transferID even though the file spells it in two cases.
        let halves = backup.wallets.flatMap(\.transactions).filter { $0.kind == .transfer }
        XCTAssertEqual(halves.count, 2)
        XCTAssertEqual(halves[0].transferID, halves[1].transferID)
        XCTAssertEqual(halves.map(\.amount).reduce(0, +), 0)

        // Rent is an envelope: 1,900 of 5,000.
        let rent = try XCTUnwrap(backup.wallets.first { $0.name == "Rent" })
        let progress = try XCTUnwrap(Envelope.progress(balance: rent.balance, target: rent.target))
        XCTAssertEqual(Money.double(progress), try XCTUnwrap(want["rentProgress"] as? Double), accuracy: 0.0001)
        XCTAssertEqual(Envelope.shortBy(balance: rent.balance, target: rent.target),
                       Money.decimal(try XCTUnwrap(want["rentShortBy"] as? String)))

        // Exact decimals, fractional-second dates, settings and unknown fields.
        let main = backup.wallets[0]
        XCTAssertEqual(main.transactions.first { $0.tag == "transit" }?.amount, Decimal(string: "-20.5"))
        let atm = try XCTUnwrap(main.transactions.first { $0.kind == .atm })
        XCTAssertEqual(atm.timestamp.timeIntervalSince1970, 1_794_819_600.25, accuracy: 0.0001) // 2026-11-16T09:00:00.250Z
        XCTAssertEqual(main.dailyBudget, 300)
        XCTAssertEqual(main.lowCashThreshold, 200)
        XCTAssertNil(main.target)
        XCTAssertEqual(main.addPresets, [1000, 500])
        XCTAssertEqual(backup.settings.quickSpendAmount, 10)
        XCTAssertNil(backup.settings.quickSpendWalletID)
        XCTAssertTrue(backup.settings.plusButtonsAreATM)
        XCTAssertEqual(backup.wallets.map(\.currencyCode), ["PHP", "PHP", "JPY"])
        XCTAssertEqual(backup.wallets.map(\.sortOrder), [0, 1, 2])
    }

    func testRoundTrip() throws {
        let original = try BackupCodec.decode(exampleData())
        let encoded = BackupCodec.encode(original)
        let decoded = try BackupCodec.decode(encoded)
        XCTAssertEqual(decoded, original)

        // The writer's own output reads back field for field, including nils and fractional seconds.
        let id = UUID()
        let made = Backup(
            exportedAt: Date(timeIntervalSince1970: 1_800_000_000.5), platform: "ios", appVersion: "1.0",
            settings: Backup.Settings(plusButtonsAreATM: false, privacyMode: true, lowCashAlerts: true,
                                      quickSpendAmount: Decimal(string: "12.75"), quickSpendWalletID: id),
            wallets: [Backup.Wallet(
                id: id, name: "Coin \"pouch\"\n", currencyCode: "USD", symbol: "Ⓣ", addPresets: [100],
                spendPresets: [Decimal(string: "0.25")!], dailyBudget: nil, lowCashThreshold: 5, target: 1000,
                sortOrder: 0, createdAt: Date(timeIntervalSince1970: 1_799_000_000),
                transactions: [Backup.Transaction(id: UUID(), amount: Decimal(string: "-0.1")!, kind: .spend,
                                                  timestamp: Date(timeIntervalSince1970: 1_799_000_123.125),
                                                  tag: "food", note: "tab\there", transferID: nil)])])
        let text = String(decoding: BackupCodec.encode(made), as: UTF8.self)
        XCTAssertTrue(text.hasPrefix("{\n  \"format\": \"pocketcash-backup\",\n  \"version\": 1,"), text)
        XCTAssertTrue(text.contains("\"amount\": \"-0.1\""))
        XCTAssertEqual(try BackupCodec.decode(Data(text.utf8)), made)
    }

    func testFileName() {
        let date = Date(timeIntervalSince1970: 1_795_608_000)
        XCTAssertEqual(BackupCodec.fileName(for: date, timeZone: TimeZone(identifier: "UTC")!),
                       "PocketCash-backup-2026-11-25.json")
    }

    func testDatesWithAndWithoutFractions() {
        XCTAssertEqual(BackupCodec.parseDate("2026-11-25T12:00:00Z"), Date(timeIntervalSince1970: 1_795_608_000))
        XCTAssertEqual(BackupCodec.parseDate("2026-11-25T12:00:00.5Z")?.timeIntervalSince1970 ?? 0, 1_795_608_000.5, accuracy: 0.0001)
        XCTAssertEqual(BackupCodec.parseDate("2026-11-25T12:00:00.123456789Z")?.timeIntervalSince1970 ?? 0,
                       1_795_608_000.123, accuracy: 0.001)
        XCTAssertEqual(BackupCodec.parseDate("2026-11-25T20:00:00+08:00"), Date(timeIntervalSince1970: 1_795_608_000))
        XCTAssertNil(BackupCodec.parseDate("2026-11-25"))
        XCTAssertNil(BackupCodec.parseDate("yesterday"))
    }

    func testAmounts() {
        XCTAssertEqual(BackupCodec.parseAmount("1000"), 1000)
        XCTAssertEqual(BackupCodec.parseAmount("-20.50"), Decimal(string: "-20.5"))
        XCTAssertEqual(BackupCodec.parseAmount("0.25"), Decimal(string: "0.25"))
        XCTAssertNil(BackupCodec.parseAmount("1,000"))
        XCTAssertNil(BackupCodec.parseAmount("1e5"))
        XCTAssertNil(BackupCodec.parseAmount("12abc"))
        XCTAssertNil(BackupCodec.parseAmount(""))
        XCTAssertNil(BackupCodec.parseAmount("-"))
    }

    // MARK: Rejections

    /// The example with one edit applied to its JSON object.
    private func mutated(_ edit: (inout [String: Any]) -> Void) throws -> Data {
        var root = try XCTUnwrap(try JSONSerialization.jsonObject(with: exampleData()) as? [String: Any])
        edit(&root)
        return try JSONSerialization.data(withJSONObject: root)
    }

    private func assertRejects(_ data: Data, _ expected: BackupError, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try BackupCodec.decode(data), file: file, line: line) { error in
            XCTAssertEqual(error as? BackupError, expected, file: file, line: line)
        }
    }

    private func editFirstTransaction(_ root: inout [String: Any], _ edit: (inout [String: Any]) -> Void) {
        var wallets = root["wallets"] as! [[String: Any]]
        var txs = wallets[0]["transactions"] as! [[String: Any]]
        edit(&txs[0])
        wallets[0]["transactions"] = txs
        root["wallets"] = wallets
    }

    func testRejectsWrongFormat() throws {
        assertRejects(try mutated { $0["format"] = "splitty-backup" }, .notABackup)
        assertRejects(try mutated { $0.removeValue(forKey: "format") }, .notABackup)
        assertRejects(Data("not json".utf8), .notABackup)
        assertRejects(Data("[1, 2]".utf8), .notABackup)
    }

    func testRejectsNewerVersion() throws {
        assertRejects(try mutated { $0["version"] = 2 }, .newerVersion(2))
    }

    func testRejectsDuplicateIDs() throws {
        // The same transaction ID twice, the second time in lower case.
        let data = try mutated { root in
            var wallets = root["wallets"] as! [[String: Any]]
            var txs = wallets[2]["transactions"] as! [[String: Any]]
            txs[1]["id"] = "a0000000-0000-4000-8000-000000000001"
            wallets[2]["transactions"] = txs
            root["wallets"] = wallets
        }
        assertRejects(data, .duplicateID("A0000000-0000-4000-8000-000000000001"))

        let twoWallets = try mutated { root in
            var wallets = root["wallets"] as! [[String: Any]]
            wallets[1]["id"] = "6F1C2A4E-8B1D-4C3A-9E2F-1A2B3C4D5E60"
            root["wallets"] = wallets
        }
        assertRejects(twoWallets, .duplicateID("6F1C2A4E-8B1D-4C3A-9E2F-1A2B3C4D5E60"))
    }

    func testRejectsBadAmount() throws {
        assertRejects(try mutated { editFirstTransaction(&$0) { $0["amount"] = "5OO" } }, .badAmount("5OO"))
        assertRejects(try mutated { editFirstTransaction(&$0) { $0["amount"] = 500 } }, .badAmount(""))
    }

    func testRejectsBadKindAndCurrency() throws {
        assertRejects(try mutated { editFirstTransaction(&$0) { $0["kind"] = "gift" } }, .badKind("gift"))
        let badCurrency = try mutated { root in
            var wallets = root["wallets"] as! [[String: Any]]
            wallets[0]["currencyCode"] = "PESO"
            root["wallets"] = wallets
        }
        assertRejects(badCurrency, .badCurrency("PESO"))
    }
}
