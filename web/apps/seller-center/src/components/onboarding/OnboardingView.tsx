'use client';

import { isApiError, type OnboardingEntityType, type OnboardingForm, type OnboardingOffering, type OnboardingStatus, type OrgKind, sellerKeys, sellerQueries, US_STATES } from '@afrotransact/api-client';
import { Button, cx, Select, TextField, useToast } from '@afrotransact/ui';
import { CheckCircle, CircleDashed, HourglassMedium, UserCircleCheck, WarningCircle, X, XCircle } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, type ReactNode, useState } from 'react';

import { ModulePage } from '@/components/ui/ModulePage';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, Spinner } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { formValues, isPrefilled, needsLegalName, type OnboardingValues, prefillNote, toBusinessInput, toDetailsInput } from '@/lib/onboarding-form';
import { useSeller } from '@/lib/session';

const STATE = {
  DONE: { Icon: CheckCircle, cls: 'text-success', text: 'Complete' },
  TODO: { Icon: CircleDashed, cls: 'text-subtle', text: 'To do' },
  ACTION: { Icon: WarningCircle, cls: 'text-warning', text: 'Action needed' },
  WAITING: { Icon: HourglassMedium, cls: 'text-info', text: 'In review · SLA 2 business days' },
  BLOCKED: { Icon: XCircle, cls: 'text-danger', text: 'Not approved' }
} as const;

const OFFERINGS: { value: OnboardingOffering; label: string }[] = [
  { value: 'PRODUCTS', label: 'Products' },
  { value: 'SERVICES', label: 'Services' },
  { value: 'BOTH', label: 'Products and services' }
];
const ENTITY_TYPES: { value: OnboardingEntityType; label: string }[] = [
  { value: 'SOLE_PROPRIETOR', label: 'Sole proprietor' },
  { value: 'SINGLE_MEMBER_LLC', label: 'Single-member LLC' },
  { value: 'MULTI_MEMBER_LLC', label: 'Multi-member LLC' },
  { value: 'CORPORATION', label: 'Corporation' },
  { value: 'PARTNERSHIP', label: 'Partnership' },
  { value: 'NONPROFIT', label: 'Nonprofit' }
];
const STATES = US_STATES.map(([value, label]) => ({ value, label }));

/** Server field names (seller service) → wizard fields. */
const FIELD_OF: Record<string, keyof OnboardingValues> = {
  offering: 'offering', entityType: 'entityType', 'business.entityType': 'entityType', storeName: 'storeName', contactEmail: 'contactEmail', phone: 'phone',
  website: 'website', legalFirstName: 'legalFirstName', legalLastName: 'legalLastName', legalName: 'legalName', address: 'line1', 'address.line1': 'line1',
  'address.city': 'city', 'address.state': 'state', 'address.postalCode': 'postalCode'
};

function FromAccount({ on }: { on: boolean }) {
  return on ? <span className="ml-1 rounded-pill bg-gold-100 px-2 py-0.5 text-xs font-semibold text-text">From your account</span> : null;
}

function Section({ title, hint, children }: { title: string; hint: string; children: ReactNode }) {
  return (
    <fieldset className="m-0 flex min-w-0 flex-col gap-4 rounded-md border border-border p-5">
      <legend className="px-1 text-lg font-bold">{title}</legend>
      <p className="m-0 -mt-2 text-sm text-muted">{hint}</p>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">{children}</div>
    </fieldset>
  );
}

