import SwiftUI

/// The single canvas (docs/spec.md section 7): wallet switcher, hero balance, action pad, insight card, feed.
struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore
    @State private var collapsed: Set<String> = []

    var body: some View {
        NavigationStack {
            if let wallet = model.wallet {
                content(wallet)
            }
        }
    }

    private func content(_ wallet: Wallet) -> some View {
        let now = Date()
        let feed = pro.isPro ? Feed.byCycle(wallet, now: now) : Feed.byDay(wallet, now: now)
        return List {
            Section {
                HeroBalance(wallet: wallet)
                ActionPad(wallet: wallet)
                InsightCard(wallet: wallet)
            }
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))

            if feed.groups.isEmpty {
                Section {
                    ContentUnavailableView("No cash moves yet", systemImage: "banknote",
                                           description: Text("Tap a button above when you pay or withdraw cash."))
                }
                .listRowBackground(Color.clear)
            }

            ForEach(feed.groups) { group in
                Section {
                    if !collapsed.contains(group.id) {
                        ForEach(group.items) { tx in
                            TransactionRow(tx: tx, wallet: wallet)
                        }
                    }
                } header: {
                    FeedHeader(group: group, collapsed: collapsed.contains(group.id)) {
                        withAnimation(.snappy) {
                            if collapsed.contains(group.id) { collapsed.remove(group.id) } else { collapsed.insert(group.id) }
                        }
                    }
                }
            }

            if feed.hasOlder {
                Section {
                    Button { model.route = .paywall } label: {
                        Label("Older history and ATM cycles are in Pro", systemImage: "lock.fill")
                            .font(.subheadline)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .refreshable { await MainActor.run { model.route = .count } }
        .safeAreaInset(edge: .bottom) {
            if let undo = model.undo {
                UndoBanner(state: undo) { model.undoLast() }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) { WalletMenu(current: wallet) }
            ToolbarItem(placement: .topBarTrailing) {
                Button { model.route = .settings } label: { Image(systemName: "gearshape") }
                    .accessibilityLabel("Settings")
            }
        }
    }
}

/// "Main Wallet ▾": switch wallets, add one (Pro), and a PRO badge when unlocked.
private struct WalletMenu: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore
    let current: Wallet

    var body: some View {
        Menu {
            ForEach(model.wallets) { wallet in
                Button {
                    if pro.isPro { model.selectedWalletID = wallet.id } else if wallet.id != model.wallets.first?.id { model.route = .paywall }
                } label: {
                    if wallet.id == current.id { Label(wallet.name, systemImage: "checkmark") } else { Text(wallet.name) }
                }
            }
            Divider()
            Button { model.route = pro.isPro ? .settings : .paywall } label: {
                Label("Add a wallet", systemImage: pro.isPro ? "plus" : "lock.fill")
            }
        } label: {
            HStack(spacing: 6) {
                Text(current.name).font(.headline).lineLimit(1)
                Image(systemName: "chevron.down").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                if pro.isPro { ProBadge() }
            }
            .foregroundStyle(.primary)
        }
    }
}

struct ProBadge: View {
    var body: some View {
        Text("PRO")
            .font(.system(size: 10, weight: .heavy, design: .rounded))
            .foregroundStyle(.white)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Theme.emerald, in: Capsule())
    }
}

/// The oversized balance. Tap it, or pull the screen down, to count your cash.
struct HeroBalance: View {
    @EnvironmentObject private var model: AppModel
    let wallet: Wallet

    var body: some View {
        let items = wallet.ledger
        let balance = Ledger.balance(items)
        let today = Ledger.todaySpent(items, now: .now)
        VStack(spacing: 8) {
            Text(wallet.format(balance))
                .font(Theme.balance(64))
                .lineLimit(1)
                .minimumScaleFactor(0.35)
                .foregroundStyle(balance < 0 ? Theme.danger : .primary)
                .contentTransition(.numericText(value: Money.double(balance)))
                .animation(.snappy, value: balance)
            HStack(spacing: 6) {
                Text(today == 0 ? "Nothing spent today" : "Today \(wallet.format(-today))")
                    .monospacedDigit()
                if let budget = wallet.dailyBudget, budget > 0 {
                    let left = budget - today
                    Text("·")
                    Text(left >= 0 ? "\(wallet.format(left)) left today" : "Over by \(wallet.format(-left))")
                        .monospacedDigit()
                        .foregroundStyle(left >= 0 ? Theme.emerald : Theme.danger)
                }
            }
            .font(.subheadline.weight(.medium))
            .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 8)
        .padding(.bottom, 4)
        .contentShape(Rectangle())
        .onTapGesture { model.route = .count }
        .accessibilityElement(children: .combine)
        .accessibilityHint("Count your cash")
        .accessibilityAddTraits(.isButton)
    }
}

