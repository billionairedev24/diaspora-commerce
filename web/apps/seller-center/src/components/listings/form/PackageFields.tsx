'use client';

import { fieldStyles, InfoTip, RequiredMark, useFormLabels } from '@afrotransact/ui';

import { type MessageKey, useT } from '@/i18n/t';

import { fieldId, type PackageInput, type PackageUnits, SIZE_UNITS, WEIGHT_UNITS } from '../form-model';

interface Props {
  /** The row key ('' = the product's single row). */
  rowKey: string;
  value: PackageInput;
  units: PackageUnits;
  onUnits: (units: PackageUnits) => void;
  /** Shown as the placeholder while empty: the first variant's package, which an empty variant starts from. */
  inherited?: PackageInput | undefined;
  /** The variant this row belongs to (its own caption is shown by the table): only names the controls for assistive technology. */
  name?: string;
  /** One message for the whole group, shown only after the seller left the group with a problem or tried to submit. */
  error?: string | undefined;
  onChange: (value: PackageInput) => void;
}

const SIZE_FIELDS = ['length', 'width', 'height'] as const;

/**
 * The package as one compact row: "Length × Width × Height" with the one size unit beside it, then Weight with its unit. Short labels sit
 * above the boxes, everything is on one baseline and every control has the same height and focus ring. The limit note is in the info
 * popover. Blurring the group (not each box) is what makes a problem visible, and the one message sits under the whole row.
 * Empty = the box of the first variant.
 */
export function PackageInputs({ rowKey, value, units, onUnits, inherited, name, error, onChange }: Props) {
  const t = useT();
  const labels = useFormLabels();
  const id = fieldId.rowPkg(rowKey);
  const msg = `${id}-msg`;
  const box = (f: keyof PackageInput, label: MessageKey, width: string) => (
    <span className="flex flex-col gap-0.5">
      <label htmlFor={`${id}-${f}`} className="text-xs text-muted">{t(label)}</label>
      <input id={`${id}-${f}`} inputMode="decimal" value={value[f]} placeholder={inherited?.[f] ?? ''} aria-label={name ? `${t(label)} · ${name}` : undefined}
        aria-invalid={error ? true : undefined} aria-describedby={error ? msg : undefined} onChange={(e) => onChange({ ...value, [f]: e.target.value })}
        className={`${fieldStyles.control} ${width} text-end`} />
    </span>
  );
  const unit = <T extends string>(current: T, options: readonly T[], label: MessageKey, set: (u: T) => void) => (
    <select aria-label={name ? `${t(label)} · ${name}` : t(label)} value={current} onChange={(e) => set(e.target.value as T)} className={`${fieldStyles.control} w-[4.5rem]`}>
      {options.map((u) => <option key={u} value={u}>{u}</option>)}
    </select>
  );
  return (
    <div id={id} tabIndex={-1} role="group" data-field-group="" data-testid="package-inputs" aria-labelledby={`${id}-h`} className="flex flex-col gap-1.5 outline-none">
      {name ? <span id={`${id}-h`} className="at-visually-hidden">{t('sc.pf.pkg.forVariant', { name })}</span> : (
        <span className="flex items-center gap-1">
          <span id={`${id}-h`} className="text-sm font-semibold">{t('sc.pf.field.package')}<RequiredMark text={labels.required} /></span>
          <InfoTip label={t('sc.pf.pkg.info')}>{t('sc.pf.pkg.hint')}</InfoTip>
        </span>
      )}
      <div className="flex flex-wrap items-end gap-x-4 gap-y-2">
        <div className="flex items-end gap-1.5" data-testid="package-size">
          {SIZE_FIELDS.map((f, i) => (
            <span key={f} className="flex items-end gap-1.5">
              {i > 0 ? <span aria-hidden className="grid h-[var(--at-size-control)] place-items-center text-muted">×</span> : null}
              {box(f, `sc.pf.pkg.${f}`, 'w-20')}
            </span>
          ))}
          <span className="flex items-end">{unit(units.size, SIZE_UNITS, 'sc.pf.pkg.sizeUnit', (u) => onUnits({ ...units, size: u }))}</span>
        </div>
        <div className="flex items-end gap-1.5" data-testid="package-weight">
          {box('weight', 'sc.pf.pkg.weight', 'w-24')}
          {unit(units.weight, WEIGHT_UNITS, 'sc.pf.pkg.weightUnit', (u) => onUnits({ ...units, weight: u }))}
        </div>
      </div>
      <p id={msg} role={error ? 'alert' : undefined} className="m-0 min-h-0 text-xs text-danger" data-testid="package-error">{error}</p>
    </div>
  );
}
