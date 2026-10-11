import { PRIVACY_DETAILS_MAX, PRIVACY_REQUEST_TYPES, type PrivacyRequestInput, type PrivacyRequestType, US_STATES } from '@afrotransact/api-client';

/* Your Privacy Choices → "Make a privacy request" (design/AfroTransact Legal.dc.html). The same rules run in the
   gateway BFF and content-service; the form checks them first so every problem is listed at once. */

export interface PrivacyRequestForm {
  type: PrivacyRequestType | '';
  email: string;
  state: string;
  details: string;
}

export type PrivacyRequestField = 'type' | 'email' | 'state' | 'details';
/** i18n keys of the messages, per field. */
export type PrivacyRequestErrors = Partial<Record<PrivacyRequestField, 'legal.errType' | 'legal.errEmail' | 'legal.errState' | 'legal.errDetails'>>;

/** Fields in form order (the error summary lists them in this order). */
export const PRIVACY_FIELDS: readonly PrivacyRequestField[] = ['type', 'email', 'state', 'details'];

const EMAIL = /^[^\s@]+@[^\s@]+\.[a-z]{2,}$/i;

export const emptyPrivacyRequest = (email = ''): PrivacyRequestForm => ({ type: '', email, state: '', details: '' });

export function validatePrivacyRequest(f: PrivacyRequestForm): PrivacyRequestErrors {
  const e: PrivacyRequestErrors = {};
  if (!(PRIVACY_REQUEST_TYPES as readonly string[]).includes(f.type)) e.type = 'legal.errType';
  if (!EMAIL.test(f.email.trim())) e.email = 'legal.errEmail';
  if (!US_STATES.some(([code]) => code === f.state)) e.state = 'legal.errState';
  if (f.details.trim().length > PRIVACY_DETAILS_MAX) e.details = 'legal.errDetails';
  return e;
}

/** The request body for a valid form (call validatePrivacyRequest first). */
export function toPrivacyRequest(f: PrivacyRequestForm, locale: string): PrivacyRequestInput {
  const details = f.details.trim();
  return { type: f.type as PrivacyRequestType, email: f.email.trim(), state: f.state, ...(details ? { details } : {}), locale };
}

/** Maps a 422 from the BFF (English messages keyed by field) onto the form's own messages. */
export function serverErrors(fieldErrors: Record<string, string>): PrivacyRequestErrors {
  const messages = { type: 'legal.errType', email: 'legal.errEmail', state: 'legal.errState', details: 'legal.errDetails' } as const;
  const out: PrivacyRequestErrors = {};
  for (const f of PRIVACY_FIELDS) if (fieldErrors[f]) out[f] = messages[f];
  return out;
}
