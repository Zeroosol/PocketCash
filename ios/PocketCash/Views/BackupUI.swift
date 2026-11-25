import SwiftUI
import UniformTypeIdentifiers

/// The backup file for the system's file exporter (docs/spec.md section 11).
struct BackupDocument: FileDocument {
    static let readableContentTypes: [UTType] = [.json]

    let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }

    /// "PocketCash-backup-2026-11-25" (the exporter adds ".json" from the content type).
    static func defaultName(now: Date = .now) -> String {
        (BackupCodec.fileName(for: now) as NSString).deletingPathExtension
    }
}

extension BackupError {
    /// Why the file can't be restored, for people.
    var message: String {
        switch self {
        case .notABackup:
            return String(localized: "This file isn't a PocketCash backup.")
        case .newerVersion:
            return String(localized: "This backup is from a newer version of PocketCash. Update the app, then try again.")
        case .badAmount(let value), .badKind(let value), .badCurrency(let value), .badDate(let value),
             .badID(let value), .duplicateID(let value), .missing(let value):
            return String(localized: "This backup file is damaged, so nothing was changed.") + "\n(\(value))"
        }
    }
}

extension Backup {
    /// "Wallets: 2 · Cash moves: 143 · Backed up Nov 25, 2026"
    var summary: String {
        let wallets = String(self.wallets.count)
        let moves = String(transactionCount)
        guard let exportedAt else { return String(localized: "Wallets: \(wallets) · Cash moves: \(moves)") }
        let date = exportedAt.formatted(date: .abbreviated, time: .omitted)
        return String(localized: "Wallets: \(wallets) · Cash moves: \(moves) · Backed up \(date)")
    }
}

extension View {
    /// "Restore from a file": pick a JSON file, check it, show what it holds, and replace everything on this phone
    /// only after the person confirms. A file that fails validation changes nothing.
    func restoresBackup(isPresented: Binding<Bool>) -> some View {
        modifier(RestoreBackupModifier(isPresented: isPresented))
    }
}

private struct RestoreBackupModifier: ViewModifier {
    @EnvironmentObject private var model: AppModel
    @Binding var isPresented: Bool
    @State private var pending: Backup?
    @State private var problem: String?

    func body(content: Content) -> some View {
        content
            .fileImporter(isPresented: $isPresented, allowedContentTypes: [.json, .data]) { result in
                switch result {
                case .success(let url): read(url)
                case .failure(let error): problem = error.localizedDescription
                }
            }
            .alert("Restore this backup?", isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }),
                   presenting: pending) { backup in
                Button("Replace all data on this phone", role: .destructive) { model.restore(backup) }
                Button("Cancel", role: .cancel) {}
            } message: { backup in
                Text(verbatim: backup.summary + "\n\n" + String(localized: "Everything on this phone is replaced by the backup. This can't be undone."))
            }
            .alert("Can't restore this file", isPresented: Binding(get: { problem != nil }, set: { if !$0 { problem = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(verbatim: problem ?? "")
            }
    }

    private func read(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let data = try Data(contentsOf: url)
            pending = try BackupCodec.decode(data)
        } catch let error as BackupError {
            problem = error.message
        } catch {
            problem = error.localizedDescription
        }
    }
}
