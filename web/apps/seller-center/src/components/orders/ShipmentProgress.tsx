'use client';

import { type SellerShipDetails, type SellerShipment, type SellerStatusAction } from '@afrotransact/api-client';
import { Button, cx, TextField } from '@afrotransact/ui';
import { CheckCircle, Circle } from '@phosphor-icons/react/ssr';
import { useState } from 'react';

import { type MessageKey, useT } from '@/i18n/t';

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

/** What a seller reads while AfroTransact holds the shipment (read-only): the title says who has it, the help what they need to do. */
function afroNoteKey(status: string, mode: string): { title: MessageKey; help: MessageKey } | null {
  switch (status) {
    case 'READY':
      return mode === 'afrotransact_delivery' ? { title: 'sc.ship.afro.picksUp', help: 'sc.ship.afro.picksUpHelp' } : null;
    case 'PICKED_UP':
    case 'IN_TRANSIT':
    case 'OUT_FOR_DELIVERY':
      return { title: 'sc.ship.afro.hasIt', help: 'sc.ship.afro.hasItHelp' };
    case 'DELIVERY_FAILED':
      return { title: 'sc.ship.afro.failed', help: 'sc.ship.afro.failedHelp' };
    default:
      return null;
  }
}

interface Props {
  shipment: Pick<SellerShipment, 'status' | 'nextActions' | 'trackingNumber' | 'canBuyLabel' | 'fulfilment' | 'fulfilmentMode' | 'needsShipDetails'>;
  editable: boolean;
  loading: boolean;
  onMove: (to: SellerStatusAction, handoverCode?: string, ship?: SellerShipDetails) => void;
}

/**
 * Where this shipment is (Accepted → Packed → Ready → Shipped → Delivered) and the one thing to do next, in plain words with a
 * line on what it does. "Shipped" waits for a label, since the carrier tracks the parcel from it.
 */
export function ShipmentProgress({ shipment, editable, loading, onMove }: Props) {
  const t = useT();
  const at = stepOf(shipment.status);
  const next = editable ? shipment.nextActions[0] : undefined;
  // You ship it yourself (no carrier label): say how it left, with a tracking number or in words.
  const ownShipping = next === 'PICKED_UP' && shipment.needsShipDetails;
  const needsLabel = next === 'PICKED_UP' && !shipment.trackingNumber && !ownShipping;
  const [code, setCode] = useState('');
  const [tracking, setTracking] = useState('');
  const [courier, setCourier] = useState('');
  const [note, setNote] = useState('');
  const detailsOk = !ownShipping || tracking.trim() !== '' || note.trim() !== '';
  // Only a pickup order waiting at the shop is handed over against the buyer's code; a shipped parcel is just confirmed.
  const needsCode = next === 'DELIVERED' && shipment.status === 'READY';
  const hint =
    next === 'DELIVERED' && !needsCode
      ? t('sc.ship.hint.DELIVERED_SHIPPED')
      : next === 'READY' && shipment.fulfilmentMode === 'afrotransact_delivery'
        ? t('sc.ship.hint.READY_ATD')
        : next === 'READY' && shipment.fulfilmentMode === 'seller_ships'
          ? t('sc.ship.hint.READY_OWN')
          : next === 'PICKED_UP' && ownShipping
            ? t('sc.ship.hint.PICKED_UP_OWN')
            : next
              ? t(`sc.ship.hint.${next}`)
              : '';
  const doLabel = next === 'DELIVERED' && !needsCode ? t('sc.ship.do.DELIVERED_SHIPPED') : next ? t(`sc.ship.do.${next}`) : '';
  const codeOk = /^\d{4}$/.test(code);
  // Fulfilled by AfroTransact: AfroTransact collects the parcel and delivers it, so the seller only sees where it is. Never a
  // button here, whatever the role: AfroTransact staff move it with the delivery steps (docs/engineering/delivery-fulfilment.md).
  const byAfroTransact = shipment.fulfilmentMode === 'afrotransact_delivery' || shipment.fulfilmentMode === 'fulfilled_by_afrotransact';
  const afroNote = byAfroTransact && !next ? afroNoteKey(shipment.status, shipment.fulfilmentMode) : null;
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
      {afroNote ? (
        <p className="m-0 text-sm" data-testid="afrotransact-note">
          <strong>{t(afroNote.title)}</strong> <span className="text-muted">{t(afroNote.help)}</span>
        </p>
      ) : null}
      {next ? (
        <div className="flex flex-col items-start gap-1">
          {needsCode ? (
            <div className="w-40">
              <TextField label={t('sc.ship.code')} inputMode="numeric" maxLength={4} autoComplete="off" value={code} onChange={(e) => setCode(e.currentTarget.value.replace(/\D/g, ''))} />
            </div>
          ) : null}
          {ownShipping ? (
            <div className="flex w-full max-w-sm flex-col gap-2" data-testid="ship-details">
              <TextField label={t('sc.ship.tracking')} help={t('sc.ship.trackingHelp')} maxLength={80} autoComplete="off" value={tracking} onChange={(e) => setTracking(e.currentTarget.value.replace(/\s/g, ''))} />
              {tracking ? <TextField label={t('sc.ship.courier')} help={t('sc.ship.courierHelp')} maxLength={40} autoComplete="off" value={courier} onChange={(e) => setCourier(e.currentTarget.value)} /> : null}
              <TextField label={t('sc.ship.sentHow')} help={t('sc.ship.sentHowHelp')} maxLength={500} autoComplete="off" value={note} onChange={(e) => setNote(e.currentTarget.value)} />
            </div>
          ) : null}
          <Button
            size="sm"
            loading={loading}
            disabled={needsLabel || (needsCode && !codeOk) || !detailsOk}
            onClick={() => onMove(next, needsCode ? code : undefined, ownShipping ? { trackingNumber: tracking.trim() || undefined, trackingCarrier: courier.trim() || undefined, shipNote: note.trim() || undefined } : undefined)}
          >
            {doLabel}
          </Button>
          <span className="text-xs text-muted">{needsLabel ? t('sc.ship.needLabel') : needsCode && !codeOk ? t('sc.ship.needCode') : ownShipping && !detailsOk ? t('sc.ship.needDetails') : hint}</span>
        </div>
      ) : null}
    </div>
  );
}
