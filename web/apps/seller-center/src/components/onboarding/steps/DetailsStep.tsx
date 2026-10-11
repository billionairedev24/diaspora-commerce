'use client';

import { Select, TextField } from '@afrotransact/ui';
import { LockSimple } from '@phosphor-icons/react/ssr';
import { useImperativeHandle, useState } from 'react';

import { sellerApi } from '@/lib/api';
import { detailsValues, fieldId, formatTaxId, isPrefilled, isSole, maskTaxId, toDetailsInput, validateDetails } from '@/lib/onboarding-form';

import { AddressFields, Cell, DocumentField, FieldGrid, FromAccount, Optional, STATE_OPTIONS, StepErrors, type StepProps, useStepForm } from '../WizardKit';

/**
 * The tax ID: typed digits are formatted as they're typed and shown in full only while the field has focus; after
 * that (and once saved) only the last 4 are shown. It goes to the seller service once, which encrypts it.
 */
export function TaxIdField({
  kind,
  value,
  stored,
  onChange,
  error,
  disabled
}: {
  kind: 'SSN' | 'EIN';
  value: string;
  stored: string | null;
  onChange: (v: string) => void;
  error?: string;
  disabled?: boolean;
}) {
  const [focused, setFocused] = useState(false);
  const label = kind === 'EIN' ? 'Employer Identification Number (EIN)' : 'SSN or ITIN';
  const shown = focused ? value : value ? maskTaxId(value, kind) : '';
  return (
    <TextField
      id={fieldId('taxId')}
      label={label}
      inputMode="numeric"
      autoComplete="off"
      spellCheck={false}
      placeholder={stored ? `${stored} on file — type to replace` : kind === 'EIN' ? '12-3456789' : '123-45-6789'}
      value={shown}
      onFocus={() => setFocused(true)}
      onBlur={() => setFocused(false)}
      onChange={(e) => onChange(formatTaxId(e.target.value, kind))}
      maxLength={11}
      error={error}
      disabled={disabled}
      help={
        <span className="inline-flex items-center gap-1">
          <LockSimple size={12} aria-hidden />
          Just type the 9 digits. Encrypted, and sent to Stripe for the IRS name and TIN match.
        </span>
      }
    />
  );
}

