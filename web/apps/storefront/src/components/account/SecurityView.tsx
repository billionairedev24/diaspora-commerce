'use client';

import { isApiError, type NotificationPreferences, type RecoveryCodes, securityKeys, type TotpSetup } from '@afrotransact/api-client';
import { Button, DeviceMobile, Fingerprint, Key, LockSimple, ShieldCheck, Skeleton, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';

import { t } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import { AccountCrumb } from './AccountCrumb';
import styles from './Pages.module.css';
import { PhoneVerificationCard } from './PhoneVerificationCard';

const when = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : '—');
const errorOf = (e: unknown, field?: string) => (isApiError(e) ? ((field && e.fieldErrors[field]) || e.message) : t('auth.genericError'));

function Codes({ codes }: { codes: RecoveryCodes }) {
  if (!codes.recoveryCodes.length) return null;
  return (
    <div className={styles.callout} role="status">
      <p className={styles.muted}>
        <strong>{t('sec.recoveryShownOnce')}</strong>
      </p>
      <p className={styles.code}>{codes.recoveryCodes.join('  ')}</p>
    </div>
  );
}

function PasswordBlock() {
  const toast = useToast();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const change = useMutation({
    mutationFn: () => browserApi().changePassword({ currentPassword: current, newPassword: next }),
    onSuccess: () => { setCurrent(''); setNext(''); setErrors({}); toast.show(t('sec.passwordChanged')); },
    onError: (e) => setErrors(isApiError(e) && Object.keys(e.fieldErrors).length ? e.fieldErrors : { _: errorOf(e) })
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (next.length < 12) return setErrors({ newPassword: t('sec.passwordRule') });
    change.mutate();
  };
  return (
    <section className={styles.card} aria-labelledby="sec-password">
      <h2 id="sec-password" className={styles.h2}>
        <LockSimple aria-hidden /> {t('sec.password')}
      </h2>
      <form className={styles.form} onSubmit={submit} noValidate>
        <div className={styles.two}>
          <TextField label={t('sec.currentPassword')} type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} error={errors.currentPassword} />
          <TextField label={t('sec.newPassword')} type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} error={errors.newPassword} help={t('sec.passwordRule')} />
        </div>
        {errors._ && <p className={styles.error} role="alert">{errors._}</p>}
        <div className={styles.actions}>
          <Button type="submit" variant="neutral" loading={change.isPending}>
            {t('sec.changePassword')}
          </Button>
        </div>
      </form>
    </section>
  );
}

