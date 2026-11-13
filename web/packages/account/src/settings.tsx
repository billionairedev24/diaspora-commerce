'use client';

import { Button, fieldStyles } from '@afrotransact/ui';
import { type ReactNode, useId } from 'react';

import { useAccountT } from './i18n/t';
import { styles } from './parts';

/** What a control inside a SettingRow needs to be named, described and flagged by the row's label, hint and error. */
export interface SettingControlProps {
  id: string;
  labelId: string;
  'aria-describedby': string | undefined;
  'aria-invalid': true | undefined;
}

/**
 * One setting: label and a short hint on the start side, the control on the end side (stacked on a phone). Pass a
 * function to wire the control to the label; pass plain content (a button) when the row is not a form control.
 * Rows sit in one flat column and are separated by hairlines, in every account tab and in the apps' own rows.
 */
export function SettingRow({ label, hint, error, children, group, end, testId }: {
  label: ReactNode;
  hint?: ReactNode;
  /** Shown under the control, only once there is something to fix. */
  error?: string | null;
  children: ReactNode | ((control: SettingControlProps) => ReactNode);
  /** The control is a group of radios: the label names the group instead of a single input. */
  group?: boolean;
  /** Align the control to the end of its column (switches, buttons). */
  end?: boolean;
  testId?: string;
}) {
  const auto = useId();
  const id = `s${auto.replace(/:/g, '')}`;
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const labelId = `${id}-label`;
  const control: SettingControlProps = {
    id,
    labelId,
    'aria-describedby': [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ') || undefined,
    'aria-invalid': error ? true : undefined
  };
  const wired = typeof children === 'function' && !group;
  return (
    <div className={styles.setting} data-testid={testId}>
      <div className={styles.settingText}>
        {wired ? (
          <label id={labelId} htmlFor={id} className={styles.settingLabel}>
            {label}
          </label>
        ) : (
          <span id={labelId} className={styles.settingLabel}>
            {label}
          </span>
        )}
        {hint ? (
          <span id={hintId} className={styles.settingHint}>
            {hint}
          </span>
        ) : null}
      </div>
      <div className={end ? `${styles.settingControl} ${styles.settingControlEnd}` : styles.settingControl}>
        {typeof children === 'function' ? children(control) : children}
        {error ? (
          <p id={errorId} className={styles.error} role="alert">
            {error}
          </p>
        ) : null}
      </div>
    </div>
  );
}

/** A compact native select for a SettingRow (best keyboard and screen-reader support; the page's one control size). */
export function SettingSelect({ control, value, onChange, options }: {
  control: SettingControlProps;
  value: string;
  onChange: (value: string) => void;
  options: Array<{ value: string; label: string }>;
}) {
  return (
    <select id={control.id} className={fieldStyles.control} value={value} aria-describedby={control['aria-describedby']} aria-invalid={control['aria-invalid']} onChange={(e) => onChange(e.target.value)}>
      {options.map((o) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  );
}

/**
 * A small segmented choice (two or three short options) built on native radios: one tab stop, arrow keys move and
 * select, and the group is named by the row's label.
 */
export function SegmentedToggle<T extends string>({ control, value, onChange, options }: {
  control: SettingControlProps;
  value: T;
  onChange: (value: T) => void;
  options: ReadonlyArray<{ value: T; label: string }>;
}) {
  const name = useId();
  return (
    <div role="radiogroup" aria-labelledby={control.labelId} aria-describedby={control['aria-describedby']} className={styles.segmented}>
      {options.map((o) => (
        <label key={o.value} className={styles.segment}>
          <input type="radio" name={name} value={o.value} checked={value === o.value} onChange={() => onChange(o.value)} />
          <span>{o.label}</span>
        </label>
      ))}
    </div>
  );
}

/**
 * The one Save for a settings form: disabled until something changed, a quiet "Saved" once it went through, and the
 * reason in words when it did not. Place it as the last child of the form.
 */
export function SaveBar({ dirty, saving, saved, error }: { dirty: boolean; saving: boolean; saved: boolean; error?: string | null }) {
  const t = useAccountT();
  return (
    <div className={styles.saveBar}>
      {error ? (
        <p className={styles.error} role="alert">
          {error}
        </p>
      ) : null}
      <span className={styles.saved} role="status">
        {saved && !dirty ? t('common.saved') : ''}
      </span>
      <Button type="submit" loading={saving} disabled={!dirty}>
        {t('common.save')}
      </Button>
    </div>
  );
}
