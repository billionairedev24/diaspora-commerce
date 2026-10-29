'use client';

import { LEGAL_DOCUMENT_TITLES, type LegalDocumentKey, legalHref, type OnboardingForm, PROVIDER_AGREEMENTS, SELLER_AGREEMENTS } from '@afrotransact/api-client';
import { Checkbox, cx, Select, TextField } from '@afrotransact/ui';
import { Check } from '@phosphor-icons/react/ssr';
import { useImperativeHandle } from 'react';

import { sellerApi } from '@/lib/api';
import {
  BEAUTY_CATEGORIES,
  type ComplianceValues,
  complianceValues,
  fieldId,
  FOOD_CATEGORIES,
  FOOD_PERMITS,
  offersServices,
  PRODUCT_CATEGORIES,
  sellsProducts,
  SERVICES,
  toComplianceInput,
  validateCompliance
} from '@/lib/onboarding-form';
import { useStorefrontUrl } from '@/lib/runtime-config';

import { Cell, DocumentField, FieldGrid, SectionTitle, STATE_OPTIONS, StepErrors, type StepProps, useStepForm } from '../WizardKit';

/** Toggle chips (many-of-many) as designed for categories and services. */
function Chips({
  id,
  title,
  options,
  selected,
  onToggle,
  error,
  disabled
}: {
  id: string;
  title: string;
  options: { key: string; label: string }[];
  selected: string[];
  onToggle: (key: string) => void;
  error?: string;
  disabled?: boolean;
}) {
  return (
    <div id={fieldId(id)} tabIndex={-1} className="flex flex-col gap-2 outline-none" role="group" aria-label={title}>
      <SectionTitle>{title}</SectionTitle>
      <div className="flex flex-wrap gap-2">
        {options.map((o) => {
          const on = selected.includes(o.key);
          return (
            <button
              key={o.key}
              type="button"
              aria-pressed={on}
              disabled={disabled}
              onClick={() => onToggle(o.key)}
              className={cx(
                'inline-flex h-9 cursor-pointer items-center gap-1 rounded-pill border px-4 text-sm font-semibold text-text',
                on ? 'border-gold-500 bg-gold-100' : 'border-border-strong bg-surface hover:border-ink-1000'
              )}
            >
              {on ? <Check size={14} aria-hidden /> : null}
              {o.label}
            </button>
          );
        })}
      </div>
      <p className="m-0 min-h-4 text-xs text-danger" aria-live="polite">
        {error}
      </p>
    </div>
  );
}

/** The person certifying the W-9: the sole proprietor, else the first person with significant control. */
export function w9Signer(form: OnboardingForm): string {
  if (form.business.entityType === 'SOLE_PROPRIETOR') return [form.details.legalFirstName, form.details.legalLastName].filter(Boolean).join(' ');
  const control = form.owners.find((o) => o.significantControl) ?? form.owners[0];
  return control ? [control.firstName, control.lastName].filter(Boolean).join(' ') : (form.details.legalName ?? '');
}

/** The documents this application accepts, in reading order: the seller terms, the provider terms, or both. */
export function agreementKeys(offering: OnboardingForm['business']['offering']): LegalDocumentKey[] {
  const keys: LegalDocumentKey[] = [];
  if (offering !== 'SERVICES') keys.push(...SELLER_AGREEMENTS);
  if (offering === 'SERVICES' || offering === 'BOTH') keys.push(...PROVIDER_AGREEMENTS);
  keys.push('seller-fees');
  return [...new Set(keys)];
}

/** "Seller & Provider Terms, Payments Terms and Data Processing Addendum" as links to the legal center (new tab). */
function DocumentLinks({ keys, base }: { keys: LegalDocumentKey[]; base: string }) {
  return keys.map((k, i) => (
    <span key={k}>
      {i === 0 ? '' : i === keys.length - 1 ? ' and ' : ', '}
      <a href={`${base}${legalHref(k)}`} target="_blank" rel="noopener noreferrer">
        {LEGAL_DOCUMENT_TITLES[k]}
      </a>
    </span>
  ));
}

/**
 * Step 7: "Catalog, permits & licences" (sellers: categories, the food permit, beauty labelling) or "Services &
 * licences" (providers: services and their licences, insurance), with the agreements and the W-9 certification.
 */
