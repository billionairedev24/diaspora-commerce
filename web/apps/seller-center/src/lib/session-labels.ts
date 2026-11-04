import { type SessionTimeoutLabels } from '@afrotransact/ui';

import { type MessageKey, type Translator } from '@/i18n/t';

/** The session timer's strings from this app's catalogue (sc.session.*, all five languages). */
export function sessionLabels(t: Translator<MessageKey>): SessionTimeoutLabels {
  return {
    warnTitle: t('sc.session.warnTitle'),
    idleBody: t('sc.session.idleBody'),
    absoluteBody: t('sc.session.absoluteBody'),
    absoluteNote: t('sc.session.absoluteNote'),
    timeLeft: t('sc.session.timeLeft'),
    announceIdle: t('sc.session.announceIdle'),
    announceAbsolute: t('sc.session.announceAbsolute'),
    stay: t('sc.session.stay'),
    signOutNow: t('sc.session.signOutNow'),
    dismiss: t('sc.session.dismiss'),
    close: t('sc.session.close'),
    failed: t('sc.session.failed'),
    endedTitle: t('sc.session.endedTitle'),
    endedIdle: t('sc.session.endedIdle'),
    endedAbsolute: t('sc.session.endedAbsolute'),
    endedSignedOut: t('sc.session.endedSignedOut'),
    signInAgain: t('sc.session.signInAgain'),
    signingOut: t('sc.session.signingOut')
  };
}
