'use client';

import { type CountryCode, evaluatePassword, type IdentityProviderId, isApiError, isValidMobile, SignUpFormSchema, toSignUpRequest } from '@afrotransact/api-client';
import { Button, Checkbox, PasswordField, PhoneInput, TextField, useToast } from '@afrotransact/ui';
import Link from 'next/link';
import { type FormEvent, useMemo, useState } from 'react';

import { t } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { signInHref } from '@/lib/links';

import styles from './Auth.module.css';
import { CodeStep } from './CodeStep';
import { SocialSignIn } from './SocialSignIn';
import { useAuthSuccess } from './useAuthSuccess';

type Field = 'firstName' | 'lastName' | 'email' | 'phone' | 'password' | 'confirmPassword' | 'termsAccepted';
const ORDER: Field[] = ['firstName', 'lastName', 'email', 'phone', 'password', 'confirmPassword', 'termsAccepted'];
const IDS: Record<Field, string> = { firstName: 'su-first', lastName: 'su-last', email: 'su-email', phone: 'su-phone', password: 'su-password', confirmPassword: 'su-confirm', termsAccepted: 'su-terms' };

export interface SignUpFormProps {
  providers: IdentityProviderId[];
  returnTo: string;
  demo: boolean;
  smsProgram: string;
  links: { terms: string; privacy: string; sms: string };
}

/** Unified account creation (customers, sellers and providers share it — the role is decided server-side). */
export function SignUpForm({ providers, returnTo, demo, smsProgram, links }: SignUpFormProps) {
  const api = browserApi();
  const done = useAuthSuccess();
  const toast = useToast();
  const [v, setV] = useState({ firstName: '', lastName: '', email: '', phoneCountry: 'US' as CountryCode, phone: '', password: '', confirmPassword: '', termsAccepted: false, smsConsent: false });
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [verify, setVerify] = useState<{ challengeId: string; destination: string } | null>(null);

  const set = <K extends keyof typeof v>(k: K, value: (typeof v)[K]) => {
    setV((cur) => ({ ...cur, [k]: value }));
    if (k in IDS) setErrors((e) => ({ ...e, [k]: undefined }));
  };
  const rules = useMemo(() => evaluatePassword(v.password, { firstName: v.firstName, lastName: v.lastName, email: v.email, confirm: v.confirmPassword }), [v]);
  const phoneOk = v.phone ? isValidMobile(v.phone, v.phoneCountry) : false;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const parsed = SignUpFormSchema.safeParse(v);
    if (!parsed.success) {
      const fe: Partial<Record<Field, string>> = {};
      parsed.error.issues.forEach((i) => {
        const k = i.path[0] as Field;
        fe[k] ??= i.message;
      });
      setErrors(fe);
      setFormError(t('auth.fixFields'));
      const first = ORDER.find((k) => fe[k]);
      if (first) document.getElementById(IDS[first])?.focus();
      return;
    }
    setBusy(true);
    setFormError(null);
    try {
      const r = await api.signUp(toSignUpRequest(parsed.data, returnTo));
      if (r.status === 'verification_required') setVerify({ challengeId: r.challengeId, destination: r.destination });
      else if (r.status === 'authenticated') await done(r.redirectTo);
    } catch (err) {
      if (isApiError(err)) {
        const fe = err.fieldErrors as Partial<Record<Field, string>>;
        setErrors(fe);
        setFormError(err.message);
      } else setFormError(t('auth.genericError'));
    } finally {
      setBusy(false);
    }
  };

  if (verify) {
    return (
      <CodeStep
        title={t('auth.verifyEmailTitle')}
        hint={t('auth.verifyEmailHint', { destination: verify.destination })}
        demoHint={demo ? t('auth.demo') : null}
        onStartAgain={() => setVerify(null)}
        onResend={async () => (await api.resendEmailCode(verify.challengeId)).resendAfterSeconds}
        onVerify={async (code) => {
          try {
            const r = await api.verifyEmail({ challengeId: verify.challengeId, code });
            if (r.status === 'authenticated') {
              toast.show(t('auth.welcome', { name: v.firstName }));
              await done(r.redirectTo);
            }
          } catch (err) {
            throw new Error(isApiError(err) ? err.message : t('auth.genericError'));
          }
        }}
      />
    );
  }

  const errorList = ORDER.filter((k) => errors[k]);
  return (
    <form onSubmit={submit} noValidate className={styles.form} style={{ width: '100%' }}>
      <h1 className={styles.h1}>{t('auth.signupTitle')}</h1>
      {errorList.length > 0 && (
        <div className={styles.summary} role="alert">
          {t('auth.errorSummary', { n: errorList.length })}
          <ul>
            {errorList.map((k) => (
              <li key={k}>
                <a href={`#${IDS[k]}`}>{errors[k]}</a>
              </li>
            ))}
          </ul>
        </div>
      )}
      <div className={styles.grid2}>
        <TextField id={IDS.firstName} label={t('auth.firstName')} autoComplete="given-name" value={v.firstName} onChange={(e) => set('firstName', e.target.value)} error={errors.firstName} required />
        <TextField id={IDS.lastName} label={t('auth.lastName')} autoComplete="family-name" value={v.lastName} onChange={(e) => set('lastName', e.target.value)} error={errors.lastName} required />
        <TextField id={IDS.email} className={styles.span2} label={t('auth.email')} type="email" autoComplete="email" placeholder="you@example.com" value={v.email} onChange={(e) => set('email', e.target.value)} error={errors.email} required />
        <div className={styles.span2}>
          <PhoneInput
            id={IDS.phone}
            label={t('auth.mobile')}
            country={v.phoneCountry}
            onCountryChange={(c) => set('phoneCountry', c)}
            value={v.phone}
            onChange={(p) => set('phone', p)}
            error={errors.phone}
            help={phoneOk ? t('auth.mobileOk') : t('auth.mobileHelp')}
            helpTone={phoneOk ? 'success' : 'muted'}
            required
          />
        </div>
        <PasswordField id={IDS.password} label={t('auth.password')} autoComplete="new-password" value={v.password} onChange={(e) => set('password', e.target.value)} error={errors.password} required />
        <PasswordField id={IDS.confirmPassword} label={t('auth.confirmPassword')} autoComplete="new-password" value={v.confirmPassword} onChange={(e) => set('confirmPassword', e.target.value)} error={errors.confirmPassword} required />
      </div>
      <PasswordRules rules={rules} />
      <Checkbox
        id={IDS.termsAccepted}
        className={styles.consent}
        checked={v.termsAccepted}
        onChange={(e) => set('termsAccepted', e.target.checked)}
        error={errors.termsAccepted}
        label={
          <TermsText terms={links.terms} privacy={links.privacy} />
        }
      />
      <Checkbox
        className={styles.consent}
        checked={v.smsConsent}
        onChange={(e) => set('smsConsent', e.target.checked)}
        label={<strong>{t('auth.smsTitle')}</strong>}
        description={<SmsDisclosure program={smsProgram} sms={links.sms} privacy={links.privacy} />}
      />
      <p className={styles.error} aria-live="polite">
        {formError && errorList.length === 0 ? formError : ''}
      </p>
      <Button type="submit" loading={busy}>
        {t('auth.createAccount')}
      </Button>
      <SocialSignIn providers={providers} returnTo={returnTo} />
      <Link href={signInHref(returnTo)} className={styles.toggle}>
        {t('auth.toSignin')}
      </Link>
    </form>
  );
}

