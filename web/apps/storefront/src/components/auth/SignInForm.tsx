'use client';

import { type IdentityProviderId, isApiError, SignInFormSchema } from '@afrotransact/api-client';
import { Button, Fingerprint, PasswordField, TextField } from '@afrotransact/ui';
import Link from 'next/link';
import { type FormEvent, useState } from 'react';

import { FormErrors } from '@/components/common/FormErrors';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { PasskeyError, signInWithPasskey } from '@/lib/auth/passkey';
import { signUpHref } from '@/lib/links';

import styles from './Auth.module.css';
import { CodeStep } from './CodeStep';
import { SocialSignIn } from './SocialSignIn';
import { useAuthSuccess } from './useAuthSuccess';

type Step = { kind: 'start' } | { kind: 'mfa'; challengeId: string; methods: string[]; destination: string };

export function SignInForm({ providers, returnTo, demo }: { providers: IdentityProviderId[]; returnTo: string; demo: boolean }) {
  const t = useT();
  const api = browserApi();
  const done = useAuthSuccess();
  const [step, setStep] = useState<Step>({ kind: 'start' });
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<{ email?: string; password?: string; form?: string }>({});
  const [busy, setBusy] = useState(false);
  const [attempts, setAttempts] = useState(0);
  const [passkeyBusy, setPasskeyBusy] = useState(false);
  const [method, setMethod] = useState<'totp' | 'sms'>('totp');

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const parsed = SignInFormSchema.safeParse({ email, password });
    if (!parsed.success) {
      const fe: typeof errors = {};
      parsed.error.issues.forEach((i) => {
        const k = i.path[0] as 'email' | 'password';
        fe[k] ??= i.message;
      });
      setErrors(fe);
      setAttempts((n) => n + 1);
      return;
    }
    setBusy(true);
    setErrors({});
    try {
      const r = await api.signIn({ ...parsed.data, returnTo });
      if (r.status === 'authenticated') await done(r.redirectTo);
      else if (r.status === 'mfa_required') setStep({ kind: 'mfa', challengeId: r.challengeId, methods: r.methods, destination: r.destination });
    } catch (err) {
      setErrors({ form: isApiError(err) ? err.message : t('auth.genericError') });
    } finally {
      setBusy(false);
    }
  };

  const passkey = async () => {
    setPasskeyBusy(true);
    setErrors({});
    try {
      const r = await signInWithPasskey(api);
      if (r.status === 'authenticated') await done(r.redirectTo === '/' ? returnTo : r.redirectTo);
    } catch (err) {
      const reason = err instanceof PasskeyError ? err.reason : 'failed';
      setErrors({ form: reason === 'unsupported' ? t('auth.passkeyUnsupported') : reason === 'cancelled' ? t('auth.passkeyCancelled') : t('auth.passkeyFailed') });
    } finally {
      setPasskeyBusy(false);
    }
  };

  if (step.kind === 'mfa') {
    return (
      <CodeStep
        title={t('auth.mfaTitle')}
        showTrust
        methods={
          <div className={styles.chips} role="group" aria-label={t('auth.mfaTitle')}>
            {(['totp', 'sms'] as const)
              .filter((m) => step.methods.includes(m))
              .map((m) => (
                <button key={m} type="button" className={styles.chip} aria-pressed={method === m} onClick={() => setMethod(m)}>
                  {m === 'totp' ? t('auth.mfaTotp') : t('auth.mfaSms')}
                </button>
              ))}
          </div>
        }
        hint={method === 'totp' ? t('auth.mfaHintTotp') : t('auth.mfaHintSms', { destination: step.destination })}
        demoHint={demo ? t('auth.demo') : null}
        onStartAgain={() => setStep({ kind: 'start' })}
        onVerify={async (code, trust) => {
          try {
            const r = await api.verifyMfa({ challengeId: step.challengeId, code, trustDevice: trust });
            if (r.status === 'authenticated') await done(r.redirectTo);
          } catch (err) {
            throw new Error(isApiError(err) ? err.message : t('auth.genericError'));
          }
        }}
      />
    );
  }

  return (
    <form onSubmit={submit} noValidate className={styles.form} style={{ width: '100%' }}>
      <h1 className={styles.h1}>{t('auth.signinTitle')}</h1>
      <FormErrors errors={errors} ids={{ email: 'signin-email', password: 'signin-password' }} submitCount={attempts} />
      <Button variant="dark" size="lg" icon={<Fingerprint size={20} aria-hidden />} onClick={() => void passkey()} loading={passkeyBusy}>
        {t('auth.passkey')}
      </Button>
      <div className={styles.divider}>{t('auth.orEmail')}</div>
      <TextField id="signin-email" label={t('auth.email')} type="email" autoComplete="username webauthn" value={email} onChange={(e) => setEmail(e.target.value)} error={errors.email} required />
      <PasswordField id="signin-password" label={t('auth.password')} autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} error={errors.password} required />
      <p className={styles.error} role="alert">
        {errors.form}
      </p>
      <Button type="submit" loading={busy}>
        {t('auth.continue')}
      </Button>
      {demo && <p className={styles.demo}>{t('auth.demo')}</p>}
      <SocialSignIn providers={providers} returnTo={returnTo} />
      <Link href={signUpHref(returnTo)} className={styles.toggle}>
        {t('auth.toSignup')}
      </Link>
    </form>
  );
}
