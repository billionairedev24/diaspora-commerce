import { type SessionTimeoutLabels } from '@afrotransact/ui';

import { type MessageKey, type Translator } from '@/i18n/t';

/** The session timer's strings from this app's catalogue (st.session.*, all five languages). */
export function sessionLabels(t: Translator<MessageKey>): SessionTimeoutLabels {
  return {
    warnTitle: t('st.session.warnTitle'),
    idleBody: t('st.session.idleBody'),
    absoluteBody: t('st.session.absoluteBody'),
    absoluteNote: t('st.session.absoluteNote'),
    timeLeft: t('st.session.timeLeft'),
    announceIdle: t('st.session.announceIdle'),
    announceAbsolute: t('st.session.announceAbsolute'),
    stay: t('st.session.stay'),
    signOutNow: t('st.session.signOutNow'),
    dismiss: t('st.session.dismiss'),
    close: t('st.session.close'),
    failed: t('st.session.failed'),
    endedTitle: t('st.session.endedTitle'),
    endedIdle: t('st.session.endedIdle'),
    endedAbsolute: t('st.session.endedAbsolute'),
    endedSignedOut: t('st.session.endedSignedOut'),
    signInAgain: t('st.session.signInAgain'),
    signingOut: t('st.session.signingOut')
  };
}
