'use client';

import { Tabs } from '@afrotransact/ui';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { ModulePage } from '@/components/module/ModulePage';
import { ResourceTable } from '@/components/resources/ResourceTable';
import { useT } from '@/i18n/t';

import { TokenInspector } from './TokenInspector';

/**
 * Developer tools › Identity & access (S291), for staff with identity:admin: the OAuth clients registered at identity (create,
 * edit, disable, rotate a secret), every active sign-in with revoke, the JWKS signing keys and their rotation, the token
 * inspector and the sign-in settings. Every change asks for a reason and lands in identity's access log. The tables are
 * the shared server-driven DataTable; only the inspector is its own screen.
 */
export function IdentityAdminView() {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const tab = params.get('tab') ?? 'oauth-clients';
  return (
    <ModulePage module="identity">
      <Tabs
        label={t('st.identity.tabs')}
        value={tab}
        // Inside Settings the section stays in the address; the tab replaces whatever record was open.
        onValueChange={(v) => router.replace(`${pathname}?${new URLSearchParams([...params.entries()].filter(([k]) => k === 'section').concat([['tab', v]])).toString()}`, { scroll: false })}
        items={[
          { value: 'oauth-clients', label: t('st.identity.tab.clients'), content: <ResourceTable resource="oauth-clients" urlPrefix="oauth-clients" /> },
          { value: 'identity-sessions', label: t('st.identity.tab.sessions'), content: <ResourceTable resource="identity-sessions" urlPrefix="identity-sessions" /> },
          { value: 'signing-keys', label: t('st.identity.tab.keys'), content: <ResourceTable resource="signing-keys" urlPrefix="signing-keys" /> },
          { value: 'inspector', label: t('st.identity.tab.inspector'), content: <TokenInspector /> },
          { value: 'auth-settings', label: t('st.identity.tab.settings'), content: <ResourceTable resource="auth-settings" urlPrefix="auth-settings" /> }
        ]}
      />
    </ModulePage>
  );
}
