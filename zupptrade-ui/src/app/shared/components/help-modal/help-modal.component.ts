import { Component, EventEmitter, HostListener, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Centered "How ZuppTrade works" help popup, opened from the ? button in the nav.
 *
 * Content is intentionally terse (one line per concept) and number-free — it explains the
 * philosophy and each tab in plain language, mirroring the Contexts guides. Source of truth for
 * the copy is zupptrade-ui/HELP_CONTENT_DRAFT.md; keep the two in sync when editing.
 */
@Component({
  selector: 'app-help-modal',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="help-backdrop" *ngIf="open" (click)="closed.emit()">
      <div class="help-card" role="dialog" aria-modal="true" aria-label="How ZuppTrade works"
           (click)="$event.stopPropagation()">

        <header class="help-head">
          <h2>How ZuppTrade works</h2>
          <button class="help-x" (click)="closed.emit()" aria-label="Close">✕</button>
        </header>

        <div class="help-body">
          <!-- Philosophy -->
          <p class="help-philosophy">
            An automated Nifty 50 trading assistant — covering both weekly options and logical
            intraday futures — built for <strong>consistency, not big wins</strong>; every trade is
            rule-based, repeatable, and sized to your risk.
          </p>
          <p class="help-philosophy">
            It doesn't stop at entry: positions are <strong>monitored automatically and exited by
            rule</strong> — and nothing is a black box, so each signal, trade, and exit traces back
            to the exact inputs and simple maths you can re-derive yourself.
          </p>

          <!-- Trading -->
          <section>
            <h3>Trading — generate a signal, get a trade, let it run</h3>
            <p class="help-cap">What you do here</p>
            <ul>
              <li><b>Your view (optional)</b> — type a market view or paste commentary; it feeds one part of the scoring.</li>
              <li><b>Generate Signal</b> — the system reads the market and returns a direction.</li>
              <li><b>Trade card</b> — that direction becomes a specific options spread, sized to your risk.</li>
            </ul>
            <p class="help-cap">How the signal is calculated</p>
            <ul>
              <li>Five groups of evidence ("tiers") each cast a simple bullish / neutral / bearish vote.</li>
              <li>The tiers: price structure · technical indicators · institutional flow (FII/DII) · volatility &amp; macro · commentary &amp; news — some count more than others.</li>
              <li>Their weighted result becomes your <b>bias</b> and <b>strength</b>.</li>
              <li><b>Confidence</b> reflects how many tiers agree, adjusted for how calm or stressed volatility is.</li>
              <li>When volatility is extreme, the system won't auto-trade — it flags the situation to you.</li>
            </ul>
            <p class="help-cap">How the trade is built</p>
            <ul>
              <li>The strategy is chosen from your bias, the volatility regime, and how richly options are priced.</li>
              <li>It estimates how far the market can realistically move by expiry, and sells strikes beyond that likely range.</li>
              <li>The candidate must clear quality gates — a high probability of profit, internal consistency, and a worthwhile return — or it's rejected.</li>
              <li>Position size is set so a realistic bad outcome stays within a small, fixed slice of your capital.</li>
            </ul>
            <p class="help-cap">Live Trades Monitor (right-hand panel) — the trade watches itself</p>
            <ul>
              <li>A live trade is not place-and-forget: it's re-checked regularly while the market is open.</li>
              <li>Each check picks one of four actions: <b>Hold · Watch · Readjust · Exit</b>.</li>
              <li>Exit levels tighten as expiry nears — Watch as the market approaches, Readjust as it gets closer, Exit if it breaches.</li>
              <li>Safety first: a sharp volatility spike forces an immediate exit; extreme volatility pauses monitoring and alerts you.</li>
            </ul>
          </section>

          <!-- Futures -->
          <section>
            <h3>Futures — a separate, disciplined intraday engine</h3>
            <ul>
              <li>A standalone intraday Nifty-futures strategy: intraday only, hard stop-loss, no overnight risk.</li>
              <li>Runs twice around the open — first a primed plan, then a confirmation of which setup is live.</li>
              <li>Uses yesterday's range to draw reference levels and a grid of long/short rotation and breakout/breakdown setups.</li>
              <li>Bias picks the side, confidence sets how aggressively, and a compression check can veto a trade.</li>
              <li>A trade is placed only when price actually reaches the trigger level — no anticipation, no chasing.</li>
              <li>Every trade carries both a stop-loss and a profit-booking target, set upfront — disciplined risk management, never improvised.</li>
            </ul>
          </section>

          <!-- Audit -->
          <section>
            <h3>Audit — every number is re-derivable</h3>
            <ul>
              <li>Each trade is recorded end to end: the signal that started it, the trade built, the fills paid, and every check that followed.</li>
              <li>Turned into plain figures you can re-derive: how often it wins, how much of the possible profit it kept, and whether the signal was right.</li>
            </ul>
          </section>

          <!-- Profile -->
          <section>
            <h3>Profile — your risk settings</h3>
            <ul>
              <li>Your capital and risk limits — how safe a trade must be, how much you'll risk, and how tightly the trade must hold together.</li>
              <li>The tier weights that personalise how your signal is scored.</li>
            </ul>
          </section>

          <!-- Critical Alerts -->
          <section>
            <h3>Critical Alerts — when the system stops and asks you</h3>
            <ul>
              <li>Raised whenever the system can't be certain your position is safe — it stops and shows the full picture rather than guessing.</li>
              <li>Typical triggers: an order leg didn't fill (the other is auto-closed), an exit couldn't complete, extreme volatility forced an emergency action, or fills came in worse than expected.</li>
              <li>Each alert stays until you acknowledge it, so nothing critical is ever silently missed.</li>
            </ul>
          </section>

          <!-- Simulation -->
          <section>
            <h3>Simulation mode — test a full trade safely</h3>
            <ul>
              <li>A testing mode: instead of live market data, the system reads a saved scenario (a market path, option chain, flows, and sentiment).</li>
              <li>The whole flow — signal, trade, monitoring, exit — runs from that saved data, touching no live market and placing no real orders.</li>
              <li>Replay a full multi-day trade in minutes to verify how it behaves; copy a scenario, tweak the data, and re-run to explore new "what-ifs".</li>
            </ul>
          </section>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .help-backdrop {
      position: fixed; inset: 0; z-index: 1000;
      background: rgba(15, 23, 42, 0.5);
      display: flex; align-items: center; justify-content: center;
      padding: 24px;
    }
    .help-card {
      background: #fff;
      width: 100%; max-width: 720px;
      max-height: 86vh;
      border-radius: 12px;
      box-shadow: 0 20px 50px rgba(15, 23, 42, 0.25);
      display: flex; flex-direction: column;
      overflow: hidden;
    }
    .help-head {
      display: flex; align-items: center; justify-content: space-between;
      padding: 16px 22px;
      border-bottom: 1px solid #E2E8F0;
      flex-shrink: 0;
    }
    .help-head h2 { margin: 0; font-size: 16px; font-weight: 600; color: #0F172A; }
    .help-x {
      background: none; border: none; font-size: 16px; color: #94A3B8;
      cursor: pointer; line-height: 1; padding: 4px;
    }
    .help-x:hover { color: #475569; }
    .help-body {
      padding: 18px 22px 24px;
      overflow-y: auto;
      color: #475569; font-size: 13px; line-height: 1.55;
    }
    .help-philosophy {
      margin: 0 0 10px;
      padding: 12px 14px;
      background: #F1F5F9;
      border-left: 3px solid #2563EB;
      border-radius: 6px;
      color: #334155;
    }
    .help-body section { margin-top: 18px; }
    .help-body h3 {
      margin: 0 0 6px; font-size: 13.5px; font-weight: 700; color: #0F172A;
    }
    .help-cap {
      margin: 10px 0 3px; font-size: 11px; font-weight: 700;
      text-transform: uppercase; letter-spacing: .04em; color: #94A3B8;
    }
    .help-body ul { margin: 0 0 4px; padding-left: 18px; }
    .help-body li { margin: 3px 0; }
    .help-body b { color: #334155; font-weight: 600; }
  `],
})
export class HelpModalComponent {
  @Input() open = false;
  @Output() closed = new EventEmitter<void>();

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.open) this.closed.emit();
  }
}
