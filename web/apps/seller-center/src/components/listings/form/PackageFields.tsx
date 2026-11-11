'use client';

import { cx, Select } from '@afrotransact/ui';

import { useT } from '@/i18n/t';

import { fieldId, type PackageInput, type PackageUnits, SIZE_UNITS, WEIGHT_UNITS } from '../form-model';

interface InputsProps {
  /** The row key ('' = the product's single row). */
  rowKey: string;
  value: PackageInput;
  /** Shown as the placeholder while empty: the first variant's package, which an empty variant starts from. */
  inherited?: PackageInput | undefined;
  /** Accessible name suffix, for rows of a variant table. */
  name?: string;
  error?: string | undefined;
  onChange: (value: PackageInput) => void;
}

const FIELDS = ['length', 'width', 'height', 'weight'] as const;

/** The four numbers of one box (length, width, height, weight). Empty = the box of the first variant. */
export function PackageInputs({ rowKey, value, inherited, name, error, onChange }: InputsProps) {
  const t = useT();
  return (
    <fieldset id={fieldId.rowPkg(rowKey)} tabIndex={-1} aria-invalid={error ? true : undefined} className="m-0 flex flex-col gap-1 border-0 p-0 outline-none" data-testid="package-inputs">
      <legend className="sr-only">{name ? t('sc.pf.pkg.forVariant', { name }) : t('sc.pf.pkg.title')}</legend>
      <div className="grid grid-cols-2 gap-2 @md:grid-cols-4">
        {FIELDS.map((f) => (
          <label key={f} className="flex flex-col gap-0.5 text-2xs text-muted">
            {t(`sc.pf.pkg.${f}`)}
            <input inputMode="decimal" value={value[f]} placeholder={inherited?.[f] ?? ''} aria-label={name ? `${t(`sc.pf.pkg.${f}`)} · ${name}` : t(`sc.pf.pkg.${f}`)}
              onChange={(e) => onChange({ ...value, [f]: e.target.value })}
              className={cx('h-8 w-full min-w-0 rounded-sm border bg-surface px-1.5 text-sm text-text', error ? 'border-danger' : 'border-border-strong')} />
          </label>
        ))}
      </div>
      {error ? <span role="alert" className="text-xs text-danger">{error}</span> : null}
    </fieldset>
  );
}

/** The units the package is typed in (the catalog stores inches and pounds). */
export function PackageUnitsSelect({ units, onChange }: { units: PackageUnits; onChange: (u: PackageUnits) => void }) {
  const t = useT();
  return (
    <div className="grid grid-cols-2 gap-x-4 sm:max-w-sm">
      <Select label={t('sc.pf.pkg.sizeUnit')} value={units.size} options={SIZE_UNITS.map((u) => ({ value: u, label: u }))} onChange={(e) => onChange({ ...units, size: e.target.value === 'cm' ? 'cm' : 'in' })} />
      <Select label={t('sc.pf.pkg.weightUnit')} value={units.weight} options={WEIGHT_UNITS.map((u) => ({ value: u, label: u }))} onChange={(e) => onChange({ ...units, weight: e.target.value === 'kg' ? 'kg' : 'lb' })} />
    </div>
  );
}
