'use client';

import { AccountShell, AlertsScreen, PreferencesScreen, ProfileScreen, SecurityScreen } from '@afrotransact/account';
import { sellerKeys } from '@afrotransact/api-client';
import { useToast } from '@afrotransact/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';

import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';

/** My account › Profile: details, memberships with the organisation switcher and "Leave". */
export function SellerProfilePage() {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const switchOrg = useMutation({
    mutationFn: (orgId: string) => sellerApi().setActiveOrg(orgId),
    onSuccess: async (s) => {
      qc.setQueryData(sellerKeys.session(), s);
      await qc.invalidateQueries();
      toast.show(t('sc.portalShell.switched', { org: s.activeOrg?.name ?? t('sc.portalShell.organisation') }));
    },
    onError: () => toast.show(t('sc.portalShell.switchFailed'), 'error')
  });
  return (
    <AccountShell active="profile">
      <ProfileScreen heading={false} onSwitchOrg={(id) => switchOrg.mutate(id)} switching={switchOrg.isPending} />
    </AccountShell>
  );
}

export function SellerSecurityPage() {
  return (
    <AccountShell active="security">
      <SecurityScreen audience="seller" heading={false} />
    </AccountShell>
  );
}

export function SellerNotificationsPage() {
  return (
    <AccountShell active="notifications">
      <AlertsScreen heading={false} />
    </AccountShell>
  );
}

export function SellerPreferencesPage() {
  const router = useRouter();
  return (
    <AccountShell active="preferences">
      <PreferencesScreen heading={false} showDensity onLanguageChanged={() => router.refresh()} />
    </AccountShell>
  );
}