function PasswordRules({ rules }: { rules: Array<{ id: string; label: string; ok: boolean }> }) {
  return (
    <ul style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '2px 12px', margin: 0, padding: 0, listStyle: 'none', fontSize: 'var(--at-text-xs)' }} aria-label="Password requirements" aria-live="polite">
      {rules.map((r) => (
        <li key={r.id} style={{ color: r.ok ? 'var(--at-color-success)' : 'var(--at-color-text-muted)' }}>
          {r.ok ? '✓' : '○'} {r.label}
          <span className="at-visually-hidden">{r.ok ? ' — met' : ' — not met'}</span>
        </li>
      ))}
    </ul>
  );
}

function TermsText({ terms, privacy }: { terms: string; privacy: string }) {
  const [a, rest = ''] = t('auth.terms').split('{terms}');
  const [b, c = ''] = rest.split('{privacy}');
  return (
    <>
      {a}
      <Link href={terms} target="_blank">
        {t('auth.termsLink')}
      </Link>
      {b}
      <Link href={privacy} target="_blank">
        {t('auth.privacyLink')}
      </Link>
      {c}
    </>
  );
}

function SmsDisclosure({ program, sms, privacy }: { program: string; sms: string; privacy: string }) {
  const text = t('auth.smsDisclosure', { program });
  const [a, rest = ''] = text.split('{sms}');
  const [b, c = ''] = rest.split('{privacy}');
  return (
    <>
      {a}
      <Link href={sms} target="_blank">
        {t('auth.smsLink')}
      </Link>
      {b}
      <Link href={privacy} target="_blank">
        {t('auth.privacyLink')}
      </Link>
      {c}
    </>
  );
}
