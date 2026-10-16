'use client';

import { type SellerShipment, type SellerStatusAction } from '@afrotransact/api-client';
import { Button, cx, TextField } from '@afrotransact/ui';
import { CheckCircle, Circle } from '@phosphor-icons/react/ssr';
import { useState } from 'react';

import { useT } from '@/i18n/t';

const STEPS = ['CONFIRMED', 'PACKED', 'READY', 'PICKED_UP', 'DELIVERED'] as const;

/** Where a shipment is on the five steps (-1: just placed); null when it left the path (cancelled, returned, failed). */
function stepOf(status: string): number | null {
  switch (status) {
    case 'PLACED':
      return -1;
    case 'CONFIRMED':
      return 0;
    case 'PACKED':
      return 1;
    case 'READY':
      return 2;
    case 'PICKED_UP':
    case 'IN_TRANSIT':
    case 'OUT_FOR_DELIVERY':
      return 3;
    case 'DELIVERED':
      return 4;
    default:
      return null;
  }
}

interface Props {
  shipment: Pick<SellerShipment, 'status' | 'nextActions' | 'trackingNumber' | 'canBuyLabel' | 'fulfilment'>;
  editable: boolean;
  loading: boolean;
  onMove: (to: SellerStatusAction, handoverCode?: string) => void;
}

/**
 * Where this shipment is (Accepted → Packed → Ready → Shipped → Delivered) and the one thing to do next, in plain words with a
 * line on what it does. "Shipped" waits for a label, since the carrier tracks the parcel from it.
 */
export function ShipmentProgress({ shipment, editable, loading, onMove }: Props) {
  const t = useT();
  const at = stepOf(shipment.status);
  const next = editable ? shipment.nextActions[0] : undefined;
  const needsLabel = next === 'PICKED_UP' && !shipment.trackingNumber;
  const [code, setCode] = useState('');
  const needsCode = next === 'DELIVERED';
  const codeOk = /^\d{4}$/.test(code);
  return (
    <div className="flex flex-col gap-3" data-testid="shipment-progress">
      {at !== null ? (
        <ol className="m-0 flex list-none flex-wrap gap-x-4 gap-y-1 p-0 text-xs" aria-label={t('sc.ship.progress')}>
          {STEPS.map((step, i) => {
            const done = i <= at;
            return (
              <li key={step} aria-current={i === at ? 'step' : undefined} className={cx('flex items-center gap-1', done ? 'font-semibold text-text' : 'text-muted')}>
                {done ? <CheckCircle size={14} weight="fill" aria-hidden /> : <Circle size={14} aria-hidden />}
                {t(`sc.ship.step.${step}`)}
              </li>
            );
          })}
        </ol>
      ) : null}
      {next ? (
        <div className="flex flex-col items-start gap-1">
          {needsCode ? (
            <div className="w-40">
              <TextField label={t('sc.ship.code')} inputMode="numeric" maxLength={4} autoComplete="off" value={code} onChange={(e) => setCode(e.currentTarget.value.replace(/\D/g, ''))} />
            </div>
          ) : null}
          <Button size="sm" loading={loading} disabled={needsLabel || (needsCode && !codeOk)} onClick={() => onMove(next, needsCode ? code : undefined)}>
            {t(`sc.ship.do.${next}`)}
          </Button>
          <span className="text-xs text-muted">{needsLabel ? t('sc.ship.needLabel') : needsCode && !codeOk ? t('sc.ship.needCode') : t(`sc.ship.hint.${next}`)}</span>
        </div>
      ) : null}
    </div>
  );
}
