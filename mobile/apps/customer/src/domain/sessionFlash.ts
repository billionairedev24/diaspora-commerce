/* Why the person is back on sign-in after the sign-out policy ended their session. The sign-in screen words each code in
   the shopper's language (cu.session.idle / cu.session.absolute). */
import { type SessionEndReason } from './sessionPolicy';

export type SessionFlashCode = 'session-idle' | 'session-absolute';

export function flashCodeFor(reason: SessionEndReason): SessionFlashCode {
  return reason === 'idle' ? 'session-idle' : 'session-absolute';
}
