'use client';

import { cx } from '@afrotransact/ui';
import { type ReactNode, useId } from 'react';

interface SwitchBase {
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
  id?: string;
  className?: string;
}

/** The track and knob: the hit area is as tall as every other control (--at-size-control) so a row's centres line up. */
function Track({ checked }: { checked: boolean }) {
  return (
    <span aria-hidden className={cx('relative h-6 w-11 flex-none rounded-pill transition-colors', checked ? 'bg-success' : 'bg-border-strong')}>
      <span className={cx('absolute top-0.5 size-5 rounded-pill bg-surface shadow-sm transition-[inset-inline-start]', checked ? 'start-[22px]' : 'start-0.5')} />
    </span>
  );
}

/** An on/off switch with its name beside it (clicking the name toggles). */
export function Switch({ label, checked, onChange, disabled, id: idProp, className }: SwitchBase & { label: ReactNode }) {
  const auto = useId();
  const id = idProp ?? `sw${auto.replace(/:/g, '')}`;
  return (
    <div className={cx('flex min-h-(--at-size-control) items-center gap-3', className)}>
      <button
        type="button"
        id={id}
        role="switch"
        aria-checked={checked}
        aria-labelledby={`${id}-label`}
        disabled={disabled}
        onClick={() => onChange(!checked)}
        className="inline-flex h-(--at-size-control) flex-none cursor-pointer items-center border-0 bg-transparent p-0 disabled:cursor-not-allowed disabled:opacity-50"
      >
        <Track checked={checked} />
      </button>
      <label id={`${id}-label`} htmlFor={id} className={cx('text-md font-semibold', disabled ? 'cursor-not-allowed text-muted' : 'cursor-pointer')}>
        {label}
      </label>
    </div>
  );
}

/** A switch whose name is spoken but not shown (a day's open/closed toggle in a list that already shows the day). */
export function SwitchOnly({ ariaLabel, checked, onChange, disabled, id, className }: SwitchBase & { ariaLabel: string }) {
  return (
    <button
      type="button"
      id={id}
      role="switch"
      aria-checked={checked}
      aria-label={ariaLabel}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cx('inline-flex h-(--at-size-control) w-11 flex-none cursor-pointer items-center border-0 bg-transparent p-0 disabled:cursor-not-allowed disabled:opacity-50', className)}
    >
      <Track checked={checked} />
    </button>
  );
}
