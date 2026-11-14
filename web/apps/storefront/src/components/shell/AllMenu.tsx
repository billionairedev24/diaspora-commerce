'use client';

import { type Me, SearchQuerySchema, type SiteDepartment, type SiteNavigation } from '@afrotransact/api-client';
import { LOCALES } from '@afrotransact/i18n';
import { LANGUAGE_SWITCH_ENABLED, useI18n } from '@afrotransact/i18n/react';
import { CaretLeft, CaretRight, Drawer, List, UserCircle } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { type KeyboardEvent, useEffect, useId, useRef, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { searchHref, searchQueries } from '@/lib/api/queries';
import { signInHref } from '@/lib/links';
import { communityLabel, departmentLabel } from '@/lib/nav/labels';
import { setAttribution } from '@/lib/preorder/attribution';

import styles from './AllMenu.module.css';
import { programRows } from './allMenuModel';
import header from './Header.module.css';
import { useChangeLocale } from './useChangeLocale';
import { useSignOut } from './useSignOut';

/** Departments shown before "See all" (a layout choice; which departments exist always comes from the gateway). */
const DEPARTMENT_PREVIEW = 6;

type View = { id: 'main' } | { id: 'department'; key: string } | { id: 'language' };

type Row = {
  key: string;
  label: string;
  /** Muted text after the label (the current language). */
  detail?: string;
  kind: 'link' | 'action' | 'drill' | 'back' | 'radio';
  href?: string;
  external?: boolean;
  strong?: boolean;
  checked?: boolean;
  expanded?: boolean;
  onSelect?: () => void;
};
type Section = { id: string; heading?: string; rows: Row[] };

export interface AllMenuProps {
  navigation: SiteNavigation;
  locale: string;
  me: Me | undefined;
  /** The pre-order window the shopper is in or that is open now (the Pre-order entry shows only with one); `code` is its attribution code. */
  preorder: { code?: string } | null;
  /** The "Deliver to" label of the header ("Austin 78701"), empty until a location is set. */
  deliverLabel: string;
  onAskLola: () => void;
  onDeliverTo: () => void;
}

/**
 * The "All" menu, Amazon style: a drawer that slides in from the start edge (the right in Arabic) with a dim backdrop and a dark greeting bar.
 * "Shop by department" lists the departments that have something a shopper may buy now (the gateway's eligible-only navigation, nothing
 * listed by hand); a department opens its own list in the same panel (a "Main menu" row goes back): "See all in the department" and the
 * communities that have products in it. Then "Programs & features" (only those that exist now) and "Help & settings".
 * Modal: Escape and the backdrop close it, focus is trapped and returns to the "All" button, the page behind cannot scroll. Menu semantics:
 * arrow keys, Home and End move between rows; the forward arrow opens a department, the back arrow returns.
 */
export function AllMenu({ navigation, locale, me, preorder, deliverLabel, onAskLola, onDeliverTo }: AllMenuProps) {
  const t = useT();
  const { dir } = useI18n();
  const pathname = usePathname();
  const signOut = useSignOut();
  const changeLocale = useChangeLocale();
  const api = browserApi();
  const uid = useId();
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<View>({ id: 'main' });
  const [showAll, setShowAll] = useState(false);
  const [cur, setCur] = useState(0);
  const menu = useRef<HTMLDivElement>(null);
  const opener = useRef<HTMLButtonElement>(null);
  // The row to focus once the next view has rendered ('' = its first row).
  const pending = useRef<string | null>(null);
  const user = me?.authenticated ? me.user : null;
  const department = view.id === 'department' ? navigation.all.find((d) => d.key === view.key) : undefined;

  // Communities that have products in the department: the search facets (eligible products only), limited to the communities the navigation lists.
  const facets = useQuery({
    ...searchQueries.results(
      api,
      SearchQuerySchema.parse({
        department: department?.name ?? null,
        size: 1
      })
    ),
    enabled: open && department !== undefined
  });

  const rowEls = (): HTMLElement[] => [...(menu.current?.querySelectorAll<HTMLElement>('[role^="menuitem"]') ?? [])];
  const focusRow = (key: string) => {
    const els = rowEls();
    (els.find((el) => el.dataset.row === key) ?? els[0])?.focus();
  };
  const focusRowRef = useRef(focusRow);
  focusRowRef.current = focusRow;
  useEffect(() => {
    if (pending.current === null) return;
    const key = pending.current;
    pending.current = null;
    focusRowRef.current(key);
  }, [view]);

  const change = (next: boolean) => {
    setOpen(next);
    if (!next) {
      setView({ id: 'main' });
      setShowAll(false);
      setCur(0);
    }
  };
  const close = () => change(false);
  // Closes, then runs an action that opens something else (Lola, the location picker, sign out) once focus is back on the page.
  const closeThen = (fn: () => void) => () => {
    close();
    setTimeout(fn, 0);
  };
  const go = (next: View, focus: string) => {
    pending.current = focus;
    setCur(0);
    setView(next);
  };
  const back = () => {
    if (view.id === 'department') go({ id: 'main' }, `dept:${view.key}`);
    else if (view.id === 'language') go({ id: 'main' }, 'language');
  };

  const dl = (d: SiteDepartment) => departmentLabel(d, locale, t);
  const mainBack: Row = {
    key: 'back',
    kind: 'back',
    label: t('nav.mainMenu'),
    onSelect: back
  };

  const sections = ((): Section[] => {
    if (view.id === 'language') {
      return [
        { id: 'back', rows: [mainBack] },
        {
          id: 'language',
          heading: t('nav.language'),
          rows: LOCALES.map((l) => ({
            key: `locale:${l.code}`,
            kind: 'radio' as const,
            label: l.name,
            checked: l.code === locale,
            onSelect: () => {
              close();
              changeLocale(l.code);
            }
          }))
        }
      ];
    }
    if (view.id === 'department') {
      if (!department) return [{ id: 'back', rows: [mainBack] }];
      const known = new Map(navigation.communities.map((c) => [c.label, c]));
      const communities = (facets.data?.facets.community ?? []).filter((b) => b.count > 0 && known.has(b.value));
      const out: Section[] = [
        { id: 'back', rows: [mainBack] },
        {
          id: 'department',
          heading: dl(department),
          rows: [
            {
              key: 'see-all',
              kind: 'link',
              strong: true,
              label: t('nav.seeAllIn', { department: dl(department) }),
              href: department.href ?? searchHref({ department: department.name }),
              onSelect: close
            }
          ]
        }
      ];
      if (communities.length > 0) {
        out.push({
          id: 'communities',
          heading: t('nav.byCommunity'),
          rows: communities.map((b) => ({
            key: `community:${b.value}`,
            kind: 'link' as const,
            label: communityLabel(known.get(b.value)!, locale),
            href: searchHref({
              department: department.name,
              community: b.value
            }),
            onSelect: close
          }))
        });
      }
      return out;
    }

    const shown = showAll ? navigation.all : navigation.all.slice(0, DEPARTMENT_PREVIEW);
    const departments: Row[] = shown.map((d) => ({
      key: `dept:${d.key}`,
      kind: 'drill' as const,
      label: dl(d),
      onSelect: () => go({ id: 'department', key: d.key }, '')
    }));
    if (navigation.all.length > DEPARTMENT_PREVIEW)
      departments.push({
        key: 'toggle',
        kind: 'action',
        label: showAll ? t('nav.seeLess') : t('nav.seeAll'),
        expanded: showAll,
        onSelect: () => setShowAll((v) => !v)
      });
    departments.push({
      key: 'all-products',
      kind: 'link',
      label: t('nav.allProducts'),
      href: '/s',
      strong: true,
      onSelect: close
    });

    const programs: Row[] = programRows({
      navigation,
      me,
      preorder: preorder !== null
    }).map((p) => {
      const label = t(p.label);
      if (p.action === 'lola')
        return {
          key: p.id,
          kind: 'action' as const,
          label,
          onSelect: closeThen(onAskLola)
        };
      return {
        key: p.id,
        kind: 'link' as const,
        label,
        href: p.href,
        external: p.external,
        onSelect:
          p.action === 'preorder'
            ? () => {
                setAttribution('page', { po: preorder?.code });
                close();
              }
            : close
      };
    });

    const help: Row[] = [
      {
        key: 'account',
        kind: 'link',
        label: t('account.yourAccount'),
        href: '/account',
        onSelect: close
      }
    ];
    if (LANGUAGE_SWITCH_ENABLED)
      help.push({
        key: 'language',
        kind: 'drill',
        label: t('nav.language'),
        detail: LOCALES.find((l) => l.code === locale)?.name,
        onSelect: () => go({ id: 'language' }, '')
      });
    help.push(
      {
        key: 'deliver',
        kind: 'action',
        label: deliverLabel ? t('nav.deliverTo', { label: deliverLabel }) : t('header.setLocation'),
        onSelect: closeThen(onDeliverTo)
      },
      {
        key: 'help',
        kind: 'link',
        label: t('nav.customerService'),
        href: '/help',
        onSelect: close
      }
    );
    help.push(
      user
        ? {
            key: 'sign-out',
            kind: 'action',
            label: t('account.signOut'),
            onSelect: closeThen(() => void signOut())
          }
        : {
            key: 'sign-in',
            kind: 'link',
            label: t('account.signIn'),
            href: signInHref(pathname ?? '/'),
            external: true,
            onSelect: close
          }
    );

    return [
      {
        id: 'departments',
        heading: t('nav.shopByDepartment'),
        rows: departments
      },
      { id: 'programs', heading: t('nav.programs'), rows: programs },
      { id: 'help', heading: t('nav.helpSettings'), rows: help }
    ];
  })();

  const forward = dir === 'rtl' ? 'ArrowLeft' : 'ArrowRight';
  const backward = dir === 'rtl' ? 'ArrowRight' : 'ArrowLeft';
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const els = rowEls();
    const i = els.indexOf(document.activeElement as HTMLElement);
    const to = (n: number) => {
      e.preventDefault();
      els[(n + els.length) % els.length]?.focus();
    };
    if (e.key === 'ArrowDown') to(i + 1);
    else if (e.key === 'ArrowUp') to(i < 0 ? els.length - 1 : i - 1);
    else if (e.key === 'Home') to(0);
    else if (e.key === 'End') to(els.length - 1);
    else if (e.key === forward && els[i]?.dataset.drill) {
      e.preventDefault();
      els[i].click();
    } else if (e.key === backward && view.id !== 'main') {
      e.preventDefault();
      back();
    }
  };

  const Chevron = dir === 'rtl' ? CaretLeft : CaretRight;
  const BackChevron = dir === 'rtl' ? CaretRight : CaretLeft;
  const greeting = (
    <>
      <UserCircle size={28} aria-hidden />
      <span>{user ? t('nav.hello', { name: user.firstName }) : t('nav.helloSignIn')}</span>
    </>
  );
  let index = -1;
  const viewLabel = view.id === 'department' && department ? dl(department) : view.id === 'language' ? t('nav.language') : t('nav.allMenu');

  return (
    <li>
      <button ref={opener} type="button" className={`${header.navItem} ${header.navBold}`} aria-haspopup="dialog" aria-expanded={open} onClick={() => change(true)}>
        <List aria-hidden />
        {t('nav.all')}
      </button>
      <Drawer
        open={open}
        onOpenChange={change}
        label={t('nav.allMenu')}
        closeLabel={t('nav.closeMenu')}
        side="start"
        returnFocusTo={opener}
        header={
          user ? (
            <Link href="/account" className={styles.greeting} onClick={close}>
              {greeting}
            </Link>
          ) : (
            <a href={signInHref(pathname ?? '/')} className={styles.greeting} onClick={close}>
              {greeting}
            </a>
          )
        }
        onOpenAutoFocus={(e) => {
          e.preventDefault();
          focusRow('');
        }}
      >
        <div ref={menu} role="menu" aria-label={viewLabel} tabIndex={-1} onKeyDown={onKeyDown}>
          {sections.map((s) => (
            <div key={s.id} role="group" aria-labelledby={s.heading ? `${uid}${s.id}` : undefined} className={styles.section}>
              {s.heading ? (
                <h2 id={`${uid}${s.id}`} role="presentation" className={styles.heading}>
                  {s.heading}
                </h2>
              ) : null}
              {s.rows.map((r) => {
                index += 1;
                const i = index;
                const className = `${styles.row} ${r.strong ? styles.strong : ''}`;
                const common = {
                  className,
                  tabIndex: i === cur ? 0 : -1,
                  'data-row': r.key,
                  onFocus: () => setCur(i)
                };
                const content = (
                  <>
                    {r.kind === 'back' ? <BackChevron size={16} aria-hidden /> : null}
                    <span className={styles.label}>{r.label}</span>
                    {r.detail ? <span className={styles.detail}>{r.detail}</span> : null}
                    {r.kind === 'drill' ? <Chevron size={16} className={styles.chevron} aria-hidden /> : null}
                  </>
                );
                if (r.kind === 'link' && r.href) {
                  return r.external ? (
                    <a key={r.key} role="menuitem" href={r.href} onClick={r.onSelect} {...common}>
                      {content}
                    </a>
                  ) : (
                    <Link key={r.key} role="menuitem" href={r.href} onClick={r.onSelect} {...common}>
                      {content}
                    </Link>
                  );
                }
                return (
                  <button
                    key={r.key}
                    type="button"
                    role={r.kind === 'radio' ? 'menuitemradio' : 'menuitem'}
                    aria-checked={r.kind === 'radio' ? r.checked : undefined}
                    aria-expanded={r.expanded}
                    aria-haspopup={r.kind === 'drill' ? 'menu' : undefined}
                    data-drill={r.kind === 'drill' ? 'true' : undefined}
                    onClick={r.onSelect}
                    {...common}
                  >
                    {content}
                  </button>
                );
              })}
            </div>
          ))}
        </div>
      </Drawer>
    </li>
  );
}