/// Cash-in presets (emerald), spend presets (monochrome), and Custom.
struct ActionPad: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore
    let wallet: Wallet

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                ForEach(Array(wallet.addButtons(isPro: pro.isPro).enumerated()), id: \.offset) { _, value in
                    PadButton(label: Money.buttonLabel(value, cashIn: true), style: .cashIn) {
                        model.add(value, cashIn: true)
                    }
                }
            }
            HStack(spacing: 10) {
                ForEach(Array(wallet.spendButtons(isPro: pro.isPro).enumerated()), id: \.offset) { _, value in
                    PadButton(label: Money.buttonLabel(value, cashIn: false), style: .spend) {
                        model.add(value, cashIn: false)
                    }
                }
            }
            HStack(spacing: 10) {
                PadButton(label: "Custom", systemImage: "number", style: .neutral) {
                    model.route = .keypad(.spend)
                }
                if pro.isPro && model.wallets.count > 1 {
                    PadButton(label: "Move", systemImage: "arrow.left.arrow.right", style: .neutral) {
                        model.route = .transfer
                    }
                }
            }
        }
        .contextMenu {
            Button { model.route = pro.isPro ? .settings : .paywall } label: {
                Label("Edit buttons…", systemImage: pro.isPro ? "slider.horizontal.3" : "lock.fill")
            }
        }
    }
}

struct PadButton: View {
    enum Style { case cashIn, spend, neutral }

    let label: String
    var systemImage: String?
    let style: Style
    let action: () -> Void

    private var foreground: Color {
        switch style {
        case .cashIn: return Theme.emerald
        case .spend, .neutral: return .primary
        }
    }

    private var fill: Color {
        switch style {
        case .cashIn: return Theme.emerald.opacity(0.15)
        case .spend: return Color.primary.opacity(0.07)
        case .neutral: return Theme.surface
        }
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage) }
                Text(label)
            }
            .font(.system(style == .neutral ? .body : .title3, design: .rounded, weight: .semibold))
            .monospacedDigit()
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .foregroundStyle(foreground)
            .frame(maxWidth: .infinity, minHeight: style == .neutral ? 48 : 58)
            .padSurface(fill)
        }
        .buttonStyle(PressableStyle())
    }
}

struct PressableStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.95 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.snappy(duration: 0.15), value: configuration.isPressed)
    }
}

extension View {
    /// Liquid Glass on iOS 26 and later, a soft rounded fill before that.
    @ViewBuilder
    func padSurface(_ color: Color) -> some View {
        if #available(iOS 26.0, *) {
            self.background(color, in: RoundedRectangle(cornerRadius: Theme.corner, style: .continuous))
                .glassEffect(.regular.interactive(), in: RoundedRectangle(cornerRadius: Theme.corner, style: .continuous))
        } else {
            self.background(color, in: RoundedRectangle(cornerRadius: Theme.corner, style: .continuous))
        }
    }
}

/// Burn rate and runway with Pro, a teaser without.
struct InsightCard: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore
    let wallet: Wallet

    var body: some View {
        Button { model.route = pro.isPro ? .insights : .paywall } label: {
            HStack(spacing: 12) {
                Image(systemName: "flame.fill")
                    .font(.title3)
                    .foregroundStyle(.orange)
                    .frame(width: 32)
                VStack(alignment: .leading, spacing: 2) {
                    if pro.isPro {
                        let summary = BurnSummary(wallet: wallet, now: .now)
                        Text(summary.headline).font(.subheadline.weight(.semibold))
                        Text(summary.detail).font(.caption).foregroundStyle(.secondary)
                    } else {
                        Text("How fast does your cash burn?").font(.subheadline.weight(.semibold))
                        Text("ATM cycles, burn rate and a runway forecast with Pro").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: pro.isPro ? "chevron.right" : "lock.fill").foregroundStyle(.tertiary)
            }
            .padding(14)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: Theme.corner, style: .continuous))
        }
        .buttonStyle(PressableStyle())
    }
}

/// Burn rate and runway in words.
struct BurnSummary {
    let headline: String
    let detail: String

    init(wallet: Wallet, now: Date) {
        let items = wallet.ledger
        let balance = Ledger.balance(items)
        guard let rate = Ledger.burnRatePerDay(items, now: now), rate > 0 else {
            headline = "Not enough spending yet"
            detail = "Your burn rate shows up after a few cash moves."
            return
        }
        let perDay = Money.rounded(Decimal(rate), scale: wallet.allowsFractions ? 2 : 0)
        headline = "Burning \(wallet.format(perDay)) a day"
        if let days = Ledger.runwayDays(balance: balance, burnRate: rate) {
            let date = now.addingTimeInterval(days * Ledger.secondsPerDay)
            detail = days < 1 ? "Cash runs out today at this pace"
                : "Cash lasts until about \(date.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day()))"
        } else {
            detail = "Time for an ATM run"
        }
    }
}

struct UndoBanner: View {
    let state: UndoState
    let onUndo: () -> Void

    var body: some View {
        HStack {
            Text(state.text)
                .font(.subheadline.weight(.semibold))
                .monospacedDigit()
                .lineLimit(1)
            Spacer()
            Button("Undo", action: onUndo)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(Theme.emerald)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .background(.regularMaterial, in: Capsule())
        .shadow(color: .black.opacity(0.12), radius: 12, y: 4)
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}
