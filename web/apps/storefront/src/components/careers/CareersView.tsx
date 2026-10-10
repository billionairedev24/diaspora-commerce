'use client';

import { type Job, JobApplicationFormSchema, RESUME_CONTENT_TYPE, RESUME_MAX_BYTES, uploadResume, WORK_AUTHORIZATION } from '@afrotransact/api-client';
import { AirplaneTilt, Button, CaretDown, CaretUp, Checkbox, GraduationCap, Heartbeat, Select, ShoppingBag, Skeleton, TextField } from '@afrotransact/ui';
import { useMutation, useQuery } from '@tanstack/react-query';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { type FormEvent, useState } from 'react';

import contentStyles from '@/components/common/Content.module.css';
import { t } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { careersQueries } from '@/lib/api/queries';

import styles from './Careers.module.css';

type ApplyErrors = Partial<Record<'name' | 'email' | 'linkedin' | 'workAuthorization' | 'resumeFileName' | 'consent', string>>;

function ApplyForm({ job }: { job: Job }) {
  const [form, setForm] = useState({ name: '', email: '', linkedin: '', workAuthorization: '', resumeFileName: '', resumeAssetId: '', consent: false });
  const [errors, setErrors] = useState<ApplyErrors>({});
  const [upload, setUpload] = useState<'idle' | 'uploading' | 'done'>('idle');
  // The résumé goes straight to the media service (signed upload); the application carries its asset id.
  const attach = async (f: File | undefined) => {
    setForm((x) => ({ ...x, resumeFileName: '', resumeAssetId: '' }));
    setUpload('idle');
    if (!f) return;
    const isPdf = f.type === RESUME_CONTENT_TYPE || /\.pdf$/i.test(f.name);
    if (!isPdf || f.size > RESUME_MAX_BYTES) {
      setErrors((x) => ({ ...x, resumeFileName: t('careers.tooBig') }));
      return;
    }
    setErrors((x) => ({ ...x, resumeFileName: undefined }));
    setUpload('uploading');
    try {
      const asset = await uploadResume(browserApi(), f);
      setForm((x) => ({ ...x, resumeFileName: f.name, resumeAssetId: asset.assetId }));
      setUpload('done');
    } catch {
      setUpload('idle');
      setErrors((x) => ({ ...x, resumeFileName: t('careers.uploadFailed') }));
    }
  };
  const m = useMutation({ mutationFn: (body: Parameters<ReturnType<typeof browserApi>['applyToJob']>[1]) => browserApi().applyToJob(job.id, body) });
  const set = (k: keyof typeof form, v: string | boolean) => {
    setForm((f) => ({ ...f, [k]: v }));
    setErrors((e) => ({ ...e, [k]: undefined }));
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const parsed = JobApplicationFormSchema.safeParse(form);
    if (!parsed.success) {
      const fe: ApplyErrors = {};
      parsed.error.issues.forEach((i) => {
        const k = (i.path[0] === 'resumeAssetId' ? 'resumeFileName' : i.path[0]) as keyof ApplyErrors;
        fe[k] ??= i.message;
      });
      setErrors(fe);
      const first = (['name', 'email', 'linkedin', 'workAuthorization', 'resumeFileName', 'consent'] as const).find((k) => fe[k]);
      if (first) document.getElementById(`${job.id}-${first}`)?.focus();
      return;
    }
    m.mutate(parsed.data);
  };
  if (m.isSuccess) {
    return (
      <p className={styles.received} role="status">
        <strong>{t('careers.received', { ref: m.data.reference })}</strong>
      </p>
    );
  }
  return (
    <form onSubmit={submit} noValidate className={styles.apply} aria-label={`${t('careers.apply')}: ${job.title}`}>
      <strong>{t('careers.apply')}</strong>
      <TextField id={`${job.id}-name`} label={t('careers.name')} value={form.name} onChange={(e) => set('name', e.target.value)} error={errors.name} autoComplete="name" />
      <TextField id={`${job.id}-email`} label={t('careers.email')} type="email" value={form.email} onChange={(e) => set('email', e.target.value)} error={errors.email} autoComplete="email" />
      <TextField id={`${job.id}-linkedin`} label={t('careers.linkedin')} type="url" placeholder="https://linkedin.com/in/…" value={form.linkedin} onChange={(e) => set('linkedin', e.target.value)} error={errors.linkedin} help={t('careers.linkedinHelp')} />
      <Select
        id={`${job.id}-workAuthorization`}
        label={t('careers.auth')}
        placeholder={t('careers.choose')}
        options={WORK_AUTHORIZATION.map((v) => ({ value: v, label: v }))}
        value={form.workAuthorization}
        onChange={(e) => set('workAuthorization', e.target.value)}
        error={errors.workAuthorization}
      />
      <label className={styles.fileLabel} htmlFor={`${job.id}-resumeFileName`}>
        {t('careers.resume')}
      </label>
      <input
        id={`${job.id}-resumeFileName`}
        className={styles.file}
        type="file"
        accept="application/pdf,.pdf"
        aria-invalid={errors.resumeFileName ? true : undefined}
        aria-describedby={`${job.id}-resume-err`}
        aria-busy={upload === 'uploading' ? true : undefined}
        onChange={(e) => void attach(e.target.files?.[0])}
      />
      <p id={`${job.id}-resume-err`} className={errors.resumeFileName ? styles.err : styles.small} aria-live="polite">
        {errors.resumeFileName ?? (upload === 'uploading' ? t('careers.uploading') : upload === 'done' ? t('careers.uploaded', { file: form.resumeFileName }) : '')}
      </p>
      <Checkbox id={`${job.id}-consent`} label={t('careers.consent')} checked={form.consent} onChange={(e) => set('consent', e.target.checked)} error={errors.consent} />
      <Button type="submit" loading={m.isPending} disabled={upload === 'uploading'} style={{ alignSelf: 'flex-start' }}>
        {t('careers.submit')}
      </Button>
      {m.isError && (
        <p className={styles.err} role="alert">
          {m.error.message}
        </p>
      )}
    </form>
  );
}

