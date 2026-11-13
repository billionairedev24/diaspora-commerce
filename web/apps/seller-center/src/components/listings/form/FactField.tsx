'use client';

import { type AttributeDef } from '@afrotransact/api-client';
import { Field, fieldStyles, Select, TextField } from '@afrotransact/ui';

import { useT } from '@/i18n/t';

interface Props {
  def: AttributeDef;
  id: string;
  value: string;
  error?: string | undefined;
  onChange: (value: string) => void;
}

/** One tax question in plain words: yes/no, a number with its unit, a percentage, a choice, or (for proof files) a note that the
    file is uploaded on the item's page once it is saved. Optional ones say so. */
export function FactField({ def, id, value, error, onChange }: Props) {
  const t = useT();
  const label = def.label;
  const required = def.required;
  const help = def.help ?? undefined;
  switch (def.kind) {
    case 'BOOLEAN':
      return (
        <Select id={id} label={label} required={required} help={help} placeholder={t('sc.pf.choose')} value={value} error={error} onChange={(e) => onChange(e.target.value)}
          options={[{ value: 'yes', label: t('sc.pf.facts.yes') }, { value: 'no', label: t('sc.pf.facts.no') }]} />
      );
    case 'ENUM':
      return (
        <Select id={id} label={label} required={required} help={help} placeholder={t('sc.pf.choose')} value={value} error={error} onChange={(e) => onChange(e.target.value)}
          options={def.options.map((o) => ({ value: o, label: def.optionLabels[o] ?? o }))} />
      );
    case 'DECIMAL':
    case 'INTEGER':
    case 'PERCENT': {
      const unit = def.kind === 'PERCENT' ? '%' : def.unit;
      return (
        <Field id={id} label={label} required={required} help={help} error={error}>
          {(a11y) => (
            <div className="flex items-stretch gap-2">
              <input {...a11y} inputMode={def.kind === 'INTEGER' ? 'numeric' : 'decimal'} className={fieldStyles.control} value={value} onChange={(e) => onChange(e.target.value)} />
              {unit ? <span className="grid min-w-10 place-items-center text-sm text-muted">{unit}</span> : null}
            </div>
          )}
        </Field>
      );
    }
    case 'FILE':
      return <p id={id} tabIndex={-1} className="m-0 text-sm text-muted">{t('sc.pf.facts.file', { label: def.label })}</p>;
    default:
      return <TextField id={id} label={label} required={required} help={help} value={value} error={error} onChange={(e) => onChange(e.target.value)} />;
  }
}
