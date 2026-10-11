import { type TotpSetup } from '@afrotransact/api-client';

/* Authenticator-app enrolment as a small state machine, so every app walks the same steps:
   idle → starting → scan (QR + setup key, code entry) → verifying → codes (first time) | done; errors stay on scan. */

export type EnrolmentStep = 'idle' | 'starting' | 'scan' | 'verifying' | 'codes' | 'done' | 'failed';

export interface EnrolmentState {
  step: EnrolmentStep;
  setup: TotpSetup | null;
  codes: string[];
  error: string | null;
}

export type EnrolmentAction =
  | { type: 'start' }
  | { type: 'started'; setup: TotpSetup }
  | { type: 'startFailed'; error: string }
  | { type: 'submit' }
  | { type: 'rejected'; error: string }
  | { type: 'confirmed'; codes: string[] }
  | { type: 'saved' }
  | { type: 'reset' };

export const initialEnrolment: EnrolmentState = { step: 'idle', setup: null, codes: [], error: null };

export function enrolmentReducer(state: EnrolmentState, action: EnrolmentAction): EnrolmentState {
  switch (action.type) {
    case 'start':
      return { ...initialEnrolment, step: 'starting' };
    case 'started':
      return state.step === 'starting' ? { ...state, step: 'scan', setup: action.setup, error: null } : state;
    case 'startFailed':
      return { ...state, step: 'failed', error: action.error };
    case 'submit':
      return state.step === 'scan' ? { ...state, step: 'verifying', error: null } : state;
    case 'rejected':
      return state.step === 'verifying' || state.step === 'scan' ? { ...state, step: 'scan', error: action.error } : state;
    case 'confirmed':
      return state.step === 'verifying' ? { ...state, step: action.codes.length ? 'codes' : 'done', codes: action.codes, error: null } : state;
    case 'saved':
      return state.step === 'codes' ? { ...state, step: 'done' } : state;
    case 'reset':
      return initialEnrolment;
  }
}

/** A base32 setup key in groups of four for reading aloud or typing ("JBSW Y3DP EHPK 3PXP"). */
export function groupKey(secret: string): string {
  return secret.replace(/\s+/g, '').replace(/(.{4})(?=.)/g, '$1 ');
}

/** Keeps the digits of a typed or pasted code, at most six. */
export function cleanCode(v: string): string {
  return v.replace(/\D/g, '').slice(0, 6);
}

/** Plain-text file of recovery codes for download. */
export function recoveryFile(header: string, codes: string[]): string {
  return `${header}\n\n${codes.map((c, i) => `${String(i + 1).padStart(2, ' ')}. ${c}`).join('\n')}\n`;
}
