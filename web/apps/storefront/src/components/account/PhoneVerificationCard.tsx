'use client';

import { accountKeys, CodeSchema, isApiError } from '@afrotransact/api-client';
import { Button, TextField } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';

import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { accountQueries } from '@/lib/api/queries';

import styles from './Account.module.css';

const message = (e: unknown, t: Translator<MessageKey>) => (isApiError(e) ? e.message : t('auth.genericError'));

/** Account phone verification (identity S234): text a 6-digit code to the account phone, then confirm it. */
export function PhoneVerificationCard() {
  const t = useT();
  const api = browserApi();
  const qc = useQueryClient();
  const me = useQuery(accountQueries.me(api));
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const send = useMutation({ mutationFn: () => api.startPhoneVerification(), onMutate: () => setError(null), onError: (e) => setError(message(e, t)) });
  const confirm = useMutation({
    mutationFn: (c: string) => api.confirmPhoneVerification(c),
    onSuccess: () => void qc.invalidateQueries({ queryKey: accountKeys.me() }),
    onError: (e) => setError(isApiError(e) ? (e.fieldErrors.code ?? e.message) : t('auth.genericError'))
  });

  const user = me.data?.user;
  if (!user?.phone) return null;
  const verified = user.phoneVerified === true || confirm.isSuccess;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const parsed = CodeSchema.safeParse(code.trim());
    if (!parsed.success) return setError(parsed.error.issues[0]?.message ?? t('auth.genericError'));
    setError(null);
    confirm.mutate(parsed.data);
  };

  return (
    <section className={styles.card} aria-labelledby="phone-title">
      <h2 id="phone-title" className={styles.title}>
        {t('account.phoneTitle')}
      </h2>
      {verified ? (
        <p className={styles.verified} role="status">
          {t('account.phoneVerified', { phone: user.phone })}
        </p>
      ) : send.isSuccess ? (
        <form className={styles.row} onSubmit={submit} noValidate>
          <p className={styles.muted}>{t('account.phoneCodeSent', { phone: send.data.sentTo })}</p>
          <TextField label={t('account.phoneCode')} inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} error={error} onChange={(e) => setCode(e.target.value)} />
          <Button type="submit" loading={confirm.isPending}>
            {t('account.phoneConfirm')}
          </Button>
          <Button type="button" variant="link" loading={send.isPending} onClick={() => send.mutate()}>
            {t('account.phoneResend')}
          </Button>
        </form>
      ) : (
        <>
          <p className={styles.muted}>{t('account.phoneUnverified', { phone: user.phone })}</p>
          {error ? (
            <p className={styles.error} role="alert">
              {error}
            </p>
          ) : null}
          <div className={styles.row}>
            <Button loading={send.isPending} onClick={() => send.mutate()}>
              {t('account.phoneSend')}
            </Button>
          </div>
        </>
      )}
    </section>
  );
}
