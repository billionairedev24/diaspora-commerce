'use client';

import { type StaffSession } from '@afrotransact/api-client';
import { LocaleSelect } from '@afrotransact/i18n/react';
import { LogoMark, UserMenu } from '@afrotransact/ui';
import { ShieldCheck, SignOut, Storefront, UserCircle } from '@phosphor-icons/react/ssr';
import { useRouter } from 'next/navigation';

import { useT } from '@/i18n/t';
import { signOutEverywhere } from '@/lib/auth';
import { useStorefrontUrl } from '@/lib/runtime-config';

/** Black top bar: logo, gold "Staff console" tag, and the staff member's account menu (account, security, storefront, sign out). */
export function Header({ session }: { session: StaffSession | null }) {
  const storefrontUrl = useStorefrontUrl();
  const t = useT();
  const router = useRouter();
  return (
    <header className="sticky top-0 z-30 flex h-14 items-center gap-3 bg-header px-4 text-header-text lg:px-5">
      <a href="/" className="flex items-center gap-2 text-header-text no-underline hover:text-header-text hover:no-underline">
        <LogoMark size={28} />
        <span className="hidden text-xl font-black sm:inline">afrotransact</span>
        <span className="ml-1 rounded-pill bg-gold-500 px-2 py-0.5 text-2xs font-bold whitespace-nowrap text-ink-1000">{t('st.header.staffConsole')}</span>
      </a>
      <span className="flex-1" />
      <LocaleSelect
        label={t('st.header.language')}
        className="h-9 min-w-11 cursor-pointer rounded-md border border-header-muted bg-header px-2 text-sm text-header-text"
        onChanged={() => router.refresh()}
      />
      {session ? (
        <UserMenu
          initials={session.user.initials}
          name={`${session.user.firstName} ${session.user.lastName}`}
          shortName={`${session.user.firstName} ${session.user.lastName.slice(0, 1)}.`}
          email={session.user.email}
          context={session.roleLabel}
          label={t('st.header.accountMenu', { name: `${session.user.firstName} ${session.user.lastName}` })}
          links={[
            { label: t('st.header.yourAccount'), href: `${storefrontUrl}/account`, icon: <UserCircle aria-hidden /> },
            { label: t('st.header.security'), href: `${storefrontUrl}/account/security`, icon: <ShieldCheck aria-hidden /> },
            { label: t('st.header.openStorefront'), href: storefrontUrl, icon: <Storefront aria-hidden /> }
          ]}
          signOutLabel={t('st.header.signOut')}
          signOutIcon={<SignOut aria-hidden />}
          onSignOut={() => void signOutEverywhere(storefrontUrl)}
        />
      ) : null}
    </header>
  );
}
