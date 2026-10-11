'use client';

import { type GoodsReceipt, inventoryHubQueries, type ReceiptValidation, receiptVariance } from '@afrotransact/api-client';
import { Button, cx, Select, TextField } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { smallButton, todayIso, toneOf, useHub, useHubAccess, useHubMutation } from '@/components/inventory/shared';
import { Card } from '@/components/ui/Card';
import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { money, shortDate } from '@/lib/format';

interface Row {
  lineNo: number;
  poLineNo: number;
  sku: string;
  expected: number;
  asnLot: string | null;
  lotNo: string;
  mfgDate: string;
  expDate: string;
  good: string;
  damaged: string;
  bin: string;
  qa: 'PENDING' | 'PASS' | 'FAIL';
}

const rowsOf = (r: GoodsReceipt): Row[] =>
  r.lines.map((l) => ({
    lineNo: l.lineNo, poLineNo: l.poLineNo, sku: l.sku, expected: l.expectedQty, asnLot: l.asnLotNo ?? null, lotNo: l.lotNo ?? '', mfgDate: l.mfgDate ?? '', expDate: l.expDate ?? '',
    good: l.qtyGood ? String(l.qtyGood) : '', damaged: l.qtyDamaged ? String(l.qtyDamaged) : '', bin: l.bin ?? '', qa: l.qa
  }));

/**
 * A retail goods receipt at the dock (AfroTransact Retail POs): count each batch against the ASN, split mixed batches,
 * record QA and bins, validate (dates, remaining life, over / short), approve over-receipts beyond 2 % (Approve level)
 * and complete — which posts the lots to inventory with landed cost and updates the PO.
 */
