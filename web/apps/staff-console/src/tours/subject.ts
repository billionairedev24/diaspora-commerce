import { type AccessLevel, type StaffModuleKey, type StaffSession } from '@afrotransact/api-client';
import { type TourLabels, type TourSubject } from '@afrotransact/tour';
import { type HubLabels } from '@afrotransact/tour/react';

import { type MessageKey, type Translator } from '@/i18n/t';
import { SETTINGS_SECTIONS, visibleSections } from '@/lib/settings-sections';

const LEVELS: AccessLevel[] = ['VIEW', 'EDIT', 'APPROVE', 'FULL'];

/** Where the Tour hub lives (the Help menu links to it). */
export const TOUR_HUB_HREF = '/help/tours';

/** Every module key, so each gets a `can-<module>` guard (a missing guard would count as "yes"). */
const MODULES: StaffModuleKey[] = [
  'dash', 'exports', 'orders', 'catalog', 'inventory', 'retail', 'dispatch', 'orgs', 'apps', 'deptRequests', 'deliveryQuotes', 'support', 'rbac', 'payouts', 'fees', 'tax', 'marketing',
  'disputes', 'audit', 'markets', 'siteCfg', 'dev', 'identity', 'notifications', 'preorders', 'plans'
];

/**
 * What the tours know about the signed-in staff member: an opaque user id, their role keys, `<module>:<level>` permissions (each
 * level includes the ones below it) and the platform permissions the session lists. No names, no emails. A chapter is offered only
 * when the audience matches, so a person never sees a chapter about something they cannot open.
 */
export function staffSubject(session: StaffSession): TourSubject {
  const modules = session.navGroups.flatMap((g) => g.modules);
  const permissions = [
    ...modules.flatMap((m) => LEVELS.slice(0, LEVELS.indexOf(m.level) + 1).map((l) => `${m.key}:${l.toLowerCase()}`)),
    ...session.permissions
  ];
  return { userId: session.user.id, roles: session.roles.map((r) => r.key), permissions, flags: ['staff'] };
}

/** Guards named by steps' `when`: one per module and one per Settings section the person can open (a missing guard would count as "yes"). */
export function staffGuards(session: StaffSession): Record<string, () => boolean> {
  const open = new Set(session.navGroups.flatMap((g) => g.modules.map((m) => m.key)));
  const sections = new Set(visibleSections(session).map((s) => s.key));
  return {
    ...Object.fromEntries(SETTINGS_SECTIONS.map((s) => [`can-settings-${s.key}`, () => sections.has(s.key)])),
    ...Object.fromEntries(MODULES.map((m) => [`can-${m}`, () => open.has(m)]))
  };
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
    finished: t('st.tour.ui.finished'),
    skipStep: t('st.tour.ui.skipStep'),
    doItHint: t('st.tour.ui.doItHint')
  };
}

/** The Tour hub's words from the catalogue. */
export function hubLabels(t: Translator<MessageKey>): HubLabels {
  return {
    title: t('st.tour.hub.title'),
    intro: t('st.tour.hub.intro'),
    progress: t('st.tour.hub.progress'),
    skippedCount: t('st.tour.hub.skippedCount'),
    stepCount: t('st.tour.hub.stepCount'),
    start: t('st.tour.hub.start'),
    resume: t('st.tour.hub.resume'),
    replay: t('st.tour.hub.replay'),
    continueJourney: t('st.tour.hub.continue'),
    skipJourney: t('st.tour.hub.skipJourney'),
    skipChapter: t('st.tour.hub.skipChapter'),
    statusNotStarted: t('st.tour.hub.statusNotStarted'),
    statusInProgress: t('st.tour.hub.statusInProgress'),
    statusDone: t('st.tour.hub.statusDone'),
    statusSkipped: t('st.tour.hub.statusSkipped'),
    whatsNew: t('st.tour.help.new'),
    resetButton: t('st.tour.hub.reset'),
    resetConfirm: t('st.tour.hub.resetConfirm'),
    resetYes: t('st.tour.hub.resetYes'),
    resetCancel: t('st.tour.hub.resetCancel'),
    resetFailed: t('st.tour.hub.resetFailed'),
    resetDone: t('st.tour.hub.resetDone'),
    offline: t('st.tour.hub.offline'),
    autoOff: t('st.tour.hub.autoOff'),
    empty: t('st.tour.hub.empty')
  };
}
