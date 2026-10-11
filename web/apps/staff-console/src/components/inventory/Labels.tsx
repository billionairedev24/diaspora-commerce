'use client';

import { type InboundShipment, type RetailItem } from '@afrotransact/api-client';
import { Barcode, Button, caseLabelData, gtinValid, sscc } from '@afrotransact/ui';
import { Printer } from '@phosphor-icons/react/ssr';
import { type ReactNode, useEffect, useState } from 'react';
import { createPortal } from 'react-dom';

/** AfroTransact's GS1 company prefix (design/at-inventory-hub.js) for SSCC pallet labels. */
const GS1_PREFIX = '0860012';

/** SSCC of a shipment's pallet: extension 0 + company prefix + 9-digit serial from the shipment number + check digit. */
export function palletSscc(shipmentNumber: string, pallet = 1): string {
  const digits = shipmentNumber.replace(/\D/g, '').slice(-7).padStart(7, '0');
  return sscc(`0${GS1_PREFIX}${digits}${String(pallet).padStart(2, '0')}`);
}

function LabelCard({ title, children, testId }: { title: string; children: ReactNode; testId?: string }) {
  return (
    <figure className="at-label m-0 flex flex-col gap-1.5 rounded-sm border-2 border-ink-1000 bg-surface p-3 text-ink-1000" data-testid={testId}>
      <figcaption className="text-xs font-bold uppercase tracking-wide">{title}</figcaption>
      {children}
    </figure>
  );
}

export interface LabelSet {
  shipment: InboundShipment;
  /** Retail items by SKU (GTIN for the GS1-128 case labels and the EAN-13 product labels). */
  items?: Map<string, RetailItem>;
}

/** Every label of an inbound shipment: pallet (SSCC), boxes (LPN), cases (GS1-128 with lot and expiry), products. */
export function ShipmentLabels({ shipment, items }: LabelSet) {
  const s = shipment;
  return (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,300px),1fr))] gap-3">
      <LabelCard title="Pallet · SSCC (GS1-128)" testId="label-pallet">
        <span className="text-sm">
          Ship to <strong>{s.nodeId}</strong> · ASN {s.number}
        </span>
        <Barcode symbology="gs1-128" value={`(00)${palletSscc(s.number)}`} height={64} />
        <span className="text-xs">{s.expectedUnits} units · {s.lines.length} SKUs · scanned at the dock to receive the pallet against the ASN</span>
      </LabelCard>
      {s.boxes.map((b) => (
        <LabelCard key={b.lpn} title={`Box ${b.boxNo} of ${s.boxes.length} · LPN`} testId="label-box">
          <span className="text-sm">
            {s.number} → {s.nodeId}
          </span>
          <Barcode value={b.lpn} height={56} />
          <span className="text-xs">{b.contents.map((c) => `${c.qty} × ${c.sku}`).join(' · ')}</span>
        </LabelCard>
      ))}
      {s.lines.map((l) => {
        const item = items?.get(l.sku);
        const gtin = item?.gtin && gtinValid(item.gtin) ? item.gtin : null;
        const caseQty = l.casePack ?? item?.casePack ?? null;
        let caseData: string | null = null;
        if (gtin) {
          try {
            caseData = caseLabelData({ gtin, lotNo: l.lotNo, expDate: l.expDate, mfgDate: l.mfgDate });
          } catch {
            caseData = null;
          }
        }
        return (
          <LabelCard key={`case-${l.lineNo}`} title={`Case · ${caseData ? 'GS1-128 lot & expiry' : 'Code 128'}`} testId="label-case">
            <span className="text-sm">
              <strong>{item?.name ?? l.sku}</strong>
              {caseQty ? ` · ${caseQty} units per case · ${l.cases || Math.ceil(l.expectedQty / caseQty)} cases` : ''}
            </span>
            {caseData ? <Barcode symbology="gs1-128" value={caseData} height={64} module={1.5} /> : <Barcode value={l.unitLabel ?? l.sku} height={56} />}
            <span className="text-xs">
              Lot {l.lotNo ?? '—'} · exp {l.expDate ?? '—'} · made {l.mfgDate ?? '—'}
            </span>
          </LabelCard>
        );
      })}
      {s.lines.map((l) => {
        const item = items?.get(l.sku);
        const gtin = item?.gtin && gtinValid(item.gtin) ? item.gtin : null;
        return (
          <LabelCard key={`unit-${l.lineNo}`} title={gtin ? 'Product · retail barcode (EAN-13)' : 'Product · AT-FN unit label'} testId="label-unit">
            <span className="text-sm">{item?.name ?? l.sku}</span>
            {gtin ? <Barcode symbology="ean13" value={gtin} height={56} /> : <Barcode value={l.unitLabel ?? l.sku} height={56} />}
            <span className="text-xs">{gtin ? 'Printed on the pack by the supplier; verified at receiving' : `${l.expectedQty} labels, one per unit (seller-specific)`}</span>
          </LabelCard>
        );
      })}
    </div>
  );
}

/** Prints only `children` (portal under body) and restores the page afterwards. */
export function PrintButton({ children, label = 'Print labels' }: { children: ReactNode; label?: string }) {
  const [printing, setPrinting] = useState(false);
  useEffect(() => {
    if (!printing) return undefined;
    const html = document.documentElement;
    html.classList.add('printing-labels');
    const done = () => {
      html.classList.remove('printing-labels');
      setPrinting(false);
    };
    window.addEventListener('afterprint', done, { once: true });
    const t = window.setTimeout(() => window.print(), 50);
    return () => {
      window.clearTimeout(t);
      window.removeEventListener('afterprint', done);
      html.classList.remove('printing-labels');
    };
  }, [printing]);
  return (
    <>
      <Button variant="neutral" size="sm" icon={<Printer size={16} aria-hidden />} onClick={() => setPrinting(true)}>
        {label}
      </Button>
      {printing ? createPortal(<div className="at-print-root p-4">{children}</div>, document.body) : null}
    </>
  );
}
