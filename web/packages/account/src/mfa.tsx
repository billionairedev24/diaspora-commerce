'use client';

import { type AccountSecurity, isApiError, settingsKeys } from '@afrotransact/api-client';
import { Button, Checkbox, Dialog, QrCodeImage, TextField, useToast } from '@afrotransact/ui';
import { ChatText, CopySimple, DeviceMobile, DownloadSimple, EnvelopeSimple, Key, ShieldCheck, WarningCircle } from '@phosphor-icons/react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useEffect, useReducer, useState } from 'react';

import { useAccountEnv } from './context';
import { cleanCode, enrolmentReducer, groupKey, initialEnrolment, recoveryFile } from './enrolment';
import { useAccountT } from './i18n/t';
import { Card, Confirm, downloadText, errorText, Row, Status, styles, useCopy } from './parts';

/** Recovery codes shown once: grid, copy, download and an "I've saved them" check before closing. */
export function RecoveryCodesPanel({ codes, email, onDone }: { codes: string[]; email: string; onDone: () => void }) {
  const t = useAccountT();
  const [copy, copied] = useCopy();
  const [saved, setSaved] = useState(false);
  return (
    <div className={styles.cardBody} style={{ padding: 0 }} data-testid="recovery-codes">
      <p className={styles.muted}>{t('codes.lead')}</p>
      <ol className={styles.codes} aria-label={t('mfa.recovery')}>
        {codes.map((c) => (
          <li key={c}>{c}</li>
        ))}
      </ol>
      <div className={styles.actions}>
        <Button size="sm" variant="neutral" icon={<CopySimple aria-hidden />} onClick={() => void copy(codes.join('\n'))}>
          {copied ? t('common.copied') : t('codes.copy')}
        </Button>
        <Button size="sm" variant="neutral" icon={<DownloadSimple aria-hidden />} onClick={() => downloadText('afrotransact-recovery-codes.txt', recoveryFile(t('codes.fileHeader', { email }), codes))}>
          {t('codes.download')}
        </Button>
      </div>
      <Checkbox label={t('codes.saved')} checked={saved} onChange={(e) => setSaved(e.target.checked)} />
      <div className={`${styles.actions} ${styles.actionsEnd}`}>
        <Button onClick={onDone} disabled={!saved}>
          {t('codes.done')}
        </Button>
      </div>
    </div>
  );
}

