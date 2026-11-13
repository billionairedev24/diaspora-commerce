'use client';

import { type SiteNavigation } from '@afrotransact/api-client';
import { List } from '@afrotransact/ui';
import Link from 'next/link';
import { useEffect, useId, useRef, useState } from 'react';

import { useT } from '@/i18n/t';
import { searchHref } from '@/lib/api/queries';
import { communityLabel, departmentLabel } from '@/lib/nav/labels';

import styles from './AllMenu.module.css';
import header from './Header.module.css';

/**
 * The "All" menu: every department that has at least one product a shopper may buy, then the communities that exist. Both come from
 * the gateway's live counts (GET /v1/site/navigation); with none, the menu is just "All products". Opens as a disclosure (button,
 * not a link), closes on Escape, on a click outside and when a link is followed; Escape returns focus to the button.
 */
export function AllMenu({ navigation, locale }: { navigation: SiteNavigation; locale: string }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const id = useId();
  const root = useRef<HTMLLIElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const close = () => setOpen(false);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      setOpen(false);
      button.current?.focus();
    };
    const onDown = (e: PointerEvent) => {
      if (root.current && !root.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('pointerdown', onDown);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('pointerdown', onDown);
    };
  }, [open]);

  return (
    <li ref={root}>
      <button ref={button} type="button" className={`${header.navItem} ${header.navBold}`} aria-expanded={open} aria-controls={id} onClick={() => setOpen((v) => !v)}>
        <List aria-hidden />
        {t('nav.all')}
      </button>
      <div id={id} className={styles.panel} hidden={!open}>
        <h2 className={styles.title}>{t('nav.allDepartments')}</h2>
        <ul className={styles.list}>
          {navigation.all.map((d) => (
            <li key={d.key}>
              <Link href={d.href ?? searchHref({ department: d.name })} className={styles.link} onClick={close}>
                {departmentLabel(d, locale, t)}
              </Link>
            </li>
          ))}
          <li>
            <Link href="/s" className={`${styles.link} ${styles.strong}`} onClick={close}>
              {t('nav.allProducts')}
            </Link>
          </li>
        </ul>
        {navigation.communities.length > 0 ? (
          <>
            <h2 className={styles.title}>{t('nav.communities')}</h2>
            <ul className={styles.list}>
              {navigation.communities.map((c) => (
                <li key={c.key}>
                  <Link href={c.href} className={styles.link} onClick={close}>
                    {communityLabel(c, locale)}
                  </Link>
                </li>
              ))}
            </ul>
          </>
        ) : null}
      </div>
    </li>
  );
}
