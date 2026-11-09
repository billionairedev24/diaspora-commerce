import { type ReactNode, useId } from 'react';

import { cx } from '../cx';
import styles from './charts.module.css';

/** Semantic chart colours (all resolved from design tokens in charts.module.css). */
export type ChartTone = 'ink' | 'gold' | 'green' | 'blue' | 'red' | 'grey';

export interface ChartPoint {
  label: string;
  value: number;
  /** Donut slices only; falls back to a fixed sequence. */
  tone?: ChartTone;
}

const SEQUENCE: ChartTone[] = ['ink', 'gold', 'green', 'blue', 'red', 'grey'];

export const defaultFormat = (v: number): string => v.toLocaleString('en-US', { maximumFractionDigits: 2 });

interface ChartBase {
  /** Names the chart for assistive technology ("Sales per day, last 30 days"). Always required: charts have a text alternative. */
  label: string;
  points: readonly ChartPoint[];
  /** Formats a value for the text alternative and the data table (e.g. dollars). */
  format?: (v: number) => string;
  className?: string;
}

/** The accessible twin of a chart: the same numbers as a visually hidden table, read by screen readers. */
function DataTable({ label, points, format = defaultFormat, head = 'Value' }: Pick<ChartBase, 'label' | 'points' | 'format'> & { head?: string }) {
  return (
    <table className="at-visually-hidden">
      <caption>{label}</caption>
      <thead>
        <tr>
          <th scope="col">Item</th>
          <th scope="col">{head}</th>
        </tr>
      </thead>
      <tbody>
        {points.map((p, i) => (
          <tr key={`${p.label}-${i}`}>
            <th scope="row">{p.label}</th>
            <td>{format(p.value)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function summary({ label, points, format = defaultFormat }: ChartBase): string {
  if (!points.length) return label;
  const values = points.map((p) => p.value);
  const last = points[points.length - 1];
  return `${label}. ${points.length} values from ${points[0]?.label} to ${last?.label}, lowest ${format(Math.min(...values))}, highest ${format(Math.max(...values))}, latest ${format(last?.value ?? 0)}.`;
}

export interface SparklineProps extends ChartBase {
  tone?: ChartTone;
}

const W = 160;
const H = 44;
const PAD = 3;

/** A compact line with a soft area and an end marker. No axes: the headline number carries the value. */
export function Sparkline({ label, points, tone = 'ink', format, className }: SparklineProps) {
  const id = useId();
  const values = points.map((p) => p.value);
  const min = Math.min(0, ...values);
  const max = Math.max(...values, min + 1);
  const x = (i: number) => (points.length < 2 ? W / 2 : PAD + (i * (W - PAD * 2)) / (points.length - 1));
  const y = (v: number) => H - PAD - ((v - min) / (max - min)) * (H - PAD * 2);
  const line = points.map((p, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)} ${y(p.value).toFixed(1)}`).join(' ');
  const area = points.length > 1 ? `${line} L${x(points.length - 1).toFixed(1)} ${H} L${x(0).toFixed(1)} ${H} Z` : '';
  const lastP = points[points.length - 1];
  return (
    <figure className={cx(styles.figure, styles[tone], className)} data-chart="sparkline">
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className={styles.svg} role="img" aria-labelledby={id}>
        <title id={id}>{summary({ label, points, format })}</title>
        {area ? <path d={area} className={styles.area} /> : null}
        <path d={line} className={styles.line} fill="none" vectorEffect="non-scaling-stroke" />
        {lastP ? <circle cx={x(points.length - 1)} cy={y(lastP.value)} r={2.5} className={styles.dot} /> : null}
      </svg>
      <DataTable label={label} points={points} format={format} />
    </figure>
  );
}

export interface MiniBarsProps extends ChartBase {
  tone?: ChartTone;
}

/** Small vertical bars (a week, a handful of categories). */
export function MiniBars({ label, points, tone = 'ink', format, className }: MiniBarsProps) {
  const id = useId();
  const max = Math.max(1, ...points.map((p) => p.value));
  const gap = 3;
  const bw = points.length ? (W - gap * (points.length - 1)) / points.length : W;
  return (
    <figure className={cx(styles.figure, styles[tone], className)} data-chart="bars">
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className={styles.svg} role="img" aria-labelledby={id}>
        <title id={id}>{summary({ label, points, format })}</title>
        {points.map((p, i) => {
          const h = Math.max(p.value > 0 ? 2 : 0, (p.value / max) * (H - 2));
          return <rect key={`${p.label}-${i}`} x={i * (bw + gap)} y={H - h} width={bw} height={h} rx={1.5} className={styles.bar} />;
        })}
        <line x1={0} x2={W} y1={H - 0.5} y2={H - 0.5} className={styles.baseline} vectorEffect="non-scaling-stroke" />
      </svg>
      <DataTable label={label} points={points} format={format} />
    </figure>
  );
}

export interface DonutProps extends ChartBase {
  /** Text in the middle of the ring (the total). */
  centre?: ReactNode;
}

const R = 15.9155; // circumference 100: slice lengths are percentages

/** Composition ring with a legend that always names each slice and its value: colour is never the only signal. */
export function Donut({ label, points, format = defaultFormat, centre, className }: DonutProps) {
  const id = useId();
  const slices = points.filter((p) => p.value > 0);
  const total = slices.reduce((s, p) => s + p.value, 0);
  let offset = 0;
  return (
    <figure className={cx(styles.donut, className)} data-chart="donut">
      <div className={styles.ring}>
        <svg viewBox="0 0 42 42" className={styles.donutSvg} role="img" aria-labelledby={id}>
          <title id={id}>{`${label}. Total ${format(total)}. ${slices.map((p) => `${p.label} ${format(p.value)}`).join(', ')}.`}</title>
          <circle cx={21} cy={21} r={R} fill="none" className={styles.track} strokeWidth={5} />
          {total > 0
            ? slices.map((p, i) => {
                const len = (p.value / total) * 100;
                const el = (
                  <circle
                    key={`${p.label}-${i}`}
                    cx={21}
                    cy={21}
                    r={R}
                    fill="none"
                    strokeWidth={5}
                    strokeDasharray={`${Math.max(len - (slices.length > 1 ? 0.8 : 0), 0.2)} ${100 - len}`}
                    strokeDashoffset={25 - offset}
                    className={cx(styles.slice, styles[p.tone ?? SEQUENCE[i % SEQUENCE.length] ?? 'ink'])}
                  />
                );
                offset += len;
                return el;
              })
            : null}
        </svg>
        {centre ? <span className={styles.centre}>{centre}</span> : null}
      </div>
      <ul className={styles.legend} aria-hidden>
        {slices.map((p, i) => (
          <li key={`${p.label}-${i}`}>
            <span className={cx(styles.swatch, styles[p.tone ?? SEQUENCE[i % SEQUENCE.length] ?? 'ink'])} />
            <span className={styles.legendLabel}>{p.label}</span>
            <span className={styles.legendValue}>{format(p.value)}</span>
          </li>
        ))}
      </ul>
      <DataTable label={label} points={slices} format={format} head="Count" />
    </figure>
  );
}
