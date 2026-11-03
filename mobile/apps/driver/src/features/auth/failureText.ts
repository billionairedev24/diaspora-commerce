/* Words for a SignInFailure (domain/signInFailure.ts), in the driver's language. `waitLeft` is the live countdown of a
   rate limit; when the server gave no Retry-After the message says so without inventing a time. */
import { formatWait } from '@/domain/cooldown';
import { type SignInFailure } from '@/domain/signInFailure';
import { type MessageKey, type Translator } from '@/i18n';

const REQUIRED: Record<'email' | 'password' | 'code', MessageKey> = {
  email: 'dr.signIn.enterWorkEmail',
  password: 'dr.signIn.enterPassword',
  code: 'dr.mfa.enterCode'
};

export function failureMessage(tr: Translator<MessageKey>, f: SignInFailure, waitLeft?: number): string {
  switch (f.kind) {
    case 'credentials': return tr('dr.signIn.err.credentials');
    case 'invalid-code': return tr('dr.mfa.err.invalidCode');
    case 'field-required': return tr(REQUIRED[f.field]);
    case 'rate-limited': {
      const seconds = waitLeft && waitLeft > 0 ? waitLeft : f.retryAfterSeconds;
      return seconds ? tr('dr.signIn.err.rateLimited', { time: formatWait(seconds) }) : tr('dr.signIn.err.rateLimitedNoTime');
    }
    case 'expired': return tr('dr.signIn.err.expired');
    case 'refused': return f.detail;
    case 'offline': return tr('dr.signIn.err.offline');
    case 'unavailable': return tr('dr.signIn.err.unavailable');
    case 'unknown': return tr('dr.signIn.failed');
  }
}
