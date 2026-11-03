'use client';

import { ACCOUNT_ROUTES, useAccountT } from '@afrotransact/account';
import { type SellerSession } from '@afrotransact/api-client';
import { LocaleSelect } from '@afrotransact/i18n/react';
import { LogoMark, UserMenu } from '@afrotransact/ui';
import { BellSimple, ShieldCheck, SignOut, SlidersHorizontal, Storefront, UserCircle } from '@phosphor-icons/react/ssr';
import { useRouter } from 'next/navigation';
import { type ReactNode } from 'react';

import { useT } from '@/i18n/t';
import { signOutEverywhere } from '@/lib/auth';
import { useStorefrontUrl } from '@/lib/runtime-config';

/**
 * Black top bar: logo, gold portal tag ("Seller Central" / "Provider Hub"), and the member's account menu (My account in
 * Seller Center, the storefront as an external link, sign out). While the application is in progress (an organisation
 * that is not approved yet) only onboarding is open: no account menu, just a plain "Sign out".
 */
export function Header({ session, menuButton }: { session: SellerSession | null; menuButton?: ReactNode }) {
  const storefrontUrl = useStorefrontUrl();
  const t = useT();
  const at = useAccountT();
  const router = useRouter();
  const provider = session?.portal === 'PROVIDER_HUB';
  const applying = !!session?.activeOrg && !session.approved;
  return (
    <header className="sticky top-0 z-30 flex h-(--at-sticky-header-h) items-center gap-2 bg-header pt-[env(safe-area-inset-top,0px)] pr-[max(0.5rem,env(safe-area-inset-right,0px))] pl-[max(0.5rem,env(safe-area-inset-left,0px))] text-header-text sm:gap-3 sm:pr-[max(1rem,env(safe-area-inset-right,0px))] sm:pl-[max(1rem,env(safe-area-inset-left,0px))] lg:pr-[max(1.25rem,env(safe-area-inset-right,0px))] lg:pl-[max(1.25rem,env(safe-area-inset-left,0px))]">
      {menuButton}
      <a href="/" className="flex items-center gap-2 text-header-text no-underline hover:text-header-text hover:no-underline">
        <LogoMark size={28} />
        <span className="hidden text-xl font-black sm:inline">afrotransact</span>
        <span className="ml-1 rounded-pill bg-gold-500 px-2 py-0.5 text-2xs font-bold whitespace-nowrap text-ink-1000 max-[399px]:hidden">{provider ? t('sc.header.providerHub') : t('sc.header.sellerCentral')}</span>
      </a>
      <span className="flex-1" />
      <LocaleSelect
        label={t('sc.header.language')}
        className="h-9 w-16 min-w-11 cursor-pointer rounded-md border border-header-muted bg-header px-2 text-sm text-header-text sm:w-auto"
        onChanged={() => router.refresh()}
      />
      {applying ? (
        <button
          type="button"
          onClick={() => void signOutEverywhere()}
          className="inline-flex h-9 cursor-pointer items-center gap-1.5 rounded-pill border border-header-muted bg-header px-3 text-sm font-semibold text-header-text hover:border-header-text"
        >
          <SignOut size={16} aria-hidden />
          {t('sc.header.signOut')}
        </button>
      ) : session ? (
        <UserMenu
          initials={session.user.initials}
          name={`${session.user.firstName} ${session.user.lastName}`}
          shortName={`${session.user.firstName} ${session.user.lastName.slice(0, 1)}.`}
          email={session.user.email}
          context={session.activeOrg?.name ?? undefined}
          label={t('sc.header.accountMenu', { name: `${session.user.firstName} ${session.user.lastName}` })}
          links={[
            { label: at('nav.title'), href: ACCOUNT_ROUTES.profile, icon: <UserCircle aria-hidden /> },
            { label: at('nav.security'), href: ACCOUNT_ROUTES.security, icon: <ShieldCheck aria-hidden /> },
            { label: at('nav.notifications'), href: ACCOUNT_ROUTES.notifications, icon: <BellSimple aria-hidden /> },
            { label: at('nav.preferences'), href: ACCOUNT_ROUTES.preferences, icon: <SlidersHorizontal aria-hidden /> },
            { label: t('sc.header.openStorefront'), href: storefrontUrl, icon: <Storefront aria-hidden />, external: true }
          ]}
          signOutLabel={t('sc.header.signOut')}
          signOutIcon={<SignOut aria-hidden />}
          onSignOut={() => void signOutEverywhere()}
        />
      ) : null}
    </header>
  );
}
