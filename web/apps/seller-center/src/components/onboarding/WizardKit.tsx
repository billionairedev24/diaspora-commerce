'use client';

import { isApiError, type OnboardingStatus, sellerKeys, US_STATES } from '@afrotransact/api-client';
import { AddressAutocomplete, type AddressSuggestionItem, cx, ErrorSummary, type SearchAddresses, Select, TextField } from '@afrotransact/ui';
import { CheckCircle, FileArrowUp, UserCircleCheck } from '@phosphor-icons/react/ssr';
import { useQueryClient } from '@tanstack/react-query';
import { type ReactNode, type Ref, useCallback, useId, useRef, useState } from 'react';

import { sellerApi } from '@/lib/api';
import { type AddressValues, type Errors, fieldId, fromSuggestion, mapServerErrors } from '@/lib/onboarding-form';
import { useSeller } from '@/lib/session';

/* Building blocks of the application wizard: the 12-column field grid, prefill tags, choice cards, the address
   block with autocomplete, document uploads, and the step contract (save on "Save and continue" / "Save & sign out"). */

/** What the wizard asks of the step on screen. `continue` validates the whole step first; `draft` saves what is there. */
export interface StepHandle {
  save(mode: 'continue' | 'draft'): Promise<boolean>;
}

export interface StepProps {
  status: OnboardingStatus;
  ref?: Ref<StepHandle>;
}

const SPAN: Record<number, string> = {
  2: 'md:col-span-2',
  3: 'md:col-span-3',
  4: 'md:col-span-4',
  5: 'md:col-span-5',
  6: 'md:col-span-6',
  7: 'md:col-span-7',
  8: 'md:col-span-8',
  12: 'md:col-span-12'
};

/** 12-column grid on desktop, one column on phones; fields are sized by their content with `span`. */
export function FieldGrid({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cx('grid grid-cols-1 gap-x-4 gap-y-1 md:grid-cols-12', className)}>{children}</div>;
}

export function Cell({ span, children, className }: { span: 2 | 3 | 4 | 5 | 6 | 7 | 8 | 12; children: ReactNode; className?: string }) {
  return <div className={cx('min-w-0', SPAN[span], className)}>{children}</div>;
}

/** Subtle "from your account" marker next to a prefilled label. */
export function FromAccount({ on }: { on: boolean }) {
  return on ? (
    <span className="ml-1.5 inline-flex items-center gap-0.5 align-middle text-2xs font-normal text-muted" title="Filled in from your AfroTransact account">
      <UserCircleCheck size={12} aria-hidden />
      from your account
    </span>
  ) : null;
}

export function Optional() {
  return <span className="font-normal text-muted"> (optional)</span>;
}

export function SectionTitle({ children, hint }: { children: ReactNode; hint?: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <h3 className="m-0 text-md font-bold">{children}</h3>
      {hint ? <p className="m-0 text-xs text-muted">{hint}</p> : null}
    </div>
  );
}

/** One-of-many cards (offering, business type, plan) with a radio group's keyboard and screen-reader behaviour. */
export function ChoiceCards<V extends string>({
  name,
  label,
  options,
  value,
  onChange,
  error,
  disabled,
  columns = 'md:grid-cols-3'
}: {
  name: string;
  label: string;
  options: { value: V; label: string; desc?: ReactNode }[];
  value: V | '';
  onChange: (v: V) => void;
  error?: string;
  disabled?: boolean;
  columns?: string;
}) {
  return (
    <fieldset id={fieldId(name)} tabIndex={-1} className="m-0 flex min-w-0 flex-col gap-2 border-0 p-0 outline-none">
      <legend className="mb-2 p-0 text-sm font-semibold">{label}</legend>
      <div className={cx('grid grid-cols-1 gap-2 sm:grid-cols-2', columns)}>
        {options.map((o) => (
          <label
            key={o.value}
            className={cx(
              'flex cursor-pointer items-start gap-2.5 rounded-sm border p-3 has-focus-visible:outline-2 has-focus-visible:outline-focus',
              value === o.value ? 'border-gold-500 bg-gold-50' : 'border-border bg-surface hover:border-border-strong',
              disabled && 'cursor-not-allowed opacity-60'
            )}
          >
            <input
              type="radio"
              name={name}
              value={o.value}
              checked={value === o.value}
              onChange={() => onChange(o.value)}
              disabled={disabled}
              className="mt-0.5 accent-ink-1000"
            />
            <span className="flex min-w-0 flex-col gap-0.5">
              <strong className="text-sm">{o.label}</strong>
              {o.desc ? <span className="text-xs text-muted">{o.desc}</span> : null}
            </span>
          </label>
        ))}
      </div>
      <p className="m-0 min-h-4 text-xs text-danger" aria-live="polite">
        {error}
      </p>
    </fieldset>
  );
}

