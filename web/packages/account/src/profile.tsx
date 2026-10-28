'use client';

import { type AccountMembership, type AccountProfile, settingsKeys, settingsQueries } from '@afrotransact/api-client';
import { Button, PhoneNumberField, TextField, useToast } from '@afrotransact/ui';
import { Buildings, CheckCircle, IdentificationBadge, LockSimple, WarningCircle } from '@phosphor-icons/react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';

import { useAccountEnv } from './context';
import { cleanCode } from './enrolment';
import { type AccountMessageKey, useAccountT } from './i18n/t';
import { Card, Confirm, errorText, LoadError, Loading, PageHead, Row, Status, styles } from './parts';

function DetailsCard({ profile }: { profile: AccountProfile }) {
  const t = useAccountT();
  const { api } = useAccountEnv();
  const qc = useQueryClient();
  const toast = useToast();
  const backOffice = profile.kind !== 'customer';
  const [first, setFirst] = useState(profile.firstName);
  const [last, setLast] = useState(profile.lastName);
  const [phone, setPhone] = useState(profile.phone ?? '');
  const [title, setTitle] = useState(profile.jobTitle ?? '');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [verifying, setVerifying] = useState(false);
  const [code, setCode] = useState('');
  const save = useMutation({
    mutationFn: () =>
      api.updateAccountProfile({
        firstName: first.trim(),
        lastName: last.trim(),
        ...(phone.trim() && phone.trim() !== (profile.phone ?? '') ? { phone: phone.trim() } : {}),
        ...(backOffice ? { jobTitle: title.trim() || null } : {})
      }),
    onSuccess: (p) => {
      qc.setQueryData(settingsKeys.profile(), p);
      setErrors({});
      toast.show(t('profile.saved'));
    },
    onError: (e) => {
      const fields: Record<string, string> = {};
      for (const f of ['firstName', 'lastName', 'phone', 'jobTitle']) {
        const m = errorText(t, e, f);
        if (m !== errorText(t, e)) fields[f] = m;
      }
      setErrors(Object.keys(fields).length ? fields : { _: errorText(t, e) });
    }
  });
  const start = useMutation({ mutationFn: () => api.startPhoneVerification(), onSuccess: () => { setVerifying(true); setErrors({}); }, onError: (e) => setErrors({ phone: errorText(t, e) }) });
  const confirm = useMutation({
    mutationFn: () => api.confirmPhoneVerification(code),
    onSuccess: async () => { setVerifying(false); setCode(''); await qc.invalidateQueries({ queryKey: settingsKeys.profile() }); toast.show(t('profile.phoneDone')); },
    onError: (e) => setErrors({ code: errorText(t, e, 'code') })
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const errs: Record<string, string> = {};
    if (!first.trim()) errs.firstName = t('common.required');
    if (!last.trim()) errs.lastName = t('common.required');
    setErrors(errs);
    if (!Object.keys(errs).length) save.mutate();
  };
  return (
    <Card id="details" testId="profile-details" title={t('profile.details')}>
      <div className={styles.identity}>
        <span className={styles.avatar} aria-hidden>
          {profile.initials}
        </span>
        <span className={styles.rowText}>
          <strong>
            {profile.firstName} {profile.lastName}
          </strong>
          <span className={styles.rowSub}>{profile.jobTitle ?? profile.email}</span>
          <span className={styles.rowSub}>{t('profile.avatar')}</span>
        </span>
      </div>
      <form className={styles.form} onSubmit={submit} noValidate>
        <div className={styles.grid2}>
          <TextField label={t('profile.firstName')} autoComplete="given-name" value={first} maxLength={80} onChange={(e) => setFirst(e.target.value)} error={errors.firstName} />
          <TextField label={t('profile.lastName')} autoComplete="family-name" value={last} maxLength={80} onChange={(e) => setLast(e.target.value)} error={errors.lastName} />
        </div>
        <div className={styles.readonly}>
          <span className={styles.readonlyLabel}>{backOffice ? t('profile.workEmail') : t('profile.email')}</span>
          <span className={styles.readonlyValue} data-testid="profile-email">
            <LockSimple aria-hidden /> {profile.email}
          </span>
          <span className={styles.rowSub}>{profile.emailManaged ? t('profile.emailManaged') : t('profile.emailFixed')}</span>
        </div>
        <div className={styles.grid2}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--at-space-2)' }}>
            <PhoneNumberField label={t('profile.phone')} value={phone} onChange={setPhone} error={errors.phone} help={t('profile.phoneHelp')} />
            {profile.phone && phone.trim() === profile.phone ? (
              <span className={styles.actions}>
                {profile.phoneVerified ? (
                  <Status tone="on">
                    <CheckCircle aria-hidden /> {t('profile.phoneVerified')}
                  </Status>
                ) : (
                  <>
                    <Status tone="required">
                      <WarningCircle aria-hidden /> {t('profile.phoneUnverified')}
                    </Status>
                    {!verifying ? (
                      <button type="button" className={styles.link} onClick={() => start.mutate()} disabled={start.isPending}>
                        {t('profile.verifyPhone')}
                      </button>
                    ) : null}
                  </>
                )}
              </span>
            ) : null}
          </div>
          {backOffice ? (
            <TextField label={t('profile.jobTitle')} autoComplete="organization-title" value={title} maxLength={80} onChange={(e) => setTitle(e.target.value)} error={errors.jobTitle} help={t('profile.jobTitleHelp')} />
          ) : null}
        </div>
        {verifying ? (
          <div className={styles.actions} style={{ alignItems: 'flex-end' }}>
            <TextField className={styles.codeInput} label={t('profile.phoneCode')} inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(e) => setCode(cleanCode(e.target.value))} error={errors.code} />
            <Button size="sm" onClick={() => confirm.mutate()} loading={confirm.isPending} disabled={code.length !== 6}>
              {t('profile.phoneConfirm')}
            </Button>
          </div>
        ) : null}
        {errors._ ? <p className={styles.error} role="alert">{errors._}</p> : null}
        <div className={`${styles.actions} ${styles.actionsEnd}`}>
          <Button type="submit" loading={save.isPending}>
            {t('common.save')}
          </Button>
        </div>
      </form>
    </Card>
  );
}

