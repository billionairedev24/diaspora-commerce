import { type ModuleKey } from '@afrotransact/api-client';
import { type Icon } from '@phosphor-icons/react';
import {
  Bank,
  CalendarBlank,
  Clock,
  CreditCard,
  CurrencyDollar,
  House,
  ListChecks,
  PaintBrush,
  Receipt,
  Scissors,
  ShieldCheck,
  ShieldWarning,
  Storefront,
  Tag,
  Tray,
  UsersThree,
  Warehouse
} from '@phosphor-icons/react/ssr';

export const MODULE_ICONS: Record<ModuleKey, Icon> = {
  onboarding: ListChecks, home: House, orders: Receipt, listings: Tag, inventory: Warehouse, storefront: PaintBrush, calendar: CalendarBlank,
  requests: Tray, hours: Clock, services: Scissors, policy: ShieldCheck, 'public-page': Storefront, earnings: CurrencyDollar, payouts: Bank,
  billing: CreditCard, 'account-health': ShieldWarning, team: UsersThree
};

/** Page titles and lines under them (copy from the Backoffice and Provider Hub prototypes). */
export function moduleCopy(key: ModuleKey, opts: { provider: boolean; orgName: string }): { title: string; description: string } {
  const p = opts.provider;
  switch (key) {
    case 'home':
      return p ? { title: 'Good to see you', description: 'Your bookings at a glance' } : { title: 'Home', description: `Today at ${opts.orgName}.` };
    case 'onboarding':
      return { title: 'Onboarding', description: `Your application status. The rest of the ${p ? 'Provider Hub' : 'Seller Center'} unlocks after approval.` };
    case 'orders':
      return { title: 'Orders', description: 'Seller-fulfilled orders to ship, plus AfroTransact-fulfilled orders for visibility.' };
    case 'listings':
      return { title: 'Listings', description: 'Your offers on the AfroTransact catalogue. New listings pass AI and policy checks before going live.' };
    case 'inventory':
      return { title: 'Inventory & FBA', description: 'Send stock to an AfroTransact fulfilment centre to earn the “Fulfilled by AfroTransact” badge.' };
    case 'storefront':
      return { title: 'Storefront & theme', description: 'Design your store page; colours are vetted for contrast before publishing.' };
    case 'public-page':
      return { title: 'Public page', description: 'Your page in the shop and the app' };
    case 'payouts':
      return { title: 'Payouts', description: p ? 'Earnings settle for 7 days, then you request a payout to your bank.' : 'Stripe Connect payouts to your bank.' };
    case 'billing':
      return { title: 'Plan & billing', description: p ? 'Your Provider Hub subscription.' : 'Your subscription (Stripe Billing) and invoices.' };
    case 'account-health':
      return { title: 'Account health', description: 'What Stripe needs from you to keep payments and payouts switched on.' };
    case 'team':
      return { title: 'Team & roles', description: p ? 'Practitioners and staff, and what each role can and can’t do.' : 'Invite staff and give each the least access they need.' };
    case 'calendar':
      return { title: 'Calendar', description: 'Hover any booking or day for a quick view · click to open' };
    case 'requests':
      return { title: 'Requests', description: 'Requests expire after 4 hours and the deposit is released automatically' };
    case 'hours':
      return { title: 'Working hours', description: 'Customers only see times inside these hours, minus bookings, buffers and blocked dates' };
    case 'services':
      return { title: 'Services & prices', description: 'Price changes apply to new bookings; existing ones keep the agreed price' };
    case 'policy':
      return { title: 'Cancellation policy', description: 'Pick a template or build your own. AfroTransact enforces it automatically' };
    case 'earnings':
      return { title: 'Earnings', description: 'AfroTransact keeps 10% on what customers actually pay. Stripe fees pass through. Paid out every Tuesday via Stripe Connect' };
  }
}
