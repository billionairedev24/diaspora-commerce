'use client';

import { BRAND_PALETTE, buttonInk, FALLBACK_BRAND_HEX, normaliseHex, swatchOf } from '@afrotransact/tokens';
import { type ReactNode, useEffect, useId, useRef, useState } from 'react';

import { cx } from '../cx';
import styles from './BrandColourPicker.module.css';

/** Every word the picker shows, so each app supplies its own language (the ui package has no catalogue). */
export interface BrandColourLabels {
  /** Name of the group, e.g. "Brand colour". */
  label: string;
  /** Marks the field optional, e.g. "(optional)". Empty string hides it. */
  optional: string;
  help: string;
  /** The "no colour chosen" option and what it means. */
  defaultName: string;
  defaultHelp: string;
  custom: string;
  /** Names of the native picker and of the hex text field. */
  picker: string;
  hex: string;
  hexHelp: string;
  invalid: string;
  preview: string;
  previewButton: string;
  /** "Selected: {name}" read out and shown below the options. */
  selected: (name: string, hex: string) => string;
  /** Translated swatch names by palette id; the English name is the fallback. */
  names: Readonly<Record<string, string>>;
}

export const DEFAULT_BRAND_COLOUR_LABELS: BrandColourLabels = {
  label: 'Brand colour',
  optional: '(optional)',
  help: 'Used for your buttons and accents. Not sure? Leave it as the platform default.',
  defaultName: 'Platform default',
  defaultHelp: 'AfroTransact’s own colour',
  custom: 'Custom',
  picker: 'Pick a custom colour',
  hex: 'Hex code',
  hexHelp: 'For example 1A7F4B',
  invalid: 'Enter a hex code: 3 or 6 letters and digits, such as 1A7F4B (the # is optional)',
  preview: 'Preview',
  previewButton: 'Button',
  selected: (name, hex) => (hex ? `Selected: ${name} (${hex})` : `Selected: ${name}`),
  names: {}
};

export interface BrandColourPickerProps {
  /** #rrggbb, or '' for the platform default. A value that is not a colour is shown as an error. */
  value: string;
  /** Called with a normalised lower-case #rrggbb, '' (default) or, while the hex field holds nonsense, that text. */
  onChange: (value: string) => void;
  labels?: Partial<BrandColourLabels>;
  /** Message from the server or the form, shown under the control. */
  error?: string | null;
  disabled?: boolean;
  id?: string;
  className?: string;
}

