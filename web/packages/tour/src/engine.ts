import { type Placement } from './schema';

/*
 * TourEngine is the one seam between our tour logic and a spotlight/popover library. The service decides WHAT to show
 * (which step, in which order, when to skip) and records progress; the engine only draws one step and reports the
 * buttons and keys the person used. Replacing Driver.js with another library (or our own) means writing one more
 * TourEngine; nothing else changes. See docs/adr/0008-guided-tours-library.md.
 */

/** Text the popover needs that is not part of a tour (buttons, counter, announcements). Apps pass translated strings. */
export interface TourLabels {
  next: string;
  back: string;
  skip: string;
  done: string;
  /** Accessible name of the close (X) button. */
  close: string;
  dontShowAgain: string;
  /** Accessible name of the popover, e.g. "Guided tour". */
  dialog: string;
  /** "Step {n} of {total}". */
  stepOf: string;
  /** Shown on a step whose success condition is met, e.g. "Done, well done". */
  stepDone: string;
  /** Said aloud before the first step: "Guided tour started." */
  started: string;
  /** Said aloud when a tour ends. */
  finished: string;
}

/** One step, fully resolved and ready to draw. */
export interface StepView {
  /** Stable key of the step (tour id + step id); the engine may keep its popover when only `succeeded` changes. */
  key: string;
  title: string;
  body: string;
  /** The highlighted element; null for a centred step. */
  target: HTMLElement | null;
  placement: Placement;
  direction: 'ltr' | 'rtl';
  /** Narrow screens: draw a bottom sheet instead of a floating popover. */
  sheet: boolean;
  reducedMotion: boolean;
  /** Position among the steps that will be shown (skipped steps are not counted). */
  ordinal: number;
  total: number;
  isFirst: boolean;
  isLast: boolean;
  /** The step's success condition is met. */
  succeeded: boolean;
  /** Whether "Don't show again" is ticked. */
  dontShowAgain: boolean;
  labels: TourLabels;
}

export interface EngineCallbacks {
  next(): void;
  back(): void;
  /** The visible "Skip tour" button. */
  skip(): void;
  /** Esc or the X button. */
  close(): void;
  setDontShowAgain(value: boolean): void;
}

export interface TourEngine {
  /** Draws a step (replacing any step on screen). Returns once it is drawn; does not wait for the person. */
  show(view: StepView, callbacks: EngineCallbacks): Promise<void>;
  /** Removes the spotlight and popover and puts focus back where it was. */
  hide(): void;
  /** Says something to screen readers (polite live region). */
  announce(message: string): void;
}
