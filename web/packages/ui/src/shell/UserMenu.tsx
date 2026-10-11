'use client';

import { forwardRef, Fragment, type ReactNode } from 'react';

import { CaretDown } from '../icons';
import { Menu, MenuGroup, MenuItem, MenuSeparator } from '../overlay/Menu';
import styles from './UserMenu.module.css';

export interface UserMenuLink {
  label: string;
  href: string;
  icon?: ReactNode;
  /** Leaves the back office (e.g. "Open the storefront"): shown in its own group and opened in a new tab. */
  external?: boolean;
}

export interface UserMenuProps {
  initials: string;
  /** Full name, shown in the menu head; the trigger shows `shortName` next to the avatar from 640px. */
  name: string;
  shortName: string;
  email: string;
  /** A line under the email, e.g. the role or the organisation. */
  context?: string;
  links: UserMenuLink[];
  signOutLabel: string;
  signOutIcon?: ReactNode;
  onSignOut: () => void;
  /** Accessible name of the trigger, e.g. "Account menu for Ada O." */
  label: string;
}

const Trigger = forwardRef<HTMLButtonElement, { initials: string; shortName: string; label: string }>(function Trigger(
  { initials, shortName, label, ...rest },
  ref
) {
  return (
    <button ref={ref} type="button" className={styles.trigger} aria-label={label} {...rest}>
      <span aria-hidden className={styles.avatar}>
        {initials}
      </span>
      <span className={styles.name}>{shortName}</span>
      <CaretDown size={14} aria-hidden className={styles.caret} />
    </button>
  );
});

/** Back-office account menu on the black header: avatar + name opens the signed-in person's options and sign-out. */
export function UserMenu({ initials, name, shortName, email, context, links, signOutLabel, signOutIcon, onSignOut, label }: UserMenuProps) {
  return (
    <Menu trigger={<Trigger initials={initials} shortName={shortName} label={label} />} label={label} width={280}>
      <div className={styles.head}>
        <span aria-hidden className={styles.headAvatar}>
          {initials}
        </span>
        <span className={styles.headText}>
          <strong>{name}</strong>
          <span className={styles.sub}>{email}</span>
          {context ? <span className={styles.sub}>{context}</span> : null}
        </span>
      </div>
      <MenuSeparator />
      {[links.filter((l) => !l.external), links.filter((l) => l.external)].map((group, i) =>
        group.length > 0 ? (
          <Fragment key={i}>
            <MenuGroup>
              {group.map((l) => (
                <MenuItem key={l.href} href={l.href} icon={l.icon} external={l.external}>
                  {l.label}
                </MenuItem>
              ))}
            </MenuGroup>
            <MenuSeparator />
          </Fragment>
        ) : null
      )}
      <MenuGroup>
        <MenuItem icon={signOutIcon} onSelect={onSignOut}>
          {signOutLabel}
        </MenuItem>
      </MenuGroup>
    </Menu>
  );
}
