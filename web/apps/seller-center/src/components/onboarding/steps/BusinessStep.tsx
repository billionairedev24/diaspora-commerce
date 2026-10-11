'use client';

import { type OnboardingEntityType, type OnboardingOffering } from '@afrotransact/api-client';
import { PhoneInput, TextField } from '@afrotransact/ui';
import { useImperativeHandle } from 'react';

import { sellerApi } from '@/lib/api';
import { businessValues, ENTITY_TYPES, fieldId, isPrefilled, OFFERINGS, toBusinessInput, validateBusiness } from '@/lib/onboarding-form';

import { Cell, ChoiceCards, FieldGrid, FromAccount, Optional, StepErrors, type StepProps, useStepForm } from '../WizardKit';

/** Step 1 "Your business": what you offer, how you're organised, and how shoppers and our team reach you. */
export function BusinessStep({ status, ref }: StepProps) {
  const kind = status.kind;
  const form = status.form;
  const disabled = !form.editable;
  const f = useStepForm(() => businessValues(form, kind));
  const v = f.values;
  const original = businessValues(form, kind);
  const tag = (k: 'storeName' | 'contactEmail' | 'phone') => <FromAccount on={isPrefilled(form, k) && v[k] !== '' && v[k] === original[k]} />;

  useImperativeHandle(ref, () => ({
    save: (mode) =>
      f.run(
        mode,
        () => validateBusiness(f.values, kind),
        () => sellerApi().saveOnboardingBusiness(toBusinessInput(f.values))
      )
  }));

  const offerings = OFFERINGS.filter((o) => (kind === 'PROVIDER' ? o.value !== 'PRODUCTS' : o.value !== 'SERVICES'));
  return (
    <div className="flex flex-col gap-4">
      <StepErrors errors={f.errors} submitCount={f.submitCount} />
      <ChoiceCards<OnboardingOffering>
        name="offering"
        label="What will you offer on AfroTransact?"
        options={offerings}
        value={v.offering}
        onChange={(offering) => f.patch({ offering }, ['offering'])}
        error={f.errors.offering}
        disabled={disabled}
        columns="md:grid-cols-2"
      />
      <ChoiceCards<OnboardingEntityType>
        name="entityType"
        label="Business type — this decides which tax ID, documents and owners we ask for"
        options={ENTITY_TYPES}
        value={v.entityType}
        onChange={(entityType) => f.patch({ entityType }, ['entityType'])}
        error={f.errors.entityType}
        disabled={disabled}
      />
      <FieldGrid>
        <Cell span={6}>
          <TextField
            id={fieldId('storeName')}
            label={
              <>
                {kind === 'PROVIDER' ? 'Business display name' : 'Store or business display name'}
                {tag('storeName')}
              </>
            }
            placeholder="Shown to shoppers"
            autoComplete="organization"
            value={v.storeName}
            onChange={(e) => f.patch({ storeName: e.target.value }, ['storeName'])}
            error={f.errors.storeName}
            disabled={disabled}
          />
        </Cell>
        <Cell span={6}>
          <TextField
            id={fieldId('contactEmail')}
            type="email"
            label={<>Business email{tag('contactEmail')}</>}
            placeholder="you@business.com"
            autoComplete="email"
            value={v.contactEmail}
            onChange={(e) => f.patch({ contactEmail: e.target.value }, ['contactEmail'])}
            error={f.errors.contactEmail}
            disabled={disabled}
          />
        </Cell>
        <Cell span={6}>
          <PhoneInput
            id={fieldId('phone')}
            label={<>Mobile phone (for verification codes){tag('phone')}</>}
            country="US"
            onCountryChange={() => undefined}
            value={v.phone}
            onChange={(phone) => f.patch({ phone }, ['phone'])}
            placeholder="(713) 249-0142"
            error={f.errors.phone}
            disabled={disabled}
          />
        </Cell>
        <Cell span={6}>
          <TextField
            id={fieldId('website')}
            type="url"
            label={
              <>
                Website
                <Optional />
              </>
            }
            placeholder="https://"
            autoComplete="url"
            value={v.website}
            onChange={(e) => f.patch({ website: e.target.value }, ['website'])}
            error={f.errors.website}
            disabled={disabled}
          />
        </Cell>
      </FieldGrid>
      {f.failure ? (
        <p role="alert" className="m-0 text-sm text-danger">
          {f.failure}
        </p>
      ) : null}
    </div>
  );
}