const STATES = US_STATES.map(([value, label]) => ({
  value,
  label: `${value} — ${label}`
}));
export const STATE_OPTIONS = STATES;

/** The same address search the storefront uses (BFF /v1/geo/address-suggestions → geo service). */
export const sellerAddressSearch: SearchAddresses = async (q, { signal }) => (await sellerApi().searchAddresses(q, signal)).suggestions;

/**
 * Street with address suggestions; picking one fills street, city, state and ZIP, and every part can still be typed.
 * City / State / ZIP share one row on desktop.
 */
export function AddressFields({
  prefix,
  label,
  value,
  onChange,
  errors,
  disabled,
  prefilled,
  search = sellerAddressSearch
}: {
  prefix: string;
  label: string;
  value: AddressValues;
  onChange: (next: AddressValues) => void;
  errors: Errors;
  disabled?: boolean;
  prefilled?: boolean;
  search?: SearchAddresses;
}) {
  const set = (k: keyof AddressValues) => (e: { target: { value: string } }) =>
    onChange({
      ...value,
      [k]: k === 'state' ? e.target.value.toUpperCase() : e.target.value
    });
  const err = (k: string) => errors[`${prefix}.${k}`];
  return (
    <>
      <Cell span={8}>
        {disabled ? (
          <TextField id={fieldId(`${prefix}.line1`)} label={label} value={value.line1} disabled />
        ) : (
          <AddressAutocomplete
            id={fieldId(`${prefix}.line1`)}
            name={`${prefix}.line1`}
            label={
              <>
                {label}
                <FromAccount on={!!prefilled} />
              </>
            }
            value={value.line1}
            onChange={(line1) => onChange({ ...value, line1 })}
            onSelect={(s: AddressSuggestionItem) => onChange(fromSuggestion(value, s))}
            searchAddresses={search}
            placeholder="Start typing the street address"
            error={err('line1')}
          />
        )}
      </Cell>
      <Cell span={4}>
        <TextField
          id={fieldId(`${prefix}.line2`)}
          label={
            <>
              Apt, suite, unit
              <Optional />
            </>
          }
          value={value.line2}
          onChange={set('line2')}
          autoComplete="address-line2"
          disabled={disabled}
        />
      </Cell>
      <Cell span={5}>
        <TextField
          id={fieldId(`${prefix}.city`)}
          label="City"
          value={value.city}
          onChange={set('city')}
          autoComplete="address-level2"
          error={err('city')}
          disabled={disabled}
        />
      </Cell>
      <Cell span={3}>
        <Select
          id={fieldId(`${prefix}.state`)}
          label="State"
          placeholder="State"
          options={STATES}
          value={value.state}
          onChange={set('state')}
          error={err('state')}
          disabled={disabled}
        />
      </Cell>
      <Cell span={4}>
        <TextField
          id={fieldId(`${prefix}.postalCode`)}
          label="ZIP code"
          value={value.postalCode}
          onChange={set('postalCode')}
          inputMode="numeric"
          maxLength={10}
          autoComplete="postal-code"
          error={err('postalCode')}
          disabled={disabled}
        />
      </Cell>
    </>
  );
}

const ACCEPT = 'application/pdf,image/jpeg,image/png,image/webp';
type UploadContentType = 'application/pdf' | 'image/jpeg' | 'image/png' | 'image/webp';
const isUploadType = (t: string): t is UploadContentType => ACCEPT.split(',').includes(t);

