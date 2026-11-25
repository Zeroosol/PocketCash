import LocalAuthentication
import SwiftUI
import UIKit

/// App lock (Pro, docs/spec.md section 12): Face ID / Touch ID with the passcode as fallback. The app locks every
/// time it goes to the background, and a cover hides its content whenever it isn't active (app switcher, Control
/// Center, the Face ID prompt). The cover lives in its own window above everything, so open sheets are hidden too.
@MainActor
final class AppLock: ObservableObject {
    enum Method {
        case faceID, touchID, opticID, passcode, unavailable

        /// "Lock with Face ID", or the passcode when the phone has no biometrics set up.
        var title: LocalizedStringKey {
            switch self {
            case .faceID: return LocalizedStringKey("Lock with Face ID")
            case .touchID: return LocalizedStringKey("Lock with Touch ID")
            case .opticID: return LocalizedStringKey("Lock with Optic ID")
            case .passcode, .unavailable: return LocalizedStringKey("Lock with passcode")
            }
        }
    }

    @Published private(set) var isEnabled: Bool
    @Published private(set) var isLocked: Bool
    @Published private(set) var isAuthenticating = false

    private var phase: ScenePhase = .active
    /// Ask for Face ID as soon as the app is active again, but only once per trip to the background, so a cancelled
    /// prompt doesn't pop up again by itself.
    private var promptWhenActive: Bool
    private var shield: UIWindow?

    init() {
        let on = AppSettings.appLock && AppSettings.isPro
        isEnabled = on
        isLocked = on
        promptWhenActive = on
    }

    /// What this phone offers right now.
    static var method: Method {
        let context = LAContext()
        var error: NSError?
        if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) {
            switch context.biometryType {
            case .faceID: return .faceID
            case .touchID: return .touchID
            case .opticID: return .opticID
            default: return .passcode
            }
        }
        return context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) ? .passcode : .unavailable
    }

    /// Whatever hides the app's content: the lock screen, or the plain cover while the app isn't in front.
    var showsCover: Bool { isEnabled && (isLocked || phase != .active) }

    /// Turning the lock on needs one successful unlock first. Returns whether the lock is on afterwards.
    @discardableResult
    func setEnabled(_ on: Bool) async -> Bool {
        guard on else {
            AppSettings.appLock = false
            isEnabled = false
            isLocked = false
            updateShield()
            return false
        }
        guard AppSettings.isPro, Self.method != .unavailable else { return false }
        guard await authenticate(reason: String(localized: "Turn on app lock")) else { return false }
        AppSettings.appLock = true
        isEnabled = true
        isLocked = false
        return true
    }

    func unlock() async {
        guard isLocked, !isAuthenticating else { return }
        isAuthenticating = true
        let ok = await authenticate(reason: String(localized: "Unlock PocketCash"))
        isAuthenticating = false
        if ok {
            isLocked = false
            updateShield()
        }
    }

    func scenePhaseChanged(_ newPhase: ScenePhase) {
        phase = newPhase
        refresh()
    }

    /// Re-reads the setting (Pro may have been lost) and locks, prompts or uncovers for the current phase.
    func refresh() {
        guard AppSettings.appLock && AppSettings.isPro else {
            isEnabled = false
            isLocked = false
            updateShield()
            return
        }
        isEnabled = true
        switch phase {
        case .background:
            isLocked = true
            promptWhenActive = true
        case .active:
            if isLocked && promptWhenActive && !isAuthenticating {
                promptWhenActive = false
                Task { await unlock() }
            }
        default:
            break
        }
        updateShield()
    }

    private func authenticate(reason: String) async -> Bool {
        let context = LAContext()
        do {
            return try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
        } catch {
            return false
        }
    }

    private func updateShield() {
        guard showsCover else {
            shield?.isHidden = true
            return
        }
        if shield == nil,
           let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
            let window = UIWindow(windowScene: scene)
            window.windowLevel = .alert + 1
            let host = UIHostingController(rootView: LockScreenView(lock: self))
            host.view.backgroundColor = .systemBackground
            window.rootViewController = host
            shield = window
        }
        shield?.isHidden = false
    }
}

/// "PocketCash is locked" with an Unlock button. While the app is merely in the background it's a plain cover.
struct LockScreenView: View {
    @ObservedObject var lock: AppLock

    var body: some View {
        VStack(spacing: 18) {
            Spacer()
            Image(systemName: "wallet.pass")
                .font(.system(size: 34, weight: .regular))
                .foregroundStyle(.white)
                .frame(width: 72, height: 72)
                .background(Theme.emerald, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .accessibilityHidden(true)
            if lock.isLocked {
                Text("PocketCash is locked")
                    .font(.title3.weight(.semibold))
                Spacer()
                Button {
                    Task { await lock.unlock() }
                } label: {
                    Text("Unlock").font(.headline).frame(maxWidth: .infinity, minHeight: 34)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(lock.isAuthenticating)
                .padding(.horizontal, 24)
                .padding(.bottom, 24)
            } else {
                Spacer()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.background)
        .tint(Theme.emerald)
    }
}
