'use client';

import { type CookieCategory, legalQueries } from '@afrotransact/api-client';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { ACCEPT_ALL, type ConsentChoice, OPTIONAL_CATEGORIES, type OptionalCategory, REJECT_ALL } from '@/lib/consent/consent';
import { useConsent } from '@/lib/consent/ConsentProvider';

import styles from './Legal.module.css';

const KNOWN = new Set<string>(['necessary', ...OPTIONAL_CATEGORIES]);
const isOptional = (k: string): k is OptionalCategory => (OPTIONAL_CATEGORIES as readonly string[]).includes(k);

/** Category name and description: translated for the known categories, content-service's English otherwise. */
export function categoryText(t: Translator<MessageKey>, c: Pick<CookieCategory, 'key' | 'name' | 'description'>) {
  return KNOWN.has(c.key) ? { name: t(`legal.c.${c.key}` as MessageKey), description: t(`legal.cd.${c.key}` as MessageKey) } : { name: c.name, description: c.description };
}

export function Switch({ checked, onChange, labelledBy, describedBy, disabled }: { checked: boolean; onChange: () => void; labelledBy: string; describedBy?: string; disabled?: boolean }) {
  return <button type="button" role="switch" aria-checked={checked} aria-labelledby={labelledBy} aria-describedby={describedBy} disabled={disabled} onClick={onChange} className={styles.switch} />;
}

/** Cookie Notice → Cookie preferences: a switch per category, equal-weight Save / Accept all / Reject, and the cookie table. */
export function CookiePreferences() {
  const t = useT();
  const { consent, gpc, save } = useConsent();
  const { data: policy } = useQuery(legalQueries.cookiePolicy(browserApi()));
  const [draft, setDraft] = useState<ConsentChoice>(() => (consent ? { functional: consent.functional, analytics: consent.analytics, advertising: consent.advertising, dns: consent.dns } : REJECT_ALL));
  const [status, setStatus] = useState('');

  const commit = async (choice: ConsentChoice) => {
    const saved = await save({ ...choice, dns: choice.dns || (consent?.dns ?? false) }, 'preferences', policy?.noticeVersion);
    setDraft({ functional: saved.functional, analytics: saved.analytics, advertising: saved.advertising && !gpc, dns: saved.dns });
    setStatus(t('legal.saved'));
  };

  const categories = policy?.categories ?? [];
  return (
    <section aria-labelledby="prefs-h" className={styles.panel}>
      <h2 id="prefs-h" className={styles.panelTitle}>
        {t('legal.cookiePrefs')}
      </h2>
      {gpc && (
        <p role="note" className={`${styles.note} ${styles.noteGood}`}>
          {t('legal.gpc')}
        </p>
      )}
      <ul className={styles.cats}>
        {categories.map((c) => {
          const text = categoryText(t, c);
          const locked = c.gpcOptOut && (gpc || (consent?.dns ?? false));
          const on = c.required || (isOptional(c.key) && draft[c.key] && !locked);
          return (
            <li key={c.key} className={styles.cat}>
              <span id={`cat-${c.key}`} className={styles.catName}>
                {text.name}
              </span>
              {c.required ? (
                <span className={styles.alwaysOn}>{t('legal.alwaysOn')}</span>
              ) : (
                <Switch
                  checked={on}
                  disabled={locked || !isOptional(c.key)}
                  labelledBy={`cat-${c.key}`}
                  describedBy={`catd-${c.key}`}
                  onChange={() => {
                    if (isOptional(c.key)) {
                      const k = c.key;
                      setDraft((d) => ({ ...d, [k]: !d[k] }));
                      setStatus('');
                    }
                  }}
                />
              )}
              <span id={`catd-${c.key}`} className={styles.catDesc}>
                {text.description}
              </span>
            </li>
          );
        })}
      </ul>
      <div className={styles.buttons}>
        <button type="button" className={`${styles.btn} ${styles.btnDark}`} onClick={() => void commit(draft)}>
          {t('legal.save')}
        </button>
        <button type="button" className={styles.btn} onClick={() => void commit(ACCEPT_ALL)}>
          {t('legal.acceptAll')}
        </button>
        <button type="button" className={styles.btn} onClick={() => void commit(REJECT_ALL)}>
          {t('legal.rejectAll')}
        </button>
      </div>
      <p role="status" aria-live="polite" className={status ? styles.muted : styles.srOnly}>
        {status}
      </p>

      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- a horizontally scrolling region must be keyboard-focusable (WCAG 2.1.1) */}
      <div className={styles.tableWrap} tabIndex={0} role="region" aria-labelledby="ctab-cap">
        <div id="ctab-cap" className={styles.tableCaption}>
          {t('legal.table')}
        </div>
        <div role="table" aria-labelledby="ctab-cap" lang="en" dir="ltr" className={styles.table}>
          <div role="rowgroup">
            <div role="row" className={styles.row}>
              {(['colName', 'colProvider', 'colPurpose', 'colDuration', 'colCategory'] as const).map((k) => (
                <span key={k} role="columnheader" className={styles.th}>
                  {t(`legal.${k}`)}
                </span>
              ))}
            </div>
          </div>
          <div role="rowgroup">
            {(policy?.cookies ?? []).map((ck) => {
              const cat = categories.find((c) => c.key === ck.category);
              return (
                <div key={ck.name} role="row" className={styles.row}>
                  <span role="rowheader" className={`${styles.td} ${styles.mono}`}>
                    {ck.name}
                  </span>
                  <span role="cell" className={styles.td}>
                    {ck.provider}
                  </span>
                  <span role="cell" className={styles.td}>
                    {ck.purpose}
                  </span>
                  <span role="cell" className={styles.td}>
                    {ck.duration}
                  </span>
                  <span role="cell" className={styles.td}>
                    {cat ? categoryText(t, cat).name : ck.category}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </section>
  );
}
