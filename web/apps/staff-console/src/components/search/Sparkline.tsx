import { cx } from '@afrotransact/ui';

/**
 * A tiny trend line for a daily series, next to a number that is also written out. `label` is its text alternative.
 * Draws nothing for fewer than two days.
 */
export function Sparkline({ values, label, className }: { values: number[]; label: string; className?: string }) {
  if (values.length < 2) return null;
  const w = 96;
  const h = 24;
  const max = Math.max(...values);
  const min = Math.min(...values);
  const span = max - min || 1;
  const points = values.map((v, i) => `${((i / (values.length - 1)) * w).toFixed(1)},${(h - 2 - ((v - min) / span) * (h - 4)).toFixed(1)}`).join(' ');
  return (
    <svg role="img" aria-label={label} viewBox={`0 0 ${w} ${h}`} width={w} height={h} className={cx('text-ink-700', className)}>
      <polyline points={points} fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}
