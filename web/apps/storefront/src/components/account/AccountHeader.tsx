'use client';

import { type BreadcrumbItem, PageHeader } from '@afrotransact/ui';
import Link from 'next/link';
import { type ReactNode } from 'react';

import { useT } from '@/i18n/t';

export interface AccountHeaderProps {
  title: ReactNode;
  /** The current page's name in the trail; defaults to the title when that is a string. */
  crumb?: string;
  /** Pages between "Your account" and this one (for example Gift cards & credit before Buy a gift card). */
  via?: Array<{ label: string; href: string }>;
  lead?: ReactNode;
  /** Secondary text links beside the title. */
  actions?: ReactNode;
}

/** The one page header of the account area: "Your account › Page" breadcrumb, a left-aligned Fraunces title, optional lead. */
export function AccountHeader({ title, crumb, via = [], lead, actions }: AccountHeaderProps) {
  const t = useT();
  const items: BreadcrumbItem[] = [{ label: t('account.yourAccount'), href: '/account' }, ...via, { label: crumb ?? (typeof title === 'string' ? title : '') }];
  return (
    <PageHeader
      title={title}
      lead={lead}
      actions={actions}
      breadcrumb={{
        label: t('acct.breadcrumb'),
        items,
        renderLink: (item) => (
          <Link href={item.href ?? '/account'}>{item.label}</Link>
        )
      }}
    />
  );
}
