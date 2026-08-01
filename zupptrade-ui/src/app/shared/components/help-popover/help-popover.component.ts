import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Small circled "?" that reveals a plain-English explanation on hover or keyboard focus.
 * Used on the signal (market strip) and recommendation widgets to build user trust by showing
 * WHY the system produced a given signal/trade. Renders nothing when there is no text.
 *
 * Pure CSS hover/focus — no JS state. stopPropagation on click so it never triggers a parent's
 * click handler (e.g. the market strip's expand toggle).
 */
@Component({
  selector: 'app-help-popover',
  standalone: true,
  imports: [CommonModule],
  template: `
    <span class="help" *ngIf="text" tabindex="0" role="button"
          [class.align-right]="align === 'right'"
          [class.tone-dark]="tone === 'dark'"
          [attr.aria-label]="'Why: ' + text"
          (click)="$event.stopPropagation()">
      <span class="help-icon" aria-hidden="true">?</span>
      <span class="help-pop" role="tooltip">
        <span class="help-pop-title">Why this {{ subject }}</span>
        {{ text }}
      </span>
    </span>
  `,
  styles: [`
    .help {
      position: relative;
      display: inline-flex;
      outline: none;
      vertical-align: middle;
    }
    .help-icon {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 15px;
      height: 15px;
      border-radius: 50%;
      background: #EFF2F7;
      color: #64748B;
      font-size: 10px;
      font-weight: 800;
      cursor: help;
      border: 1px solid #D9E0EA;
      transition: background .15s, color .15s;
    }
    .help:hover .help-icon,
    .help:focus .help-icon { background: #2563EB; color: #fff; border-color: #2563EB; }

    /* Dark variant — for use on coloured/gradient headers */
    .help.tone-dark .help-icon {
      background: rgba(255,255,255,.18);
      color: #fff;
      border-color: rgba(255,255,255,.45);
    }
    .help.tone-dark:hover .help-icon,
    .help.tone-dark:focus .help-icon { background: #fff; color: #2563EB; border-color: #fff; }

    .help-pop {
      position: absolute;
      top: calc(100% + 6px);
      left: 0;
      z-index: 60;
      width: 280px;
      max-width: 70vw;
      padding: 10px 12px;
      background: #fff;
      border: 1px solid #E2E8F0;
      border-radius: 8px;
      box-shadow: 0 6px 20px rgba(15,23,42,.16);
      font-size: 11.5px;
      font-weight: 500;
      line-height: 1.5;
      color: #334155;
      text-align: left;
      white-space: normal;
      opacity: 0;
      visibility: hidden;
      transform: translateY(-2px);
      transition: opacity .12s ease, transform .12s ease, visibility .12s;
      pointer-events: none;
    }
    /* Flip to the right edge when the icon sits near the viewport's right side */
    .help.align-right .help-pop { left: auto; right: 0; }

    .help:hover .help-pop,
    .help:focus .help-pop,
    .help:focus-within .help-pop {
      opacity: 1;
      visibility: visible;
      transform: translateY(0);
    }
    .help-pop-title {
      display: block;
      font-size: 10px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      color: #94A3B8;
      margin-bottom: 4px;
    }
  `],
})
export class HelpPopoverComponent {
  /** The explanation text. When null/empty the whole control renders nothing. */
  @Input() text: string | null = null;
  /** Noun used in the popover title, e.g. "signal" or "trade". */
  @Input() subject = 'signal';
  /** Which edge the popover anchors to. Use 'right' when the icon sits near the right edge. */
  @Input() align: 'left' | 'right' = 'left';
  /** Icon styling: 'light' for white backgrounds, 'dark' for coloured/gradient headers. */
  @Input() tone: 'light' | 'dark' = 'light';
}
