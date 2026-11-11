'use client';

import { type AttributeDef, type ListingRuleField } from '@afrotransact/api-client';
import { Checkbox, TextField } from '@afrotransact/ui';

import { useT } from '@/i18n/t';

import { fieldId, ruleField } from '../form-model';
import { AttributeField } from './AttributeField';

/** A rule field as the attribute control that asks it (the same controls the product-type details use). */
export function ruleDef(f: ListingRuleField): AttributeDef {
  const kind = f.control === 'long_text' ? 'MULTILINE' : f.control === 'choice' ? 'ENUM' : f.control === 'measure' ? 'NUMBER' : 'TEXT';
  return {
    key: f.attributeKey ?? f.id, label: f.label, options: f.control === 'choice' ? f.options : [], required: f.status === 'required', multiline: f.control === 'long_text', kind,
    units: f.control === 'measure' ? f.units : [], section: 'DETAILS', help: f.note || null, taxFact: false, evidence: false, optionLabels: {}
  };
}

interface Props {
  field: ListingRuleField;
  value: string;
  unit: string;
  error?: string | undefined;
  /** The seller's reason for not providing the field (a "required or exemption" field); undefined = no exemption claimed. */
  reason: string | undefined;
  onChange: (value: string) => void;
  onUnit: (unit: string) => void;
  onReason: (reason: string | undefined) => void;
}

/** One question the listing rules own. Allergens are a tick list; a "required or exemption" field offers "I can't provide this" with a reason. */
export function RuleAttributeField({ field, value, unit, error, reason, onChange, onUnit, onReason }: Props) {
  const t = useT();
  const id = fieldId.attr(field.attributeKey ?? field.id);
  const exempt = field.status === 'required_or_exemption';
  const claimed = exempt && reason !== undefined;
  const ticked = value === 'None' ? [] : value.split(',').map((x) => x.trim()).filter(Boolean);
  return (
    <div className="flex flex-col gap-1.5" data-rule-field={field.id} data-status={field.status}>
      {field.control === 'multi_choice' ? (
        <fieldset id={id} tabIndex={-1} className="m-0 flex flex-col gap-1.5 border-0 p-0 outline-none">
          <legend className="p-0 text-sm font-semibold">{field.status === 'optional' ? t('sc.pf.optional', { label: field.label }) : field.label}</legend>
          {field.note ? <span className="text-xs text-muted">{field.note}</span> : null}
          <div className="grid grid-cols-1 gap-x-4 gap-y-1 @md:grid-cols-2 @3xl:grid-cols-3">
            {field.options.map((o) => (
              <Checkbox key={o} label={o} checked={ticked.includes(o)}
                onChange={(e) => onChange((e.target.checked ? [...ticked, o] : ticked.filter((x) => x !== o)).join(', '))} />
            ))}
            <Checkbox label={t('sc.pf.allergens.none')} checked={value === 'None'} onChange={(e) => onChange(e.target.checked ? 'None' : '')} />
          </div>
          {error ? <span role="alert" className="text-xs text-danger">{error}</span> : null}
        </fieldset>
      ) : (
        <AttributeField def={ruleDef(field)} value={value} unit={unit} error={error} onChange={onChange} onUnit={onUnit} />
      )}
      {exempt ? (
        <div className="flex flex-col gap-1">
          <Checkbox label={t('sc.pf.exempt.toggle')} checked={claimed} disabled={value.trim() !== ''} onChange={(e) => onReason(e.target.checked ? '' : undefined)} />
          {claimed ? (
            <TextField id={fieldId.exemption(field.id)} label={t('sc.pf.exempt.reason')} help={t('sc.pf.exempt.reasonHelp')} value={reason ?? ''} maxLength={300} onChange={(e) => onReason(e.target.value)} />
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

/** True when the field is asked as one of the rule-owned details. */
export const isRuleDetail = (rules: Parameters<typeof ruleField>[0], id: string) => Boolean(ruleField(rules, id)?.attributeKey);
