/* PocketCash money rules for the preview site (docs/spec.md sections 3 and 4).
   Amounts are integer hundredths ("cents") so sums stay exact; timestamps are epoch milliseconds.
   Checked against spec/vectors/cycles.json, the same vectors the iOS and Android tests read. */
(function (root) {
  'use strict';

  const DAY = 86400000;

  /** Money that left the wallet: spends, and negative reconciles ("unrecorded spending"). */
  const spending = t => (t.kind === 'spend' || (t.kind === 'adjust' && t.amount < 0)) ? -t.amount : 0;
  /** Money that came in: cash in, ATM withdrawals, and positive reconciles. Transfers are neither. */
  const inflow = t => (t.kind === 'in' || t.kind === 'atm' || (t.kind === 'adjust' && t.amount > 0)) ? t.amount : 0;
  const sorted = txs => txs.slice().sort((a, b) => a.at - b.at);
  const balance = txs => txs.reduce((s, t) => s + t.amount, 0);
  const total = (txs, f) => txs.reduce((s, t) => s + f(t), 0);

  function wallClock(ms, timeZone) {
    const f = new Intl.DateTimeFormat('en-US', {
      timeZone, hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric',
      hour: 'numeric', minute: 'numeric', second: 'numeric',
    });
    const p = {};
    for (const part of f.formatToParts(new Date(ms))) p[part.type] = Number(part.value);
    return p;
  }

  function zoneOffset(ms, timeZone) {
    const p = wallClock(ms, timeZone);
    return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - Math.floor(ms / 1000) * 1000;
  }

  /** Midnight of the day containing `ms`, in `timeZone` (undefined: the browser's own zone). */
  function startOfDay(ms, timeZone) {
    if (!timeZone) {
      const d = new Date(ms);
      d.setHours(0, 0, 0, 0);
      return d.getTime();
    }
    const p = wallClock(ms, timeZone);
    const target = Date.UTC(p.year, p.month - 1, p.day);
    let guess = target - zoneOffset(target, timeZone);
    guess = target - zoneOffset(guess, timeZone); // second pass settles DST edges
    return guess;
  }

  const nextDay = (dayStart, timeZone) => startOfDay(dayStart + 30 * 3600000, timeZone);

  function todaySpent(txs, now, timeZone) {
    const start = startOfDay(now, timeZone);
    const end = nextDay(start, timeZone);
    return total(txs.filter(t => t.at >= start && t.at < end), spending);
  }

  /** ATM cycles, oldest first. */
  function cycles(txs, now) {
    const s = sorted(txs);
    if (!s.length) return [];
    const atms = [];
    s.forEach((t, i) => { if (t.kind === 'atm') atms.push(i); });
    const groups = [];
    const firstATM = atms.length ? atms[0] : s.length;
    if (firstATM > 0) groups.push({ isATM: false, items: s.slice(0, firstATM), start: s[0].at, withdrawn: 0, atm: null });
    atms.forEach((idx, k) => {
      const next = k + 1 < atms.length ? atms[k + 1] : s.length;
      groups.push({ isATM: true, items: s.slice(idx, next), start: s[idx].at, withdrawn: s[idx].amount, atm: s[idx] });
    });
    return groups.map((g, i) => {
      const end = i + 1 < groups.length ? groups[i + 1].start : null;
      const spent = total(g.items, spending);
      const days = ((end == null ? now : end) - g.start) / DAY;
      return {
        isATM: g.isATM,
        start: g.start,
        end,
        isOpen: end == null,
        withdrawn: g.withdrawn,
        spent,
        inflow: total(g.items.filter(t => t !== g.atm), inflow),
        days,
        avgDaily: spent / Math.max(days, 1),
        ...topAmount(g.items),
        topTag: topTag(g.items),
        items: g.items,
      };
    });
  }

  /** Which denomination drains the wallet fastest: by total, then count, then the larger amount. */
  function topAmount(items) {
    const groups = new Map();
    for (const t of items) {
      if (t.kind !== 'spend') continue;
      const key = Math.abs(t.amount);
      const g = groups.get(key) || { amount: key, count: 0, total: 0 };
      g.count += 1;
      g.total += key;
      groups.set(key, g);
    }
    let best = null;
    for (const g of groups.values()) {
      if (!best || g.total > best.total || (g.total === best.total && (g.count > best.count ||
          (g.count === best.count && g.amount > best.amount)))) best = g;
    }
    return best
      ? { topAmount: best.amount, topAmountCount: best.count, topAmountTotal: best.total }
      : { topAmount: null, topAmountCount: 0, topAmountTotal: 0 };
  }

  /** The tag with the most spending (untagged excluded), ties broken alphabetically. */
  function topTag(items) {
    const byTag = spendingByTag(items);
    let best = null;
    for (const [tag, amount] of byTag) {
      if (amount <= 0) continue;
      if (!best || amount > best[1] || (amount === best[1] && tag < best[0])) best = [tag, amount];
    }
    return best ? best[0] : null;
  }

  function spendingByTag(items) {
    const byTag = new Map();
    for (const t of items) {
      const spent = spending(t);
      if (!t.tag || spent <= 0) continue;
      byTag.set(t.tag, (byTag.get(t.tag) || 0) + spent);
    }
    return byTag;
  }

  /** Spending per day over the last 14 days (cents per day), or null with no transactions. */
  function burnRatePerDay(txs, now) {
    if (!txs.length) return null;
    const first = Math.min(...txs.map(t => t.at));
    const age = Math.min(Math.max((now - first) / DAY, 1), 14);
    const recent = txs.filter(t => t.at > now - 14 * DAY && t.at <= now);
    return total(recent, spending) / age;
  }

  /** Days the balance lasts at the burn rate, only when both are positive. */
  const runwayDays = (bal, rate) => (bal > 0 && rate > 0) ? bal / rate : null;

  const api = {
    DAY, spending, inflow, sorted, balance, startOfDay, nextDay, todaySpent, cycles,
    spendingByTag, burnRatePerDay, runwayDays,
  };
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.PocketLedger = api;
})(typeof window !== 'undefined' ? window : globalThis);
