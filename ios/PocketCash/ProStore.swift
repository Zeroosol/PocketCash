import StoreKit
import SwiftUI

/// The lifetime Pro unlock through StoreKit 2 (docs/spec.md section 5). No third-party SDK, no server.
@MainActor
final class ProStore: ObservableObject {
    enum LoadState: Equatable {
        case loading, ready, unavailable
    }

    @Published private(set) var product: Product?
    @Published private(set) var loadState: LoadState = .loading
    @Published private(set) var purchased = AppSettings.isProCached
    @Published private(set) var purchasing = false
    @Published var message: String?
    /// Debug builds only: unlock Pro without the App Store.
    @Published var debugUnlock = AppSettings.debugPro {
        didSet { AppSettings.debugPro = debugUnlock }
    }

    var isPro: Bool { purchased || (AppSettings.debugProAvailable && debugUnlock) }

    private var updates: Task<Void, Never>?

    init() {
        updates = Task { [weak self] in
            for await result in StoreKit.Transaction.updates {
                await self?.handle(result)
            }
        }
        Task { await refresh() }
    }

    deinit { updates?.cancel() }

    func refresh() async {
        do {
            product = try await Product.products(for: [PocketCash.proProductID]).first
            loadState = product == nil ? .unavailable : .ready
        } catch {
            loadState = .unavailable
        }
        await updateEntitlements()
    }

    func updateEntitlements() async {
        var owned = false
        for await result in StoreKit.Transaction.currentEntitlements {
            if case .verified(let transaction) = result, transaction.productID == PocketCash.proProductID,
               transaction.revocationDate == nil {
                owned = true
            }
        }
        setPurchased(owned)
    }

    func buy() async {
        guard let product, !purchasing else { return }
        purchasing = true
        defer { purchasing = false }
        do {
            switch try await product.purchase() {
            case .success(let verification):
                if case .verified(let transaction) = verification {
                    await transaction.finish()
                    setPurchased(true)
                    message = String(localized: "Pro unlocked. Thank you!")
                } else {
                    message = String(localized: "The App Store couldn't verify that purchase.")
                }
            case .pending:
                message = String(localized: "Your purchase is waiting for approval.")
            case .userCancelled:
                break
            @unknown default:
                break
            }
        } catch {
            message = error.localizedDescription
        }
    }

    func restore() async {
        do {
            try await AppStore.sync()
        } catch {
            message = error.localizedDescription
        }
        await updateEntitlements()
        message = purchased ? String(localized: "Pro restored.") : String(localized: "No PocketCash Pro purchase found for this Apple Account.")
    }

    private func handle(_ result: VerificationResult<StoreKit.Transaction>) async {
        guard case .verified(let transaction) = result, transaction.productID == PocketCash.proProductID else { return }
        await transaction.finish()
        await updateEntitlements()
    }

    private func setPurchased(_ owned: Bool) {
        purchased = owned
        AppSettings.isProCached = owned
    }
}