/** "Set up your authenticator app": QR of the otpauth URI, grouped setup key with copy, 6-digit confirmation, then codes. */
export function TotpEnrolmentDialog({ open, email, onClose }: { open: boolean; email: string; onClose: () => void }) {
  const t = useAccountT();
  const { api } = useAccountEnv();
  const qc = useQueryClient();
  const toast = useToast();
  const [state, dispatch] = useReducer(enrolmentReducer, initialEnrolment);
  const [code, setCode] = useState('');
  const [copy, copied] = useCopy();

  useEffect(() => {
    if (!open) return;
    let live = true;
    setCode('');
    dispatch({ type: 'start' });
    api.startTotp().then(
      (setup) => live && dispatch({ type: 'started', setup }),
      (e: unknown) => live && dispatch({ type: 'startFailed', error: errorText(t, e) })
    );
    return () => {
      live = false;
    };
    // Start a fresh enrolment each time the dialog opens.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const finish = async () => {
    await qc.invalidateQueries({ queryKey: settingsKeys.security() });
    toast.show(t('totp.done'));
    dispatch({ type: 'reset' });
    onClose();
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (code.length !== 6) return dispatch({ type: 'rejected', error: t('totp.codeInvalid') });
    dispatch({ type: 'submit' });
    try {
      const rc = await api.confirmTotp(code);
      dispatch({ type: 'confirmed', codes: rc.recoveryCodes });
      if (!rc.recoveryCodes.length) await finish();
    } catch (err) {
      setCode('');
      dispatch({ type: 'rejected', error: errorText(t, err, 'code') });
    }
  };

  const close = () => {
    if (state.step === 'codes') return; // codes must be acknowledged
    dispatch({ type: 'reset' });
    onClose();
  };

  const s = state.setup;
  return (
    <Dialog open={open} onOpenChange={(o) => !o && close()} title={state.step === 'codes' ? t('codes.title') : t('totp.title')} closeLabel={t('common.close')} width={620}>
      {state.step === 'starting' ? <p className={styles.muted} role="status">{t('totp.starting')}</p> : null}
      {state.step === 'failed' ? <p className={styles.error} role="alert">{state.error}</p> : null}
      {s && (state.step === 'scan' || state.step === 'verifying') ? (
        <div className={styles.enrol} data-testid="totp-enrolment">
          <div className={styles.qrFrame}>
            <QrCodeImage value={s.otpauthUri} label={t('totp.qrLabel')} size={184} />
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--at-space-4)', minWidth: 0 }}>
            <ol className={styles.steps}>
              <li>{t('totp.step1')}</li>
              <li>{t('totp.step2')}</li>
              <li>{t('totp.step3')}</li>
            </ol>
            <details className={styles.details}>
              <summary>{t('totp.cantScan')}</summary>
              <div className={styles.keyBox}>
                <span className={styles.muted}>{t('totp.manual')}</span>
                <div className={styles.keyRow}>
                  <code className={styles.key} aria-label={t('totp.keyLabel')} data-testid="totp-secret" data-secret={s.secret}>
                    {groupKey(s.secret)}
                  </code>
                  <Button size="sm" variant="neutral" icon={<CopySimple aria-hidden />} onClick={() => void copy(s.secret)}>
                    {copied ? t('common.copied') : t('totp.copyKey')}
                  </Button>
                </div>
                <span className={styles.rowSub}>{t('totp.account', { email })}</span>
              </div>
            </details>
            <form className={styles.actions} style={{ alignItems: 'flex-end' }} onSubmit={(e) => void submit(e)} noValidate>
              <TextField
                className={styles.codeInput}
                label={t('totp.code')}
                help={t('totp.codeHelp')}
                value={code}
                onChange={(e) => setCode(cleanCode(e.target.value))}
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                error={state.error}
                // eslint-disable-next-line jsx-a11y/no-autofocus -- the code is the only thing to type in this dialog
                autoFocus
              />
              <Button type="submit" loading={state.step === 'verifying'}>
                {t('totp.verify')}
              </Button>
            </form>
          </div>
        </div>
      ) : null}
      {state.step === 'codes' ? <RecoveryCodesPanel codes={state.codes} email={email} onDone={() => void finish()} /> : null}
    </Dialog>
  );
}

/** Text-message codes: send a code to the account phone, confirm it, then show recovery codes the first time. */
export function SmsEnrolmentDialog({ open, phone, email, onClose }: { open: boolean; phone: string | null; email: string; onClose: () => void }) {
  const t = useAccountT();
  const { api, Link, profileHref } = useAccountEnv();
  const qc = useQueryClient();
  const toast = useToast();
  const [sent, setSent] = useState(false);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  const masked = phone ? `•••• ${phone.replace(/\D/g, '').slice(-4)}` : '';
  const reset = () => {
    setSent(false);
    setCode('');
    setError(null);
    setCodes(null);
  };
  const finish = async () => {
    await qc.invalidateQueries({ queryKey: settingsKeys.security() });
    toast.show(t('sms.done'));
    reset();
    onClose();
  };
  const send = useMutation({ mutationFn: () => api.startSmsMfa(), onSuccess: () => { setSent(true); setError(null); }, onError: (e) => setError(errorText(t, e)) });
  const confirm = useMutation({
    mutationFn: () => api.confirmSmsMfa(code),
    onSuccess: async (rc) => {
      if (rc.recoveryCodes.length) setCodes(rc.recoveryCodes);
      else await finish();
    },
    onError: (e) => { setCode(''); setError(errorText(t, e, 'code')); }
  });
  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o && !codes) { reset(); onClose(); } }} title={codes ? t('codes.title') : t('sms.title')} closeLabel={t('common.close')} width={520}>
      {codes ? (
        <RecoveryCodesPanel codes={codes} email={email} onDone={() => void finish()} />
      ) : !phone ? (
        <div className={styles.cardBody} style={{ padding: 0 }}>
          <p className={styles.muted}>{t('sms.noPhone')}</p>
          <div>
            <Link href={profileHref} className={styles.pill}>
              {t('sms.goProfile')}
            </Link>
          </div>
        </div>
      ) : !sent ? (
        <div className={styles.cardBody} style={{ padding: 0 }}>
          <p className={styles.muted}>{t('sms.intro', { phone: masked })}</p>
          {error ? <p className={styles.error} role="alert">{error}</p> : null}
          <div className={`${styles.actions} ${styles.actionsEnd}`}>
            <Button onClick={() => send.mutate()} loading={send.isPending} icon={<ChatText aria-hidden />}>
              {t('sms.send')}
            </Button>
          </div>
        </div>
      ) : (
        <form
          className={styles.cardBody}
          style={{ padding: 0 }}
          onSubmit={(e) => {
            e.preventDefault();
            if (code.length !== 6) return setError(t('totp.codeInvalid'));
            confirm.mutate();
          }}
          noValidate
        >
          <p className={styles.muted} role="status">{t('sms.sent', { phone: masked })}</p>
          <TextField className={styles.codeInput} label={t('totp.code')} value={code} onChange={(e) => setCode(cleanCode(e.target.value))} inputMode="numeric" autoComplete="one-time-code" maxLength={6} error={error} />
          <div className={styles.actions}>
            <Button type="submit" loading={confirm.isPending}>
              {t('totp.verify')}
            </Button>
            <button type="button" className={styles.link} onClick={() => send.mutate()} disabled={send.isPending}>
              {t('sms.resend')}
            </button>
          </div>
        </form>
      )}
    </Dialog>
  );
}

