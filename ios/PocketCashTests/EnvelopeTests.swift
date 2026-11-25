import XCTest
#if canImport(PocketCashCore)
@testable import PocketCashCore
#else
@testable import PocketCash
#endif

/// Envelopes and savings goals (docs/spec.md section 10) and the keypad's change calculator (section 7.2).
final class EnvelopeTests: XCTestCase {
    func testProgressAndShortBy() {
        XCTAssertEqual(Envelope.progress(balance: 1900, target: 5000), Decimal(string: "0.38"))
        XCTAssertEqual(Envelope.shortBy(balance: 1900, target: 5000), 3100)
        XCTAssertEqual(Envelope.progress(balance: 6000, target: 5000), 1)
        XCTAssertEqual(Envelope.shortBy(balance: 6000, target: 5000), 0)
        XCTAssertTrue(Envelope.isReached(balance: 5000, target: 5000))
        XCTAssertEqual(Envelope.progress(balance: -50, target: 5000), 0)
        XCTAssertEqual(Envelope.shortBy(balance: -50, target: 5000), 5050)
        XCTAssertNil(Envelope.progress(balance: 100, target: nil))
        XCTAssertNil(Envelope.progress(balance: 100, target: 0))
        XCTAssertEqual(Envelope.shortBy(balance: 100, target: nil), 0)
    }

    func testSuggestedFillWalksEnvelopesInOrder() {
        // Main has 5,200: Rent is short 3,100, Food (no target) gets nothing, Savings is short 2,500.
        let fill = Envelope.suggestedFill(sourceBalance: 5200, envelopes: [
            (balance: 1900, target: 5000),
            (balance: 300, target: nil),
            (balance: 500, target: 3000),
        ])
        XCTAssertEqual(fill, [3100, 0, 2100])
    }

    func testSuggestedFillWithNothingToGive() {
        XCTAssertEqual(Envelope.suggestedFill(sourceBalance: -10, envelopes: [(balance: 0, target: 100)]), [0])
        XCTAssertEqual(Envelope.suggestedFill(sourceBalance: 1000, envelopes: [(balance: 200, target: 100)]), [0])
        XCTAssertEqual(Envelope.suggestedFill(sourceBalance: 1000, envelopes: []), [])
    }

    func testChangeMath() {
        XCTAssertEqual(ChangeMath.spend(paid: 500, change: 130), 370)
        XCTAssertEqual(ChangeMath.spend(paid: 500, change: 0), 500)
        XCTAssertEqual(ChangeMath.spend(paid: Decimal(string: "20")!, change: Decimal(string: "7.25")!), Decimal(string: "12.75"))
        XCTAssertNil(ChangeMath.spend(paid: 500, change: 500))
        XCTAssertNil(ChangeMath.spend(paid: 500, change: 600))
        XCTAssertNil(ChangeMath.spend(paid: 0, change: 0))
    }

    func testPaidChips() {
        // PHP: bills of at least the largest spend button (100), four largest.
        let php = DefaultPresets.forCurrency("PHP")
        XCTAssertEqual(ChangeMath.paidChips(denominations: php.denominations, spendPresets: php.spend), [1000, 500, 200, 100])
        // USD: only three bills reach $20.
        let usd = DefaultPresets.forCurrency("USD")
        XCTAssertEqual(ChangeMath.paidChips(denominations: usd.denominations, spendPresets: usd.spend), [100, 50, 20])
        // Custom spend buttons above every bill leave no chips.
        XCTAssertEqual(ChangeMath.paidChips(denominations: usd.denominations, spendPresets: [500]), [])
    }
}