/** Careers: perks, open roles filtered by team/location (URL-synced), role detail and validated application. */
export function CareersView() {
  const { data: jobs, isPending } = useQuery(careersQueries.jobs(browserApi()));
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const team = params.get('team') ?? '';
  const location = params.get('location') ?? '';
  const open = params.get('role');
  const setParam = (k: string, v: string | null) => {
    const p = new URLSearchParams(params.toString());
    if (v) p.set(k, v);
    else p.delete(k);
    router.replace(`${pathname}${p.toString() ? `?${p.toString()}` : ''}`, { scroll: false });
  };
  const all = jobs ?? [];
  const teams = [...new Set(all.map((j) => j.team))];
  const locations = [...new Set(all.map((j) => j.location))];
  const list = all.filter((j) => (!team || j.team === team) && (!location || j.location === location));
  const perks = [
    { Icon: Heartbeat, title: t('careers.perk1'), text: t('careers.perk1d') },
    { Icon: AirplaneTilt, title: t('careers.perk2'), text: t('careers.perk2d') },
    { Icon: GraduationCap, title: t('careers.perk3'), text: t('careers.perk3d') },
    { Icon: ShoppingBag, title: t('careers.perk4'), text: t('careers.perk4d') }
  ];
  return (
    <div className={contentStyles.stack} style={{ gap: 'var(--at-space-8)' }}>
      <section className={contentStyles.col} style={{ maxWidth: 760, gap: 'var(--at-space-3)' }}>
        <span className={contentStyles.kicker}>{t('careers.kicker')}</span>
        <h1 className={contentStyles.display}>{t('careers.title')}</h1>
        <p className={contentStyles.lead}>{t('careers.intro')}</p>
      </section>
      <ul className={styles.perks}>
        {perks.map(({ Icon, title, text }) => (
          <li key={title} className={styles.perk}>
            <Icon className={styles.perkIcon} aria-hidden />
            <span>
              <strong>{title}</strong>
              <br />
              <span className={styles.small}>{text}</span>
            </span>
          </li>
        ))}
      </ul>
      <section aria-labelledby="open-roles" style={{ display: 'flex', flexDirection: 'column', gap: 'var(--at-space-3)' }}>
        <div className={styles.rolesHead}>
          <h2 id="open-roles" className={styles.rolesTitle}>
            {t('careers.openRoles')} <span className={styles.count}>({list.length})</span>
          </h2>
          <Select className={styles.filter} label={t('careers.team')} value={team} onChange={(e) => setParam('team', e.target.value || null)} options={[{ value: '', label: t('careers.allTeams') }, ...teams.map((v) => ({ value: v, label: v }))]} />
          <Select className={styles.filter} label={t('careers.location')} value={location} onChange={(e) => setParam('location', e.target.value || null)} options={[{ value: '', label: t('careers.allLocations') }, ...locations.map((v) => ({ value: v, label: v }))]} />
        </div>
        {isPending && (
          <div aria-busy="true" aria-label={t('careers.loading')} style={{ display: 'grid', gap: 12 }}>
            <Skeleton height={72} />
            <Skeleton height={72} />
            <Skeleton height={72} />
          </div>
        )}
        {!isPending && list.length === 0 && <p className={styles.small}>{t('careers.noRoles')}</p>}
        <ul className={styles.jobs}>
          {list.map((j) => {
            const isOpen = open === j.id;
            return (
              <li key={j.id} className={styles.job}>
                <h3 className={styles.jobHead}>
                  <button type="button" className={styles.toggle} aria-expanded={isOpen} aria-controls={`${j.id}-detail`} onClick={() => setParam('role', isOpen ? null : j.id)}>
                    <span>
                      <span className={styles.jobTitle}>{j.title}</span>
                      <br />
                      <span className={styles.small}>
                        {j.team} · {j.location} · {j.type} · {j.pay}
                      </span>
                    </span>
                    {isOpen ? <CaretUp className={styles.caret} aria-hidden /> : <CaretDown className={styles.caret} aria-hidden />}
                  </button>
                </h3>
                {isOpen && (
                  <div id={`${j.id}-detail`} className={styles.detail}>
                    <div className={styles.desc}>
                      <p style={{ margin: 0 }}>{j.summary}</p>
                      <strong>{t('careers.youll')}</strong>
                      <ul>
                        {j.responsibilities.map((d) => (
                          <li key={d}>{d}</li>
                        ))}
                      </ul>
                      <strong>{t('careers.youHave')}</strong>
                      <ul>
                        {j.requirements.map((d) => (
                          <li key={d}>{d}</li>
                        ))}
                      </ul>
                    </div>
                    <ApplyForm job={j} />
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      </section>
    </div>
  );
}
