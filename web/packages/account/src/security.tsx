'use client';

import { type AccountProfile, type AccountSecurity, type Passkey, settingsKeys, settingsQueries, type SignInEvent } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, PasswordField, TextField, useToast } from '@afrotransact/ui';
import { AppleLogo, Browser, CheckCircle, Desktop, DeviceMobile, FacebookLogo, Fingerprint, GoogleLogo, LockKey, SignIn, WarningCircle } from '@phosphor-icons/react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, type ReactNode, useEffect, useState } from 'react';

import { useAccountEnv } from './context';
import { type AccountMessageKey, type AccountT, useAccountT } from './i18n/t';
import { TwoStepCard } from './mfa';
import { Card, Confirm, errorText, LoadError, Loading, PageHead, Row, styles, useShowMore } from './parts';

export type AccountAudience = 'customer' | 'seller' | 'staff';

function useWhen() {
  const fmt = useFormatters();
  const t = useAccountT();
  return (iso: string | null | undefined) => (iso ? fmt.date(iso, 'short') : t('common.never'));
}

/** Password: collapsed row; "Change password" opens the form in place. */
export function PasswordCard({ profile }: { profile: AccountProfile | undefined }) {
  const t = useAccountT();
  const { api } = useAccountEnv();
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const change = useMutation({
    mutationFn: () => api.changePassword({ currentPassword: current, newPassword: next }),
    onSuccess: () => {
      setOpen(false);
      setCurrent('');
      setNext('');
      setConfirm('');
      setErrors({});
      toast.show(t('password.changed'));
    },
    onError: (e) => {
      const fields: Record<string, string> = {};
      for (const f of ['currentPassword', 'newPassword']) {
        const msg = errorText(t, e, f);
        if (msg !== errorText(t, e)) fields[f] = msg;
      }
      setErrors(Object.keys(fields).length ? fields : { _: errorText(t, e) });
    }
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const errs: Record<string, string> = {};
    if (next.length < 12) errs.newPassword = t('password.tooShort');
    if (confirm !== next) errs.confirm = t('password.mismatch');
    setErrors(errs);
    if (!Object.keys(errs).length) change.mutate();
  };
  return (
    <Card
      id="password"
      title={t('password.title')}
      lead={t('password.sub')}
      action={
        !open ? (
          <button type="button" className={styles.pill} onClick={() => setOpen(true)}>
            {t('password.change')}
          </button>
        ) : undefined
      }
    >
      {open ? (
        <form className={`${styles.form} ${styles.formFirst}`} onSubmit={submit} noValidate>
          <input type="text" name="username" autoComplete="username" value={profile?.email ?? ''} readOnly hidden />
          <PasswordField label={t('password.current')} autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} error={errors.currentPassword} />
          <div className={styles.grid2}>
            <PasswordField label={t('password.new')} autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} error={errors.newPassword} />
            <PasswordField label={t('password.confirm')} autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} error={errors.confirm} />
          </div>
          {errors._ ? <p className={styles.error} role="alert">{errors._}</p> : null}
          <div className={styles.actions}>
            <Button type="submit" loading={change.isPending}>
              {t('password.change')}
            </Button>
            <Button variant="neutral" onClick={() => { setOpen(false); setErrors({}); }}>
              {t('common.cancel')}
            </Button>
          </div>
        </form>
      ) : null}
    </Card>
  );
}

