import Foundation

/// CSV export (docs/spec.md section 9): date,time,wallet,kind,amount,currency,category,note.
enum CSVExport {
    struct Row {
        var date: Date
        var wallet: String
        var kind: CashKind
        var amount: Decimal
        var currency: String
        var category: String?
        var note: String?

        init(date: Date, wallet: String, kind: CashKind, amount: Decimal, currency: String,
             category: String? = nil, note: String? = nil) {
            self.date = date
            self.wallet = wallet
            self.kind = kind
            self.amount = amount
            self.currency = currency
            self.category = category
            self.note = note
        }
    }

    static func make(_ rows: [Row], timeZone: TimeZone = .current) -> String {
        let day = DateFormatter()
        day.locale = Money.posix
        day.timeZone = timeZone
        day.dateFormat = "yyyy-MM-dd"
        let time = DateFormatter()
        time.locale = Money.posix
        time.timeZone = timeZone
        time.dateFormat = "HH:mm"

        var lines = ["date,time,wallet,kind,amount,currency,category,note"]
        for row in rows.sorted(by: { $0.date < $1.date }) {
            lines.append([
                day.string(from: row.date),
                time.string(from: row.date),
                escape(row.wallet),
                row.kind.rawValue,
                Money.string(row.amount),
                row.currency,
                escape(row.category ?? ""),
                escape(row.note ?? ""),
            ].joined(separator: ","))
        }
        return lines.joined(separator: "\n") + "\n"
    }

    static func escape(_ field: String) -> String {
        guard field.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) else { return field }
        return "\"" + field.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }
}
