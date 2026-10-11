'use client';

import { ACCOUNT_ROUTES, useAccountT } from '@afrotransact/account';
import { type SellerSession } from '@afrotransact/api-client';
import { LocaleSelect } from '@afrotransact/i18n/react';
import { LogoMark, UserMenu } from '@afrotransact/ui';
import { BellSimple, ShieldCheck, SignOut, SlidersHorizontal, Storefront, UserCircle } from '@phosphor-icons/react/ssr';
import { useRouter } from 'next/navigation';

import { useT } from '@/i18n/t';
import { signOutEverywhere } from '@/lib/auth';
import { useStorefrontUrl } from '@/lib/runtime-config';

/** Black top bar: logo, gold portal tag ("Seller Central" / "Provider Hub"), and the member's account menu (My account in Seller Center, the storefront as an external link, sign out). */
export function Header({ session }: { session: SellerSession | null }) {
  const storefrontUrl = useStorefrontUrl();
  const t = useT();
  const at = useAccountT();
  const router = useRouter();
  const provider = session?.portal === 'PROVIDER_HUB';
  return (
    <header className="sticky top-0 z-30 flex h-14 items-center gap-3 bg-header px-4 text-header-text lg:px-5">
      <a href="/" className="flex items-center gap-2 text-header-text no-underline hover:text-header-text hover:no-underline">
        <LogoMark size={28} />
        <span className="hidden text-xl font-black sm:inline">afrotransact</span>
        <span className="ml-1 rounded-pill bg-gold-500 px-2 py-0.5 text-2xs font-bold whitespace-nowrap text-ink-1000">{provider ? t('sc.header.providerHub') : t('sc.header.sellerCentral')}</span>
      </a>
      <span className="flex-1" />
      <LocaleSelect
        label={t('sc.header.language')}
        className="h-9 min-w-11 cursor-pointer rounded-md border border-header-muted bg-header px-2 text-sm text-header-text"
        onChanged={() => router.refresh()}
      />
      {session ? (
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
          onSignOut={() => void signOutEverywhere(storefrontUrl)}
        />
      ) : null}
    </header>
  );
}
