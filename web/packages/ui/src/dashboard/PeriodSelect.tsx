import { useCallback, useEffect, useState } from 'react';

import { ChipGroup } from '../form/ChipGroup';

export type Period = '7' | '30' | '90';

const PERIODS: readonly Period[] = ['7', '30', '90'];

const isPeriod = (v: unknown): v is Period => typeof v === 'string' && (PERIODS as readonly string[]).includes(v);

/**
 * The dashboard period, remembered per viewer in localStorage. Storage can be missing or throw (private windows, blocked
 * site data): every access is guarded and the default is used. The first render always uses the default so the server
 * and client markup match; the saved choice is applied right after mount.
 */
export function usePeriod(storageKey: string, fallback: Period = '30'): [Period, (p: Period) => void] {
  const [period, setPeriod] = useState<Period>(fallback);
  useEffect(() => {
    try {
      const saved = window.localStorage.getItem(storageKey);
      if (isPeriod(saved)) setPeriod(saved);
    } catch {
      /* storage unavailable: keep the default */
    }
  }, [storageKey]);
  const set = useCallback(
    (p: Period) => {
      setPeriod(p);
      try {
        window.localStorage.setItem(storageKey, p);
      } catch {
        /* not remembered */
      }
    },
    [storageKey]
  );
  return [period, set];
}

export interface PeriodSelectProps {
  value: Period;
  onChange: (p: Period) => void;
  /** Accessible name of the group (translate it). */
  label?: string;
  /** Option labels (translate them); defaults to "7 days", "30 days", "90 days". */
  labels?: Partial<Record<Period, string>>;
}

/** 7 / 30 / 90 days. */
export function PeriodSelect({ value, onChange, label = 'Period', labels }: PeriodSelectProps) {
  return (
    <ChipGroup
      label={label}
      value={value}
      onChange={onChange}
      options={PERIODS.map((p) => ({ value: p, label: labels?.[p] ?? `${p} days` }))}
    />
  );
}
