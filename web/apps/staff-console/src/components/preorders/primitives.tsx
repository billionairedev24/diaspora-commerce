'use client';

import { type StaffPreordersApi } from '@afrotransact/api-client';
import { cx } from '@afrotransact/ui';
import { type ButtonHTMLAttributes, type ReactNode, useMemo } from 'react';

import { staffApi } from '@/lib/api';
import { useStaff } from '@/lib/session';

import { type PreorderAccess, preorderAccess } from './model';

export function usePreordersApi(): StaffPreordersApi {
  return staffApi().preorders;
}

/** The signed-in staff member's Pre-orders access (module level + preorders:* permissions). */
export function usePreorderAccess(): PreorderAccess {
  const { level, hasPermission } = useStaff();
  const lv = level('preorders');
  return useMemo(() => preorderAccess(lv, hasPermission), [lv, hasPermission]);
}

/** On/off switch (the design's pill toggle): role="switch" with aria-checked. */
export function Toggle({ on, label, size = 'md', className, ...rest }: { on: boolean; label: string; size?: 'sm' | 'md' | 'lg' } & Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'role'>) {
  const dims = size === 'lg' ? 'h-[30px] w-14' : size === 'sm' ? 'h-5 w-[34px]' : 'h-[22px] w-10';
  const knob = size === 'lg' ? 'top-1 size-[22px]' : size === 'sm' ? 'top-[3px] size-3.5' : 'top-[3px] size-4';
  const left = size === 'lg' ? (on ? 'left-[30px]' : 'left-1') : size === 'sm' ? (on ? 'left-[17px]' : 'left-[3px]') : on ? 'left-[21px]' : 'left-[3px]';
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={label}
      className={cx('relative flex-none cursor-pointer rounded-pill border-0 disabled:cursor-not-allowed disabled:opacity-45', dims, on ? 'bg-green-600' : 'bg-ink-300', className)}
      {...rest}
    >
      <span aria-hidden className={cx('absolute rounded-pill bg-ink-0 transition-[left]', knob, left)} />
    </button>
  );
}

export function Pill({ className, children, testId }: { className: string; children: ReactNode; testId?: string }) {
  return (
    <span data-testid={testId} className={cx('inline-flex items-center rounded-pill px-2 py-0.5 text-xs font-bold whitespace-nowrap', className)}>
      {children}
    </span>
  );
}

/** Choice chip (categories, late-add items). */
export function Chip({ on, children, ...rest }: { on: boolean; children: ReactNode } & Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'type'>) {
  return (
    <button
      type="button"
      aria-pressed={on}
      className={cx(
        'cursor-pointer rounded-pill border px-3 py-1 text-xs font-semibold',
        on ? 'border-ink-1000 bg-ink-1000 text-ink-0' : 'border-border-strong bg-ink-0 text-ink-800'
      )}
      {...rest}
    >
      {children}
    </button>
  );
}

export const controlClass = 'h-10 rounded-sm border border-border-strong bg-ink-0 px-3 text-sm';
export const selectClass = 'h-10 min-w-0 rounded-sm border border-border-strong bg-ink-0 px-2 text-sm disabled:opacity-45';
export const outlineButton =
  'flex h-10 cursor-pointer items-center gap-2 rounded-md border border-border-strong bg-surface px-3 text-sm font-semibold text-text disabled:cursor-not-allowed disabled:opacity-45';
export const smallButton = 'h-8 cursor-pointer rounded-sm border border-border-strong bg-ink-0 px-3 text-xs font-semibold disabled:cursor-not-allowed disabled:opacity-45';
export const greenButton = 'flex h-10 cursor-pointer items-center gap-1 rounded-md border-0 bg-green-600 px-4 font-bold text-ink-0 disabled:cursor-not-allowed disabled:opacity-45';
export const labelClass = 'flex flex-col gap-1 text-sm font-semibold';
export const smallLabelClass = 'flex flex-col gap-1 text-xs font-semibold text-muted';