/** The application's first two steps, prefilled from the account and autosaved on "Save and continue". */
function ApplicationForm({ status, kind }: { status: OnboardingStatus; kind: OrgKind }) {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const form: OnboardingForm = status.form;
  const [values, setValues] = useState<OnboardingValues>(() => formValues(form, kind));
  const [errors, setErrors] = useState<Partial<Record<keyof OnboardingValues, string>>>({});
  const [note, setNote] = useState<string | null>(() => prefillNote(form));
  const disabled = !form.editable;

  const save = useMutation({
    mutationFn: async (v: OnboardingValues) => {
      await api.saveOnboardingBusiness(toBusinessInput(v));
      return api.saveOnboardingDetails(toDetailsInput(v));
    },
    onSuccess: (st) => {
      qc.setQueryData(sellerKeys.onboarding(orgId), st);
      void qc.invalidateQueries({ queryKey: sellerKeys.session() });
      toast.show('Saved. You can sign out and continue any time.');
    },
    onError: (e) => {
      if (isApiError(e) && Object.keys(e.fieldErrors).length) {
        const next: Partial<Record<keyof OnboardingValues, string>> = {};
        Object.entries(e.fieldErrors).forEach(([k, msg]) => {
          const f = FIELD_OF[k];
          if (f) next[f] = msg;
        });
        setErrors(next);
      }
      toast.show(isApiError(e) && e.problem.detail ? e.problem.detail : 'We couldn’t save your answers. Try again.', 'error');
    }
  });

  const set = (k: keyof OnboardingValues) => (e: { target: { value: string } }) => {
    const value = e.target.value;
    setValues((v) => ({ ...v, [k]: value }));
    setErrors((er) => ({ ...er, [k]: undefined }));
  };
  const field = (k: keyof OnboardingValues, label: string, extra: { type?: string; autoComplete?: string; span?: boolean; optional?: boolean } = {}) => (
    <TextField
      label={
        <>
          {label}
          {extra.optional ? <span className="font-normal text-muted"> (optional)</span> : null}
          <FromAccount on={isPrefilled(form, k) && values[k] !== '' && values[k] === formValues(form, kind)[k]} />
        </>
      }
      name={k}
      type={extra.type ?? 'text'}
      autoComplete={extra.autoComplete}
      value={values[k]}
      onChange={set(k)}
      error={errors[k]}
      disabled={disabled}
      className={extra.span ? 'sm:col-span-2' : undefined}
    />
  );

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!values.entityType) {
      setErrors((er) => ({ ...er, entityType: 'Choose your business type' }));
      return;
    }
    save.mutate(values);
  };

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-5" aria-label="Your application">
      {note ? (
        <div role="status" className="flex items-start gap-3 rounded-sm bg-gold-50 px-4 py-3 text-sm">
          <UserCircleCheck size={22} aria-hidden className="flex-none" />
          <span className="flex-1">
            <strong>Using your existing AfroTransact account.</strong> {note}
          </span>
          <button type="button" aria-label="Dismiss" className="cursor-pointer border-0 bg-transparent p-0 text-text" onClick={() => setNote(null)}>
            <X size={18} aria-hidden />
          </button>
        </div>
      ) : null}
      <Section title="Your business" hint="What you’ll offer and how shoppers and our team reach you.">
        <Select label="What will you offer?" name="offering" options={OFFERINGS} value={values.offering} onChange={set('offering')} error={errors.offering} disabled={disabled} />
        <Select
          label="Business type"
          name="entityType"
          placeholder="Choose…"
          options={ENTITY_TYPES}
          value={values.entityType}
          onChange={set('entityType')}
          error={errors.entityType}
          disabled={disabled}
        />
        {field('storeName', kind === 'PROVIDER' ? 'Business name' : 'Store name', { autoComplete: 'organization', span: true })}
        {field('contactEmail', 'Contact email', { type: 'email', autoComplete: 'email' })}
        {field('phone', 'Mobile phone', { type: 'tel', autoComplete: 'tel' })}
        {field('website', 'Website', { type: 'url', autoComplete: 'url', span: true, optional: true })}
      </Section>
      <Section title="About you" hint="Your legal name as on your ID, and your business address.">
        {field('legalFirstName', 'Legal first name', { autoComplete: 'given-name' })}
        {field('legalLastName', 'Legal last name', { autoComplete: 'family-name' })}
        {needsLegalName(values.entityType) ? field('legalName', 'Registered business name', { autoComplete: 'organization', span: true }) : null}
        {field('line1', 'Street address', { autoComplete: 'address-line1', span: true })}
        {field('line2', 'Apt, suite, unit', { autoComplete: 'address-line2', span: true, optional: true })}
        {field('city', 'City', { autoComplete: 'address-level2' })}
        <div className="grid grid-cols-2 gap-4">
          <Select
            label={
              <>
                State
                <FromAccount on={isPrefilled(form, 'state') && values.state !== '' && values.state === formValues(form, kind).state} />
              </>
            }
            name="state"
            placeholder="State"
            options={STATES}
            value={values.state}
            onChange={set('state')}
            error={errors.state}
            disabled={disabled}
          />
          {field('postalCode', 'ZIP code', { autoComplete: 'postal-code' })}
        </div>
      </Section>
      {form.editable ? (
        <div className="flex flex-wrap items-center gap-3">
          <Button type="submit" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save and continue'}
          </Button>
          <span className="text-sm text-muted">Progress saves to your account. Sign out any time and continue later.</span>
        </div>
      ) : null}
    </form>
  );
}

/** Onboarding: the only page before approval — the application (prefilled from the account) and its checklist. */
export function OnboardingView() {
  const { orgId, isProvider, session } = useSeller();
  const q = useQuery(sellerQueries.onboarding(sellerApi(), orgId));
  const kind: OrgKind = session.activeOrg?.kind ?? (isProvider ? 'PROVIDER' : 'SELLER');
  return (
    <ModulePage module="onboarding" readOnlyNote={false}>
      <Notice tone="warning" title={`${isProvider ? 'Provider Hub' : 'Seller Center'} is locked.`} icon="shield">
        Finish your application and wait for approval. Only onboarding is open to your account.
      </Notice>
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {q.data ? (
        <>
          <h2 className="m-0 text-xl font-bold">
            {isProvider ? 'Your provider application' : 'Your seller application'} {q.data.reference ? <span className="font-normal text-muted">· {q.data.reference}</span> : null}
          </h2>
          <ApplicationForm key={q.data.orgId} status={q.data} kind={kind} />
          <section className="flex flex-col gap-4 rounded-md border border-border p-6" aria-label="Application progress">
            <strong className="text-lg">Progress</strong>
            <ol className="m-0 flex list-none flex-col gap-2 p-0">
              {q.data.steps.map((s) => {
                const st = STATE[s.state];
                return (
                  <li key={s.key} className="flex items-center gap-3">
                    <st.Icon size={22} weight="fill" aria-hidden className={st.cls} />
                    <span className="flex-1">{s.label}</span>
                    <span className={cx('text-xs', st.cls)}>{st.text}</span>
                  </li>
                );
              })}
            </ol>
            {q.data.requestedItems.length ? (
              <Notice tone="warning" title="We need:">{q.data.requestedItems.join(', ')}</Notice>
            ) : null}
          </section>
        </>
      ) : null}
    </ModulePage>
  );
}
