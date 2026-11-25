import Combine
import SwiftUI
import UIKit

/// Collapses the bottom bar while the person scrolls down and expands it again on the way up or at the top
/// (docs/spec.md section 7). Only the selected destination reports its scrolling.
@MainActor
final class NavBarState: ObservableObject {
    @Published private(set) var collapsed = false

    private var anchor: CGFloat = 0
    private var last: CGFloat = 0
    private var goingDown = false
    /// How far to scroll in one direction before the bar reacts, so small jiggles don't flip it.
    private let threshold: CGFloat = 28

    func scrolled(to y: CGFloat) {
        defer { last = y }
        if y <= 8 {
            anchor = y
            set(false)
            return
        }
        let down = y > last
        if down != goingDown {
            goingDown = down
            anchor = last
        }
        if y - anchor > threshold { set(true) } else if anchor - y > threshold { set(false) }
    }

    func expand() { set(false) }

    private func set(_ value: Bool) {
        guard collapsed != value else { return }
        withAnimation(.snappy(duration: 0.3)) { collapsed = value }
    }
}

/// The four destinations with the floating bottom bar on top. Every destination keeps its own navigation stack and
/// scroll position while another one is showing; each is created the first time it's opened.
struct MainShell: View {
    @EnvironmentObject private var model: AppModel
    @StateObject private var bar = NavBarState()
    @State private var visited: Set<Destination> = [.home]
    @State private var keyboardVisible = false

    var body: some View {
        let barHidden = model.route != nil || keyboardVisible
        ZStack {
            ForEach(Destination.allCases) { destination in
                if visited.contains(destination) || model.destination == destination {
                    let selected = model.destination == destination
                    screen(destination)
                        .opacity(selected ? 1 : 0)
                        .allowsHitTesting(selected)
                        .accessibilityHidden(!selected)
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            // The undo banner sits above the bar on every destination, since + adds from anywhere.
            VStack(spacing: 0) {
                if let undo = model.undo {
                    UndoBanner(state: undo) { model.undoLast() }
                }
                NavBar()
            }
            .opacity(barHidden ? 0 : 1)
            .offset(y: barHidden ? 120 : 0)
            .allowsHitTesting(!barHidden)
            .animation(.snappy(duration: 0.3), value: barHidden)
        }
        .environmentObject(bar)
        .onChange(of: model.destination) { _, destination in
            visited.insert(destination)
            bar.expand()
        }
        .sensoryFeedback(.selection, trigger: model.destination) { _, _ in AppSettings.haptics }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
            keyboardVisible = true
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            keyboardVisible = false
        }
    }

    @ViewBuilder
    private func screen(_ destination: Destination) -> some View {
        switch destination {
        case .home: HomeView()
        case .insights: InsightsScreen()
        case .envelopes: EnvelopesScreen()
        case .settings: SettingsView()
        }
    }
}

/// The floating capsule (Home, Insights, Envelopes, Settings) and the round + button beside it.
struct NavBar: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var bar: NavBarState
    @Namespace private var highlight

    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 2) {
                if bar.collapsed {
                    Button { bar.expand() } label: {
                        icon(model.destination, selected: true)
                            .frame(width: 44, height: 44)
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(model.destination.title))
                    .accessibilityHint(Text("Shows all destinations"))
                } else {
                    ForEach(Destination.allCases) { destination in
                        item(destination)
                    }
                }
            }
            .padding(5)
            .barSurface(Capsule())

            Spacer(minLength: 0)

            PlusButton()
        }
        .padding(.horizontal, 16)
        .padding(.top, 6)
        .padding(.bottom, 6)
    }

    private func icon(_ destination: Destination, selected: Bool) -> some View {
        Image(systemName: destination.icon)
            .font(.system(size: 17, weight: .regular))
            .foregroundStyle(selected ? Color.primary : Color.secondary)
    }

    private func item(_ destination: Destination) -> some View {
        let selected = model.destination == destination
        return Button {
            withAnimation(.snappy(duration: 0.3)) { model.destination = destination }
        } label: {
            HStack(spacing: 6) {
                icon(destination, selected: selected)
                if selected {
                    Text(destination.title)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .fixedSize()
                        .transition(.opacity.combined(with: .scale(scale: 0.8, anchor: .leading)))
                }
            }
            .padding(.horizontal, selected ? 14 : 12)
            .frame(height: 44)
            .background {
                if selected {
                    Capsule()
                        .fill(Color.primary.opacity(0.08))
                        .matchedGeometryEffect(id: "highlight", in: highlight)
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(destination.title))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Tap: the keypad. Touch and hold: Count my cash.
struct PlusButton: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Image(systemName: "plus")
            .font(.system(size: 22, weight: .medium))
            .foregroundStyle(.white)
            .frame(width: 54, height: 54)
            .plusSurface()
            .contentShape(Circle())
            .onTapGesture { model.route = .keypad(.spend) }
            .onLongPressGesture(minimumDuration: 0.4) {
                if AppSettings.haptics { model.lightTick += 1 }
                model.route = .count
            }
            .accessibilityElement()
            .accessibilityLabel(Text("New cash move"))
            .accessibilityHint(Text("Opens the keypad. Touch and hold to count your cash."))
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { model.route = .keypad(.spend) }
            .accessibilityAction(named: Text("Count my cash")) { model.route = .count }
    }
}

extension View {
    /// Liquid Glass on iOS 26 and later, a thin material with a hairline edge before that.
    @ViewBuilder
    func barSurface<S: Shape>(_ shape: S) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(.regular.interactive(), in: shape)
        } else {
            self.background(.thinMaterial, in: shape)
                .overlay(shape.stroke(Theme.hairline, lineWidth: 0.5))
        }
    }

    @ViewBuilder
    fileprivate func plusSurface() -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(.regular.tint(Theme.emerald).interactive(), in: Circle())
        } else {
            self.background(Theme.emerald, in: Circle())
        }
    }

    /// Lets the bottom bar collapse while this destination scrolls down.
    func tracksNavBar(_ destination: Destination) -> some View {
        modifier(NavBarScrollTracking(destination: destination))
    }
}

private struct NavBarScrollTracking: ViewModifier {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var bar: NavBarState
    let destination: Destination

    func body(content: Content) -> some View {
        content.onScrollGeometryChange(for: CGFloat.self) { geometry in
            geometry.contentOffset.y + geometry.contentInsets.top
        } action: { _, y in
            if model.destination == destination { bar.scrolled(to: y) }
        }
    }
}

/// A Pro destination for Free users: a quiet, blurred preview with an Unlock button that opens the paywall.
struct ProPreview<Content: View>: View {
    @EnvironmentObject private var model: AppModel
    let title: LocalizedStringKey
    let message: LocalizedStringKey
    @ViewBuilder let content: Content

    var body: some View {
        ZStack {
            content
                .blur(radius: 9)
                .opacity(0.45)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            VStack(spacing: 12) {
                Image(systemName: "lock")
                    .font(.title2)
                    .foregroundStyle(.secondary)
                Text(title)
                    .font(.title3.weight(.semibold))
                    .multilineTextAlignment(.center)
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Button { model.route = .paywall } label: {
                    Text("Unlock").font(.headline).padding(.horizontal, 28).frame(minHeight: 34)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding(.top, 4)
            }
            .padding(32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
