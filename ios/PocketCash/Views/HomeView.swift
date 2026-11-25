import SwiftUI

/// Home (docs/spec.md section 7.1): wallet switcher, hero balance, action pad, the Pro insight line and the feed.
/// No title; once the hero balance scrolls under the top bar it appears small in the bar's center.
struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore
    @State private var collapsed: Set<String> = []
    @State private var showsTopBalance = false

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
            Group {
                HeroBalance(wallet: wallet)
                ActionPad(wallet: wallet)
                if pro.isPro {
                    InsightLine(wallet: wallet)
                }
            }
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets(top: 8, leading: 20, bottom: 8, trailing: 20))

            if feed.groups.isEmpty {
                ContentUnavailableView("No cash moves yet", systemImage: "banknote",
                                       description: Text("Tap a button above when you pay or withdraw cash."))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }

            ForEach(feed.groups) { group in
                Section {
                    if !collapsed.contains(group.id) {
                        ForEach(group.items) { tx in
                            TransactionRow(tx: tx, wallet: wallet)
                                .listRowBackground(Color.clear)
                        }
                    }
                } header: {
                    FeedHeader(group: group, collapsible: pro.isPro, collapsed: collapsed.contains(group.id)) {
                        withAnimation(.snappy) {
                            if collapsed.contains(group.id) { collapsed.remove(group.id) } else { collapsed.insert(group.id) }
                        }
                    }
                }
            }

            if feed.hasOlder {
                Button { model.route = .paywall } label: {
                    Label("Older history and ATM cycles are in Pro", systemImage: "lock")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .refreshable { await MainActor.run { model.route = .count } }
        .tracksNavBar(.home)
        .onScrollGeometryChange(for: Bool.self) { geometry in
            geometry.contentOffset.y + geometry.contentInsets.top > 96
        } action: { _, show in
            withAnimation(.snappy(duration: 0.25)) { showsTopBalance = show }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) { WalletMenu(current: wallet) }
            ToolbarItem(placement: .principal) {
                Text(wallet.format(wallet.balance))
                    .font(.headline.monospacedDigit())
                    .lineLimit(1)
                    .opacity(showsTopBalance ? 1 : 0)
                    .offset(y: showsTopBalance ? 0 : 8)
                    .accessibilityHidden(!showsTopBalance)
            }
        }
        .toolbarBackground(Theme.background, for: .navigationBar)
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
            Button { model.route = pro.isPro ? .newWallet : .paywall } label: {
                Label("Add a wallet", systemImage: pro.isPro ? "plus" : "lock")
            }
        } label: {
            HStack(spacing: 6) {
                Text(current.name).font(.headline).lineLimit(1)
                Image(systemName: "chevron.down").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                if pro.isPro { ProBadge() }
            }
            .foregroundStyle(.primary)
        }
    }
}

/// A small monochrome "PRO" tag.
struct ProBadge: View {
    var body: some View {
        Text(verbatim: "PRO")
            .font(.system(size: 9, weight: .bold, design: .rounded))
            .tracking(0.5)
            .foregroundStyle(.secondary)
            .padding(.horizontal, 5)
            .padding(.vertical, 2)
            .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))
    }
}

/// The oversized balance. Tap it, or pull the screen down, to count your cash.
struct HeroBalance: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore
    let wallet: Wallet

    var body: some View {
        let items = wallet.ledger
        let balance = Ledger.balance(items)
        let today = Ledger.todaySpent(items, now: .now)
        let spentText = wallet.format(-today)
        VStack(spacing: 10) {
            Text(wallet.format(balance))
                .font(Theme.balance(68))
                .lineLimit(1)
                .minimumScaleFactor(0.35)
                .foregroundStyle(balance < 0 ? Theme.danger : .primary)
                .contentTransition(.numericText(value: Money.double(balance)))
                .animation(.snappy, value: balance)
            HStack(spacing: 6) {
                if today == 0 {
                    Text("Nothing spent today")
                } else {
                    Text("Today \(spentText)").monospacedDigit()
                }
                if let budget = wallet.activeBudget(isPro: pro.isPro) {
                    let left = budget - today
                    Text(verbatim: "·")
                    if left >= 0 {
                        let leftText = wallet.format(left)
                        Text("\(leftText) left today").monospacedDigit()
                    } else {
                        let overText = wallet.format(-left)
                        Text("Over by \(overText)").monospacedDigit().foregroundStyle(Theme.danger)
                    }
                }
            }
            .font(.subheadline)
            .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 12)
        .padding(.bottom, 12)
        .contentShape(Rectangle())
        .onTapGesture { model.route = .count }
        .accessibilityElement(children: .combine)
        .accessibilityHint(Text("Count your cash"))
        .accessibilityAddTraits(.isButton)
    }
}