export function RetailReceiptScreen({ id }: { id: string }) {
  const api = useHub();
  const { retailEdit, retailApprove } = useHubAccess();
  const q = useQuery(inventoryHubQueries.receipt(api, id));
  const r = q.data;
  const [rows, setRows] = useState<Row[]>([]);
  const [check, setCheck] = useState<ReceiptValidation | null>(null);
  useEffect(() => {
    if (r) setRows(rowsOf(r));
  }, [r?.id, r?.version]); // eslint-disable-line react-hooks/exhaustive-deps
  const set = (i: number, patch: Partial<Row>) => setRows((rs) => rs.map((x, k) => (k === i ? { ...x, ...patch } : x)));
  const save = useHubMutation(
    () =>
      api.retail.countReceipt(
        id,
        rows.map((x) => ({ lineNo: x.lineNo, poLineNo: x.poLineNo, lotNo: x.lotNo.trim() || undefined, mfgDate: x.mfgDate || undefined, expDate: x.expDate || undefined, qtyGood: Number(x.good || 0), qtyDamaged: Number(x.damaged || 0), bin: x.bin.trim() || undefined, qa: x.qa })),
        r?.version
      ),
    (x) => `Counts saved on ${x.number}.`,
    () => setCheck(null)
  );
  const validate = useHubMutation(() => api.retail.validateReceipt(id), (v) => (v.ok ? 'Ready to complete.' : 'Fix the receipt before completing it.'), setCheck);
  const approve = useHubMutation(() => api.retail.approveOverReceipt(id, r?.version), (x) => `Over-receipt approved on ${x.number}.`, () => setCheck(null));
  const complete = useHubMutation(() => api.retail.completeReceipt(id), (x) => `${x.number} completed: ${x.postedLots.length} lots posted to inventory.`);
  if (q.isPending) return <Spinner />;
  if (q.isError || !r) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const open = r.status === 'OPEN' && retailEdit;
  const overBlocked = check?.variances.some((v) => v.overBlocked) ?? false;
  return (
    <Card title={`Goods receipt ${r.number}`}>
      <div className="flex flex-wrap items-center gap-2 text-sm" data-testid="grn">
        <StatusPill tone={toneOf(r.status)}>{r.status === 'OPEN' ? 'Counting' : r.status.toLowerCase()}</StatusPill>
        <span className="text-muted">
          {r.poNumber} · {r.shipmentRef ?? 'no ASN'} · {r.nodeId} · {r.dock}
        </span>
        {r.overApprovedBy ? <StatusPill tone="warn">Over-receipt approved by {r.overApprovedBy}</StatusPill> : null}
      </div>
      {rows.map((x, i) => {
        const v = receiptVariance(x.expected, Number(x.good || 0), Number(x.damaged || 0));
        const lineCheck = check?.lines.find((l) => l.lineNo === x.lineNo);
        const expired = x.expDate !== '' && x.expDate <= todayIso();
        return (
          <fieldset key={x.lineNo} className="m-0 flex flex-col gap-2 rounded-md border border-border p-3" aria-label={`Receipt line ${x.lineNo}`} data-testid="grn-line">
            <legend className="px-1 text-sm font-semibold">
              Line {x.lineNo} · <span className="font-mono">{x.sku}</span> · expected {x.expected}
              {x.asnLot ? <span className="text-xs text-muted"> · ASN lot {x.asnLot}</span> : null}
            </legend>
            <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,120px),1fr))] gap-2">
              <TextField label="Good" inputMode="numeric" value={x.good} disabled={!open} onChange={(e) => set(i, { good: e.target.value })} />
              <TextField label="Damaged" inputMode="numeric" value={x.damaged} disabled={!open} onChange={(e) => set(i, { damaged: e.target.value })} />
              <TextField label="Lot / batch" value={x.lotNo} disabled={!open} onChange={(e) => set(i, { lotNo: e.target.value })} />
              <TextField label="Made on" type="date" value={x.mfgDate} disabled={!open} onChange={(e) => set(i, { mfgDate: e.target.value })} />
              <TextField label="Expires on" type="date" value={x.expDate} disabled={!open} onChange={(e) => set(i, { expDate: e.target.value })} error={expired ? 'Expired: reject this batch' : undefined} />
              <TextField label="Bin" value={x.bin} disabled={!open} onChange={(e) => set(i, { bin: e.target.value })} />
              <Select label="QA" value={x.qa} disabled={!open} onChange={(e) => set(i, { qa: e.target.value as Row['qa'] })} options={[{ value: 'PENDING', label: 'Pending' }, { value: 'PASS', label: 'Pass' }, { value: 'FAIL', label: 'Fail (quarantine)' }]} />
            </div>
            <span className={cx('text-sm font-semibold', v.kind === 'match' ? 'text-success' : v.kind === 'overBlocked' ? 'text-danger' : 'text-warning')} data-testid="grn-variance">
              {v.kind === 'match' ? 'Matches' : v.kind === 'short' ? `Short ${-v.diff} (a claim is raised)` : v.kind === 'over' ? `Over ${v.diff} (within 2 %)` : `Over ${v.diff}: needs purchasing approval`}
            </span>
            {lineCheck?.errors.map((e) => <span key={e} role="alert" className="text-sm text-danger">{e}</span>)}
            {lineCheck?.warnings.map((w) => <span key={w} className="text-sm text-warning">{w}</span>)}
            {open ? (
              <button
                type="button"
                className={cx(smallButton, 'self-start')}
                onClick={() => setRows((rs) => [...rs, { ...x, lineNo: Math.max(...rs.map((y) => y.lineNo)) + 1, lotNo: '', good: '', damaged: '', mfgDate: '', expDate: '' }])}
              >
                Split: another batch of this line
              </button>
            ) : null}
          </fieldset>
        );
      })}
      {check?.receiptErrors.map((e) => <p key={e} role="alert" className="m-0 text-sm text-danger">{e}</p>)}
      {open ? (
        <div className="flex flex-wrap gap-2">
          <Button variant="neutral" loading={save.isPending} onClick={() => save.mutate(undefined)}>
            Save counts
          </Button>
          <Button variant="neutral" loading={validate.isPending} onClick={() => validate.mutate(undefined)}>
            Validate
          </Button>
          {overBlocked && retailApprove ? (
            <Button variant="secondary" loading={approve.isPending} onClick={() => approve.mutate(undefined)}>
              Approve over-receipt
            </Button>
          ) : null}
          <Button loading={complete.isPending} disabled={check !== null && !check.ok} onClick={() => complete.mutate(undefined)}>
            Complete receipt
          </Button>
        </div>
      ) : null}
      {r.postedLots.length ? (
        <div className="flex flex-col gap-1 text-sm" data-testid="posted-lots">
          <strong>Lots posted to inventory</strong>
          {r.postedLots.map((l) => (
            <span key={l.lotId}>
              <span className="font-mono">{l.lotNo}</span> · {l.sku} · {l.qty} units · {l.status.toLowerCase()} · landed {money(l.unitLandedCostMinor)} / unit
            </span>
          ))}
        </div>
      ) : null}
      {r.discrepancies.length ? (
        <div className="flex flex-col gap-1 text-sm">
          <strong>Claims</strong>
          {r.discrepancies.map((d) => (
            <span key={d.claimRef}>
              {d.claimRef} · {d.type.toLowerCase()} {d.qty} of {d.sku} · {d.status.toLowerCase()}
            </span>
          ))}
        </div>
      ) : null}
      <span className="text-xs text-muted">Opened {shortDate(r.openedAt)} by {r.openedBy ?? '—'}</span>
    </Card>
  );
}
