import { type AccessLevel, type StaffSession } from '@afrotransact/api-client';
import { type TourLabels, type TourSubject } from '@afrotransact/tour';

import { type MessageKey, type Translator } from '@/i18n/t';
import { SETTINGS_SECTIONS, visibleSections } from '@/lib/settings-sections';

const LEVELS: AccessLevel[] = ['VIEW', 'EDIT', 'APPROVE', 'FULL'];

/**
 * What the tours know about the signed-in staff member: an opaque user id, their role keys, `<module>:<level>` permissions (each
 * level includes the ones below it) and the platform permissions the session lists. No names, no emails. A tour is offered only
 * when the audience matches, so a person never sees a tour about something they cannot open.
 */
export function staffSubject(session: StaffSession): TourSubject {
  const modules = session.navGroups.flatMap((g) => g.modules);
  const permissions = [
    ...modules.flatMap((m) => LEVELS.slice(0, LEVELS.indexOf(m.level) + 1).map((l) => `${m.key}:${l.toLowerCase()}`)),
    ...session.permissions
  ];
  return { userId: session.user.id, roles: session.roles.map((r) => r.key), permissions, flags: ['staff'] };
}

/** Guards named by steps' `when`: one per Settings section the person can open (a missing guard would count as "yes"). */
export function staffGuards(session: StaffSession): Record<string, () => boolean> {
  const open = new Set(visibleSections(session).map((s) => s.key));
  return Object.fromEntries(SETTINGS_SECTIONS.map((s) => [`can-settings-${s.key}`, () => open.has(s.key)]));
}

/** The popover's own words (buttons, counter, announcements) from the catalogue. */
export function tourLabels(t: Translator<MessageKey>): TourLabels {
  return {
    next: t('st.tour.ui.next'),
    back: t('st.tour.ui.back'),
    skip: t('st.tour.ui.skip'),
    done: t('st.tour.ui.done'),
    close: t('st.tour.ui.close'),
    dontShowAgain: t('st.tour.ui.dontShowAgain'),
    dialog: t('st.tour.ui.dialog'),
    stepOf: t('st.tour.ui.stepOf'),
    stepDone: t('st.tour.ui.stepDone'),
    started: t('st.tour.ui.started'),
    finished: t('st.tour.ui.finished')
  };
}