/** Email codes: we email a first code to the verified account email, confirm it, then show recovery codes the first time. */
export function EmailCodesDialog({ open, email, onClose }: { open: boolean; email: string; onClose: () => void }) {
  const t = useAccountT();
  const { api } = useAccountEnv();
  const qc = useQueryClient();
  const toast = useToast();
  const [sent, setSent] = useState(false);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  const reset = () => {
    setSent(false);
    setCode('');
    setError(null);
    setCodes(null);
  };
  const finish = async () => {
    await qc.invalidateQueries({ queryKey: settingsKeys.security() });
    toast.show(t('email.done'));
    reset();
    onClose();
  };
  const send = useMutation({ mutationFn: () => api.startEmailMfa(), onSuccess: () => { setSent(true); setError(null); }, onError: (e) => setError(errorText(t, e)) });
  const confirm = useMutation({
    mutationFn: () => api.confirmEmailMfa(code),
    onSuccess: async (rc) => {
      if (rc.recoveryCodes.length) setCodes(rc.recoveryCodes);
      else await finish();
    },
    onError: (e) => { setCode(''); setError(errorText(t, e, 'code')); }
  });
  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o && !codes) { reset(); onClose(); } }} title={codes ? t('codes.title') : t('email.title')} closeLabel={t('common.close')} width={520}>
      {codes ? (
        <RecoveryCodesPanel codes={codes} email={email} onDone={() => void finish()} />
      ) : !sent ? (
        <div className={styles.cardBody} style={{ padding: 0 }}>
          <p className={styles.muted}>{t('email.intro', { email })}</p>
          {error ? <p className={styles.error} role="alert">{error}</p> : null}
          <div className={`${styles.actions} ${styles.actionsEnd}`}>
            <Button onClick={() => send.mutate()} loading={send.isPending} icon={<EnvelopeSimple aria-hidden />}>
              {t('email.send')}
            </Button>
          </div>
        </div>
      ) : (
        <form
          className={styles.cardBody}
          style={{ padding: 0 }}
          onSubmit={(e) => {
            e.preventDefault();
            if (code.length !== 6) return setError(t('totp.codeInvalid'));
            confirm.mutate();
          }}
          noValidate
        >
          <p className={styles.muted} role="status">{t('email.sent', { email })}</p>
          <TextField className={styles.codeInput} label={t('totp.code')} value={code} onChange={(e) => setCode(cleanCode(e.target.value))} inputMode="numeric" autoComplete="one-time-code" maxLength={6} error={error} />
          <div className={styles.actions}>
            <Button type="submit" loading={confirm.isPending}>
              {t('totp.verify')}
            </Button>
            <button type="button" className={styles.link} onClick={() => send.mutate()} disabled={send.isPending}>
              {t('email.resend')}
            </button>
          </div>
        </form>
      )}
    </Dialog>
  );
}