function Tick() {
  return (
    <svg className={styles.tick} viewBox="0 0 16 16" aria-hidden focusable="false">
      <path d="M3 8.5l3.2 3L13 4.5" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

/**
 * One brand-colour control for every place a seller or provider picks the colour of their public page: curated named
 * swatches (radio group: arrow keys, visible focus, names beside the colour), a Custom option with the native colour
 * picker and a hex field that stay in sync, and a preview of a button on the choice with text that always reads. The
 * default is "no colour" and is shown as such.
 */
export function BrandColourPicker({ value, onChange, labels: given, error, disabled, id, className }: BrandColourPickerProps) {
  const l: BrandColourLabels = { ...DEFAULT_BRAND_COLOUR_LABELS, ...given };
  const auto = useId().replace(/:/g, '');
  const base = id ?? `bc${auto}`;
  const normal = normaliseHex(value);
  const [custom, setCustom] = useState(() => value.trim() !== '' && !swatchOf(value));
  const [draft, setDraft] = useState(value);

  // The form changed the value from outside (discard, reload): follow it. Our own changes are not "outside".
  const emitted = useRef(value);
  useEffect(() => {
    if (value === emitted.current) return;
    emitted.current = value;
    setDraft(value);
    setCustom(value.trim() !== '' && !swatchOf(value));
  }, [value]);

  const nameOf = (id: string, fallback: string) => l.names[id] ?? fallback;
  const swatch = custom ? undefined : swatchOf(value);
  const group = custom ? 'custom' : swatch ? swatch.id : 'default';

  const emit = (next: string) => {
    emitted.current = next;
    onChange(next);
  };

  const choose = (next: string, fromCustom = false) => {
    setCustom(fromCustom);
    setDraft(next);
    emit(next);
  };

  const typed = (text: string) => {
    setDraft(text);
    emit(normaliseHex(text) ?? text.trim());
  };

  const invalid = custom && draft.trim() !== '' && normaliseHex(draft) === null;
  const message: ReactNode = error ?? (invalid ? l.invalid : null);
  const previewBg = normal;
  const ink = previewBg ? buttonInk(previewBg).ink : null;
  const currentName = custom ? l.custom : swatch ? nameOf(swatch.id, swatch.name) : l.defaultName;
  const msgId = `${base}-msg`;
  const radioName = `${base}-colour`;

  return (
    <fieldset className={cx(styles.root, className)} id={base} disabled={disabled} data-at-field="">
      <legend className={styles.legend}>
        {l.label}
        {l.optional ? <span className={styles.optional}> {l.optional}</span> : null}
      </legend>
      <p className={styles.help} id={`${base}-help`}>
        {l.help}
      </p>
      <div role="radiogroup" aria-label={l.label} aria-describedby={`${base}-help`} className={styles.grid}>
        {/* eslint-disable-next-line jsx-a11y/label-has-associated-control -- the radio is inside; its name is the text of the chip span */}
        <label className={styles.option}>
          <input type="radio" name={radioName} className={styles.input} checked={group === 'default'} onChange={() => choose('')} />
          <span className={styles.chip}>
            <span className={cx(styles.swatch, styles.swatchDefault)} aria-hidden>
              {group === 'default' ? <Tick /> : null}
            </span>
            <span className={styles.name}>
              {l.defaultName}
              <span className="at-visually-hidden">. {l.defaultHelp}</span>
            </span>
          </span>
        </label>
        {BRAND_PALETTE.map((s) => (
          // eslint-disable-next-line jsx-a11y/label-has-associated-control -- the radio is inside; its name is the text of the chip span
          <label key={s.id} className={styles.option}>
            <input type="radio" name={radioName} className={styles.input} checked={group === s.id} onChange={() => choose(s.hex)} />
            <span className={styles.chip}>
              <span className={styles.swatch} style={{ background: s.hex, color: buttonInk(s.hex).ink }} aria-hidden>
                {group === s.id ? <Tick /> : null}
              </span>
              <span className={styles.name}>{nameOf(s.id, s.name)}</span>
            </span>
          </label>
        ))}
        {/* eslint-disable-next-line jsx-a11y/label-has-associated-control -- the radio is inside; its name is the text of the chip span */}
        <label className={styles.option}>
          <input type="radio" name={radioName} className={styles.input} checked={group === 'custom'} onChange={() => choose(normal ?? '', true)} />
          <span className={styles.chip}>
            <span className={cx(styles.swatch, styles.swatchCustom)} aria-hidden>
              {group === 'custom' ? <Tick /> : null}
            </span>
            <span className={styles.name}>{l.custom}</span>
          </span>
        </label>
      </div>

      {custom ? (
        <div className={styles.custom}>
          <div className={styles.pickerWrap}>
            { }
            <label htmlFor={`${base}-picker`} className={styles.small}>
              {l.picker}
            </label>
            <input
              id={`${base}-picker`}
              type="color"
              className={styles.native}
              value={normal ?? FALLBACK_BRAND_HEX}
              onChange={(e) => {
                setDraft(e.target.value.toLowerCase());
                emit(e.target.value.toLowerCase());
              }}
            />
          </div>
          <div className={styles.hexWrap}>
            { }
            <label htmlFor={`${base}-hex`} className={styles.small}>
              {l.hex}
            </label>
            <input
              id={`${base}-hex`}
              type="text"
              dir="ltr"
              inputMode="text"
              autoCapitalize="off"
              autoComplete="off"
              spellCheck={false}
              maxLength={9}
              className={styles.hex}
              value={draft}
              placeholder={l.hexHelp}
              aria-invalid={message ? true : undefined}
              aria-describedby={message ? msgId : undefined}
              onChange={(e) => typed(e.target.value)}
              onBlur={() => {
                const n = normaliseHex(draft);
                if (n) setDraft(n);
              }}
            />
          </div>
        </div>
      ) : null}
      <p id={msgId} className={styles.error} aria-live="polite">
        {message}
      </p>

      <div className={styles.previewRow}>
        <span className={styles.previewLabel}>{l.preview}</span>
        <span
          data-testid="brand-colour-preview"
          className={styles.previewButton}
          style={previewBg && ink ? { background: previewBg, color: ink } : { background: 'var(--at-color-action-primary)', color: 'var(--at-color-action-primary-text)' }}
        >
          {l.previewButton}
        </span>
        <span className={styles.previewState} role="status">
          {l.selected(currentName, normal ?? '')}
        </span>
      </div>
    </fieldset>
  );
}
