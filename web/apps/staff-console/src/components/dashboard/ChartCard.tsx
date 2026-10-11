'use client';

import { type ChartSpec } from '@afrotransact/api-client';
import { resolvedVariables } from '@afrotransact/tokens';
import { Menu, MenuItem, useToast } from '@afrotransact/ui';
import { CaretDown, CircleNotch, DownloadSimple, FileCsv, FilePdf, MicrosoftExcelLogo } from '@phosphor-icons/react/ssr';
import { useRef, useState } from 'react';
import { Area, Bar, BarChart, CartesianGrid, Cell, ComposedChart, Legend, Line, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

import { useT } from '@/i18n/t';
import { toCsv } from '@/lib/export/csv';
import { toPdf } from '@/lib/export/pdf';
import { download, sliceColour, slug, tabular, toneColour } from '@/lib/export/table';
import { toXlsx } from '@/lib/export/xlsx';
import { dollars } from '@/lib/format';

const tick = { fontSize: 12, fill: resolvedVariables['--at-color-text-muted'] };
const gridStroke = resolvedVariables['--at-color-border'];
const tooltip = { contentStyle: { borderRadius: 8, border: `1px solid ${resolvedVariables['--at-color-border']}`, fontSize: 12 } };

const compactMoney = (v: number) => (v >= 1000 ? `$${(v / 1000).toFixed(1)}k` : `$${v}`);

function Plot({ spec }: { spec: ChartSpec }) {
  const rows = spec.rows.map((r) => ({ category: r.category, ...r.values }));
  const fmt = spec.valueFormat === 'money' ? compactMoney : undefined;
  const s0 = spec.series[0];
  const s1 = spec.series[1];
  switch (spec.kind) {
    case 'trend':
      return (
        <ComposedChart data={rows} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
          <CartesianGrid stroke={gridStroke} vertical={false} />
          <XAxis dataKey="category" tick={tick} interval="preserveStartEnd" minTickGap={24} tickLine={false} axisLine={false} />
          <YAxis yAxisId="l" tick={tick} width={52} axisLine={false} tickLine={false} tickFormatter={s0?.format === 'money' ? compactMoney : undefined} />
          {s1 ? <YAxis yAxisId="r" orientation="right" tick={tick} width={44} axisLine={false} tickLine={false} tickFormatter={s1.format === 'money' ? compactMoney : undefined} /> : null}
          <Tooltip {...tooltip} />
          <Legend iconType="circle" wrapperStyle={{ fontSize: 12 }} />
          {s0 ? <Area yAxisId="l" type="monotone" dataKey={s0.key} name={s0.label} stroke={toneColour(s0.tone)} fill={toneColour(s0.tone)} fillOpacity={0.08} strokeWidth={2} /> : null}
          {s1 ? <Line yAxisId="r" type="monotone" dataKey={s1.key} name={s1.label} stroke={resolvedVariables['--at-gold-700']} strokeWidth={2} dot={false} /> : null}
        </ComposedChart>
      );
    case 'hbar':
      return (
        <BarChart data={rows} layout="vertical" margin={{ top: 4, right: 16, left: 8, bottom: 0 }}>
          <CartesianGrid stroke={gridStroke} horizontal={false} />
          <XAxis type="number" tick={tick} tickFormatter={fmt} axisLine={false} tickLine={false} />
          <YAxis type="category" dataKey="category" tick={tick} width={120} axisLine={false} tickLine={false} />
          <Tooltip {...tooltip} />
          {s0 ? <Bar dataKey={s0.key} name={s0.label} fill={toneColour(s0.tone)} radius={[0, 4, 4, 0]} barSize={18} /> : null}
        </BarChart>
      );
    case 'donut':
      return (
        <PieChart>
          <Pie data={spec.rows.map((r) => ({ name: r.category, value: s0 ? (r.values[s0.key] ?? 0) : 0 }))} dataKey="value" nameKey="name" innerRadius="55%" outerRadius="85%" paddingAngle={2}>
            {spec.rows.map((r, i) => (
              <Cell key={r.category} fill={sliceColour(i)} />
            ))}
          </Pie>
          <Tooltip {...tooltip} />
          <Legend layout="vertical" align="right" verticalAlign="middle" iconType="circle" wrapperStyle={{ fontSize: 12 }} />
        </PieChart>
      );
    case 'stack':
      return (
        <BarChart data={rows} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
          <CartesianGrid stroke={gridStroke} vertical={false} />
          <XAxis dataKey="category" tick={tick} axisLine={false} tickLine={false} />
          <YAxis tick={tick} width={52} tickFormatter={fmt} axisLine={false} tickLine={false} />
          <Tooltip {...tooltip} />
          <Legend iconType="circle" wrapperStyle={{ fontSize: 12 }} />
          {spec.series.map((s, i) => (
            <Bar key={s.key} dataKey={s.key} name={s.label} stackId="a" fill={toneColour(s.tone)} radius={i === spec.series.length - 1 ? [4, 4, 0, 0] : 0} />
          ))}
        </BarChart>
      );
  }
}

type Format = 'pdf' | 'xlsx' | 'csv';

/** One dashboard chart with its Export menu: PDF (chart + table), Excel (data + native chart) and CSV (raw data). */
export function ChartCard({ spec }: { spec: ChartSpec }) {
  const tr = useT();
  const ref = useRef<HTMLDivElement>(null);
  const toast = useToast();
  const [busy, setBusy] = useState<Format | null>(null);
  const run = async (f: Format) => {
    setBusy(f);
    try {
      const t = tabular(spec);
      const name = slug(spec.title);
      if (f === 'csv') download(new Blob([toCsv(t)], { type: 'text/csv;charset=utf-8' }), `${name}.csv`);
      if (f === 'xlsx') download(new Blob([toXlsx(spec)], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }), `${name}.xlsx`);
      if (f === 'pdf') download(await toPdf(t, ref.current?.querySelector('svg.recharts-surface') ?? null), `${name}.pdf`);
    } catch {
      toast.show(tr('st.chartCard.exportFailed'), 'error');
    } finally {
      setBusy(null);
    }
  };
  const t = tabular(spec);
  return (
    <section aria-label={spec.title} className="flex min-w-0 flex-col gap-3 rounded-md border border-border bg-surface p-4">
      <div className="flex items-center gap-2">
        <h2 className="m-0 flex-1 text-md font-semibold">{spec.title}</h2>
        {spec.available ? (
          <Menu
            align="end"
            width={220}
            label={`Export ${spec.title}`}
            trigger={
              <button type="button" className="flex h-[30px] items-center gap-1.5 rounded-pill border border-border-strong bg-surface px-3 text-xs font-semibold hover:bg-surface-hover" aria-busy={busy !== null || undefined}>
                {busy ? <CircleNotch size={14} aria-hidden className="animate-spin" /> : <DownloadSimple size={14} aria-hidden />}
                {busy ? 'Exporting…' : 'Export'}
                <CaretDown size={12} aria-hidden />
              </button>
            }
          >
            <MenuItem icon={<FilePdf size={16} aria-hidden />} onSelect={() => void run('pdf')}>
              {tr('st.chartCard.pdfChartTable')}
            </MenuItem>
            <MenuItem icon={<MicrosoftExcelLogo size={16} aria-hidden />} onSelect={() => void run('xlsx')}>
              {tr('st.chartCard.excelDataLiveChart')}
            </MenuItem>
            <MenuItem icon={<FileCsv size={16} aria-hidden />} onSelect={() => void run('csv')}>
              {tr('st.chartCard.csvRawData')}
            </MenuItem>
          </Menu>
        ) : null}
      </div>
      {spec.available ? (
        <div ref={ref} className="h-60">
          <ResponsiveContainer width="100%" height="100%">
            <Plot spec={spec} />
          </ResponsiveContainer>
        </div>
      ) : (
        <div className="grid h-60 place-items-center rounded-md bg-surface-muted px-6 text-center text-sm text-muted">{tr('st.chartCard.thisChartAppearsOnceIts')}</div>
      )}
      <table className="at-visually-hidden">
        <caption>{spec.title}</caption>
        <thead>
          <tr>
            {t.headers.map((h) => (
              <th key={h} scope="col">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {t.rows.map((r) => (
            <tr key={String(r[0])}>
              {r.map((v, i) => (
                <td key={t.headers[i]}>{typeof v === 'number' && spec.valueFormat === 'money' && i > 0 ? dollars(v) : v}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
