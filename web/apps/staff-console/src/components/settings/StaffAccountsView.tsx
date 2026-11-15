'use client';

import Link from 'next/link';

import { ModuleView } from '@/components/module/ModuleView';
import { useT } from '@/i18n/t';

/**
 * Staff accounts: everyone in the people directory with their roles and whether the account is on, the sheet that assigns or
 * removes a role and turns an account off, and "New" to create a staff account. What a role can do is in Roles and permissions.
 */
export function StaffAccountsView() {
  const t = useT();
  return (
    <>
      <p className="m-0 max-w-[80ch] text-sm text-muted">
        {t('st.sx.staff.intro')} <Link href="/settings/access">{t('st.sx.staff.introLink')}</Link>
      </p>
      <ModuleView module="rbac" />
    </>
  );
}
