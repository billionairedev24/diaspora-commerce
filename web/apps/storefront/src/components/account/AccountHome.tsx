'use client';

import { type AccountMessageKey, useAccountT } from '@afrotransact/account';
import { creditQueries } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { FlatPage, LinkEntry, linkEntryClassName, LinkGrid, LinkGroup, PageHeader } from '@afrotransact/ui';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { type ReactNode } from 'react';

import { useLolaLauncher } from '@/components/shell/LolaLauncher';
import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { accountQueries, membershipQueries, shopperQueries } from '@/lib/api/queries';

interface Entry {
  id: string;
  title: AccountMessageKey | MessageKey;
  sub: AccountMessageKey | MessageKey;
  href?: string;
  fact?: ReactNode;
  action?: () => void;
}

interface Group {
  title: MessageKey;
  entries: Entry[];
}

/**
 * Your Account (design: ACCOUNT › acctHome): grouped lists of links, each with a one-line description and, where there
 * is one, a live fact in small text (credit balance, membership, default address, default card). Flat, no tiles.
 */
export function AccountHome() {
  const t = useT();
  const at = useAccountT();
  const fmt = useFormatters();
  const lola = useLolaLauncher();
  const api = browserApi();
  const me = useQuery(accountQueries.me(api));
  const credit = useInfiniteQuery(creditQueries.account(api));
  const membership = useQuery(membershipQueries.account(api));
  const addresses = useQuery(shopperQueries.addresses(api));
  const cards = useQuery(shopperQueries.paymentMethods(api));
  const user = me.data?.user;

  const balance = credit.data?.pages[0];
  const address = addresses.data?.find((a) => a.isDefault) ?? addresses.data?.[0];
  const card = cards.data?.find((c) => c.isDefault) ?? cards.data?.[0];
  const tier = membership.data?.member ? membership.data.tier?.name : null;

  const groups: Group[] = [
    {
      title: 'acct.group.orders',
      entries: [
        { id: 'orders', href: '/orders', title: 'hub.orders', sub: 'hub.ordersSub' },
        { id: 'bookings', href: '/account/bookings', title: 'hub.bookings', sub: 'hub.bookingsSub' }
      ]
    },
    {
      title: 'acct.group.security',
      entries: [
        { id: 'security', href: '/account/security', title: 'hub.security', sub: 'hub.securitySub' },
        { id: 'profile', href: '/account/profile', title: 'hub.profile', sub: 'hub.profileSub' }
      ]
    },
    {
      title: 'acct.group.payments',
      entries: [
        { id: 'payments', href: '/account/payments', title: 'hub.payments', sub: 'hub.paymentsSub', fact: cards.isSuccess ? (card ? t('acct.fact.card', { card: card.label }) : t('acct.fact.noCard')) : null },
        { id: 'credit', href: '/account/credit', title: 'hub.credit', sub: 'hub.creditSub', fact: balance ? t('acct.fact.credit', { amount: fmt.money(balance.balanceMinor, balance.currency) }) : null }
      ]
    },
    {
      title: 'acct.group.addresses',
      entries: [
        {
          id: 'addresses',
          href: '/account/addresses',
          title: 'hub.addresses',
          sub: 'hub.addressesSub',
          fact: addresses.isSuccess ? (address ? t('acct.fact.address', { line: `${address.name}, ${address.city}, ${address.state}` }) : t('acct.fact.noAddress')) : null
        },
        { id: 'delivery-alerts', href: '/account/delivery-alerts', title: 'hub.deliveryAlerts', sub: 'hub.deliveryAlertsSub' }
      ]
    },
    {
      title: 'acct.group.membership',
      entries: [
        { id: 'membership', href: '/account/membership', title: 'hub.membership', sub: 'hub.membershipSub', fact: membership.isSuccess ? (tier ? t('mem.badge', { tier }) : t('acct.fact.notMember')) : null },
        { id: 'refer', href: '/account/refer', title: 'hub.refer', sub: 'hub.referSub' }
      ]
    },
    {
      title: 'acct.group.privacy',
      entries: [
        { id: 'notifications', href: '/account/notifications', title: 'hub.notifications', sub: 'hub.notificationsSub' },
        { id: 'privacy-choices', href: '/legal/privacy-choices', title: 'acct.privacyChoices', sub: 'acct.privacyChoicesSub' }
      ]
    },
    {
      title: 'acct.group.help',
      entries: [
        { id: 'help', href: '/account/help', title: 'hub.help', sub: 'hub.helpSub' },
        { id: 'help-center', href: '/help', title: 'acct.helpCenter', sub: 'acct.helpCenterSub' },
        { id: 'lola', title: 'hub.lola', sub: 'hub.lolaSub', action: () => lola.open() }
      ]
    }
  ];

  // Entries come from the shared account catalogue ("hub.*") or the storefront's own ("acct.*").
  const text = (key: AccountMessageKey | MessageKey): string => (key.startsWith('hub.') ? at(key as AccountMessageKey) : t(key as MessageKey));

  return (
    <FlatPage width="wide">
      <PageHeader title={at('hub.title')} lead={user ? at('hub.signedInAs', { email: user.email }) : undefined} />
      <LinkGrid>
        {groups.map((g) => (
          <LinkGroup key={g.title} title={t(g.title)}>
            {g.entries.map((e) => (
              <LinkEntry
                key={e.id}
                testId={`hub-${e.id}`}
                link={
                  e.href ? (
                    <Link href={e.href} className={linkEntryClassName}>
                      {text(e.title)}
                    </Link>
                  ) : (
                    <button type="button" className={linkEntryClassName} onClick={e.action}>
                      {text(e.title)}
                    </button>
                  )
                }
                text={text(e.sub)}
                fact={e.fact}
              />
            ))}
          </LinkGroup>
        ))}
      </LinkGrid>
    </FlatPage>
  );
}