/// Cash-in presets (muted emerald), spend presets (monochrome), and Custom.
struct ActionPad: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var pro: ProStore
    let wallet: Wallet

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                ForEach(Array(wallet.addButtons(isPro: pro.isPro).enumerated()), id: \.offset) { _, value in
                    PadButton(label: Text(verbatim: Money.buttonLabel(value, cashIn: true)), style: .cashIn) {
                        model.add(value, cashIn: true)
                    }
                }
            }
            HStack(spacing: 10) {
                ForEach(Array(wallet.spendButtons(isPro: pro.isPro).enumerated()), id: \.offset) { _, value in
                    PadButton(label: Text(verbatim: Money.buttonLabel(value, cashIn: false)), style: .spend) {
                        model.add(value, cashIn: false)
                    }
                }
            }
            HStack(spacing: 10) {
                PadButton(label: Text("Custom"), systemImage: "number", style: .neutral) {
                    model.route = .keypad(.spend)
                }
                if pro.isPro && model.wallets.count > 1 {
                    PadButton(label: Text("Move"), systemImage: "arrow.left.arrow.right", style: .neutral) {
                        model.route = .transfer
                    }
                }
            }
        }
        .contextMenu {
            Button { model.route = pro.isPro ? .editButtons : .paywall } label: {
                Label("Edit buttons…", systemImage: pro.isPro ? "slider.horizontal.3" : "lock")
            }
        }
    }
}

struct PadButton: View {
    enum Style { case cashIn, spend, neutral }

    let label: Text
    var systemImage: String?
    let style: Style
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage) }
                label
            }
            .font(.system(style == .neutral ? .body : .title3, design: .rounded, weight: style == .neutral ? .medium : .semibold))
            .monospacedDigit()
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .foregroundStyle(style == .cashIn ? Theme.emerald : Color.primary)
            .frame(maxWidth: .infinity, minHeight: style == .neutral ? 46 : 56)
            .background(fill, in: RoundedRectangle(cornerRadius: Theme.corner, style: .continuous))
            .overlay {
                if style == .neutral {
                    RoundedRectangle(cornerRadius: Theme.corner, style: .continuous).stroke(Theme.hairline, lineWidth: 1)
                }
            }
        }
        .buttonStyle(PressableStyle())
    }

    private var fill: Color {
        switch style {
        case .cashIn: return Theme.emerald.opacity(0.12)
        case .spend: return Theme.surface
        case .neutral: return .clear
        }
    }
}

struct PressableStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .opacity(configuration.isPressed ? 0.8 : 1)
            .animation(.snappy(duration: 0.15), value: configuration.isPressed)
    }
}

/// Pro: one quiet line with the burn rate and runway. Tapping it opens Insights.
struct InsightLine: View {
    @EnvironmentObject private var model: AppModel
    let wallet: Wallet

    var body: some View {
        if let line = BurnSummary(wallet: wallet, now: .now).line {
            Button { model.destination = .insights } label: {
                HStack(spacing: 6) {
                    Text(line).monospacedDigit()
                    Image(systemName: "chevron.right").font(.caption2.weight(.semibold))
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.plain)
        }
    }
}

/// Burn rate and runway in words (docs/spec.md section 4).
struct BurnSummary {
    let headline: String
    let detail: String
    /// Home's insight line, "₱61/day · lasts until about Thu, Dec 24"; nil until there's spending to measure.
    let line: String?

    init(wallet: Wallet, now: Date) {
        let items = wallet.ledger
        let balance = Ledger.balance(items)
        guard let rate = Ledger.burnRatePerDay(items, now: now), rate > 0 else {
            headline = String(localized: "Not enough spending yet")
            detail = String(localized: "Your burn rate shows up after a few cash moves.")
            line = nil
            return
        }
        let perDay = wallet.format(Money.rounded(Decimal(rate), scale: wallet.allowsFractions ? 2 : 0))
        headline = String(localized: "Burning \(perDay) a day")
        if let days = Ledger.runwayDays(balance: balance, burnRate: rate) {
            let date = now.addingTimeInterval(days * Ledger.secondsPerDay)
                .formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
            if days < 1 {
                detail = String(localized: "Cash runs out today at this pace")
                line = String(localized: "\(perDay)/day · runs out today")
            } else {
                detail = String(localized: "Cash lasts until about \(date)")
                line = String(localized: "\(perDay)/day · lasts until about \(date)")
            }
        } else {
            detail = String(localized: "Time for an ATM run")
            line = String(localized: "\(perDay)/day")
        }
    }
}

struct UndoBanner: View {
    let state: UndoState
    let onUndo: () -> Void

    var body: some View {
        HStack {
            Text(state.text)
                .font(.subheadline.weight(.medium))
                .monospacedDigit()
                .lineLimit(1)
            Spacer()
            Button("Undo", action: onUndo)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 13)
        .barSurface(Capsule())
        .padding(.horizontal, 16)
        .padding(.bottom, 6)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}
