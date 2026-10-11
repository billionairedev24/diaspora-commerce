'use client';

import { type StaffSession } from '@afrotransact/api-client';
import { LocaleSelect } from '@afrotransact/i18n/react';
import { IconButton, LogoMark } from '@afrotransact/ui';
import { List, SignOut } from '@phosphor-icons/react/ssr';
import { useRouter } from 'next/navigation';

import { useT } from '@/i18n/t';
import { signOutEverywhere } from '@/lib/auth';
import { useStorefrontUrl } from '@/lib/runtime-config';

/** Black top bar: logo, gold "Staff console" tag, menu button on small screens, the staff member and sign out. */
export function Header({ session, onMenu }: { session: StaffSession | null; onMenu?: () => void }) {
  const storefrontUrl = useStorefrontUrl();
  const t = useT();
  const router = useRouter();
  return (
    <header className="sticky top-0 z-30 flex h-14 items-center gap-3 bg-header px-4 text-header-text lg:px-5">
      {onMenu ? (
        <IconButton label={t('st.header.openMenu')} onDark className="lg:hidden" onClick={onMenu}>
          <List size={22} aria-hidden />
        </IconButton>
      ) : null}
      <a href="/" className="flex items-center gap-2 text-header-text no-underline hover:text-header-text hover:no-underline">
        <LogoMark size={28} />
        <span className="text-xl font-black">afrotransact</span>
        <span className="ml-1 rounded-pill bg-gold-500 px-2 py-0.5 text-2xs font-bold text-ink-1000">{t('st.header.staffConsole')}</span>
      </a>
      <span className="flex-1" />
      <LocaleSelect
        label={t('st.header.language')}
        className="h-9 min-w-11 cursor-pointer rounded-md border border-header-muted bg-header px-2 text-sm text-header-text"
        onChanged={() => router.refresh()}
      />
      {session ? (
        <span className="flex items-center gap-2 text-sm">
          <span aria-hidden className="grid size-[30px] place-items-center rounded-pill bg-gold-500 text-xs font-bold text-ink-1000">{session.user.initials}</span>
          <span className="hidden sm:inline">
            {session.user.firstName} {session.user.lastName.slice(0, 1)}.
          </span>
          <IconButton label={t('st.header.signOut')} onDark onClick={() => void signOutEverywhere(storefrontUrl)}>
            <SignOut size={20} aria-hidden />
          </IconButton>
        </span>
      ) : null}
    </header>
  );
}