/** A permit, licence, insurance or formation document: a private upload (media, purpose kyc-document). */
export function DocumentField({
  name,
  label,
  assetId,
  onChange,
  error,
  disabled
}: {
  name: string;
  label: ReactNode;
  assetId: string;
  onChange: (assetId: string) => void;
  error?: string;
  disabled?: boolean;
}) {
  const input = useRef<HTMLInputElement>(null);
  const hint = useId();
  const [busy, setBusy] = useState(false);
  const [fileName, setFileName] = useState<string | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const upload = async (file: File) => {
    setFailed(null);
    if (!isUploadType(file.type)) {
      setFailed('Use a PDF, JPG, PNG or WebP file');
      return;
    }
    if (file.size > 15 * 1024 * 1024) {
      setFailed('Documents must be 15 MB or smaller');
      return;
    }
    setBusy(true);
    try {
      const api = sellerApi();
      const ticket = await api.requestOnboardingUpload({
        fileName: file.name,
        contentType: file.type,
        size: file.size
      });
      const res = await fetch(ticket.url, {
        method: ticket.method,
        headers: ticket.headers,
        body: file
      });
      if (!res.ok) throw new Error(`upload ${res.status}`);
      await api.completeOnboardingUpload(ticket.assetId);
      setFileName(file.name);
      onChange(ticket.assetId);
    } catch {
      setFailed('The upload didn’t go through. Try again.');
    } finally {
      setBusy(false);
    }
  };
  const shown = error ?? failed;
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <span className="text-sm font-semibold">{label}</span>
      <button
        id={fieldId(name)}
        type="button"
        disabled={disabled || busy}
        aria-describedby={hint}
        onClick={() => input.current?.click()}
        className={cx(
          'flex h-11 min-w-0 cursor-pointer items-center gap-2 rounded-sm border border-dashed bg-surface px-3 text-left text-sm disabled:cursor-not-allowed',
          shown ? 'border-danger' : 'border-border-strong hover:border-ink-1000'
        )}
      >
        {assetId ? (
          <CheckCircle size={18} weight="fill" className="flex-none text-success" aria-hidden />
        ) : (
          <FileArrowUp size={18} className="flex-none text-muted" aria-hidden />
        )}
        <span className="min-w-0 flex-1 truncate">{busy ? 'Uploading…' : assetId ? (fileName ?? 'Document on file') : 'Upload PDF or photo'}</span>
        {assetId && !busy ? <span className="text-xs text-link">Replace</span> : null}
      </button>
      <input
        ref={input}
        type="file"
        accept={ACCEPT}
        className="hidden"
        tabIndex={-1}
        aria-hidden
        onChange={(e) => {
          const f = e.target.files?.[0];
          e.target.value = '';
          if (f) void upload(f);
        }}
      />
      <span id={hint} className={cx('min-h-4 text-xs', shown ? 'text-danger' : 'text-muted')}>
        {shown ?? 'Private: only AfroTransact reviewers see it. PDF, JPG or PNG, up to 15 MB.'}
      </span>
    </div>
  );
}

/** Errors as the error summary lists them, in the order of the form. */
export function StepErrors({ errors, submitCount }: { errors: Errors; submitCount: number }) {
  const items = Object.entries(errors).map(([key, message]) => ({
    fieldId: fieldId(key),
    message
  }));
  const n = items.length;
  return (
    <ErrorSummary title={n === 1 ? 'There is 1 problem with this step' : `There are ${n} problems with this step`} errors={items} submitCount={submitCount} />
  );
}

/**
 * Local state of a step: values, errors (inline and in the summary) and saving through the BFF. `run` validates
 * (continue) or not (draft), calls `send`, stores the returned application and maps the seller service's field
 * errors back onto the fields.
 */
export function useStepForm<V>(initial: () => V) {
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const [values, setValues] = useState<V>(initial);
  const [errors, setErrors] = useState<Errors>({});
  const [submitCount, setSubmitCount] = useState(0);
  const [failure, setFailure] = useState<string | null>(null);

  const patch = useCallback((p: Partial<V>, clear: string[] = []) => {
    setValues((v) => ({ ...v, ...p }));
    if (clear.length) setErrors((e) => Object.fromEntries(Object.entries(e).filter(([k]) => !clear.some((c) => k === c || k.startsWith(`${c}.`)))));
  }, []);

  const run = useCallback(
    async (mode: 'continue' | 'draft', validate: () => Errors, send: () => Promise<OnboardingStatus>): Promise<boolean> => {
      setFailure(null);
      if (mode === 'continue') {
        const e = validate();
        setErrors(e);
        if (Object.keys(e).length) {
          setSubmitCount((n) => n + 1);
          return false;
        }
      }
      try {
        const st = await send();
        qc.setQueryData(sellerKeys.onboarding(orgId), st);
        void qc.invalidateQueries({ queryKey: sellerKeys.session() });
        setErrors({});
        return true;
      } catch (err) {
        if (isApiError(err) && Object.keys(err.fieldErrors).length) {
          setErrors(mapServerErrors(err.fieldErrors));
          setSubmitCount((n) => n + 1);
        } else {
          setFailure(isApiError(err) && err.problem.detail ? err.problem.detail : 'We couldn’t save this step. Try again.');
        }
        return false;
      }
    },
    [qc, orgId]
  );

  return {
    values,
    setValues,
    patch,
    errors,
    setErrors,
    submitCount,
    setSubmitCount,
    failure,
    setFailure,
    run
  };
}