function PasskeyRow({ passkey, onRemove }: { passkey: Passkey; onRemove: (p: Passkey) => void }) {
  const t = useAccountT();
  const when = useWhen();
  const { api } = useAccountEnv();
  const qc = useQueryClient();
  const toast = useToast();
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(passkey.label);
  const [error, setError] = useState<string | null>(null);
  const rename = useMutation({
    mutationFn: () => api.renamePasskey(passkey.credentialId, name.trim()),
    onSuccess: async () => { setEditing(false); setError(null); await qc.invalidateQueries({ queryKey: settingsKeys.security() }); toast.show(t('passkeys.renamed')); },
    onError: (e) => setError(errorText(t, e, 'label'))
  });
  if (editing) {
    return (
      <li className={styles.row}>
        <span className={styles.rowIcon} aria-hidden>
          <Fingerprint />
        </span>
        <form className={styles.actions} style={{ gridColumn: '2 / -1', alignItems: 'flex-end' }} onSubmit={(e) => { e.preventDefault(); rename.mutate(); }}>
          <TextField label={t('passkeys.nameLabel')} value={name} maxLength={64} onChange={(e) => setName(e.target.value)} error={error} />
          <Button type="submit" size="sm" loading={rename.isPending}>
            {t('common.save')}
          </Button>
          <Button size="sm" variant="neutral" onClick={() => { setEditing(false); setName(passkey.label); setError(null); }}>
            {t('common.cancel')}
          </Button>
        </form>
      </li>
    );
  }
  return (
    <Row
      testId="passkey"
      icon={<Fingerprint />}
      title={passkey.label}
      sub={t('passkeys.meta', { created: when(passkey.createdAt), used: when(passkey.lastUsedAt) })}
      actions={
        <>
          <button type="button" className={styles.link} onClick={() => setEditing(true)}>
            {t('common.rename')}
          </button>
          <button type="button" className={`${styles.link} ${styles.danger}`} onClick={() => onRemove(passkey)}>
            {t('common.remove')}
          </button>
        </>
      }
    />
  );
}

