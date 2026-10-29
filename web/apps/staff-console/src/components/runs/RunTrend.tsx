'use client';

import { type RunHistoryRow } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { resolvedVariables } from '@afrotransact/tokens';
import { Bar, BarChart, Cell, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

import { useT } from '@/i18n/t';

const tick = { fontSize: 12, fill: resolvedVariables['--at-color-text-subtle'] };
const tooltip = { contentStyle: { borderRadius: 8, border: `1px solid ${resolvedVariables['--at-color-border']}`, fontSize: 12 }, cursor: { fill: resolvedVariables['--at-ink-50'] } };
const INK = resolvedVariables['--at-ink-800'];
const GREEN = resolvedVariables['--at-green-600'];
const RED = resolvedVariables['--at-red-600'];

interface Point {
  k: string;
  n: number;
}

/** A small column chart, oldest run first; values below zero are drawn in the danger colour. */
function Columns({ title, points, signed, text }: { title: string; points: Point[]; signed: boolean; text: (n: number) => string }) {
  return (
    <section aria-label={title} className="flex min-w-0 flex-col gap-2" data-testid="run-trend-chart">
      <strong className="text-sm">{title}</strong>
      <div className="h-32">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={points} margin={{ top: 4, right: 0, left: 0, bottom: 0 }} barCategoryGap={3}>
            <XAxis dataKey="k" tick={tick} tickLine={false} axisLine={false} interval="preserveStartEnd" />
            <YAxis hide domain={signed ? ['auto', 'auto'] : [0, 'auto']} />
            {signed ? <ReferenceLine y={0} stroke={resolvedVariables['--at-color-border']} /> : null}
            <Tooltip {...tooltip} formatter={(v) => [text(Number(v)), title]} />
            <Bar dataKey="n" radius={[3, 3, 0, 0]} minPointSize={2}>
              {points.map((p) => (
                <Cell key={p.k} fill={signed ? (p.n < 0 ? RED : GREEN) : INK} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
      <table className="at-visually-hidden">
        <caption>{title}</caption>
        <tbody>
          {points.map((p) => (
            <tr key={p.k}>
              <th scope="row">{p.k}</th>
              <td>{text(p.n)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

/**
 * A small trend of the location's runs, oldest first (S265): the orders each run carried and the margin each made
 * (delivery fees kept minus the estimated cost), so staff see whether runs are moving toward break-even. Cancelled runs
 * earn and cost nothing; a run whose route could not be measured has no margin and is left out of the second chart.
 */
export function RunTrend({ rows, currency }: { rows: RunHistoryRow[]; currency: string }) {
  const t = useT();
  const fmt = useFormatters();
  const oldestFirst = [...rows].reverse();
  const label = (r: RunHistoryRow) => fmt.date(r.windowStart, 'short');
  const orders = oldestFirst.map((r) => ({ k: label(r), n: r.orders }));
  const margin = oldestFirst.flatMap((r) => (r.marginMinor === null ? [] : [{ k: label(r), n: r.marginMinor }]));
  return (
    <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,320px),1fr))] gap-5" data-testid="run-trend">
      <Columns title={t('st.runs.runs.history.chart')} points={orders} signed={false} text={(n) => fmt.number(n)} />
      <Columns title={t('st.runs.runs.trend.margin')} points={margin} signed text={(n) => fmt.money(n, currency)} />
    </div>
  );
}
