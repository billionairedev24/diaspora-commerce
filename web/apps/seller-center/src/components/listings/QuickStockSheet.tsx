'use client';

import { type ListingRow, sellerKeys, type SellerOffer, sellerQueries } from '@afrotransact/api-client';
import { Button, Sheet, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { type FormEvent, useRef, useState } from 'react';

import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { idempotencyKey } from '@/lib/format';
import { useSeller } from '@/lib/session';

const MAX_STOCK = 100_000;

/** One seller-held offer: the quantity on hand, saved on its own (an absolute count, so saving twice is harmless). */
function StockRow({ offer }: { offer: SellerOffer }) {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const [value, setValue] = useState(String(offer.quantity));
  const [error, setError] = useState<string | undefined>();
  const key = useRef(idempotencyKey());
  const quantity = Number(value);
  const valid = value.trim() !== '' && Number.isInteger(quantity) && quantity >= 0 && quantity <= MAX_STOCK;
  const save = useMutation({
    mutationFn: () => sellerApi().adjustStock({ sku: offer.sellerSku, quantity, reason: 'STOCK_TAKE' }, key.current),
    onSuccess: (a) => {
      key.current = idempotencyKey();
      setError(undefined);
      toast.show(t('sc.stock.saved', { sku: a.sku, quantity: a.quantity }));
      qc.setQueriesData<{ items: ListingRow[]; total: number }>({ queryKey: sellerKeys.listings(orgId) }, (old) => {
        if (!old || !old.items) return old;
        return { ...old, items: old.items.map((r) => (r.productId === offer.productId ? { ...r, stock: Math.max(0, r.stock - offer.quantity + a.quantity) } : r)) };
      });
      void qc.invalidateQueries({ queryKey: sellerKeys.listings(orgId) });
      void qc.invalidateQueries({ queryKey: sellerKeys.offers(orgId, offer.productId) });
      void qc.invalidateQueries({ queryKey: sellerKeys.org(orgId) });
    },
    onError: (e) => setError(problemMessage(e))
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!valid) {
      setError(t('sc.stock.invalid', { max: MAX_STOCK.toLocaleString() }));
      return;
    }
    setError(undefined);
    save.mutate();
  };
  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-2 rounded-md border border-border p-3" data-testid="quick-stock-row">
      <p className="m-0 flex flex-wrap items-baseline gap-x-2 text-sm">
        <strong className="font-mono">{offer.sellerSku}</strong>
        <span className="text-xs text-muted">{t('sc.stock.reserved', { count: Math.max(0, offer.quantity - offer.availableQty) })}</span>
      </p>
      <div className="flex items-end gap-2">
        <div className="min-w-0 flex-1">
          <TextField
            label={t('sc.stock.quantityOf', { sku: offer.sellerSku })}
            inputMode="numeric"
            value={value}
            error={error}
            onChange={(e) => setValue(e.currentTarget.value.replace(/[^0-9]/g, ''))}
          />
        </div>
        <Button type="submit" size="sm" loading={save.isPending} disabled={!valid || quantity === offer.quantity}>
          {t('sc.stock.save')}
        </Button>
      </div>
    </form>
  );
}

/** Listings → Stock: set the quantity on hand of each offer without opening the product form. */
export function QuickStockSheet({ listing, onClose }: { listing: ListingRow | null; onClose: () => void }) {
  const t = useT();
  const { orgId } = useSeller();
  const offers = useQuery({ ...sellerQueries.offers(sellerApi(), orgId, listing?.productId ?? ''), enabled: listing !== null });
  const items = offers.data?.items ?? [];
  const held = items.filter((o) => o.fulfilment !== 'AFROTRANSACT');
  const fba = items.filter((o) => o.fulfilment === 'AFROTRANSACT');
  return (
    <Sheet open={listing !== null} onOpenChange={(open) => (open ? undefined : onClose())} title={listing ? t('sc.stock.title', { title: listing.title }) : ''} description={t('sc.stock.description')}>
      {offers.isPending && listing ? <Spinner /> : null}
      {offers.isError ? <ErrorState error={offers.error} onRetry={() => void offers.refetch()} /> : null}
      {offers.data ? (
        <div className="flex flex-col gap-3">
          {items.length === 0 ? <p className="m-0 text-sm text-muted">{t('sc.ff.noOffers')}</p> : null}
          {held.map((o) => (
            <StockRow key={`${o.offerId}-${o.version}`} offer={o} />
          ))}
          {fba.length ? (
            <p className="m-0 text-sm text-muted">
              {t('sc.stock.managed', { skus: fba.map((o) => o.sellerSku).join(', ') })}{' '}
              <Link href="/inventory" className="text-link">
                {t('sc.stock.openInventory')}
              </Link>
            </p>
          ) : null}
        </div>
      ) : null}
    </Sheet>
  );
}
