import { type AccessLevel, type ModuleKey, type SellerSession } from '@afrotransact/api-client';
import { type TourLabels, type TourSubject } from '@afrotransact/tour';
import { type HubLabels } from '@afrotransact/tour/react';

import { type MessageKey, type Translator } from '@/i18n/t';

const LEVELS: AccessLevel[] = ['VIEW', 'EDIT', 'APPROVE', 'FULL'];

/** Where the Tour hub lives (the Help menu links to it). */
export const TOUR_HUB_HREF = '/help/tours';

/** Every module key, so each gets a `can-<module>` guard (a missing guard would count as "yes"). */
const MODULES: ModuleKey[] = [
  'onboarding', 'home', 'orders', 'returns', 'disputes', 'listings', 'reviews', 'inventory', 'coupons', 'calendar', 'requests', 'hours', 'services', 'policy',
  'earnings', 'payouts', 'fees', 'billing', 'account-health', 'team'
];

/**
 * What the tours know about the signed-in member of the active organisation: an opaque user id, the organisation role,
 * `<module>:<level>` permissions (each level includes the ones below it) and a few flags (`seller` or `provider`, `approved` or
 * `applying`, `offers-services`, and `cap-<capability>` for each plan capability). No names, no emails.
 */
export function sellerSubject(session: SellerSession): TourSubject {
  const modules = session.navGroups.flatMap((g) => g.modules);
  const permissions = modules.flatMap((m) => LEVELS.slice(0, LEVELS.indexOf(m.level) + 1).map((l) => `${m.key}:${l.toLowerCase()}`));
  const flags: string[] = [session.portal === 'PROVIDER_HUB' ? 'provider' : 'seller'];
  if (session.approved) flags.push('approved');
  else if (session.activeOrg) flags.push('applying');
  if (modules.some((m) => m.key === 'services')) flags.push('offers-services');
  for (const c of session.capabilities) flags.push(`cap-${c}`);
  return { userId: session.user.id, roles: session.activeOrg ? [session.activeOrg.roleKey] : [], permissions, flags };
}

/** Guards named by steps' `when`. */
export function sellerGuards(session: SellerSession): Record<string, () => boolean> {
  const open = new Set(session.navGroups.flatMap((g) => g.modules.map((m) => m.key)));
  const guards: Record<string, () => boolean> = {
    approved: () => session.approved,
    applying: () => !session.approved && session.activeOrg !== null,
    seller: () => session.portal !== 'PROVIDER_HUB',
    provider: () => session.portal === 'PROVIDER_HUB'
  };
  for (const key of MODULES) guards[`can-${key}`] = () => open.has(key);
  return guards;
}

/** The popover's own words (buttons, counter, announcements) from the catalogue. */
export function tourLabels(t: Translator<MessageKey>): TourLabels {
  return {
    next: t('sc.tour.ui.next'),
    back: t('sc.tour.ui.back'),
    skip: t('sc.tour.ui.skip'),
    done: t('sc.tour.ui.done'),
    close: t('sc.tour.ui.close'),
    dontShowAgain: t('sc.tour.ui.dontShowAgain'),
    dialog: t('sc.tour.ui.dialog'),
    stepOf: t('sc.tour.ui.stepOf'),
    stepDone: t('sc.tour.ui.stepDone'),
    started: t('sc.tour.ui.started'),
    finished: t('sc.tour.ui.finished'),
    skipStep: t('sc.tour.ui.skipStep'),
    doItHint: t('sc.tour.ui.doItHint')
  };
}

/** The Tour hub's words from the catalogue. */
export function hubLabels(t: Translator<MessageKey>): HubLabels {
  return {
    title: t('sc.tour.hub.title'),
    intro: t('sc.tour.hub.intro'),
    progress: t('sc.tour.hub.progress'),
    skippedCount: t('sc.tour.hub.skippedCount'),
    stepCount: t('sc.tour.hub.stepCount'),
    start: t('sc.tour.hub.start'),
    resume: t('sc.tour.hub.resume'),
    replay: t('sc.tour.hub.replay'),
    continueJourney: t('sc.tour.hub.continue'),
    skipJourney: t('sc.tour.hub.skipJourney'),
    skipChapter: t('sc.tour.hub.skipChapter'),
    statusNotStarted: t('sc.tour.hub.statusNotStarted'),
    statusInProgress: t('sc.tour.hub.statusInProgress'),
    statusDone: t('sc.tour.hub.statusDone'),
    statusSkipped: t('sc.tour.hub.statusSkipped'),
    whatsNew: t('sc.tour.help.new'),
    resetButton: t('sc.tour.hub.reset'),
    resetConfirm: t('sc.tour.hub.resetConfirm'),
    resetYes: t('sc.tour.hub.resetYes'),
    resetCancel: t('sc.tour.hub.resetCancel'),
    resetFailed: t('sc.tour.hub.resetFailed'),
    resetDone: t('sc.tour.hub.resetDone'),
    offline: t('sc.tour.hub.offline'),
    autoOff: t('sc.tour.hub.autoOff'),
    empty: t('sc.tour.hub.empty')
  };
}