function TwoStep({ totp, sms, smsPhone, recoveryLeft, mayDisable }: { totp: boolean; sms: boolean; smsPhone: string | null; recoveryLeft: number; mayDisable: boolean }) {
  const api = browserApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [setup, setSetup] = useState<TotpSetup | null>(null);
  const [smsPending, setSmsPending] = useState(false);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [codes, setCodes] = useState<RecoveryCodes | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: securityKeys.overview() });
  const fail = (e: unknown) => setError(errorOf(e, 'code'));
  const startTotp = useMutation({ mutationFn: () => api.startTotp(), onSuccess: (s) => { setSetup(s); setSmsPending(false); setCode(''); setError(null); }, onError: fail });
  const startSms = useMutation({ mutationFn: () => api.startSmsMfa(), onSuccess: () => { setSmsPending(true); setSetup(null); setCode(''); setError(null); }, onError: fail });
  const confirm = useMutation({
    mutationFn: () => (setup ? api.confirmTotp(code.trim()) : api.confirmSmsMfa(code.trim())),
    onSuccess: async (rc) => { setCodes(rc); setSetup(null); setSmsPending(false); await refresh(); toast.show(t('sec.twoStepOn')); },
    onError: fail
  });
  const off = useMutation({ mutationFn: (which: 'totp' | 'sms') => (which === 'totp' ? api.disableTotp() : api.disableSmsMfa()), onSuccess: () => refresh(), onError: (e) => toast.show(errorOf(e)) });
  const regenerate = useMutation({ mutationFn: () => api.regenerateRecoveryCodes(), onSuccess: async (rc) => { setCodes(rc); await refresh(); }, onError: (e) => toast.show(errorOf(e)) });

  return (
    <section className={styles.card} aria-labelledby="sec-2sv">
      <h2 id="sec-2sv" className={styles.h2}>
        <ShieldCheck aria-hidden /> {t('sec.twoStep')}
      </h2>
      <ul className={styles.list}>
        <li className={styles.row}>
          <Key aria-hidden />
          <span>
            <strong>{t('sec.authenticator')}</strong>
            <br />
            <span className={styles.muted}>{totp ? t('sec.on') : t('sec.off')}</span>
          </span>
          {totp ? (
            <Button size="sm" variant="neutral" onClick={() => off.mutate('totp')} disabled={!mayDisable} loading={off.isPending}>
              {t('sec.turnOff')}
            </Button>
          ) : (
            <Button size="sm" variant="neutral" onClick={() => startTotp.mutate()} loading={startTotp.isPending}>
              {t('sec.turnOn')}
            </Button>
          )}
        </li>
        <li className={styles.row}>
          <DeviceMobile aria-hidden />
          <span>
            <strong>{t('sec.smsCodes')}</strong>
            <br />
            <span className={styles.muted}>{sms ? t('sec.smsTo', { phone: smsPhone ?? '' }) : t('sec.off')}</span>
          </span>
          {sms ? (
            <Button size="sm" variant="neutral" onClick={() => off.mutate('sms')} disabled={!mayDisable} loading={off.isPending}>
              {t('sec.turnOff')}
            </Button>
          ) : (
            <Button size="sm" variant="neutral" onClick={() => startSms.mutate()} loading={startSms.isPending}>
              {t('sec.turnOn')}
            </Button>
          )}
        </li>
        <li className={styles.row}>
          <Key aria-hidden />
          <span>
            <strong>{t('sec.recoveryCodes')}</strong>
            <br />
            <span className={styles.muted}>{t('sec.recoveryLeft', { n: recoveryLeft })}</span>
          </span>
          <Button size="sm" variant="neutral" onClick={() => regenerate.mutate()} loading={regenerate.isPending}>
            {t('sec.newCodes')}
          </Button>
        </li>
      </ul>
      {(setup || smsPending) && (
        <form className={styles.form} onSubmit={(e) => { e.preventDefault(); confirm.mutate(); }}>
          {setup ? (
            <p className={styles.muted}>
              {t('sec.totpSetup')} <span className={styles.code}>{setup.secret}</span>{' '}
              <a href={setup.otpauthUri}>{t('sec.openAuthenticator')}</a>
            </p>
          ) : (
            <p className={styles.muted}>{t('sec.smsSent')}</p>
          )}
          <div className={styles.actions}>
            <TextField label={t('account.phoneCode')} value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" autoComplete="one-time-code" maxLength={6} error={error} />
            <Button type="submit" loading={confirm.isPending}>
              {t('account.phoneConfirm')}
            </Button>
          </div>
        </form>
      )}
      {codes && <Codes codes={codes} />}
      {!mayDisable && <p className={styles.muted}>{t('sec.required')}</p>}
    </section>
  );
}

function Notifications() {
  const api = browserApi();
  const qc = useQueryClient();
  const toast = useToast();
  const prefs = useQuery(shopperQueries.notificationPreferences(api));
  const save = useMutation({
    mutationFn: (p: NotificationPreferences) => api.saveNotificationPreferences(p),
    onSuccess: (p) => { qc.setQueryData(securityKeys.preferences(), p); toast.show(t('sec.prefsSaved')); },
    onError: (e) => toast.show(errorOf(e))
  });
  const p = prefs.data;
  const rows: Array<[keyof NotificationPreferences, string]> = [
    ['email', t('sec.prefEmail')],
    ['sms', t('sec.prefSms')],
    ['push', t('sec.prefPush')],
    ['marketingOptIn', t('sec.prefMarketing')]
  ];
  return (
    <section className={styles.card} aria-labelledby="sec-notify">
      <h2 id="sec-notify" className={styles.h2}>
        {t('sec.notifications')}
      </h2>
      {!p && <Skeleton height={96} />}
      {p &&
        rows.map(([k, label]) => (
          <label key={k} className={styles.check}>
            <input type="checkbox" checked={p[k]} disabled={save.isPending} onChange={(e) => save.mutate({ ...p, [k]: e.target.checked })} />
            {label}
          </label>
        ))}
    </section>
  );
}

