'use client';

import { accountKeys, cartKeys, type Me } from '@afrotransact/api-client';
import {
  ArrowUUpLeft,
  CalendarCheck,
  CaretDown,
  CreditCard,
  Desktop,
  MapPin,
  Menu,
  MenuGroup,
  MenuItem,
  MenuSeparator,
  Package,
  ShieldCheck,
  SignIn,
  SignOut,
  Sparkle,
  Storefront,
  User,
  UserCircle,
  UserPlus,
  useToast
} from '@afrotransact/ui';
import { useQueryClient } from '@tanstack/react-query';
import { usePathname, useRouter } from 'next/navigation';
import { type ReactNode } from 'react';

import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './AccountMenu.module.css';
import { type AccountMenuIcon, accountMenuModel, signOutReturnTo } from './accountMenuItems';

const ICONS: Record<AccountMenuIcon, ReactNode> = {
  user: <UserCircle aria-hidden />,
  package: <Package aria-hidden />,
  returns: <ArrowUUpLeft aria-hidden />,
  pin: <MapPin aria-hidden />,
  card: <CreditCard aria-hidden />,
  shield: <ShieldCheck aria-hidden />,
  sparkle: <Sparkle aria-hidden />,
  store: <Storefront aria-hidden />,
  calendar: <CalendarCheck aria-hidden />,
  desktop: <Desktop aria-hidden />,
  signin: <SignIn aria-hidden />,
  signup: <UserPlus aria-hidden />
};

export interface AccountMenuProps {
  me: Me | undefined;
  onAskLola: () => void;
  /** Render the menu inline (tests). */
  inline?: boolean;
  defaultOpen?: boolean;
}

/** Circular avatar with a dropdown: "Welcome, Guest" (sign in / create account) or the signed-in account menu. */
export function AccountMenu({ me, onAskLola, inline, defaultOpen }: AccountMenuProps) {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const qc = useQueryClient();
  const toast = useToast();
  const model = accountMenuModel(me, pathname ?? '/');
  const isUser = model.variant === 'user';
  const title = isUser ? model.title : t(model.title as MessageKey);
  const subtitle = isUser ? model.subtitle : t(model.subtitle as MessageKey);

  const signOut = async () => {
    let redirectTo: string | null = null;
    try {
      ({ redirectTo } = await browserApi().signOut({ returnTo: signOutReturnTo(window.location.pathname, window.location.search) }));
    } catch {
      /* signed out locally anyway: the session cookie is gone or was never there */
    }
    if (redirectTo) {
      // Ends the identity session too (single sign-out); identity then returns the browser to this page (or the home
      // page) with signedOut=1, where SignedOutNotice says so. No sign-in wall.
      window.location.assign(redirectTo);
      return;
    }
    await Promise.all([qc.invalidateQueries({ queryKey: accountKeys.all }), qc.invalidateQueries({ queryKey: cartKeys.all })]);
    toast.show(t('account.signedOut'));
    router.push('/');
    router.refresh();
  };

  const trigger = (
    <button type="button" className={styles.trigger} aria-label={`${t('header.accountMenu')}: ${title}`}>
      <span className={`${styles.avatar} ${isUser ? styles.avatarUser : styles.avatarGuest}`} aria-hidden>
        {isUser ? model.initials : <User size={20} />}
      </span>
      <CaretDown size={14} aria-hidden />
    </button>
  );

  return (
    <Menu trigger={trigger} label={t('header.accountMenu')} inline={inline} defaultOpen={defaultOpen}>
      <div className={styles.head}>
        <span className={`${styles.headAvatar} ${isUser ? styles.headUser : styles.headGuest}`} aria-hidden>
          {model.initials}
        </span>
        <span className={styles.headText}>
          <strong>{title}</strong>
          <span className={styles.headSub}>{subtitle}</span>
        </span>
      </div>
      <MenuGroup>
        {model.items.map((item) => (
          <MenuItem
            key={item.id}
            icon={ICONS[item.icon]}
            count={item.count}
            href={item.external ? item.href : undefined}
            onSelect={() => {
              if (item.action === 'lola') onAskLola();
              else if (item.href && !item.external) router.push(item.href);
            }}
          >
            {t(item.label)}
          </MenuItem>
        ))}
      </MenuGroup>
      {model.showSignOut && (
        <>
          <MenuSeparator />
          <MenuItem icon={<SignOut aria-hidden />} onSelect={() => void signOut()}>
            {t('account.signOut')}
          </MenuItem>
        </>
      )}
    </Menu>
  );
}
