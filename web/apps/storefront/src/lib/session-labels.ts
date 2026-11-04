import { type SessionTimeoutLabels } from '@afrotransact/ui';

import { type MessageKey, type Translator } from '@/i18n/t';

/** The session timer's strings from this app's catalogue (session.*, all five languages). */
export function sessionLabels(t: Translator<MessageKey>): SessionTimeoutLabels {
  return {
    warnTitle: t('session.warnTitle'),
    idleBody: t('session.idleBody'),
    absoluteBody: t('session.absoluteBody'),
    absoluteNote: t('session.absoluteNote'),
    timeLeft: t('session.timeLeft'),
    announceIdle: t('session.announceIdle'),
    announceAbsolute: t('session.announceAbsolute'),
    stay: t('session.stay'),
    signOutNow: t('session.signOutNow'),
    dismiss: t('session.dismiss'),
    close: t('session.close'),
    failed: t('session.failed'),
    endedTitle: t('session.endedTitle'),
    endedIdle: t('session.endedIdle'),
    endedAbsolute: t('session.endedAbsolute'),
    endedSignedOut: t('session.endedSignedOut'),
    signInAgain: t('session.signInAgain'),
    signingOut: t('session.signingOut')
  };
}
