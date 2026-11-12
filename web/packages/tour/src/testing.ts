import { type EngineCallbacks, type StepView, type TourEngine, type TourLabels } from './engine';
import { type TourEnvironment, type TourEvent } from './environment';

/** Test doubles for the tour service: a recording engine and a scriptable page. Used by this package's tests and the apps'. */

export const TEST_LABELS: TourLabels = {
  next: 'Next',
  back: 'Back',
  skip: 'Skip tour',
  done: 'Done',
  close: 'Close tour',
  dontShowAgain: "Don't show again",
  dialog: 'Guided tour',
  stepOf: 'Step {n} of {total}',
  stepDone: 'Done',
  started: 'Guided tour started.',
  finished: 'Guided tour ended.'
};

export interface FakeEngine extends TourEngine {
  /** Every step drawn, in order. */
  readonly shown: StepView[];
  readonly announcements: string[];
  hidden: number;
  /** The last callbacks the service handed over (what the buttons would call). */
  callbacks: EngineCallbacks | null;
  readonly last: StepView | undefined;
}

export function createFakeEngine(): FakeEngine {
  const engine: FakeEngine = {
    shown: [],
    announcements: [],
    hidden: 0,
    callbacks: null,
    get last() {
      return engine.shown[engine.shown.length - 1];
    },
    async show(view, callbacks) {
      engine.shown.push(view);
      engine.callbacks = callbacks;
    },
    hide() {
      engine.hidden += 1;
    },
    announce(message) {
      engine.announcements.push(message);
    }
  };
  return engine;
}

export interface FakeEnvironment extends TourEnvironment {
  /** Ids of the targets currently "on the page". */
  targets: Set<string>;
  width: number;
  rtl: boolean;
  reduced: boolean;
  blocking: boolean;
  unsaved: boolean;
  /** Pretend the page is driven by automation. */
  robot: boolean;
  readonly logs: string[];
  readonly events: TourEvent[];
  /** Calls the listeners registered with observe() (after changing `targets`). */
  notify(): void;
}

export function createFakeEnvironment(targets: Iterable<string> = []): FakeEnvironment {
  const listeners = new Set<() => void>();
  const env: FakeEnvironment = {
    targets: new Set(targets),
    width: 1280,
    rtl: false,
    reduced: false,
    blocking: false,
    unsaved: false,
    robot: false,
    logs: [],
    events: [],
    notify: () => [...listeners].forEach((l) => l()),
    findTarget: (id) => (env.targets.has(id) ? (document.body as HTMLElement) : null),
    waitForTarget: async (id) => env.findTarget(id),
    observe: (l) => {
      listeners.add(l);
      return () => void listeners.delete(l);
    },
    onClick: () => () => undefined,
    viewportWidth: () => env.width,
    reducedMotion: () => env.reduced,
    direction: () => (env.rtl ? 'rtl' : 'ltr'),
    automated: () => env.robot,
    hasBlockingUi: () => env.blocking,
    hasUnsavedInput: () => env.unsaved,
    emit: (e) => void env.events.push(e),
    isDev: () => true,
    log: (m) => void env.logs.push(m)
  };
  return env;
}