/** Turning a factor off is a sensitive change: the account password is asked again. */
export function PasswordConfirm({ open, title, body, confirmLabel, busy, error, onConfirm, onClose }: {
  open: boolean;
  title: string;
  body: string;
  confirmLabel: string;
  busy?: boolean;
  error?: string | null;
  onConfirm: (password: string) => void;
  onClose: () => void;
}) {
  const t = useAccountT();
  const [password, setPassword] = useState('');
  useEffect(() => {
    if (!open) setPassword('');
  }, [open]);
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()} title={title} closeLabel={t('common.close')} width={440}>
      <form
        className={styles.cardBody}
        style={{ padding: 0 }}
        onSubmit={(e) => {
          e.preventDefault();
          if (password) onConfirm(password);
        }}
        noValidate
      >
        <p className={styles.muted}>{body}</p>
        <TextField label={t('mfa.password')} type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} error={error ?? null} />
        <div className={`${styles.actions} ${styles.actionsEnd}`}>
          <Button type="button" variant="neutral" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" variant="dark" loading={busy} disabled={!password}>
            {confirmLabel}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

/** The gentle reminder: nothing is set up yet and it is optional. Dismissing it hides it for a while (identity decides how long). */
export function SecureAccountPrompt({ onChoose, onLater, busy }: { onChoose: (method: 'totp' | 'email') => void; onLater: () => void; busy?: boolean }) {
  const t = useAccountT();
  return (
    <div className={styles.note} role="region" aria-label={t('mfa.suggestTitle')} data-testid="mfa-setup-prompt">
      <ShieldCheck aria-hidden /> <strong>{t('mfa.suggestTitle')}</strong> {t('mfa.suggestBody')}
      <div className={styles.actions}>
        <button type="button" className={styles.pill} onClick={() => onChoose('totp')}>{t('mfa.suggestAuth')}</button>
        <button type="button" className={styles.pill} onClick={() => onChoose('email')}>{t('mfa.suggestEmail')}</button>
        <button type="button" className={styles.link} onClick={onLater} disabled={busy}>{t('mfa.suggestLater')}</button>
      </div>
    </div>
  );
}

