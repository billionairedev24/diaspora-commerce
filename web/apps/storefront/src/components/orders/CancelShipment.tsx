'use client';

import { useFormatters } from '@afrotransact/i18n/react';
import { Button, Dialog } from '@afrotransact/ui';
import { useState } from 'react';

import pageStyles from '@/components/account/Pages.module.css';
import { useT } from '@/i18n/t';

interface Props {
  shipment: { items: string; cancellable?: boolean; cancellableUntil?: string | null };
  onCancel: () => void;
  loading: boolean;
  block?: boolean;
}

/**
 * "Cancel items" with the time it is on offer until, and a confirmation: it refunds in full and cannot be undone. Shown
 * only while the order says it can still be cancelled (before packing, inside the cancellation window).
 */
export function CancelShipment({ shipment, onCancel, loading, block }: Props) {
  const t = useT();
  const fmt = useFormatters();
  const [open, setOpen] = useState(false);
  if (!shipment.cancellable) return null;
  const until = shipment.cancellableUntil
    ? new Date(shipment.cancellableUntil).toLocaleString(fmt.tag, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
    : null;
  return (
    <>
      <Button size="sm" variant="neutral" block={block} onClick={() => setOpen(true)} loading={loading}>
        {t('ord.cancelItems')}
      </Button>
      {until && <p className={pageStyles.muted}>{t('ord.cancelUntil', { time: until })}</p>}
      <Dialog open={open} onOpenChange={setOpen} title={t('ord.cancelConfirmTitle')} description={t('ord.cancelConfirmBody', { items: shipment.items })}>
        <div className={pageStyles.actions}>
          <Button variant="neutral" onClick={() => setOpen(false)}>
            {t('ord.cancelKeep')}
          </Button>
          <Button
            variant="primary"
            loading={loading}
            onClick={() => {
              setOpen(false);
              onCancel();
            }}
          >
            {t('ord.cancelYes')}
          </Button>
        </div>
      </Dialog>
    </>
  );
}
