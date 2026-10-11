'use client';

import { accountQueries, isApiError, US_STATES } from '@afrotransact/api-client';
import { useLocale } from '@afrotransact/i18n/react';
import { ErrorSummary, Select, TextField } from '@afrotransact/ui';
import { useMutation, useQuery } from '@tanstack/react-query';
import { type FormEvent, useEffect, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { REJECT_ALL } from '@/lib/consent/consent';
import { useConsent } from '@/lib/consent/ConsentProvider';
import { emptyPrivacyRequest, PRIVACY_FIELDS, type PrivacyRequestErrors, type PrivacyRequestForm, serverErrors, toPrivacyRequest, validatePrivacyRequest } from '@/lib/legal/privacyRequest';

import { Switch } from './CookiePreferences';
import styles from './Legal.module.css';

const FIELD_IDS = { type: 'f-type', email: 'f-email', state: 'f-state', details: 'f-details' } as const;
const TYPES = ['access', 'delete', 'correct', 'optout'] as const;

/** "Do not sell or share my personal information": stored with the cookie choice (and the account when signed in). */
function DoNotSell() {
  const t = useT();
  const { consent, gpc, save } = useConsent();
  const on = (consent?.dns ?? false) || gpc;
  const [status, setStatus] = useState('');
  const toggle = async () => {
    if (on && gpc) {
      setStatus(t('legal.dnsGpc'));
      return;
    }
    const base = consent ?? { ...REJECT_ALL };
    await save({ functional: base.functional, analytics: base.analytics, advertising: base.advertising, dns: !on }, 'privacy-choices');
    setStatus(!on ? t('legal.dnsOn') : t('legal.dnsOff'));
  };
  return (
    <section aria-labelledby="dns-h" className={styles.panel}>
      <div className={styles.dnsHead}>
        <h2 id="dns-h" className={styles.dnsTitle}>
          {t('legal.dns')}
        </h2>
        <Switch checked={on} labelledBy="dns-h" describedBy="dns-d" onChange={() => void toggle()} />
      </div>
      <p id="dns-d" className={styles.muted}>
        {on ? t('legal.dnsOn') : t('legal.dnsOff')} {gpc && t('legal.dnsGpc')}
      </p>
      <p role="status" aria-live="polite" className={styles.srOnly}>
        {status}
      </p>
    </section>
  );
}

/** Make a privacy request: access, delete, correct or opt out. Submitted to content-service through the BFF. */
function PrivacyRequestPanel({ privacyEmail }: { privacyEmail: string }) {
  const t = useT();
  const locale = useLocale();
  const { data: me } = useQuery(accountQueries.me(browserApi()));
  const accountEmail = me?.user?.email ?? '';
  const [form, setForm] = useState<PrivacyRequestForm>(() => emptyPrivacyRequest(accountEmail));
  const [errors, setErrors] = useState<PrivacyRequestErrors>({});
  const [attempts, setAttempts] = useState(0);
  const [sent, setSent] = useState<{ ref: string; email: string } | null>(null);
  const [failed, setFailed] = useState(false);

  // Signed-in shoppers get their account email filled in (once the session has loaded).
  useEffect(() => {
    if (accountEmail) setForm((f) => (f.email ? f : { ...f, email: accountEmail }));
  }, [accountEmail]);

  const m = useMutation({ mutationFn: (f: PrivacyRequestForm) => browserApi().submitPrivacyRequest(toPrivacyRequest(f, locale)) });

  const set = <K extends keyof PrivacyRequestForm>(k: K, v: PrivacyRequestForm[K]) => {
    setForm((f) => ({ ...f, [k]: v }));
    setErrors((e) => ({ ...e, [k]: undefined }));
    setSent(null);
    setFailed(false);
  };

  const submit = (ev: FormEvent) => {
    ev.preventDefault();
    const e = validatePrivacyRequest(form);
    setAttempts((n) => n + 1);
    setErrors(e);
    setSent(null);
    setFailed(false);
    if (Object.keys(e).length) return;
    m.mutate(form, {
      onSuccess: (r) => setSent({ ref: r.reference, email: form.email.trim() }),
      onError: (err) => {
        const fe = isApiError(err) ? serverErrors(err.fieldErrors) : {};
        if (Object.keys(fe).length) setErrors(fe);
        else setFailed(true);
      }
    });
  };

  const list = PRIVACY_FIELDS.filter((f) => errors[f]).map((f) => ({ fieldId: FIELD_IDS[f], message: t(errors[f] ?? 'legal.errType') }));
  const err = (f: keyof typeof FIELD_IDS) => (errors[f] ? t(errors[f]) : null);

  return (
    <form onSubmit={submit} noValidate aria-labelledby="req-h" className={styles.panel}>
      <h2 id="req-h" className={styles.panelTitle}>
        {t('legal.reqTitle')}
      </h2>
      <p className={styles.muted}>{t('legal.reqIntro')}</p>
      <ErrorSummary title={t('legal.errSummary', { n: list.length })} errors={list} submitCount={attempts} />
      {sent && (
        <p role="status" className={`${styles.note} ${styles.noteGood}`}>
          {t('legal.submitted', { ref: sent.ref, email: sent.email })}
        </p>
      )}
      {failed && (
        <p role="alert" className={styles.fieldError}>
          {t('legal.submitError', { email: privacyEmail })}
        </p>
      )}
      <fieldset id={FIELD_IDS.type} tabIndex={-1} aria-invalid={errors.type ? true : undefined} aria-describedby={errors.type ? 'f-type-e' : undefined} className={styles.fieldset}>
        <legend className={styles.legend}>{t('legal.reqType')}</legend>
        {TYPES.map((v) => (
          <label key={v} className={styles.radio}>
            <input type="radio" name="reqtype" value={v} checked={form.type === v} onChange={() => set('type', v)} />
            {t(`legal.r.${v}`)}
          </label>
        ))}
        {errors.type && (
          <span id="f-type-e" className={styles.fieldError}>
            {err('type')}
          </span>
        )}
      </fieldset>
      <div className={styles.grid2}>
        <TextField id={FIELD_IDS.email} label={t('legal.email')} type="email" autoComplete="email" value={form.email} onChange={(e) => set('email', e.target.value)} error={err('email')} />
        <Select
          id={FIELD_IDS.state}
          label={t('legal.state')}
          autoComplete="address-level1"
          value={form.state}
          onChange={(e) => set('state', e.target.value)}
          placeholder={t('legal.choose')}
          options={US_STATES.map(([value, label]) => ({ value, label }))}
          error={err('state')}
        />
      </div>
      <div className={styles.stack}>
        <label htmlFor={FIELD_IDS.details} className={styles.label}>
          {t('legal.details')}
        </label>
        <textarea
          id={FIELD_IDS.details}
          rows={3}
          maxLength={2000}
          className={styles.textarea}
          value={form.details}
          onChange={(e) => set('details', e.target.value)}
          aria-invalid={errors.details ? true : undefined}
          aria-describedby={errors.details ? 'f-details-e' : undefined}
        />
        {errors.details && (
          <span id="f-details-e" className={styles.fieldError}>
            {err('details')}
          </span>
        )}
      </div>
      <button type="submit" className={`${styles.btn} ${styles.btnGold}`} disabled={m.isPending}>
        {m.isPending ? t('legal.submitting') : t('legal.submit')}
      </button>
    </form>
  );
}

export function PrivacyChoices({ privacyEmail }: { privacyEmail: string }) {
  return (
    <>
      <DoNotSell />
      <PrivacyRequestPanel privacyEmail={privacyEmail} />
    </>
  );
}
