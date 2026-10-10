'use client';

import { CodeSchema } from '@afrotransact/api-client';
import { Button, Checkbox, TextField } from '@afrotransact/ui';
import { type FormEvent, type ReactNode, useEffect, useRef, useState } from 'react';

import { t } from '@/i18n/t';

import styles from './Auth.module.css';

export interface CodeStepProps {
  title: string;
  hint: ReactNode;
  onVerify: (code: string, trust: boolean) => Promise<void>;
  onStartAgain: () => void;
  onResend?: () => Promise<number>;
  showTrust?: boolean;
  methods?: ReactNode;
  demoHint?: string | null;
}

/** 6-digit code entry used for two-step verification and sign-up email confirmation. */
export function CodeStep({ title, hint, onVerify, onStartAgain, onResend, showTrust, methods, demoHint }: CodeStepProps) {
  const [code, setCode] = useState('');
  const [trust, setTrust] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [cooldown, setCooldown] = useState(onResend ? 30 : 0);
  const [notice, setNotice] = useState('');
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => heading.current?.focus(), []);
  useEffect(() => {
    if (cooldown <= 0) return;
    const id = setTimeout(() => setCooldown((c) => c - 1), 1000);
    return () => clearTimeout(id);
  }, [cooldown]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const parsed = CodeSchema.safeParse(code);
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? t('auth.genericError'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onVerify(parsed.data, trust);
    } catch (err) {
      setError(err instanceof Error ? err.message : t('auth.genericError'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate className={styles.form} style={{ width: '100%' }}>
      <h1 ref={heading} tabIndex={-1} className={styles.h1}>
        {title}
      </h1>
      {methods}
      <p className={styles.hint}>{hint}</p>
      <TextField
        label={t('auth.code')}
        value={code}
        onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
        inputMode="numeric"
        autoComplete="one-time-code"
        maxLength={6}
        placeholder="••••••"
        className={styles.codeField}
        error={error}
      />
      {showTrust && <Checkbox label={t('auth.trust')} checked={trust} onChange={(e) => setTrust(e.target.checked)} />}
      <Button type="submit" loading={busy}>
        {t('auth.verify')}
      </Button>
      <div className={styles.row}>
        {onResend && (
          <button
            type="button"
            className="link-button"
            disabled={cooldown > 0}
            onClick={async () => {
              const s = await onResend();
              setCooldown(s);
              setNotice(t('auth.resent'));
            }}
          >
            {cooldown > 0 ? t('auth.resendIn', { s: cooldown }) : t('auth.resend')}
          </button>
        )}
        <button type="button" className="link-button" onClick={onStartAgain}>
          {t('auth.startAgain')}
        </button>
      </div>
      <p className={styles.demo} aria-live="polite">
        {notice}
      </p>
      {demoHint && <p className={styles.demo}>{demoHint}</p>}
    </form>
  );
}
