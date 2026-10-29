'use client';

import {
  attributeKeyFrom,
  type AttributeKind,
  attributeProblems,
  attributesChanged,
  isApiError,
  staffTaxonomyKeys,
  type TaxonomyAttribute,
  type TaxonomyProductType,
  type TaxonomyTraits
} from '@afrotransact/api-client';
import { Button, Checkbox, cx, Select, TextField, useToast } from '@afrotransact/ui';
import { ArrowDown, ArrowUp, CaretDown, CaretRight, Lock, Plus, Trash } from '@phosphor-icons/react/ssr';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { problemMessage } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';

import { changedFields, draftOfType, linesFromText, listFromText, type TypeDraft, withTrait } from './model';
import { SellerFormPreview } from './SellerFormPreview';

const KINDS: { value: AttributeKind; label: MessageKey }[] = [
  { value: 'TEXT', label: 'st.catalog.kind.text' },
  { value: 'MULTILINE', label: 'st.catalog.kind.multiline' },
  { value: 'ENUM', label: 'st.catalog.kind.choice' },
  { value: 'NUMBER', label: 'st.catalog.kind.number' }
];
const SECTIONS: { value: TaxonomyAttribute['section']; label: MessageKey }[] = [
  { value: 'DETAILS', label: 'st.catalog.section.details' },
  { value: 'FOOD', label: 'st.catalog.section.food' },
  { value: 'REGULATED', label: 'st.catalog.section.regulated' }
];
const TRAITS: { key: keyof TaxonomyTraits; label: MessageKey; help: MessageKey }[] = [
  { key: 'food', label: 'st.catalog.trait.food', help: 'st.catalog.trait.foodHelp' },
  { key: 'perishable', label: 'st.catalog.trait.perishable', help: 'st.catalog.trait.perishableHelp' },
  { key: 'regulated', label: 'st.catalog.trait.regulated', help: 'st.catalog.trait.regulatedHelp' },
  { key: 'preorder', label: 'st.catalog.trait.preorder', help: 'st.catalog.trait.preorderHelp' }
];
/** The sales-tax question is worded and mapped to tax codes by tax-returns: staff only choose whether to ask it. */
const TAX_KEY = 'tax_class';

const blankAttribute = (): TaxonomyAttribute => ({ key: '', label: '', kind: 'TEXT', options: [], units: [], required: false, section: 'DETAILS', help: null, applies: true });

interface RowProps {
  attribute: TaxonomyAttribute;
  index: number;
  total: number;
  fresh: boolean;
  open: boolean;
  disabled: boolean;
  errors: Record<string, string>;
  onToggle: () => void;
  onChange: (next: TaxonomyAttribute, keyTouched?: boolean) => void;
  onMove: (by: -1 | 1) => void;
  onRemove: () => void;
}

