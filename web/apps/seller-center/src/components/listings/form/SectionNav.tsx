'use client';

import { cx } from '@afrotransact/ui';
import { CheckCircle, Circle } from '@phosphor-icons/react/ssr';
import { useEffect, useState } from 'react';

import { useT } from '@/i18n/t';

import { type SectionKey, type SectionStatus } from '../form-model';

export interface NavItem {
  key: SectionKey;
  label: string;
  status: SectionStatus;
}

export const motion = (): ScrollBehavior =>
  typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth';

/** Moves to a section and focuses its heading (keyboard and screen-reader users land where the page moved to). */
export function goToSection(key: SectionKey) {
  const section = typeof document !== 'undefined' ? document.getElementById(`pf-s-${key}`) : null;
  if (!section) return;
  section.scrollIntoView({ behavior: motion(), block: 'start' });
  document.getElementById(`pf-h-${key}`)?.focus({ preventScroll: true });
}

/** Sticky section navigator: Basics … Compliance with a check or "N to do" each; the section in view is marked. */
export function SectionNav({ items }: { items: NavItem[] }) {
  const t = useT();
  const [active, setActive] = useState<SectionKey | null>(null);
  const keys = items.map((i) => i.key).join();
  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') return;
    const seen = new Set<string>();
    const io = new IntersectionObserver((entries) => {
      entries.forEach((e) => (e.isIntersecting ? seen.add(e.target.id) : seen.delete(e.target.id)));
      const first = keys.split(',').find((k) => seen.has(`pf-s-${k}`));
      if (first) setActive(first as SectionKey);
    }, { rootMargin: '-20% 0px -65% 0px' });
    keys.split(',').forEach((k) => {
      const el = document.getElementById(`pf-s-${k}`);
      if (el) io.observe(el);
    });
    return () => io.disconnect();
  }, [keys]);
  return (
    <nav aria-label={t('sc.pf.nav.label')}
      className="sticky top-(--at-sticky-header-h) z-20 -mx-4 border-b border-border bg-canvas px-4 sm:-mx-8 sm:px-8">
      <ol className="m-0 flex list-none gap-1 overflow-x-auto p-0">
        {items.map((i) => {
          const done = i.status.state === 'complete';
          return (
            <li key={i.key} className="flex-none">
              <a href={`#pf-s-${i.key}`} data-state={i.status.state} aria-current={active === i.key ? 'location' : undefined}
                onClick={(e) => { e.preventDefault(); goToSection(i.key); setActive(i.key); }}
                className={cx('flex min-h-11 items-center gap-1.5 border-b-2 px-2.5 text-sm whitespace-nowrap hover:bg-surface-hover sm:px-3',
                  active === i.key ? 'border-gold-500 font-semibold text-text' : 'border-transparent text-muted')}>
                {done ? <CheckCircle size={18} weight="fill" aria-hidden className="text-success" /> : <Circle size={18} aria-hidden className="text-subtle" />}
                <span>{i.label}</span>
                <span className={cx('text-xs', done ? 'sr-only' : 'rounded-pill bg-warning-bg px-1.5 font-semibold text-warning')}>
                  {done ? t('sc.pf.nav.done') : t('sc.pf.nav.todo', { count: i.status.missing })}
                </span>
              </a>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