/** Seller Center memberships: read-only roles, switch the active organisation, leave (non-owners). */
function OrganisationsCard({ profile, onSwitchOrg, switching }: { profile: AccountProfile; onSwitchOrg?: (orgId: string) => void; switching?: boolean }) {
  const t = useAccountT();
  const { api } = useAccountEnv();
  const qc = useQueryClient();
  const toast = useToast();
  const [leaving, setLeaving] = useState<AccountMembership | null>(null);
  const leave = useMutation({
    mutationFn: (m: AccountMembership) => api.leaveOrganisation(m.orgId),
    onSuccess: async (_v, m) => { setLeaving(null); await qc.invalidateQueries(); toast.show(t('orgs.left', { name: m.name })); },
    onError: (e) => { setLeaving(null); toast.show(errorText(t, e), 'error'); }
  });
  return (
    <Card id="organisations" testId="organisations" title={t('orgs.title')} lead={t('orgs.lead')}>
      {profile.memberships.length === 0 ? <p className={styles.empty}>{t('orgs.none')}</p> : null}
      <ul className={styles.list}>
        {profile.memberships.map((m) => (
          <Row
            key={m.orgId}
            testId="membership"
            icon={<Buildings />}
            title={
              <>
                {m.name}
                {m.active ? <Status tone="on">{t('orgs.active')}</Status> : null}
              </>
            }
            sub={`${t(`orgs.kind.${m.kind}` as AccountMessageKey)} · ${m.roleLabel}${m.owner ? ` · ${t('orgs.ownerNote')}` : ''}`}
            actions={
              <>
                {!m.active && onSwitchOrg ? (
                  <button type="button" className={styles.link} onClick={() => onSwitchOrg(m.orgId)} disabled={switching}>
                    {t('orgs.switch')}
                  </button>
                ) : null}
                {!m.owner ? (
                  <button type="button" className={`${styles.link} ${styles.danger}`} onClick={() => setLeaving(m)}>
                    {t('orgs.leave')}
                  </button>
                ) : null}
              </>
            }
          />
        ))}
      </ul>
      <Confirm
        open={leaving !== null}
        title={t('orgs.leaveTitle', { name: leaving?.name ?? '' })}
        body={t('orgs.leaveBody', { name: leaving?.name ?? '' })}
        confirmLabel={t('orgs.leave')}
        busy={leave.isPending}
        onConfirm={() => leaving && leave.mutate(leaving)}
        onClose={() => setLeaving(null)}
      />
    </Card>
  );
}

function RolesCard({ profile }: { profile: AccountProfile }) {
  const t = useAccountT();
  return (
    <Card id="roles" testId="staff-roles" title={t('roles.title')} lead={t('roles.lead')}>
      {profile.roles.length === 0 ? <p className={styles.empty}>{t('roles.none')}</p> : null}
      <ul className={styles.list}>
        {profile.roles.map((r) => (
          <Row key={r.key} icon={<IdentificationBadge />} title={r.label} sub={r.key} />
        ))}
      </ul>
    </Card>
  );
}

/** Profile for every app: details for all; organisations (Seller Center) or roles (staff) read-only. */
export function ProfileScreen({ onSwitchOrg, switching, heading = true }: { onSwitchOrg?: (orgId: string) => void; switching?: boolean; heading?: boolean }) {
  const t = useAccountT();
  const { api } = useAccountEnv();
  const q = useQuery(settingsQueries.profile(api));
  const p = q.data;
  return (
    <div className={styles.page} data-testid="profile-screen">
      {heading ? <PageHead title={t('profile.title')} lead={t('profile.lead')} /> : null}
      {q.isPending ? <Loading height={260} /> : null}
      {q.isError ? (
        <div className={styles.card}>
          <LoadError onRetry={() => void q.refetch()} />
        </div>
      ) : null}
      {p ? (
        <>
          <DetailsCard key={`${p.firstName}|${p.lastName}|${p.phone}|${p.jobTitle}`} profile={p} />
          {p.kind === 'seller' ? <OrganisationsCard profile={p} onSwitchOrg={onSwitchOrg} switching={switching} /> : null}
          {p.kind === 'staff' ? <RolesCard profile={p} /> : null}
        </>
      ) : null}
    </div>
  );
}
