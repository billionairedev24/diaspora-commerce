import { type Kpi as KpiData } from '@afrotransact/api-client';
import { StatStrip } from '@afrotransact/ui';

/** The dashboard's key figures as one flat strip (shared StatStrip): no tiles, no borders around each figure. */
export function KpiStrip({ kpis, label }: { kpis: readonly KpiData[]; label?: string }) {
  return <StatStrip label={label} items={kpis.map((k) => ({ id: k.label, label: k.label, value: k.value, sub: k.detail, tone: k.tone }))} />;
}