export function ComplianceStep({ status, ref }: StepProps) {
  const form = status.form;
  const offering = form.business.offering;
  const disabled = !form.editable;
  const f = useStepForm(() => complianceValues(form));
  const v = f.values;
  const signer = w9Signer(form);
  const storefront = useStorefrontUrl();

  useImperativeHandle(ref, () => ({
    save: (mode) =>
      f.run(
        mode,
        () => validateCompliance(f.values, offering),
        () => sellerApi().saveOnboardingCompliance(toComplianceInput(f.values, offering, signer))
      )
  }));

  const food = v.categories.some((c) => FOOD_CATEGORIES.has(c));
  const beauty = v.categories.some((c) => BEAUTY_CATEGORIES.has(c));
  const setService = (i: number, p: Partial<ComplianceValues['services'][number]>, clear: string) =>
    f.patch({ services: v.services.map((s, j) => (j === i ? { ...s, ...p } : s)) }, [`services.${i}.${clear}`]);

  return (
    <div className="flex flex-col gap-4">
      <StepErrors errors={f.errors} submitCount={f.submitCount} />
      {sellsProducts(offering) ? (
        <>
          <Chips
            id="categories"
            title="What will you sell?"
            options={PRODUCT_CATEGORIES}
            selected={v.categories}
            disabled={disabled}
            error={f.errors.categories}
            onToggle={(k) =>
              f.patch(
                {
                  categories: v.categories.includes(k) ? v.categories.filter((x) => x !== k) : [...v.categories, k]
                },
                ['categories']
              )
            }
          />
          {food ? (
            <section aria-label="Food permit" className="flex flex-col gap-2">
              <SectionTitle hint="Groceries and prepared food need a valid permit before they can be listed.">Food permit</SectionTitle>
              <FieldGrid>
                <Cell span={4}>
                  <Select
                    id={fieldId('permitType')}
                    label="Food permit type"
                    placeholder="Choose…"
                    options={FOOD_PERMITS}
                    value={v.permitType}
                    onChange={(e) =>
                      f.patch(
                        {
                          permitType: FOOD_PERMITS.find((x) => x.value === e.target.value)?.value ?? ''
                        },
                        ['permitType']
                      )
                    }
                    error={f.errors.permitType}
                    disabled={disabled}
                  />
                </Cell>
                <Cell span={4}>
                  <TextField
                    id={fieldId('permitNumber')}
                    label="Permit / registration number"
                    value={v.permitNumber}
                    onChange={(e) => f.patch({ permitNumber: e.target.value }, ['permitNumber'])}
                    error={f.errors.permitNumber}
                    disabled={disabled}
                  />
                </Cell>
                <Cell span={4}>
                  <TextField
                    id={fieldId('permitExpiresOn')}
                    type="date"
                    label="Expires"
                    value={v.permitExpiresOn}
                    onChange={(e) => f.patch({ permitExpiresOn: e.target.value }, ['permitExpiresOn'])}
                    error={f.errors.permitExpiresOn}
                    disabled={disabled}
                  />
                </Cell>
                <Cell span={12}>
                  <DocumentField
                    name="permitDocumentAssetId"
                    label="Permit document"
                    assetId={v.permitDocumentAssetId}
                    onChange={(id) => f.patch({ permitDocumentAssetId: id }, ['permitDocumentAssetId'])}
                    error={f.errors.permitDocumentAssetId}
                    disabled={disabled}
                  />
                </Cell>
              </FieldGrid>
            </section>
          ) : null}
          {beauty ? (
            <Checkbox
              id={fieldId('beautyLabellingConfirmed')}
              label="I confirm my cosmetics list full ingredients and are registered under MoCRA where required"
              checked={v.beautyLabellingConfirmed}
              onChange={(e) => f.patch({ beautyLabellingConfirmed: e.target.checked }, ['beautyLabellingConfirmed'])}
              error={f.errors.beautyLabellingConfirmed}
              disabled={disabled}
            />
          ) : null}
        </>
      ) : null}
      {offersServices(offering) ? (
        <>
          <Chips
            id="services"
            title="Which services will you offer?"
            options={SERVICES.map((s) => ({ key: s.name, label: s.name }))}
            selected={v.services.map((s) => s.serviceName)}
            disabled={disabled}
            error={f.errors.services}
            onToggle={(name) =>
              f.patch(
                {
                  services: v.services.some((s) => s.serviceName === name)
                    ? v.services.filter((s) => s.serviceName !== name)
                    : [
                        ...v.services,
                        {
                          serviceName: name,
                          licenceNumber: '',
                          issuingState: '',
                          expiresOn: '',
                          documentAssetId: ''
                        }
                      ]
                },
                ['services']
              )
            }
          />
          {v.services.map((s, i) => (
            <section key={s.serviceName} aria-label={`${s.serviceName} licence`} className="flex flex-col gap-1 rounded-md border border-border p-4">
              <FieldGrid>
                <Cell span={5}>
                  <TextField
                    id={fieldId(`services.${i}.licenceNumber`)}
                    label={`${s.serviceName}: ${SERVICES.find((x) => x.name === s.serviceName)?.licence ?? 'licence'}`}
                    placeholder="Licence / registration number"
                    value={s.licenceNumber}
                    onChange={(e) => setService(i, { licenceNumber: e.target.value }, 'licenceNumber')}
                    error={f.errors[`services.${i}.licenceNumber`]}
                    disabled={disabled}
                  />
                </Cell>
                <Cell span={3}>
                  <Select
                    id={fieldId(`services.${i}.issuingState`)}
                    label="Issuing state"
                    placeholder="State"
                    options={STATE_OPTIONS}
                    value={s.issuingState}
                    onChange={(e) => setService(i, { issuingState: e.target.value }, 'issuingState')}
                    error={f.errors[`services.${i}.issuingState`]}
                    disabled={disabled}
                  />
                </Cell>
                <Cell span={4}>
                  <TextField
                    id={fieldId(`services.${i}.expiresOn`)}
                    type="date"
                    label="Expires"
                    value={s.expiresOn}
                    onChange={(e) => setService(i, { expiresOn: e.target.value }, 'expiresOn')}
                    error={f.errors[`services.${i}.expiresOn`]}
                    disabled={disabled}
                  />
                </Cell>
                <Cell span={12}>
                  <DocumentField
                    name={`services.${i}.documentAssetId`}
                    label={`${s.serviceName} licence document`}
                    assetId={s.documentAssetId}
                    onChange={(id) => setService(i, { documentAssetId: id }, 'documentAssetId')}
                    error={f.errors[`services.${i}.documentAssetId`]}
                    disabled={disabled}
                  />
                </Cell>
              </FieldGrid>
            </section>
          ))}
          {v.services.length ? (
            <DocumentField
              name="insuranceDocumentAssetId"
              label="General liability insurance certificate"
              assetId={v.insuranceDocumentAssetId}
              onChange={(id) => f.patch({ insuranceDocumentAssetId: id }, ['insuranceDocumentAssetId'])}
              error={f.errors.insuranceDocumentAssetId}
              disabled={disabled}
            />
          ) : null}
        </>
      ) : null}
      <section aria-label="Agreements" className="flex flex-col gap-1.5 border-t border-border pt-3">
        <Checkbox
          id={fieldId('agreeTerms')}
          label={
            <>
              I agree to the <DocumentLinks keys={agreementKeys(offering)} base={storefront} />
            </>
          }
          checked={v.agreeTerms}
          onChange={(e) => f.patch({ agreeTerms: e.target.checked }, ['agreeTerms'])}
          error={f.errors.agreeTerms}
          disabled={disabled}
        />
        <Checkbox
          id={fieldId('agreePolicy')}
          label={
            <>
              I will follow the <DocumentLinks keys={['prohibited-items', 'community-guidelines']} base={storefront} />
            </>
          }
          checked={v.agreePolicy}
          onChange={(e) => f.patch({ agreePolicy: e.target.checked }, ['agreePolicy'])}
          error={f.errors.agreePolicy}
          disabled={disabled}
        />
        <Checkbox
          id={fieldId('w9Certified')}
          label={`Under penalties of perjury, the TIN I provided is correct and I am a U.S. person (W-9 certification${signer ? `, signed by ${signer}` : ''})`}
          checked={v.w9Certified}
          onChange={(e) => f.patch({ w9Certified: e.target.checked }, ['w9Certified'])}
          error={f.errors.w9Certified}
          disabled={disabled}
        />
      </section>
      {f.failure ? (
        <p role="alert" className="m-0 text-sm text-danger">
          {f.failure}
        </p>
      ) : null}
    </div>
  );
}