function AttributeRow({ attribute: a, index, total, fresh, open, disabled, errors, onToggle, onChange, onMove, onRemove }: RowProps) {
  const t = useT();
  const [optionsText, setOptionsText] = useState(a.options.join('\n'));
  const [unitsText, setUnitsText] = useState(a.units.join(', '));
  const locked = a.key === TAX_KEY;
  const at = `attributes[${index}].`;
  const idBase = `attr-${index}`;
  const problem = Object.entries(errors).filter(([k]) => k.startsWith(at));
  const kindLabel = t(KINDS.find((k) => k.value === a.kind)?.label ?? 'st.catalog.kind.text');
  return (
    <li className={cx('rounded-md border bg-ink-0', problem.length ? 'border-danger' : 'border-border')} data-testid="attribute-row">
      <div className="flex flex-wrap items-center gap-2 px-2 py-1">
        <button type="button" onClick={onToggle} aria-expanded={open} className="inline-flex min-h-9 min-w-0 flex-1 cursor-pointer items-center gap-2 border-0 bg-transparent p-0 text-left text-text">
          {open ? <CaretDown size={14} aria-hidden /> : <CaretRight size={14} aria-hidden />}
          <strong className="truncate text-sm">{a.label || t('st.catalog.attr.untitled')}</strong>
          <span className="font-mono text-2xs text-subtle">{a.key}</span>
          <span className="text-xs text-muted">{kindLabel}</span>
          {a.required && a.applies ? <span className="text-xs font-semibold text-gold-900">{t('st.catalog.attr.requiredShort')}</span> : null}
          {!a.applies ? <span className="rounded-pill bg-ink-100 px-2 py-0.5 text-2xs font-bold text-muted">{t('st.catalog.attr.notAsked')}</span> : null}
          {locked ? <Lock size={12} aria-label={t('st.catalog.attr.lockedShort')} /> : null}
        </button>
        <span className="flex items-center gap-1">
          <Button variant="neutral" size="sm" disabled={disabled || index === 0} onClick={() => onMove(-1)} aria-label={t('st.catalog.attr.moveUp', { name: a.label || a.key })}>
            <ArrowUp size={14} aria-hidden />
          </Button>
          <Button variant="neutral" size="sm" disabled={disabled || index === total - 1} onClick={() => onMove(1)} aria-label={t('st.catalog.attr.moveDown', { name: a.label || a.key })}>
            <ArrowDown size={14} aria-hidden />
          </Button>
        </span>
      </div>
      {open ? (
        <div className="flex flex-col gap-3 border-t border-dashed border-border bg-ink-25 p-3">
          {locked ? <p className="m-0 text-xs text-muted">{t('st.catalog.attr.locked')}</p> : null}
          <TextField
            id={`${idBase}-label`}
            label={t('st.catalog.attr.label')}
            value={a.label}
            maxLength={120}
            disabled={disabled || locked}
            error={errors[`${at}label`]}
            onChange={(e) => onChange({ ...a, label: e.currentTarget.value })}
          />
          <TextField
            id={`${idBase}-key`}
            label={t('st.catalog.attr.key')}
            help={fresh ? t('st.catalog.attr.keyHelpNew') : t('st.catalog.attr.keyHelp')}
            value={a.key}
            maxLength={40}
            disabled={disabled || !fresh}
            error={errors[`${at}key`]}
            onChange={(e) => onChange({ ...a, key: e.currentTarget.value }, true)}
          />
          <div className="grid gap-3 sm:grid-cols-2">
            <Select
              id={`${idBase}-kind`}
              label={t('st.catalog.attr.kind')}
              options={KINDS.map((k) => ({ value: k.value, label: t(k.label) }))}
              value={a.kind}
              disabled={disabled || locked}
              error={errors[`${at}kind`]}
              onChange={(e) => {
                const kind = e.currentTarget.value as AttributeKind;
                onChange({ ...a, kind, options: kind === 'ENUM' ? a.options : [], units: kind === 'NUMBER' ? a.units : [] });
              }}
            />
            <Select
              id={`${idBase}-section`}
              label={t('st.catalog.attr.section')}
              options={SECTIONS.map((s) => ({ value: s.value, label: t(s.label) }))}
              value={a.section}
              disabled={disabled}
              error={errors[`${at}section`]}
              onChange={(e) => onChange({ ...a, section: e.currentTarget.value as TaxonomyAttribute['section'] })}
            />
          </div>
          {a.kind === 'ENUM' ? (
            <div className="flex flex-col gap-1">
              <label htmlFor={`${idBase}-options`} className="text-sm font-semibold">
                {t('st.catalog.attr.options')}
              </label>
              <textarea
                id={`${idBase}-options`}
                rows={Math.min(8, Math.max(3, a.options.length + 1))}
                className="rounded-sm border border-border-strong bg-ink-0 p-2 text-sm"
                value={optionsText}
                disabled={disabled || locked}
                aria-invalid={errors[`${at}options`] ? true : undefined}
                onChange={(e) => {
                  setOptionsText(e.currentTarget.value);
                  onChange({ ...a, options: linesFromText(e.currentTarget.value) });
                }}
              />
              <span className="text-xs text-muted">{errors[`${at}options`] ?? t('st.catalog.attr.optionsHelp')}</span>
            </div>
          ) : null}
          {a.kind === 'NUMBER' ? (
            <TextField
              id={`${idBase}-units`}
              label={t('st.catalog.attr.units')}
              help={t('st.catalog.attr.unitsHelp')}
              value={unitsText}
              disabled={disabled}
              error={errors[`${at}units`]}
              onChange={(e) => {
                setUnitsText(e.currentTarget.value);
                onChange({ ...a, units: listFromText(e.currentTarget.value) });
              }}
            />
          ) : null}
          <TextField
            id={`${idBase}-help`}
            label={t('st.catalog.attr.help')}
            value={a.help ?? ''}
            maxLength={200}
            disabled={disabled}
            error={errors[`${at}help`]}
            onChange={(e) => onChange({ ...a, help: e.currentTarget.value })}
          />
          <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
            <Checkbox
              label={t('st.catalog.attr.applies')}
              description={t('st.catalog.attr.appliesHelp')}
              checked={a.applies}
              disabled={disabled}
              onChange={(e) => onChange({ ...a, applies: e.currentTarget.checked, required: e.currentTarget.checked ? a.required : false })}
            />
            <Checkbox label={t('st.catalog.attr.required')} checked={a.required && a.applies} disabled={disabled || !a.applies} onChange={(e) => onChange({ ...a, required: e.currentTarget.checked })} />
          </div>
          {!locked ? (
            <div className="flex justify-end">
              <Button variant="neutral" size="sm" disabled={disabled} icon={<Trash size={14} aria-hidden />} onClick={onRemove}>
                {t('st.catalog.attr.remove')}
              </Button>
            </div>
          ) : null}
        </div>
      ) : null}
    </li>
  );
}

