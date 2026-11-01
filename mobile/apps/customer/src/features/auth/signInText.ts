/* Words for a failed sign-in step, in the shopper's language. Known failures use the catalogue; only what the server
   alone knows (a field's validation text, a 403 reason) is shown as the server wrote it. */
import { retryWait, type SignInFailure } from '@/domain/signInErrors';
import { type MessageKey, type Translator } from '@/i18n';

const WAIT_KEY = { seconds: 'cu.wait.seconds', minutes: 'cu.wait.minutes', hours: 'cu.wait.hours' } as const satisfies Record<string, MessageKey>;

export function waitText(tr: Translator<MessageKey>, seconds: number): string {
  const w = retryWait(seconds);
  return tr(WAIT_KEY[w.unit], { n: w.n });
}

/** The message for a failure (shown under the field named by `failure.fields`, or on the form). */
export function failureText(tr: Translator<MessageKey>, failure: SignInFailure): string {
  switch (failure.kind) {
    case 'credentials':
      return tr('cu.signIn.credentialsWrong');
    case 'invalid-code':
      return tr('cu.mfa.wrongCode');
    case 'rate-limited':
      return failure.retryAfterSeconds ? tr('cu.signIn.rateLimited', { wait: waitText(tr, failure.retryAfterSeconds) }) : tr('cu.signIn.rateLimitedLater');
    case 'network':
      return tr('cu.signIn.network');
    case 'expired':
    case 'server':
      return failure.message;
    case 'unknown':
      return tr('cu.signIn.failed');
  }
}
