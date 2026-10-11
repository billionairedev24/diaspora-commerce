'use client';

import { type AccountSecurity, settingsKeys } from '@afrotransact/api-client';
import { Button, Checkbox, Dialog, QrCodeImage, TextField, useToast } from '@afrotransact/ui';
import { ChatText, CopySimple, DeviceMobile, DownloadSimple, Key, WarningCircle } from '@phosphor-icons/react';
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

/** Two-step verification: authenticator app, text message and recovery codes. Staff can switch methods but never turn it off. */
export function TwoStepCard({ security, email, phone }: { security: AccountSecurity; email: string; phone: string | null }) {
  const t = useAccountT();
  const { api } = useAccountEnv();
  const qc = useQueryClient();
  const toast = useToast();
  const [dialog, setDialog] = useState<'totp' | 'sms' | null>(null);
  const [confirmOff, setConfirmOff] = useState<'totp' | 'sms' | null>(null);
  const [confirmNew, setConfirmNew] = useState(false);
  const [newCodes, setNewCodes] = useState<string[] | null>(null);
  const m = security.mfa;
  const mandatory = m.required && !m.mayDisable;
  const refresh = () => qc.invalidateQueries({ queryKey: settingsKeys.security() });
  const off = useMutation({
    mutationFn: (which: 'totp' | 'sms') => (which === 'totp' ? api.disableTotp() : api.disableSmsMfa()),
    onSuccess: async () => { setConfirmOff(null); await refresh(); toast.show(t('mfa.turnedOff')); },
    onError: (e) => { setConfirmOff(null); toast.show(errorText(t, e), 'error'); }
  });
  const regenerate = useMutation({
    mutationFn: () => api.regenerateRecoveryCodes(),
    onSuccess: async (rc) => { setConfirmNew(false); setNewCodes(rc.recoveryCodes); await refresh(); },
    onError: (e) => { setConfirmNew(false); toast.show(errorText(t, e), 'error'); }
  });
  const any = m.totp || m.sms;
  const badge = mandatory ? <Status tone="required">{t('mfa.badgeRequired')}</Status> : any ? <Status tone="on">{t('mfa.badgeOn')}</Status> : <Status tone="off">{t('mfa.badgeOff')}</Status>;
  const offButton = (which: 'totp' | 'sms', removable: boolean) => (
    <button type="button" className={styles.link} onClick={() => setConfirmOff(which)} disabled={!removable} title={removable ? undefined : t('mfa.lockedHint')}>
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
            <WarningCircle aria-hidden /> {t('mfa.staffNote')} {!any ? t('mfa.staffFallback') : !(m.totp && m.sms) ? t('mfa.staffNoteSingle') : null}
          </>
        ) : undefined
      }
    >
      <ul className={styles.list}>
        <Row
          testId="mfa-totp"
          icon={<DeviceMobile />}
          title={<>{t('mfa.app')} {m.totp ? <Status tone="on">{t('common.on')}</Status> : null}</>}
          sub={m.totp ? t('mfa.appOn') : t('mfa.appSub')}
          actions={m.totp ? offButton('totp', m.mayRemoveTotp) : <button type="button" className={styles.pill} onClick={() => setDialog('totp')}>{t('mfa.setUp')}</button>}
        />
        <Row
          testId="mfa-sms"
          icon={<ChatText />}
          title={<>{t('mfa.sms')} {m.sms ? <Status tone="on">{t('common.on')}</Status> : null}</>}
          sub={m.sms ? t('mfa.smsOn', { phone: m.smsPhone ?? '' }) : t('mfa.smsSub')}
          actions={m.sms ? offButton('sms', m.mayRemoveSms) : <button type="button" className={styles.pill} onClick={() => setDialog('sms')}>{t('mfa.setUp')}</button>}
        />
        <Row
          icon={<Key />}
          title={t('mfa.recovery')}
          sub={m.recoveryCodesLeft > 0 ? t('mfa.recoveryLeft', { n: m.recoveryCodesLeft }) : t('mfa.recoveryNone')}
          actions={any || mandatory ? <button type="button" className={styles.link} onClick={() => setConfirmNew(true)}>{t('mfa.newCodes')}</button> : undefined}
        />
      </ul>
      <TotpEnrolmentDialog open={dialog === 'totp'} email={email} onClose={() => setDialog(null)} />
      <SmsEnrolmentDialog open={dialog === 'sms'} phone={phone} email={email} onClose={() => setDialog(null)} />
      <Confirm
        open={confirmOff !== null}
        title={t('mfa.offConfirmTitle', { method: confirmOff === 'sms' ? t('mfa.sms') : t('mfa.app') })}
        body={(confirmOff === 'totp' ? m.sms : m.totp) ? t('mfa.offConfirmSwitch') : t('mfa.offConfirmBody')}
        confirmLabel={t('mfa.turnOff')}
        busy={off.isPending}
        onConfirm={() => confirmOff && off.mutate(confirmOff)}
        onClose={() => setConfirmOff(null)}
      />
      <Confirm open={confirmNew} title={t('codes.newTitle')} body={t('codes.newBody')} confirmLabel={t('codes.newConfirm')} busy={regenerate.isPending} onConfirm={() => regenerate.mutate()} onClose={() => setConfirmNew(false)} />
      <Dialog open={newCodes !== null} onOpenChange={() => undefined} title={t('codes.title')} closeLabel={t('common.close')} width={560}>
        {newCodes ? <RecoveryCodesPanel codes={newCodes} email={email} onDone={() => setNewCodes(null)} /> : null}
      </Dialog>
    </Card>
  );
}