/**
 * Edits what a product type asks sellers for: its name, identifier rule (GTIN or ISBN), traits (food, perishable,
 * regulated, pre-order) and every detail with its type, units, choices, form section, whether it is required and
 * whether it applies at all (brand is just an attribute; a Books type has none). The seller's form is previewed live.
 * Save stays disabled until something differs from what was loaded; only the changed parts are sent, with the reason
 * and the version loaded (a stale version is refused: someone else saved first).
 */
export function TypeEditor({ type, canEdit, onSaved }: { type: TaxonomyProductType; canEdit: boolean; onSaved?: (saved: TaxonomyProductType) => void }) {
  const t = useT();
  const api = staffApi().taxonomy;
  const qc = useQueryClient();
  const toast = useToast();
  const original = useMemo(() => draftOfType(type), [type]);
  const [draft, setDraft] = useState<TypeDraft>(original);
  const [fresh, setFresh] = useState<Set<number>>(new Set());
  const [open, setOpen] = useState<number | null>(null);
  const [reason, setReason] = useState('');
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);

  const attrsDiffer = attributesChanged(type.attributes, draft.attributes);
  const changes = changedFields(original, draft, attrsDiffer);
  const local = attributeProblems(draft.attributes, draft.traits);
  const errors = { ...serverErrors, ...local };
  const labelError = draft.label.trim().length < 2 ? t('st.catalog.type.labelError') : undefined;
  const reasonError = changes.length > 0 && reason.trim().length < 3 ? t('st.catalog.reasonError') : undefined;
  const blocked = Object.keys(local).length > 0 || labelError !== undefined;
  const preview = useMemo(() => ({ identifierRule: draft.identifierRule, traits: draft.traits, attributes: draft.attributes }), [draft]);

  const save = useMutation({
    mutationFn: () =>
      api.updateProductType(
        type.code,
        {
          ...(changes.includes('label') ? { label: draft.label.trim() } : {}),
          ...(changes.includes('identifierRule') ? { identifierRule: draft.identifierRule } : {}),
          ...(changes.includes('traits') ? { traits: draft.traits } : {}),
          ...(changes.includes('attributes') ? { attributes: draft.attributes.map((a) => ({ ...a, help: a.help?.trim() ? a.help.trim() : null })) } : {}),
          reason: reason.trim()
        },
        type.version
      ),
    retry: false,
    onSuccess: (saved) => {
      qc.setQueryData(staffTaxonomyKeys.type(saved.code), saved);
      void qc.invalidateQueries({ queryKey: staffTaxonomyKeys.all });
      setServerErrors({});
      setError(null);
      setReason('');
      setFresh(new Set());
      setDraft(draftOfType(saved));
      toast.show(t('st.catalog.type.saved', { name: saved.label }));
      onSaved?.(saved);
    },
    onError: (e) => {
      if (isApiError(e) && e.status === 422) {
        setServerErrors(e.fieldErrors);
        setError(Object.keys(e.fieldErrors).some((k) => k.startsWith('attributes[')) ? null : (e.problem.detail ?? null));
      } else {
        setError(problemMessage(e));
        if (isApiError(e) && e.status === 412) void qc.invalidateQueries({ queryKey: staffTaxonomyKeys.all });
      }
    }
  });

  const setAttribute = (i: number, next: TaxonomyAttribute, keyTouched = false) =>
    setDraft((d) => {
      const prev = d.attributes[i] as TaxonomyAttribute;
      let attribute = next;
      // A new detail's key follows its label until someone types a key.
      if (!keyTouched && fresh.has(i) && prev.key === attributeKeyFrom(prev.label)) attribute = { ...next, key: attributeKeyFrom(next.label) };
      return { ...d, attributes: d.attributes.map((a, j) => (j === i ? attribute : a)) };
    });
  const move = (i: number, by: -1 | 1) => {
    const j = i + by;
    setDraft((d) => {
      const list = d.attributes.slice();
      const a = list[i] as TaxonomyAttribute;
      list[i] = list[j] as TaxonomyAttribute;
      list[j] = a;
      return { ...d, attributes: list };
    });
    setFresh((f) => new Set([...f].map((n) => (n === i ? j : n === j ? i : n))));
    setOpen((o) => (o === i ? j : o === j ? i : o));
  };
  const remove = (i: number) => {
    setDraft((d) => ({ ...d, attributes: d.attributes.filter((_, j) => j !== i) }));
    setFresh((f) => new Set([...f].filter((n) => n !== i).map((n) => (n > i ? n - 1 : n))));
    setOpen(null);
  };
  const add = () => {
    setDraft((d) => ({ ...d, attributes: [...d.attributes, blankAttribute()] }));
    setFresh((f) => new Set([...f, draft.attributes.length]));
    setOpen(draft.attributes.length);
  };
  const reset = () => {
    setDraft(original);
    setFresh(new Set());
    setOpen(null);
    setServerErrors({});
    setError(null);
    setReason('');
  };

  const disabled = !canEdit || save.isPending;
  return (
    <div className="grid gap-4 lg:grid-cols-2" data-testid="type-editor">
      <form
        noValidate
        className="flex min-w-0 flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (changes.length === 0 || blocked || reasonError || save.isPending) return;
          save.mutate();
        }}
      >
        {!canEdit ? <Notice tone="info" title={t('st.catalog.readOnly')} /> : null}
        <p className="m-0 text-xs text-muted">{t('st.catalog.type.usedBy', { n: type.usedBy.length, products: type.productCount })}</p>
        <TextField
          id="type-label"
          label={t('st.catalog.type.label')}
          value={draft.label}
          maxLength={80}
          disabled={disabled}
          error={changes.includes('label') ? labelError : undefined}
          onChange={(e) => setDraft((d) => ({ ...d, label: e.currentTarget.value }))}
        />
        <Select
          id="type-identifier"
          label={t('st.catalog.type.identifier')}
          options={[
            { value: 'GTIN', label: t('st.catalog.type.gtin') },
            { value: 'ISBN', label: t('st.catalog.type.isbn') }
          ]}
          value={draft.identifierRule}
          disabled={disabled}
          onChange={(e) => setDraft((d) => ({ ...d, identifierRule: e.currentTarget.value as TypeDraft['identifierRule'] }))}
        />
        <fieldset className="m-0 flex flex-col gap-1.5 border-0 p-0">
          <legend className="mb-1 text-sm font-semibold">{t('st.catalog.type.traits')}</legend>
          {TRAITS.map((tr) => (
            <Checkbox
              key={tr.key}
              label={t(tr.label)}
              description={t(tr.help)}
              checked={draft.traits[tr.key]}
              disabled={disabled}
              onChange={(e) => setDraft((d) => ({ ...d, traits: withTrait(d.traits, tr.key, e.currentTarget.checked) }))}
            />
          ))}
          {serverErrors.traits ? <span className="text-xs text-danger">{serverErrors.traits}</span> : null}
        </fieldset>
        <div className="flex items-center gap-2">
          <h3 className="m-0 flex-1 text-sm font-semibold">{t('st.catalog.type.details', { n: draft.attributes.length })}</h3>
          <Button variant="neutral" size="sm" disabled={disabled} icon={<Plus size={14} aria-hidden />} onClick={add}>
            {t('st.catalog.attr.add')}
          </Button>
        </div>
        {errors.attributes ? <p className="m-0 text-xs text-danger">{errors.attributes}</p> : null}
        <ol className="m-0 flex list-none flex-col gap-2 p-0" aria-label={t('st.catalog.type.details', { n: draft.attributes.length })}>
          {draft.attributes.map((a, i) => (
            <AttributeRow
              // The editor owns the list: a row keeps its open state and typing while others move around it.
              key={`${i}-${fresh.has(i) ? 'new' : a.key}`}
              attribute={a}
              index={i}
              total={draft.attributes.length}
              fresh={fresh.has(i)}
              open={open === i}
              disabled={disabled}
              errors={errors}
              onToggle={() => setOpen(open === i ? null : i)}
              onChange={(next, keyTouched) => setAttribute(i, next, keyTouched)}
              onMove={(by) => move(i, by)}
              onRemove={() => remove(i)}
            />
          ))}
        </ol>
        <p className="m-0 text-xs text-muted">{t('st.catalog.type.removeNote')}</p>
        {canEdit ? (
          <>
            <TextField
              id="type-reason"
              label={t('st.catalog.reason')}
              help={t('st.catalog.reasonHelp')}
              value={reason}
              maxLength={1000}
              error={reasonError}
              onChange={(e) => setReason(e.currentTarget.value)}
            />
            {error ? (
              <Notice tone="danger" title={t('st.catalog.notSaved')} role="alert">
                {error}
              </Notice>
            ) : null}
            <div className="flex flex-wrap items-center justify-end gap-2">
              {changes.length > 0 ? <span className="mr-auto text-xs text-muted">{t('st.catalog.type.changed', { what: changes.map((c) => t(`st.catalog.type.field.${c}` as MessageKey)).join(', ') })}</span> : <span className="mr-auto text-xs text-muted">{t('st.catalog.nothingChanged')}</span>}
              <Button variant="neutral" onClick={reset} disabled={changes.length === 0 || save.isPending}>
                {t('st.catalog.discard')}
              </Button>
              <Button type="submit" loading={save.isPending} disabled={changes.length === 0 || blocked}>
                {t('st.catalog.type.save')}
              </Button>
            </div>
          </>
        ) : null}
      </form>
      <div className="min-w-0 lg:sticky lg:top-0 lg:self-start">
        <SellerFormPreview type={preview} />
      </div>
    </div>
  );
}
