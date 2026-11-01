/* First-run setup shows "1 of 3": Face ID → passcode (create, confirm) → notifications (pure; unit-tested). */

export type SetupStepName = 'face-id' | 'passcode' | 'notifications';

export const SETUP_ORDER: readonly SetupStepName[] = ['face-id', 'passcode', 'notifications'];

export interface SetupProgress {
  /** 1-based. */
  step: number;
  total: number;
}

export function setupProgress(step: SetupStepName): SetupProgress {
  return { step: SETUP_ORDER.indexOf(step) + 1, total: SETUP_ORDER.length };
}