/** Step 2: "About you" for a sole proprietor, "Business details & TIN" for everyone else (the design's fields). */
export function DetailsStep({ status, ref }: StepProps) {
  const form = status.form;
  const entityType = form.business.entityType ?? '';
  const sole = isSole(entityType);
  const disabled = !form.editable;
  const f = useStepForm(() => detailsValues(form));
  const v = f.values;
  const original = detailsValues(form);
  const stored = form.details.taxIdMasked;
  const tag = (k: 'legalFirstName' | 'legalLastName') => <FromAccount on={isPrefilled(form, k) && v[k] !== '' && v[k] === original[k]} />;
  const addressPrefilled = isPrefilled(form, 'address') && JSON.stringify(v.address) === JSON.stringify(original.address);

  useImperativeHandle(ref, () => ({
    save: (mode) =>
      f.run(
        mode,
        () => validateDetails(f.values, entityType, !!stored),
        () => sellerApi().saveOnboardingDetails(toDetailsInput(f.values, entityType))
      )
  }));

  if (!entityType) {
    return <p className="m-0 text-sm text-muted">Choose your business type on the first step first.</p>;
  }
  const field = (k: 'legalFirstName' | 'legalLastName' | 'legalName' | 'dba' | 'dateOfBirth' | 'dateOfFormation') => ({
    id: fieldId(k),
    value: v[k],
    onChange: (e: { target: { value: string } }) => f.patch({ [k]: e.target.value }, [k]),
    error: f.errors[k],
    disabled
  });
  return (
    <div className="flex flex-col gap-3">
      <StepErrors errors={f.errors} submitCount={f.submitCount} />
      <FieldGrid>
        {sole ? (
          <>
            <Cell span={6}>
              <TextField label={<>Legal first name{tag('legalFirstName')}</>} autoComplete="given-name" {...field('legalFirstName')} />
            </Cell>
            <Cell span={6}>
              <TextField label={<>Legal last name{tag('legalLastName')}</>} autoComplete="family-name" {...field('legalLastName')} />
            </Cell>
            <Cell span={6}>
              <TextField
                label={
                  <>
                    Doing business as
                    <Optional />
                  </>
                }
                {...field('dba')}
              />
            </Cell>
            <Cell span={3}>
              <TaxIdField
                kind="SSN"
                value={v.taxId}
                stored={stored}
                onChange={(taxId) => f.patch({ taxId }, ['taxId'])}
                error={f.errors.taxId}
                disabled={disabled}
              />
            </Cell>
            <Cell span={3}>
              <TextField type="date" label="Date of birth" autoComplete="bday" {...field('dateOfBirth')} />
            </Cell>
          </>
        ) : (
          <>
            <Cell span={6}>
              <TextField
                label={entityType === 'NONPROFIT' ? 'Legal organisation name' : 'Legal business name (as on IRS records)'}
                autoComplete="organization"
                {...field('legalName')}
              />
            </Cell>
            <Cell span={6}>
              <TextField
                label={
                  <>
                    Doing business as
                    <Optional />
                  </>
                }
                {...field('dba')}
              />
            </Cell>
            <Cell span={4}>
              <TaxIdField
                kind="EIN"
                value={v.taxId}
                stored={stored}
                onChange={(taxId) => f.patch({ taxId }, ['taxId'])}
                error={f.errors.taxId}
                disabled={disabled}
              />
            </Cell>
            <Cell span={4}>
              <Select
                id={fieldId('stateOfFormation')}
                label={entityType === 'CORPORATION' ? 'State of incorporation' : 'State of formation'}
                placeholder="Choose…"
                options={STATE_OPTIONS}
                value={v.stateOfFormation}
                onChange={(e) => f.patch({ stateOfFormation: e.target.value }, ['stateOfFormation'])}
                error={f.errors.stateOfFormation}
                disabled={disabled}
              />
            </Cell>
            <Cell span={4}>
              <TextField type="date" label="Formation date" {...field('dateOfFormation')} />
            </Cell>
          </>
        )}
        <AddressFields
          prefix="address"
          label={sole ? 'Home or business address' : 'Registered business address'}
          value={v.address}
          onChange={(address) => f.patch({ address }, ['address'])}
          errors={f.errors}
          disabled={disabled}
          prefilled={addressPrefilled}
        />
        {!sole ? (
          <Cell span={12}>
            {entityType === 'NONPROFIT' ? (
              <DocumentField
                name="irsDeterminationLetterAssetId"
                label="IRS 501(c)(3) determination letter"
                assetId={v.irsDeterminationLetterAssetId}
                onChange={(id) => f.patch({ irsDeterminationLetterAssetId: id }, ['irsDeterminationLetterAssetId'])}
                error={f.errors.irsDeterminationLetterAssetId}
                disabled={disabled}
              />
            ) : (
              <DocumentField
                name="formationDocumentAssetId"
                label={
                  entityType === 'CORPORATION'
                    ? 'Articles of incorporation'
                    : entityType === 'PARTNERSHIP'
                      ? 'Partnership agreement or certificate'
                      : 'Articles of organization'
                }
                assetId={v.formationDocumentAssetId}
                onChange={(id) => f.patch({ formationDocumentAssetId: id }, ['formationDocumentAssetId'])}
                error={f.errors.formationDocumentAssetId}
                disabled={disabled}
              />
            )}
          </Cell>
        ) : null}
      </FieldGrid>
      {f.failure ? (
        <p role="alert" className="m-0 text-sm text-danger">
          {f.failure}
        </p>
      ) : null}
    </div>
  );
}
