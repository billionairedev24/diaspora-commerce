'use client';

import Link from 'next/link';

import { InvitationsPanel } from '@/components/access/InvitationsPanel';
import { ModuleView } from '@/components/module/ModuleView';
import { useT } from '@/i18n/t';

/**
 * Staff accounts: people invited and not yet active (invite, send again, revoke), then everyone in the people directory with their
 * roles and whether the account is on, with the sheet that assigns or removes a role and turns an account off. Accounts are made
 * by invitation: the person chooses their own password. What a role can do is in Roles and permissions.
 */
export function StaffAccountsView() {
  const t = useT();
  return (
    <>
      <p className="m-0 max-w-[80ch] text-sm text-muted">
        {t('st.sx.staff.intro')} <Link href="/settings/access">{t('st.sx.staff.introLink')}</Link>
      </p>
      <InvitationsPanel />
      <section aria-labelledby="people-title" className="flex flex-col gap-3">
        <h2 id="people-title" className="m-0 text-md font-bold">
          {t('st.inv.people')}
        </h2>
        <ModuleView module="rbac" />
      </section>
    </>
  );
}
