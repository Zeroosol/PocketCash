import XCTest
#if canImport(PocketCashCore)
@testable import PocketCashCore
#else
@testable import PocketCash
#endif

/// Checks the money rules against spec/vectors/cycles.json, the same file the Android tests read.
/// Runs in Xcode (TestFlight workflow) and on Linux: `POCKETCASH_SPM=core swift test` in ios/.
final class LedgerVectorTests: XCTestCase {
    private static var vectorsURL: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // PocketCashTests
            .deletingLastPathComponent()   // ios
            .deletingLastPathComponent()   // repository root
            .appendingPathComponent("spec/vectors/cycles.json")
    }

    private func date(_ value: Any?) throws -> Date {
        let f = ISO8601DateFormatter()
        guard let s = value as? String, let d = f.date(from: s) else {
            throw NSError(domain: "vectors", code: 1, userInfo: [NSLocalizedDescriptionKey: "bad date \(String(describing: value))"])
        }
        return d
    }

    private func dec(_ value: Any?) throws -> Decimal {
        guard let s = value as? String, let d = Money.decimal(s) else {
            throw NSError(domain: "vectors", code: 2, userInfo: [NSLocalizedDescriptionKey: "bad amount \(String(describing: value))"])
        }
        return d
    }

    private func assertClose(_ actual: Double?, _ expected: Any?, _ what: String, file: StaticString = #filePath, line: UInt = #line) {
        if expected is NSNull || expected == nil {
            XCTAssertNil(actual, what, file: file, line: line)
            return
        }
        guard let e = (expected as? NSNumber)?.doubleValue, let a = actual else {
            XCTFail("\(what): expected \(String(describing: expected)), got \(String(describing: actual))", file: file, line: line)
            return
        }
        XCTAssertEqual(a, e, accuracy: 0.01, what, file: file, line: line)
    }

    func testCycleVectors() throws {
        let data = try Data(contentsOf: Self.vectorsURL)
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let cases = try XCTUnwrap(root["cases"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty)

        for c in cases {
            let name = c["name"] as? String ?? "?"
            let now = try date(c["now"])
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = try XCTUnwrap(TimeZone(identifier: c["timeZone"] as? String ?? "UTC"), name)

            let items: [LedgerItem] = try (c["transactions"] as? [[String: Any]] ?? []).map { t in
                LedgerItem(
                    id: t["id"] as? String ?? UUID().uuidString,
                    amount: try dec(t["amount"]),
                    kind: try XCTUnwrap(CashKind(rawValue: t["kind"] as? String ?? ""), "\(name): kind"),
                    timestamp: try date(t["timestamp"]),
                    tag: t["tag"] as? String)
            }
            let expected = try XCTUnwrap(c["expected"] as? [String: Any], name)

            XCTAssertEqual(Ledger.balance(items), try dec(expected["balance"]), "\(name): balance")
            XCTAssertEqual(Ledger.todaySpent(items, now: now, calendar: calendar), try dec(expected["todaySpent"]), "\(name): todaySpent")
            let burn = Ledger.burnRatePerDay(items, now: now)
            assertClose(burn, expected["burnRatePerDay"], "\(name): burnRatePerDay")
            assertClose(Ledger.runwayDays(balance: Ledger.balance(items), burnRate: burn), expected["runwayDays"], "\(name): runwayDays")

            let cycles = Ledger.cycles(items, now: now)
            let wanted = expected["cycles"] as? [[String: Any]] ?? []
            XCTAssertEqual(cycles.count, wanted.count, "\(name): cycle count")
            for (i, (got, want)) in zip(cycles, wanted).enumerated() {
                let tag = "\(name) cycle \(i)"
                XCTAssertEqual(got.isATM, want["isATM"] as? Bool, "\(tag): isATM")
                XCTAssertEqual(got.start, try date(want["start"]), "\(tag): start")
                if want["end"] is NSNull { XCTAssertNil(got.end, "\(tag): end") } else { XCTAssertEqual(got.end, try date(want["end"]), "\(tag): end") }
                XCTAssertEqual(got.isOpen, want["isOpen"] as? Bool, "\(tag): isOpen")
                XCTAssertEqual(got.withdrawn, try dec(want["withdrawn"]), "\(tag): withdrawn")
                XCTAssertEqual(got.spent, try dec(want["spent"]), "\(tag): spent")
                XCTAssertEqual(got.inflow, try dec(want["inflow"]), "\(tag): inflow")
                assertClose(got.days, want["days"], "\(tag): days")
                assertClose(got.avgDaily, want["avgDaily"], "\(tag): avgDaily")
                if want["topAmount"] is NSNull { XCTAssertNil(got.topAmount, "\(tag): topAmount") } else { XCTAssertEqual(got.topAmount, try dec(want["topAmount"]), "\(tag): topAmount") }
                XCTAssertEqual(got.topAmountCount, want["topAmountCount"] as? Int, "\(tag): topAmountCount")
                XCTAssertEqual(got.topAmountTotal, try dec(want["topAmountTotal"]), "\(tag): topAmountTotal")
                XCTAssertEqual(got.topTag, want["topTag"] as? String, "\(tag): topTag")
            }
        }
    }

    func testButtonLabels() {
        XCTAssertEqual(Money.buttonLabel(1000, cashIn: true), "+1k")
        XCTAssertEqual(Money.buttonLabel(100, cashIn: false), "\u{2212}100")
        XCTAssertEqual(Money.buttonLabel(1500, cashIn: true), "+1.5k")
        XCTAssertEqual(Money.buttonLabel(10000, cashIn: true), "+10k")
        XCTAssertEqual(Money.buttonLabel(Decimal(string: "0.25")!, cashIn: false), "\u{2212}0.25")
        XCTAssertEqual(Money.buttonLabel(1_000_000, cashIn: true), "+1M")
        XCTAssertEqual(Money.buttonLabel(500_000, cashIn: true), "+500k")
    }

    func testFullFormat() {
        let us = Locale(identifier: "en_US")
        XCTAssertEqual(Money.format(680, code: "USD", locale: us), "$680")
        XCTAssertEqual(Money.format(Decimal(string: "12.5")!, code: "USD", locale: us), "$12.50")
        XCTAssertEqual(Money.format(-50, code: "USD", locale: us), "\u{2212}$50")
        XCTAssertEqual(Money.format(50, code: "USD", signed: true, locale: us), "+$50")
        XCTAssertEqual(Money.format(1200, code: "JPY", locale: us), "¥1,200")
        XCTAssertEqual(Money.format(20, code: "USD", symbol: "Ⓣ", locale: us), "Ⓣ20")
    }

    func testPresetsCoverEveryCurrencyRule() {
        for (code, p) in DefaultPresets.table {
            XCTAssertTrue((1...2).contains(p.add.count), code)
            XCTAssertTrue((1...4).contains(p.spend.count), code)
            XCTAssertEqual(p.denominations, p.denominations.sorted(by: >), "\(code) denominations are largest first")
        }
        XCTAssertEqual(DefaultPresets.forCurrency("php").spend, [100, 50, 20, 10])
        XCTAssertEqual(DefaultPresets.forCurrency("XXX"), DefaultPresets.fallback)
    }

    func testCSVEscaping() {
        let csv = CSVExport.make([
            .init(date: Date(timeIntervalSince1970: 0), wallet: "Main, Wallet", kind: .spend, amount: -50,
                  currency: "PHP", category: "food", note: "said \"hi\""),
        ], timeZone: TimeZone(identifier: "UTC")!)
        XCTAssertEqual(csv, "date,time,wallet,kind,amount,currency,category,note\n1970-01-01,00:00,\"Main, Wallet\",spend,-50,PHP,food,\"said \"\"hi\"\"\"\n")
    }
}