/** Passkeys: add (identity's secure page in a new tab), rename and remove. */
export function PasskeysCard({ security }: { security: AccountSecurity }) {
  const t = useAccountT();
  const { api } = useAccountEnv();
  const qc = useQueryClient();
  const toast = useToast();
  const [removing, setRemoving] = useState<Passkey | null>(null);
  const remove = useMutation({
    mutationFn: (p: Passkey) => api.removePasskey(p.credentialId),
    onSuccess: async () => { setRemoving(null); await qc.invalidateQueries({ queryKey: settingsKeys.security() }); toast.show(t('passkeys.removed')); },
    onError: (e) => { setRemoving(null); toast.show(errorText(t, e), 'error'); }
  });
  // A passkey added in the other tab shows up when the person comes back.
  useEffect(() => {
    const onFocus = () => void qc.invalidateQueries({ queryKey: settingsKeys.security() });
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [qc]);
  return (
    <Card
      id="passkeys"
      title={t('passkeys.title')}
      lead={t('passkeys.lead')}
      action={
        <a className={styles.pill} href={security.passkeyRegistrationUrl} target="_blank" rel="noopener noreferrer">
          <Fingerprint aria-hidden /> {t('passkeys.add')}
        </a>
      }
      note={t('passkeys.addHint')}
    >
      {security.passkeys.length === 0 ? <p className={styles.empty}>{t('passkeys.none')}</p> : null}
      <ul className={styles.list}>
        {security.passkeys.map((p) => (
          <PasskeyRow key={p.credentialId} passkey={p} onRemove={setRemoving} />
        ))}
      </ul>
      <Confirm
        open={removing !== null}
        title={t('passkeys.removeTitle', { name: removing?.label ?? '' })}
        body={t('passkeys.removeBody')}
        confirmLabel={t('common.remove')}
        busy={remove.isPending}
        onConfirm={() => removing && remove.mutate(removing)}
        onClose={() => setRemoving(null)}
      />
    </Card>
  );
}

export function TrustedBrowsersCard({ security }: { security: AccountSecurity }) {
  const t = useAccountT();
  const when = useWhen();
  const { api } = useAccountEnv();
  const qc = useQueryClient();
  const toast = useToast();
  const forget = useMutation({
    mutationFn: (id: string | null) => (id ? api.forgetTrustedBrowser(id) : api.forgetTrustedBrowsers()),
    onSuccess: async () => { await qc.invalidateQueries({ queryKey: settingsKeys.security() }); toast.show(t('trusted.forgotten')); },
    onError: (e) => toast.show(errorText(t, e), 'error')
  });
  const list = security.trustedBrowsers;
  const { visible, toggle } = useShowMore(list, 3);
  return (
    <Card
      id="trusted"
      title={t('trusted.title')}
      lead={t('trusted.lead')}
      action={
        list.length > 1 ? (
          <button type="button" className={styles.pill} onClick={() => forget.mutate(null)} disabled={forget.isPending}>
            {t('trusted.forgetAll')}
          </button>
        ) : undefined
      }
    >
      {list.length === 0 ? <p className={styles.empty}>{t('trusted.none')}</p> : null}
      <ul className={styles.list}>
        {visible.map((b) => (
          <Row
            key={b.id}
            testId="trusted-browser"
            icon={<Browser />}
            title={b.label}
            sub={t('trusted.until', { created: when(b.createdAt), until: when(b.expiresAt) })}
            actions={
              <button type="button" className={styles.link} onClick={() => forget.mutate(b.id)} disabled={forget.isPending}>
                {t('trusted.forget')}
              </button>
            }
          />
        ))}
      </ul>
      {toggle}
    </Card>
  );
}

export function SessionsCard({ security, showDevices }: { security: AccountSecurity; showDevices: boolean }) {
  const t = useAccountT();
  const fmt = useFormatters();
  const { api } = useAccountEnv();
  const qc = useQueryClient();
  const toast = useToast();
  const refresh = () => qc.invalidateQueries({ queryKey: settingsKeys.security() });
  const act = useMutation({
    mutationFn: async (a: { kind: 'session' | 'device' | 'others'; id?: string }) => {
      if (a.kind === 'session') await api.revokeSession(a.id ?? '');
      else if (a.kind === 'device') await api.removeDevice(a.id ?? '');
      else toast.show(t('sessions.othersDone', { n: (await api.revokeOtherSessions()).revoked }));
    },
    onSuccess: () => refresh(),
    onError: (e) => toast.show(errorText(t, e), 'error')
  });
  const others = security.sessions.filter((s) => !s.current).length;
  // This device first, then the most recently active.
  const sorted = [...security.sessions].sort((a, b) => Number(b.current) - Number(a.current) || (b.lastActiveAt ?? '').localeCompare(a.lastActiveAt ?? ''));
  const { visible, toggle } = useShowMore(sorted);
  const iconOf = (name: string) => (/app|iphone|android|ios/i.test(name) ? <DeviceMobile /> : <Desktop />);
  return (
    <Card
      id="sessions"
      title={t('sessions.title')}
      lead={t('sessions.lead')}
      action={
        others > 0 ? (
          <button type="button" className={styles.pill} onClick={() => act.mutate({ kind: 'others' })} disabled={act.isPending}>
            {t('sessions.signOutOthers')}
          </button>
        ) : undefined
      }
    >
      <ul className={styles.list}>
        {visible.map((s) => (
          <Row
            key={s.id}
            testId="session"
            icon={iconOf(s.clientName)}
            title={
              <>
                {s.clientName}
                {s.current ? <span className={styles.chip}>{t('sessions.thisDevice')}</span> : null}
              </>
            }
            sub={t('sessions.lastActive', { date: s.lastActiveAt ? fmt.dateTime(s.lastActiveAt) : t('common.never') })}
            actions={
              s.current ? undefined : (
                <button type="button" className={styles.link} onClick={() => act.mutate({ kind: 'session', id: s.id })}>
                  {t('sessions.signOut')}
                </button>
              )
            }
          />
        ))}
      </ul>
      {toggle}
      {showDevices && security.devices.length > 0 ? (
        <>
          <p className={styles.note} style={{ borderTop: '1px solid var(--at-color-border)' }}>
            {t('sessions.devices')}
          </p>
          <ul className={styles.list}>
            {security.devices.map((d) => (
              <Row
                key={d.id}
                icon={<DeviceMobile />}
                title={d.deviceName}
                sub={t('sessions.lastActive', { date: d.lastSeenAt ? fmt.dateTime(d.lastSeenAt) : t('common.never') })}
                actions={
                  <button type="button" className={styles.link} onClick={() => act.mutate({ kind: 'device', id: d.id })}>
                    {t('sessions.stopPush')}
                  </button>
                }
              />
            ))}
          </ul>
        </>
      ) : null}
    </Card>
  );
}

const OUTCOMES = new Set(['SUCCEEDED', 'FAILED', 'MFA_REQUIRED', 'LOCKED', 'BLOCKED']);
const METHODS = new Set(['PASSWORD', 'MFA_TOTP', 'MFA_SMS', 'MFA_RECOVERY_CODE', 'PASSKEY']);

/** "Signed in · Authenticator code", "Failed attempt · Password". */
export function activityTitle(t: AccountT, e: SignInEvent): string {
  const outcome = OUTCOMES.has(e.outcome) ? t(`activity.outcome.${e.outcome}` as AccountMessageKey) : t('activity.outcome.other');
  return METHODS.has(e.method) ? `${outcome} · ${t(`activity.method.${e.method}` as AccountMessageKey)}` : outcome;
}

export function ActivityCard() {
  const t = useAccountT();
  const fmt = useFormatters();
  const { api } = useAccountEnv();
  const q = useQuery(settingsQueries.activity(api));
  const { visible, toggle } = useShowMore(q.data ?? [], 6);
  return (
    <Card id="activity" title={t('activity.title')} lead={t('activity.lead')}>
      {q.isPending ? <Loading height={96} /> : null}
      {q.isError ? <LoadError onRetry={() => void q.refetch()} /> : null}
      {q.data && q.data.length === 0 ? <p className={styles.empty}>{t('activity.none')}</p> : null}
      <ul className={styles.list}>
        {visible.map((e, i) => {
          const good = e.outcome === 'SUCCEEDED';
          const bad = e.outcome === 'FAILED' || e.outcome === 'LOCKED' || e.outcome === 'BLOCKED';
          return (
            <Row
              key={`${e.at}-${i}`}
              testId="activity"
              icon={good ? <CheckCircle /> : bad ? <WarningCircle /> : <SignIn />}
              title={activityTitle(t, e)}
              sub={[fmt.dateTime(e.at), e.app, e.device, e.ip].filter(Boolean).join(' · ')}
            />
          );
        })}
      </ul>
      {toggle}
    </Card>
  );
}

const PROVIDER_ICONS: Record<string, ReactNode> = { google: <GoogleLogo />, apple: <AppleLogo />, facebook: <FacebookLogo /> };

export function LinkedAccountsCard({ security }: { security: AccountSecurity }) {
  const t = useAccountT();
  const { api } = useAccountEnv();
  const qc = useQueryClient();
  const toast = useToast();
  const unlink = useMutation({
    mutationFn: (provider: string) => api.unlinkAccount(provider),
    onSuccess: () => qc.invalidateQueries({ queryKey: settingsKeys.security() }),
    onError: (e) => toast.show(errorText(t, e), 'error')
  });
  const list = security.linkedAccounts.accounts;
  return (
    <Card id="linked" title={t('linked.title')}>
      {list.length === 0 ? <p className={styles.empty}>{t('linked.none')}</p> : null}
      <ul className={styles.list}>
        {list.map((a) => (
          <Row
            key={a.provider}
            icon={PROVIDER_ICONS[a.provider.toLowerCase()] ?? <LockKey />}
            title={a.provider.charAt(0).toUpperCase() + a.provider.slice(1)}
            sub={a.email ?? undefined}
            actions={
              a.canUnlink ? (
                <button type="button" className={styles.link} onClick={() => unlink.mutate(a.provider)}>
                  {t('linked.unlink')}
                </button>
              ) : undefined
            }
          />
        ))}
      </ul>
    </Card>
  );
}

/**
 * Sign-in & security for every app. Customers also see linked social accounts and push devices; back-office members
 * see the same protections, with staff two-step verification mandatory.
 */
export function SecurityScreen({ audience, heading = true }: { audience: AccountAudience; heading?: boolean }) {
  const t = useAccountT();
  const { api } = useAccountEnv();
  const sec = useQuery(settingsQueries.security(api));
  const profile = useQuery(settingsQueries.profile(api));
  const s = sec.data;
  return (
    <div className={styles.page} data-testid="security-screen">
      {heading ? <PageHead title={t('security.title')} lead={t('security.lead')} /> : null}
      <PasswordCard profile={profile.data} />
      {sec.isPending ? <Loading height={220} /> : null}
      {sec.isError ? (
        <div className={styles.card}>
          <LoadError onRetry={() => void sec.refetch()} />
        </div>
      ) : null}
      {s ? (
        <>
          <TwoStepCard security={s} email={profile.data?.email ?? ''} phone={profile.data?.phone ?? null} />
          <PasskeysCard security={s} />
          <TrustedBrowsersCard security={s} />
          <SessionsCard security={s} showDevices={audience === 'customer'} />
          <ActivityCard />
          {audience === 'customer' ? <LinkedAccountsCard security={s} /> : null}
        </>
      ) : null}
    </div>
  );
}
