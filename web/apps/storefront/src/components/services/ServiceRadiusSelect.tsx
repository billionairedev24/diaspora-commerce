'use client';

import { SERVICE_RADIUS_STEPS, type ServiceRadius } from '@afrotransact/api-client';
import { useId } from 'react';

import { useT } from '@/i18n/t';

import styles from './Services.module.css';

/**
 * "Within [25 miles]": how far the shopper will go to a provider's shop. A native select, so keyboard (arrows, type-ahead)
 * and screen readers work as users expect; the label is "Within" and the value reads "25 miles" or "Any distance".
 * The server's category default (for example 20 miles for hair and beauty) is listed too when it is not one of the steps.
 */
export function ServiceRadiusSelect({ value, onChange }: { value: number | 'any'; onChange: (radius: ServiceRadius) => void }) {
  const t = useT();
  const id = useId();
  const current: number | 'any' = value === 0 ? 'any' : value;
  const miles: number[] = [];
  for (const s of SERVICE_RADIUS_STEPS) if (s !== 'any') miles.push(s);
  if (current !== 'any' && !miles.includes(current)) miles.push(current);
  const steps: (number | 'any')[] = [...miles.sort((x, y) => x - y), 'any'];

  return (
    <div className={styles.within}>
      <label htmlFor={id}>{t('svc.radius.within')}</label>
      <select
        id={id}
        value={String(current)}
        onChange={(e) => {
          const next = e.target.value === 'any' ? 'any' : Number(e.target.value);
          const step = SERVICE_RADIUS_STEPS.find((s) => s === next);
          if (step !== undefined) onChange(step);
        }}
      >
        {steps.map((s) => (
          <option key={s} value={String(s)}>
            {s === 'any' ? t('svc.radius.any') : t('svc.radius.miles', { n: s })}
          </option>
        ))}
      </select>
    </div>
  );
}
