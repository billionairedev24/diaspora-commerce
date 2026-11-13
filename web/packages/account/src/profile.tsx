'use client';

import { type AccountMembership, type AccountProfile, settingsKeys, settingsQueries } from '@afrotransact/api-client';
import { Button, fieldStyles, PhoneNumberField, useToast } from '@afrotransact/ui';
import { Buildings, CheckCircle, IdentificationBadge, LockSimple, WarningCircle } from '@phosphor-icons/react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';

import { useAccountEnv } from './context';
import { cleanCode } from './enrolment';
import { type AccountMessageKey, useAccountT } from './i18n/t';
import { Block, Confirm, errorText, LoadError, Loading, PageHead, Row, Status, styles } from './parts';
import { SaveBar, SettingRow } from './settings';

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
  const [saved, setSaved] = useState(false);
  const dirty = first.trim() !== profile.firstName || last.trim() !== profile.lastName || phone.trim() !== (profile.phone ?? '') || (backOffice && title.trim() !== (profile.jobTitle ?? ''));
  /** Withdraws a field's message once it is edited: an error is only shown for what was submitted, never while typing. */
  const clear = (field: string) => setErrors((prev) => Object.fromEntries(Object.entries(prev).filter(([k]) => k !== field)));
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
      setSaved(true);
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
    <Block id="details" testId="profile-details" title={t('profile.details')}>
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
      <form className={styles.settings} onSubmit={submit} noValidate>
        <SettingRow label={t('profile.firstName')} error={errors.firstName}>
          {(c) => <input id={c.id} className={fieldStyles.control} autoComplete="given-name" value={first} maxLength={80} aria-describedby={c['aria-describedby']} aria-invalid={c['aria-invalid']} onChange={(e) => { setFirst(e.target.value); clear('firstName'); }} />}
        </SettingRow>
        <SettingRow label={t('profile.lastName')} error={errors.lastName}>
          {(c) => <input id={c.id} className={fieldStyles.control} autoComplete="family-name" value={last} maxLength={80} aria-describedby={c['aria-describedby']} aria-invalid={c['aria-invalid']} onChange={(e) => { setLast(e.target.value); clear('lastName'); }} />}
        </SettingRow>
        <SettingRow label={backOffice ? t('profile.workEmail') : t('profile.email')} hint={profile.emailManaged ? t('profile.emailManaged') : t('profile.emailFixed')}>
          <span className={styles.readonlyValue} data-testid="profile-email">
            <LockSimple aria-hidden /> {profile.email}
          </span>
        </SettingRow>
        <SettingRow label={t('profile.phone')} hint={t('profile.phoneHelp')}>
          <PhoneNumberField label={t('profile.phone')} value={phone} onChange={(v) => { setPhone(v); clear('phone'); }} error={errors.phone} />
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
        </SettingRow>
        {verifying ? (
          <SettingRow label={t('profile.phoneCode')} error={errors.code}>
            {(c) => (
              <>
                <input id={c.id} className={fieldStyles.control} inputMode="numeric" autoComplete="one-time-code" value={code} aria-describedby={c['aria-describedby']} aria-invalid={c['aria-invalid']} onChange={(e) => { setCode(cleanCode(e.target.value)); clear('code'); }} />
                <Button size="sm" onClick={() => confirm.mutate()} loading={confirm.isPending} disabled={code.length !== 6}>
                  {t('profile.phoneConfirm')}
                </Button>
              </>
            )}
          </SettingRow>
        ) : null}
        {backOffice ? (
          <SettingRow label={t('profile.jobTitle')} hint={t('profile.jobTitleHelp')} error={errors.jobTitle}>
            {(c) => <input id={c.id} className={fieldStyles.control} autoComplete="organization-title" value={title} maxLength={80} aria-describedby={c['aria-describedby']} aria-invalid={c['aria-invalid']} onChange={(e) => { setTitle(e.target.value); clear('jobTitle'); }} />}
          </SettingRow>
        ) : null}
        <SaveBar dirty={dirty} saving={save.isPending} saved={saved} error={errors._} />
      </form>
    </Block>
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
    <Block id="organisations" testId="organisations" title={t('orgs.title')} lead={t('orgs.lead')}>
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
    </Block>
  );
}

function RolesCard({ profile }: { profile: AccountProfile }) {
  const t = useAccountT();
  return (
    <Block id="roles" testId="staff-roles" title={t('roles.title')} lead={t('roles.lead')}>
      {profile.roles.length === 0 ? <p className={styles.empty}>{t('roles.none')}</p> : null}
      <ul className={styles.list}>
        {profile.roles.map((r) => (
          <Row key={r.key} icon={<IdentificationBadge />} title={r.label} sub={r.key} />
        ))}
      </ul>
    </Block>
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
        <LoadError onRetry={() => void q.refetch()} />
      ) : null}
      {p ? (
        <>
          <DetailsCard profile={p} />
          {p.kind === 'seller' ? <OrganisationsCard profile={p} onSwitchOrg={onSwitchOrg} switching={switching} /> : null}
          {p.kind === 'staff' ? <RolesCard profile={p} /> : null}
        </>
      ) : null}
    </div>
  );
}