/** Login & security (S148): password, two-step verification, passkeys, linked accounts, signed-in devices, push devices, notifications. */
export function SecurityView() {
  const api = browserApi();
  const qc = useQueryClient();
  const toast = useToast();
  const sec = useQuery(shopperQueries.security(api));
  const refresh = () => qc.invalidateQueries({ queryKey: securityKeys.overview() });
  const act = useMutation({
    mutationFn: async (a: { kind: 'passkey' | 'session' | 'device' | 'others' | 'unlink'; id?: string }) => {
      if (a.kind === 'passkey') await api.removePasskey(a.id ?? '');
      else if (a.kind === 'session') await api.revokeSession(a.id ?? '');
      else if (a.kind === 'device') await api.removeDevice(a.id ?? '');
      else if (a.kind === 'unlink') await api.unlinkAccount(a.id ?? '');
      else toast.show(t('sec.othersSignedOut', { n: (await api.revokeOtherSessions()).revoked }));
    },
    onSuccess: () => refresh(),
    onError: (e) => toast.show(errorOf(e))
  });
  const s = sec.data;

  return (
    <div className={styles.page}>
      <AccountCrumb>{t('account.security')}</AccountCrumb>
      <h1 className={styles.h1}>{t('sec.title')}</h1>
      {sec.isError && <p className={styles.error} role="alert">{t('acct.loadError')}</p>}
      <PasswordBlock />
      {!s && sec.isPending && <Skeleton height={160} />}
      {s && (
        <>
          <TwoStep totp={s.mfa.totp} sms={s.mfa.sms} smsPhone={s.mfa.smsPhone} recoveryLeft={s.mfa.recoveryCodesLeft} mayDisable={s.mfa.mayDisable} />
          <section className={styles.card} aria-labelledby="sec-passkeys">
            <div className={styles.cardHead}>
              <h2 id="sec-passkeys" className={styles.h2}>
                <Fingerprint aria-hidden /> {t('sec.passkeys')}
              </h2>
              <a href={s.passkeyRegistrationUrl} target="_blank" rel="noopener noreferrer">
                {t('sec.addPasskey')}
              </a>
            </div>
            {s.passkeys.length === 0 && <p className={styles.muted}>{t('sec.noPasskeys')}</p>}
            <ul className={styles.list}>
              {s.passkeys.map((p) => (
                <li key={p.credentialId} className={styles.row}>
                  <Fingerprint aria-hidden />
                  <span>
                    <strong>{p.label}</strong>
                    <br />
                    <span className={styles.muted}>{t('sec.passkeyMeta', { created: when(p.createdAt), used: when(p.lastUsedAt) })}</span>
                  </span>
                  <button type="button" className="link-button" onClick={() => act.mutate({ kind: 'passkey', id: p.credentialId })}>
                    {t('common.remove')}
                  </button>
                </li>
              ))}
            </ul>
          </section>
          <section className={styles.card} aria-labelledby="sec-linked">
            <h2 id="sec-linked" className={styles.h2}>
              {t('sec.linked')}
            </h2>
            {s.linkedAccounts.accounts.length === 0 && <p className={styles.muted}>{t('sec.noLinked')}</p>}
            <ul className={styles.list}>
              {s.linkedAccounts.accounts.map((a) => (
                <li key={a.provider} className={styles.row}>
                  <span className={styles.chip}>{a.provider}</span>
                  <span>{a.email ?? a.provider}</span>
                  {a.canUnlink && (
                    <button type="button" className="link-button" onClick={() => act.mutate({ kind: 'unlink', id: a.provider })}>
                      {t('sec.unlink')}
                    </button>
                  )}
                </li>
              ))}
            </ul>
          </section>
          <section className={styles.card} aria-labelledby="sec-sessions">
            <div className={styles.cardHead}>
              <h2 id="sec-sessions" className={styles.h2}>
                {t('sec.signedIn')}
              </h2>
              {s.sessions.length > 1 && (
                <Button size="sm" variant="neutral" onClick={() => act.mutate({ kind: 'others' })} loading={act.isPending}>
                  {t('sec.signOutOthers')}
                </Button>
              )}
            </div>
            <ul className={styles.list}>
              {s.sessions.map((x) => (
                <li key={x.id} className={styles.row} data-testid="session">
                  <DeviceMobile aria-hidden />
                  <span>
                    <strong>{x.clientName}</strong>
                    <br />
                    <span className={styles.muted}>{x.current ? t('sec.thisDevice') : t('sec.lastActive', { date: when(x.lastActiveAt) })}</span>
                  </span>
                  {!x.current && (
                    <button type="button" className="link-button" onClick={() => act.mutate({ kind: 'session', id: x.id })}>
                      {t('sec.signOut')}
                    </button>
                  )}
                </li>
              ))}
            </ul>
            {s.devices.length > 0 && (
              <>
                <h3 className={styles.muted}>{t('sec.pushDevices')}</h3>
                <ul className={styles.list}>
                  {s.devices.map((d) => (
                    <li key={d.id} className={styles.row}>
                      <DeviceMobile aria-hidden />
                      <span>
                        <strong>{d.deviceName}</strong>
                        <br />
                        <span className={styles.muted}>{t('sec.lastActive', { date: when(d.lastSeenAt) })}</span>
                      </span>
                      <button type="button" className="link-button" onClick={() => act.mutate({ kind: 'device', id: d.id })}>
                        {t('sec.stopPush')}
                      </button>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </section>
        </>
      )}
      <Notifications />
      <PhoneVerificationCard />
    </div>
  );
}
