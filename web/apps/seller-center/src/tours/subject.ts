import { type AccessLevel, type ModuleKey, type SellerSession } from '@afrotransact/api-client';
import { type TourLabels, type TourSubject } from '@afrotransact/tour';

import { type MessageKey, type Translator } from '@/i18n/t';

const LEVELS: AccessLevel[] = ['VIEW', 'EDIT', 'APPROVE', 'FULL'];

/** Every module key, so each gets a `can-<module>` guard (a missing guard would count as "yes"). */
const MODULES: ModuleKey[] = [
  'onboarding', 'home', 'orders', 'returns', 'disputes', 'listings', 'reviews', 'inventory', 'coupons', 'calendar', 'requests', 'hours', 'services', 'policy',
  'earnings', 'payouts', 'fees', 'billing', 'account-health', 'team'
];

/**
 * What the tours know about the signed-in member of the active organisation: an opaque user id, the organisation role,
 * `<module>:<level>` permissions (each level includes the ones below it) and a few flags. No names, no emails.
 */
export function sellerSubject(session: SellerSession): TourSubject {
  const modules = session.navGroups.flatMap((g) => g.modules);
  const permissions = modules.flatMap((m) => LEVELS.slice(0, LEVELS.indexOf(m.level) + 1).map((l) => `${m.key}:${l.toLowerCase()}`));
  const flags: string[] = [session.portal === 'PROVIDER_HUB' ? 'provider' : 'seller'];
  if (session.approved) flags.push('approved');
  else if (session.activeOrg) flags.push('applying');
  if (modules.some((m) => m.key === 'services')) flags.push('offers-services');
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
    finished: t('sc.tour.ui.finished')
  };
}
