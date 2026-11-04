import { isApiError, isPlainEmail } from '@afrotransact/api-client';
import { Button, TextField } from '@afrotransact/ui';
import { useMutation } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './Product.module.css';

/**
 * "Email me when available" for an out-of-stock product. No account is needed: a guest types an address and gets one
 * email when the item is back. A signed-in shopper sees their own address prefilled (they may change it) and the alert
 * also lists under their account. The answer is the same for any address, so the confirmation never says whether the
 * address was already known.
 */
export function StockAlertForm({ atin, accountEmail }: { atin: string; accountEmail?: string }) {
  const t = useT();
  const [email, setEmail] = useState(accountEmail ?? '');
  const [error, setError] = useState<string | null>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);
  const alert = useMutation({ mutationFn: (e: string) => browserApi().createStockAlert(atin, e) });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const address = email.trim();
    if (!address) {
      setError(t('pdp.notifyEmailRequired'));
      return;
    }
    if (!isPlainEmail(address)) {
      setError(t('pdp.notifyEmailInvalid'));
      return;
    }
    setError(null);
    alert.mutate(address, {
      onSuccess: () => setSentTo(address.toLowerCase()),
      onError: (err) => {
        if (isApiError(err) && err.status === 422) setError(t(err.fieldErrors.email ? 'pdp.notifyEmailInvalid' : 'pdp.notifyFailed'));
        else if (isApiError(err) && err.status === 429) setError(t('pdp.notifyTooMany'));
        else setError(t('pdp.notifyFailed'));
      }
    });
  };

  if (sentTo) {
    return (
      <p className={styles.notifyDone} role="status">
        {t('pdp.notifyDone', { email: sentTo })}
      </p>
    );
  }
  return (
    <form className={styles.notify} onSubmit={submit} noValidate>
      <TextField
        label={t('pdp.notifyEmail')}
        type="email"
        value={email}
        onChange={(e) => setEmail(e.target.value)}
        error={error}
        autoComplete="email"
        maxLength={254}
      />
      <Button type="submit" variant="neutral" size="sm" loading={alert.isPending}>
        {t('pdp.notify')}
      </Button>
      <p className={styles.unavailableHint}>{t('pdp.notifyPrivacy')}</p>
    </form>
  );
}
