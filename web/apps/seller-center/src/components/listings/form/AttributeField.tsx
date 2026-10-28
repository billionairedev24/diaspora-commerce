'use client';

import { type AttributeDef } from '@afrotransact/api-client';
import { Combobox, Field, fieldStyles, Select, TextField } from '@afrotransact/ui';

import { useT } from '@/i18n/t';

import { fieldId } from '../form-model';

interface Props {
  def: AttributeDef;
  value: string;
  unit: string;
  error?: string;
  onChange: (value: string) => void;
  onUnit: (unit: string) => void;
}

/** Choice lists longer than this are searchable; shorter ones are a plain dropdown. */
const LONG_LIST = 8;

/** One product-type attribute: a dropdown (searchable when long), a number with its unit select, a line or a paragraph. Optional ones say so. */
export function AttributeField({ def, value, unit, error, onChange, onUnit }: Props) {
  const t = useT();
  const label = def.required ? def.label : t('sc.pf.optional', { label: def.label });
  const help = def.help ?? undefined;
  const id = fieldId.attr(def.key);
  if (def.kind === 'ENUM' && def.options.length > LONG_LIST) {
    return <Combobox id={id} label={label} help={help} placeholder={t('sc.pf.choose')} options={def.options.map((o) => ({ value: o, label: o }))} value={value} error={error}
      resultsLabel={(count) => t('sc.pf.category.count', { count })} emptyLabel={t('sc.pf.brand.none')} onChange={onChange} />;
  }
  if (def.kind === 'ENUM' && def.options.length) {
    return <Select id={id} label={label} help={help} placeholder={t('sc.pf.choose')} options={def.options.map((o) => ({ value: o, label: o }))} value={value} error={error} onChange={(e) => onChange(e.target.value)} />;
  }
  if (def.kind === 'NUMBER') {
    return (
      <Field id={id} label={label} help={help} error={error}>
        {(a11y) => (
          <span className="flex items-stretch gap-2">
            <input {...a11y} inputMode="decimal" className={fieldStyles.control} value={value} onChange={(e) => onChange(e.target.value)} />
            {def.units.length === 1 ? <span className="grid min-w-12 place-items-center text-sm text-muted">{def.units[0]}</span> : def.units.length ? (
              <select aria-label={t('sc.pf.unitOf', { label: def.label })} className={`${fieldStyles.control} max-w-32`} value={unit || def.units[0]} onChange={(e) => onUnit(e.target.value)}>
                {def.units.map((u) => <option key={u} value={u}>{u}</option>)}
              </select>
            ) : null}
          </span>
        )}
      </Field>
    );
  }
  if (def.kind === 'MULTILINE' || def.multiline) {
    return (
      <Field id={id} label={label} help={help} error={error} className="sm:col-span-2">
        {(a11y) => <textarea {...a11y} rows={3} className={`${fieldStyles.control} min-h-20 py-2`} value={value} onChange={(e) => onChange(e.target.value)} />}
      </Field>
    );
  }
  return <TextField id={id} label={label} help={help} value={value} error={error} onChange={(e) => onChange(e.target.value)} />;
}
