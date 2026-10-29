import { type ModuleKey } from '@afrotransact/api-client';
import { type Icon } from '@phosphor-icons/react';
import {
  ArrowUUpLeft,
  Bank,
  CalendarBlank,
  Clock,
  CreditCard,
  CurrencyDollar,
  Gavel,
  House,
  ListChecks,
  Percent,
  Receipt,
  Scissors,
  ShieldCheck,
  ShieldWarning,
  Star,
  Tag,
  Ticket,
  Tray,
  UsersThree,
  Warehouse
} from '@phosphor-icons/react/ssr';

import { type MessageKey, type Translator } from '@/i18n/t';

export const MODULE_ICONS: Record<ModuleKey, Icon> = {
  onboarding: ListChecks, home: House, orders: Receipt, returns: ArrowUUpLeft, disputes: Gavel, listings: Tag, reviews: Star, inventory: Warehouse, coupons: Ticket, calendar: CalendarBlank,
  requests: Tray, hours: Clock, services: Scissors, policy: ShieldCheck, earnings: CurrencyDollar, payouts: Bank, fees: Percent,
  billing: CreditCard, 'account-health': ShieldWarning, team: UsersThree
};

type ModuleCopyKey = Extract<MessageKey, `sc.module.${string}`>;

const PROVIDER_VARIANTS = new Set<ModuleKey>(['home', 'payouts', 'billing', 'team']);

/** Page titles and lines under them (copy from the Backoffice and Provider Hub prototypes), in the active language. */
export function moduleCopy(key: ModuleKey, opts: { provider: boolean; orgName: string }, t: Translator<MessageKey>): { title: string; description: string } {
  const id = key.replace(/-(\w)/g, (_, c: string) => c.toUpperCase()) + (opts.provider && PROVIDER_VARIANTS.has(key) ? 'Provider' : '');
  const vars = { org: opts.orgName, portal: t(opts.provider ? 'sc.header.providerHub' : 'sc.sidebar.sellerCenter') };
  return { title: t(`sc.module.${id}.title` as ModuleCopyKey, vars), description: t(`sc.module.${id}.description` as ModuleCopyKey, vars) };
}
