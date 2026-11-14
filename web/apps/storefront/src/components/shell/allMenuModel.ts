import { type Me, type SiteNavigation } from '@afrotransact/api-client';

import { PREORDER_PATH } from '@/components/preorder/PreorderBanners';
import { type MessageKey } from '@/i18n/t';
import { searchHref } from '@/lib/api/queries';

import { backOfficeEntries } from './accountMenuItems';

/** One row of the "Programs & features" section. `href` rows are links (`external` = full page load), `lola` opens the assistant. */
export interface ProgramRow {
  id: string;
  label: MessageKey;
  href?: string;
  external?: boolean;
  action?: 'lola' | 'preorder';
}

export interface ProgramsInput {
  navigation: SiteNavigation;
  me: Me | undefined;
  /** A pre-order window is open (or the shopper is inside a pre-order campaign): the Pre-order entry shows only then. */
  preorder: boolean;
}

/**
 * The programs and features that exist right now, in the order of the header: Pre-order, Lola AI, Today's Deals, AfroTransact Brand,
 * Services, Gift cards, then Sell on AfroTransact / Offer your services (or Seller Center / Provider Hub for someone who has them).
 * Every entry follows the same live signals as the header's department bar, so the two cannot disagree.
 */
export function programRows({ navigation, me, preorder }: ProgramsInput): ProgramRow[] {
  const rows: ProgramRow[] = [];
  if (preorder)
    rows.push({
      id: 'preorder',
      label: 'nav.preorder',
      href: PREORDER_PATH,
      action: 'preorder'
    });
  rows.push({ id: 'lola', label: 'nav.lola', action: 'lola' });
  if (navigation.deals.available)
    rows.push({
      id: 'deals',
      label: 'nav.deals',
      href: searchHref({ sort: 'price_asc' })
    });
  if (navigation.brand.available)
    rows.push({
      id: 'brand',
      label: 'nav.brand',
      href: searchHref({ sellerKind: 'PLATFORM' })
    });
  if (navigation.services.available)
    rows.push({
      id: 'services',
      label: 'nav.services',
      href: navigation.services.href
    });
  rows.push({ id: 'gift-cards', label: 'nav.giftCards', href: '/gift-cards' });
  const backOffices = me?.authenticated && me.user ? me.user.backOffices : [];
  // The staff console is not a shopping program: staff reach it from the account menu.
  for (const e of backOfficeEntries(backOffices).filter((x) => x.id !== 'staff-console')) rows.push({ id: e.id, label: e.label, href: e.href, external: e.external });
  return rows;
}