/** Two-step verification: authenticator app, email codes and recovery codes (text messages only while switched on). Where it is mandatory (staff, portal settings) a person can switch methods but never turn the last one off. */
export function TwoStepCard({ security, email, phone }: { security: AccountSecurity; email: string; phone: string | null }) {
  const t = useAccountT();
  const { api } = useAccountEnv();
  const qc = useQueryClient();
  const toast = useToast();
  const [dialog, setDialog] = useState<'totp' | 'email' | 'sms' | null>(null);
  const [confirmOff, setConfirmOff] = useState<'totp' | 'email' | 'sms' | null>(null);
  const [offError, setOffError] = useState<string | null>(null);
  const [confirmNew, setConfirmNew] = useState(false);
  const [newCodes, setNewCodes] = useState<string[] | null>(null);
  const m = security.mfa;
  const mandatory = m.required && !m.mayDisable;
  const factorCount = Number(m.totp) + Number(m.emailOtp) + Number(m.sms);
  const refresh = () => qc.invalidateQueries({ queryKey: settingsKeys.security() });
  const off = useMutation({
    mutationFn: ({ which, password }: { which: 'totp' | 'email' | 'sms'; password: string }) =>
      which === 'totp' ? api.disableTotp(password) : which === 'email' ? api.disableEmailMfa(password) : api.disableSmsMfa(password),
    onSuccess: async () => { setConfirmOff(null); setOffError(null); await refresh(); toast.show(t('mfa.turnedOff')); },
    // A wrong password stays in the dialog so it can be retried; anything else is a toast.
    onError: (e) => {
      const field = errorText(t, e, 'currentPassword');
      if (isApiError(e) && e.fieldErrors.currentPassword) setOffError(field);
      else { setConfirmOff(null); toast.show(errorText(t, e), 'error'); }
    }
  });
  const later = useMutation({
    mutationFn: () => api.dismissMfaPrompt(),
    onSuccess: async () => { await refresh(); }
  });
  const regenerate = useMutation({
    mutationFn: () => api.regenerateRecoveryCodes(),
    onSuccess: async (rc) => { setConfirmNew(false); setNewCodes(rc.recoveryCodes); await refresh(); },
    onError: (e) => { setConfirmNew(false); toast.show(errorText(t, e), 'error'); }
  });
  const any = m.totp || m.emailOtp || m.sms;
  const methodName = (which: 'totp' | 'email' | 'sms') => (which === 'sms' ? t('mfa.sms') : which === 'email' ? t('mfa.email') : t('mfa.app'));
  const badge = mandatory ? <Status tone="required">{t('mfa.badgeRequired')}</Status> : any ? <Status tone="on">{t('mfa.badgeOn')}</Status> : <Status tone="off">{t('mfa.badgeOff')}</Status>;
  const offButton = (which: 'totp' | 'email' | 'sms', removable: boolean) => (
    <button type="button" className={styles.link} onClick={() => { setOffError(null); setConfirmOff(which); }} disabled={!removable} title={removable ? undefined : t('mfa.lockedHint')}>
      {t('mfa.turnOff')}
    </button>
  );
  return (
    <Card
      id="two-step"
      testId="two-step"
      title={t('mfa.title')}
      lead={t('mfa.lead')}
      badge={badge}
      note={
        mandatory ? (
          <>
            <WarningCircle aria-hidden /> {t('mfa.staffNote')} {!any ? t('mfa.staffFallback') : factorCount < 2 ? t('mfa.staffNoteSingle') : null}
          </>
        ) : undefined
      }
    >
      {m.setupSuggested && !any ? <SecureAccountPrompt onChoose={(which) => setDialog(which)} onLater={() => later.mutate()} busy={later.isPending} /> : null}
      <ul className={styles.list}>
        <Row
          testId="mfa-totp"
          icon={<DeviceMobile />}
          title={<>{t('mfa.app')} {m.totp ? <Status tone="on">{t('common.on')}</Status> : null}</>}
          sub={m.totp ? t('mfa.appOn') : t('mfa.appSub')}
          actions={m.totp ? offButton('totp', m.mayRemoveTotp) : <button type="button" className={styles.pill} onClick={() => setDialog('totp')}>{t('mfa.setUp')}</button>}
        />
        <Row
          testId="mfa-email"
          icon={<EnvelopeSimple />}
          title={<>{t('mfa.email')} {m.emailOtp ? <Status tone="on">{t('common.on')}</Status> : null}</>}
          sub={m.emailOtp ? t('mfa.emailOn', { email }) : t('mfa.emailSub', { email })}
          actions={m.emailOtp ? offButton('email', m.mayRemoveEmailOtp) : <button type="button" className={styles.pill} onClick={() => setDialog('email')}>{t('mfa.setUp')}</button>}
        />
        {m.smsAvailable ? (
          <Row
            testId="mfa-sms"
            icon={<ChatText />}
            title={<>{t('mfa.sms')} {m.sms ? <Status tone="on">{t('common.on')}</Status> : null}</>}
            sub={m.sms ? t('mfa.smsOn', { phone: m.smsPhone ?? '' }) : t('mfa.smsSub')}
            actions={m.sms ? offButton('sms', m.mayRemoveSms) : <button type="button" className={styles.pill} onClick={() => setDialog('sms')}>{t('mfa.setUp')}</button>}
          />
        ) : null}
        <Row
          icon={<Key />}
          title={t('mfa.recovery')}
          sub={m.recoveryCodesLeft > 0 ? t('mfa.recoveryLeft', { n: m.recoveryCodesLeft }) : t('mfa.recoveryNone')}
          actions={any || mandatory ? <button type="button" className={styles.link} onClick={() => setConfirmNew(true)}>{t('mfa.newCodes')}</button> : undefined}
        />
      </ul>
      <TotpEnrolmentDialog open={dialog === 'totp'} email={email} onClose={() => setDialog(null)} />
      <EmailCodesDialog open={dialog === 'email'} email={email} onClose={() => setDialog(null)} />
      <SmsEnrolmentDialog open={dialog === 'sms'} phone={phone} email={email} onClose={() => setDialog(null)} />
      <PasswordConfirm
        open={confirmOff !== null}
        title={t('mfa.offConfirmTitle', { method: confirmOff ? methodName(confirmOff) : '' })}
        body={factorCount > 1 ? t('mfa.offConfirmSwitch') : t('mfa.offConfirmBody')}
        confirmLabel={t('mfa.turnOff')}
        busy={off.isPending}
        error={offError}
        onConfirm={(password) => confirmOff && off.mutate({ which: confirmOff, password })}
        onClose={() => setConfirmOff(null)}
      />
      <Confirm open={confirmNew} title={t('codes.newTitle')} body={t('codes.newBody')} confirmLabel={t('codes.newConfirm')} busy={regenerate.isPending} onConfirm={() => regenerate.mutate()} onClose={() => setConfirmNew(false)} />
      <Dialog open={newCodes !== null} onOpenChange={() => undefined} title={t('codes.title')} closeLabel={t('common.close')} width={560}>
        {newCodes ? <RecoveryCodesPanel codes={newCodes} email={email} onDone={() => setNewCodes(null)} /> : null}
      </Dialog>
    </Card>
  );
}
