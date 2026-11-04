'use client';

import { Button, Checkbox, cx, TextField } from '@afrotransact/ui';
import { UserPlus } from '@phosphor-icons/react/ssr';
import { useImperativeHandle } from 'react';

import { sellerApi } from '@/lib/api';
import { addressValues, emptyOwner, fieldId, ownershipTotal, type OwnerValues, ownerValues, toOwnersInput, validateOwners } from '@/lib/onboarding-form';

import { AddressFields, Cell, FieldGrid, StepErrors, type StepProps, useStepForm } from '../WizardKit';

const MAX_OWNERS = 10;

/** Step 3 "Owners": everyone with 25%+ and at least one person with significant control (FinCEN CDD rules, Stripe). */
export function OwnersStep({ status, ref }: StepProps) {
  const form = status.form;
  const entityType = form.business.entityType ?? '';
  const disabled = !form.editable;
  const business = addressValues(form.details.address);
  const f = useStepForm<{ owners: OwnerValues[] }>(() => ({
    owners: ownerValues(form)
  }));
  const owners = f.values.owners;
  const total = ownershipTotal(owners);

  useImperativeHandle(ref, () => ({
    save: (mode) =>
      f.run(
        mode,
        () => validateOwners(f.values.owners, entityType),
        () => sellerApi().saveOnboardingOwners(toOwnersInput(f.values.owners))
      )
  }));

  const update = (i: number, p: Partial<OwnerValues>, clear: string[]) =>
    f.patch({ owners: owners.map((o, j) => (j === i ? { ...o, ...p } : o)) }, [...clear.map((c) => `owners.${i}.${c}`), 'owners']);

  return (
    <div className="flex flex-col gap-3">
      <StepErrors errors={f.errors} submitCount={f.submitCount} />
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="m-0 text-xs text-muted">
          Everyone with 25% or more, and at least one person with significant control (FinCEN CDD rules and Stripe).
        </p>
        <span className={cx('text-sm font-semibold', total > 100 ? 'text-danger' : 'text-muted')} aria-live="polite">
          Ownership listed: {total}%
        </span>
      </div>
      {owners.map((o, i) => {
        const set = (k: 'firstName' | 'lastName' | 'title' | 'email' | 'ownershipPercent' | 'dateOfBirth') => ({
          id: fieldId(`owners.${i}.${k}`),
          value: o[k],
          onChange: (e: { target: { value: string } }) => update(i, { [k]: e.target.value }, [k]),
          error: f.errors[`owners.${i}.${k}`],
          disabled
        });
        return (
          <section key={i} aria-label={`Person ${i + 1}`} className="flex flex-col gap-1 rounded-lg bg-surface-sunken p-4 sm:p-5">
            <div className="flex items-center justify-between">
              <strong className="text-sm">Person {i + 1}</strong>
              {owners.length > 1 && !disabled ? (
                <button
                  type="button"
                  className="cursor-pointer border-0 bg-transparent p-0 text-sm text-link"
                  onClick={() => f.patch({ owners: owners.filter((_, j) => j !== i) }, ['owners'])}
                >
                  Remove
                </button>
              ) : null}
            </div>
            <FieldGrid>
              <Cell span={4}>
                <TextField label="Legal first name" autoComplete="off" {...set('firstName')} />
              </Cell>
              <Cell span={4}>
                <TextField label="Legal last name" autoComplete="off" {...set('lastName')} />
              </Cell>
              <Cell span={4}>
                <TextField label="Title" placeholder="CEO, managing member…" {...set('title')} />
              </Cell>
              <Cell span={5}>
                <TextField type="email" label="Email" {...set('email')} />
              </Cell>
              <Cell span={3}>
                <TextField type="number" inputMode="decimal" min={0} max={100} step="0.01" label="Ownership %" {...set('ownershipPercent')} />
              </Cell>
              <Cell span={4}>
                <TextField type="date" label="Date of birth" {...set('dateOfBirth')} />
              </Cell>
              <Cell span={12}>
                <Checkbox
                  label="This person has significant control (e.g. CEO, managing member)"
                  checked={o.significantControl}
                  onChange={(e) => update(i, { significantControl: e.target.checked }, ['ownershipPercent'])}
                  disabled={disabled}
                />
              </Cell>
              <Cell span={12} className="pt-1">
                <Checkbox
                  label="Home address is the same as the business address"
                  checked={o.sameAsBusiness}
                  disabled={disabled || !business.line1}
                  onChange={(e) =>
                    update(
                      i,
                      {
                        sameAsBusiness: e.target.checked,
                        address: e.target.checked ? { ...business } : o.address
                      },
                      ['address']
                    )
                  }
                />
              </Cell>
              {o.sameAsBusiness ? null : (
                <AddressFields
                  prefix={`owners.${i}.address`}
                  label="Home address"
                  value={o.address}
                  onChange={(address) => update(i, { address }, ['address'])}
                  errors={f.errors}
                  disabled={disabled}
                />
              )}
            </FieldGrid>
          </section>
        );
      })}
      {!disabled && owners.length < MAX_OWNERS ? (
        <div>
          <Button variant="neutral" size="sm" onClick={() => f.patch({ owners: [...owners, emptyOwner()] }, ['owners'])}>
            <UserPlus size={16} aria-hidden /> Add another person
          </Button>
        </div>
      ) : null}
      <p id={fieldId('owners')} tabIndex={-1} className="m-0 min-h-4 text-xs text-danger outline-none" aria-live="polite">
        {f.errors.owners}
      </p>
      {f.failure ? (
        <p role="alert" className="m-0 text-sm text-danger">
          {f.failure}
        </p>
      ) : null}
    </div>
  );
}
