'use client';

import { PREORDER_CHANNEL_LABEL, type PreorderSummary } from '@afrotransact/api-client/staff-preorders';
import { resolvedVariables } from '@afrotransact/tokens';
import { Bar, BarChart, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

import { fS, num } from './model';

const tick = { fontSize: 10, fill: resolvedVariables['--at-color-text-subtle'] };
const tooltip = { contentStyle: { borderRadius: 8, border: `1px solid ${resolvedVariables['--at-color-border']}`, fontSize: 12 }, cursor: { fill: resolvedVariables['--at-ink-50'] } };
const GREEN = resolvedVariables['--at-green-600'];
const GOLD = resolvedVariables['--at-gold-500'];
const INK = resolvedVariables['--at-ink-800'];

function HiddenTable({ caption, headers, rows }: { caption: string; headers: string[]; rows: (string | number)[][] }) {
  return (
    <table className="at-visually-hidden">
      <caption>{caption}</caption>
      <thead>
        <tr>
          {headers.map((h) => (
            <th key={h} scope="col">
              {h}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((r, i) => (
          <tr key={i}>
            {r.map((v, j) => (
              <td key={j}>{v}</td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** Pre-orders per day; days after the closing date (late adds) in the gold token. */
export function ByDayChart({ days }: { days: PreorderSummary['byDay'] }) {
  const every = Math.max(1, Math.ceil(days.length / 7));
  const data = days.map((d, i) => ({ label: fS(d.date), tick: i % every === 0 ? fS(d.date) : '', count: d.count, afterClose: d.afterClose }));
  return (
    <section aria-label="Pre-orders per day" className="flex min-w-0 flex-col gap-3">
      <strong className="text-sm">Pre-orders per day</strong>
      {!days.length ? <span className="text-sm text-muted">No orders yet. The window hasn&apos;t opened.</span> : null}
      <div className="h-40">
        {days.length ? (
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} margin={{ top: 4, right: 0, left: 0, bottom: 0 }} barCategoryGap={2}>
              <XAxis dataKey="label" tick={tick} tickLine={false} axisLine={false} interval={every - 1} />
              <YAxis hide allowDecimals={false} />
              <Tooltip {...tooltip} formatter={(v) => [String(v), 'Pre-orders']} />
              <Bar dataKey="count" radius={[3, 3, 0, 0]} minPointSize={2}>
                {data.map((d) => (
                  <Cell key={d.label} fill={d.afterClose ? GOLD : GREEN} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        ) : null}
      </div>
      <HiddenTable caption="Pre-orders per day" headers={['Day', 'Pre-orders', 'After close']} rows={data.map((d) => [d.label, d.count, d.afterClose ? 'yes' : 'no'])} />
    </section>
  );
}

/** Horizontal bars with the value at the end (by channel, top products, fulfilment split). */
export function HBars({ title, rows, color, labelWidth = 130, valueText }: { title: string; rows: { k: string; n: number }[]; color: 'green' | 'gold' | 'ink'; labelWidth?: number; valueText?: (n: number) => string }) {
  const fill = color === 'green' ? GREEN : color === 'gold' ? GOLD : INK;
  const fmt = valueText ?? ((n: number) => num(n));
  return (
    <section aria-label={title} className="flex min-w-0 flex-col gap-2">
      <strong className="text-sm">{title}</strong>
      {rows.length ? (
        <div style={{ height: Math.max(40, rows.length * 22) }}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={rows} layout="vertical" margin={{ top: 0, right: 44, left: 0, bottom: 0 }} barCategoryGap={6}>
              <XAxis type="number" hide domain={[0, 'dataMax']} />
              <YAxis type="category" dataKey="k" width={labelWidth} tick={{ ...tick, fontSize: 11, fill: resolvedVariables['--at-color-text'] }} tickLine={false} axisLine={false} />
              <Tooltip {...tooltip} formatter={(v) => [fmt(Number(v)), title]} />
              <Bar dataKey="n" fill={fill} radius={[5, 5, 5, 5]} barSize={10} background={{ fill: resolvedVariables['--at-ink-100'], radius: 5 }} label={{ position: 'right', fontSize: 11, fontWeight: 700, formatter: (v: unknown) => fmt(Number(v)) }} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      ) : (
        <span className="text-sm text-muted">Nothing yet.</span>
      )}
      <HiddenTable caption={title} headers={['', 'Value']} rows={rows.map((r) => [r.k, fmt(r.n)])} />
    </section>
  );
}

export const channelRows = (s: PreorderSummary) => s.byChannel.map((c) => ({ k: PREORDER_CHANNEL_LABEL[c.channel], n: c.count }));
