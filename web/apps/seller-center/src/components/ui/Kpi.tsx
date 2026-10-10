import { type Kpi as KpiData } from '@afrotransact/api-client';
import { cx } from '@afrotransact/ui';

export function KpiTile({ kpi }: { kpi: KpiData }) {
  return (
    <div className="flex min-w-0 flex-col gap-1 rounded-md border border-border bg-surface p-4">
      <span className="text-xs font-semibold text-muted">{kpi.label}</span>
      <strong className="text-2xl font-bold">{kpi.value}</strong>
      <span className={cx('text-xs', kpi.tone === 'attention' ? 'text-warning' : kpi.tone === 'positive' ? 'text-success' : 'text-muted')}>{kpi.detail}</span>
    </div>
  );
}
