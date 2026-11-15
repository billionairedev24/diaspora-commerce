'use client';

import Link from 'next/link';
import { type ReactNode } from 'react';

import { AccessView } from '@/components/access/AccessView';
import { DeliveryFeesView } from '@/components/fees/DeliveryFeesView';
import { FeesView } from '@/components/fees/FeesView';
import { IdentityAdminView } from '@/components/identity/IdentityAdminView';
import { MarketsView } from '@/components/markets/MarketsView';
import { ModuleView } from '@/components/module/ModuleView';
import { NotificationsView } from '@/components/notifications/NotificationsView';
import { PublishedPagesView } from '@/components/pages/PublishedPagesView';
import { SettingsPanel as PlanSettingsPanel } from '@/components/plans/SettingsPanel';
import { ResourceTable } from '@/components/resources/ResourceTable';
import { EconomicsSettingsView } from '@/components/runs/EconomicsSettingsView';
import { useT } from '@/i18n/t';
import { type SettingsKey } from '@/lib/settings-sections';

import { AiSpendView } from './AiSpendView';
import { StaffAccountsView } from './StaffAccountsView';

/** The body of each section page: an existing screen of the console, unchanged, or one of the new Access screens. */
export function SectionBody({ section }: { section: SettingsKey }): ReactNode {
  const t = useT();
  switch (section) {
    case 'published-pages':
      return <PublishedPagesView />;
    case 'general':
      return <ModuleView module="siteCfg" />;
    case 'markets':
      return <MarketsView />;
    case 'notifications':
      return <NotificationsView />;
    case 'sign-in':
      return <ModuleView module="dev" />;
    case 'security':
      return <IdentityAdminView />;
    case 'staff':
      return <StaffAccountsView />;
    case 'access':
      return <AccessView />;
    case 'ai':
      return <AiSpendView />;
    case 'payments':
      return (
        <div className="flex flex-col gap-3">
          <p className="m-0 max-w-[80ch] text-sm text-muted">
            These come from the deployment&rsquo;s configuration; open a row to see what it does and which setting changes it. Approving, holding and releasing payouts is in{' '}
            <Link href="/payouts">Payouts &amp; ledger</Link>.
          </p>
          <ResourceTable resource="payment-settings" urlPrefix="payment-settings" />
        </div>
      );
    case 'credit':
      return (
        <div className="flex flex-col gap-3">
          <p className="m-0 max-w-[80ch] text-sm text-muted">
            Credit only buys products and services: it can&rsquo;t be paid out, refunded to a card or moved between customers. Granting credit, issuing gift cards and the liability report are in{' '}
            <Link href="/payouts?tab=credit-adjustments">Payouts &amp; ledger</Link>.
          </p>
          <ResourceTable resource="money-settings" urlPrefix="money-settings" />
        </div>
      );
    case 'membership':
      return (
        <div className="flex flex-col gap-4">
          <p className="m-0 max-w-[80ch] text-sm text-muted">
            {t('st.settings.membership.intro')} <Link href="/payouts?tab=memberships">{t('st.settings.membership.reportLink')}</Link>
          </p>
          <h2 className="m-0 text-md font-bold">{t('st.settings.membership.tiers')}</h2>
          <ResourceTable resource="membership-tiers" urlPrefix="membership-tiers" />
          <h2 className="m-0 text-md font-bold">{t('st.settings.membership.rules')}</h2>
          <ResourceTable resource="membership-settings" urlPrefix="membership-settings" />
        </div>
      );
    case 'referrals':
      return (
        <div className="flex flex-col gap-4">
          <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.settings.referrals.intro')}</p>
          <ResourceTable resource="referral-settings" urlPrefix="referral-settings" />
          <h2 className="m-0 text-base font-semibold">{t('st.settings.referrals.listTitle')}</h2>
          <ResourceTable resource="referrals" urlPrefix="referrals" statusFilter />
        </div>
      );
    case 'fees':
      return <FeesView />;
    case 'shipping':
      return (
        <div className="flex flex-col gap-3">
          <p className="m-0 max-w-[80ch] text-sm text-muted">
            AfroTransact sets all shipping at launch: one flat fee for Delivery by AfroTransact and for AfroTransact collecting from a store, free pickup, and the
            fee each pre-order or run sets for itself. Where a delivery is too far, the shopper asks for a quote. Every change needs a reason.
          </p>
          <ResourceTable resource="shipping-settings" urlPrefix="shipping-settings" />
        </div>
      );
    case 'delivery-fees':
      return <DeliveryFeesView />;
    case 'run-economics':
      return <EconomicsSettingsView />;
    case 'exchange-rates':
      return (
        <div className="flex flex-col gap-3">
          <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.rates.intro')}</p>
          <ResourceTable resource="exchange-rates" urlPrefix="exchange-rates" />
        </div>
      );
    case 'application-review':
      return (
        <div className="flex flex-col gap-3">
          <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.settings.appReview.intro')}</p>
          <ResourceTable resource="application-review-settings" urlPrefix="application-review-settings" />
        </div>
      );
    case 'plans':
      return (
        <div className="flex flex-col gap-4">
          <PlanSettingsPanel />
          <p className="m-0 text-sm text-muted">
            The plan catalogue, trials, rewards and each seller&rsquo;s billing are in <Link href="/plans">Plans &amp; billing</Link>.
          </p>
        </div>
      );
    default:
      return null;
  }
}
