'use client';

import { type FulfilmentKind, isApiError, type ListingRow, sellerKeys, type SellerOffer,sellerQueries } from '@afrotransact/api-client';
import { Button, buttonClassName, Select, Sheet, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { type FormEvent, useEffect, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { money } from '@/lib/format';
import { useSeller } from '@/lib/session';

const CHOICES: FulfilmentKind[] = ['SELLER', 'AFROTRANSACT', 'AFROTRANSACT_DELIVERY'];

/** One offer: "I ship it", "Fulfilled by AfroTransact (FBA)" or "AfroTransact picks up from my store" with its pickup location. */
function OfferFulfilment({ offer, locations, editable }: { offer: SellerOffer; locations: { id: string; label: string }[]; editable: boolean }) {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const [choice, setChoice] = useState<FulfilmentKind>(offer.fulfilment);
  const [location, setLocation] = useState(offer.shipFromLocationId ?? locations[0]?.id ?? '');
  const [errors, setErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    setChoice(offer.fulfilment);
    if (offer.shipFromLocationId) setLocation(offer.shipFromLocationId);
  }, [offer.fulfilment, offer.shipFromLocationId]);

  const save = useMutation({
    mutationFn: () => sellerApi().setOfferFulfilment(offer.offerId, { fulfilment: choice, shipFromLocationId: choice === 'AFROTRANSACT_DELIVERY' ? location || null : null }),
    onSuccess: (o) => {
      setErrors({});
      setChoice(o.fulfilment);
      if (o.shipFromLocationId) setLocation(o.shipFromLocationId);
      toast.show(t('sc.ff.saved', { sku: o.sellerSku }));
      qc.setQueriesData<{ items: ListingRow[]; total: number }>({ queryKey: sellerKeys.listings(orgId) }, (old) => {
        if (!old || !old.items) return old;
        return {
          ...old,
          items: old.items.map((row) => (row.productId === offer.productId ? { ...row, fulfilment: o.fulfilment } : row))
        };
      });
      void qc.invalidateQueries({ queryKey: sellerKeys.listings(orgId) });
      void qc.invalidateQueries({ queryKey: sellerKeys.offers(orgId, offer.productId) });
    },
    onError: (e) => setErrors(isApiError(e) && Object.keys(e.fieldErrors).length ? e.fieldErrors : { form: problemMessage(e) })
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (choice === 'AFROTRANSACT_DELIVERY' && !location) {
      setErrors({ shipFromLocationId: t('sc.ff.locationNone') });
      return;
    }
    save.mutate();
  };
  const name = `ff-${offer.offerId}`;
  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-3 rounded-md border border-border p-4" data-testid="offer-fulfilment">
      <p className="m-0 text-sm font-semibold">
        {t('sc.ff.offer', { sku: offer.sellerSku, price: money(offer.priceMinor, offer.currency), status: offer.status.toLowerCase() })}
        {offer.shipsFrom ? <span className="block text-xs font-normal text-muted">{t('sc.ff.shipsFrom', { city: offer.shipsFrom })}</span> : null}
      </p>
      <fieldset className="m-0 flex flex-col gap-2 border-0 p-0" disabled={!editable}>
        <legend className="mb-1 text-sm">{t('sc.ff.legend', { sku: offer.sellerSku })}</legend>
        {CHOICES.map((c) => (
          <div key={c} className="flex items-start gap-2 text-sm">
            <input type="radio" id={`${name}-${c}`} name={name} value={c} checked={choice === c} onChange={() => setChoice(c)} className="mt-1 shrink-0" aria-describedby={`${name}-${c}-hint`} />
            <span>
              <label htmlFor={`${name}-${c}`} className="cursor-pointer font-semibold">
                {t(`sc.ff.choice.${c}`)}
              </label>
              <span id={`${name}-${c}-hint`} className="block text-xs text-muted">
                {t(`sc.ff.choice.${c}.hint`)}
              </span>
            </span>
          </div>
        ))}
      </fieldset>
      {choice === 'AFROTRANSACT_DELIVERY' ? (
        locations.length ? (
          <Select
            id={`${name}-location`}
            label={t('sc.ff.location')}
            value={location}
            disabled={!editable}
            error={errors.shipFromLocationId}
            options={[{ value: '', label: t('sc.ff.locationNone') }, ...locations.map((l) => ({ value: l.id, label: l.label }))]}
            onChange={(e) => setLocation(e.currentTarget.value)}
          />
        ) : (
          <Notice
            tone="warning"
            title={t('sc.ff.noLocations')}
            action={
              <Link href="/orders/ship-from" className={buttonClassName({ variant: 'neutral', size: 'sm' })}>
                {t('sc.ff.setUpPickup')}
              </Link>
            }
          />
        )
      ) : null}
      {errors.form || errors.fulfilment ? (
        <Notice tone="danger" title={errors.form ?? errors.fulfilment} role="alert" />
      ) : null}
      {editable ? (
        <div>
          <Button type="submit" size="sm" loading={save.isPending} disabled={choice === 'AFROTRANSACT_DELIVERY' && !locations.length}>
            {t('sc.ff.save')}
          </Button>
        </div>
      ) : null}
    </form>
  );
}

/** Listing → Fulfilment: who fulfils each offer of the product (S255). */
export function FulfilmentSheet({ listing, onClose }: { listing: ListingRow | null; onClose: () => void }) {
  const t = useT();
  const { orgId, can } = useSeller();
  const api = sellerApi();
  const offers = useQuery({ ...sellerQueries.offers(api, orgId, listing?.productId ?? ''), enabled: listing !== null });
  const shipFrom = useQuery({ ...sellerQueries.shipFrom(api, orgId), enabled: listing !== null });
  const locations = (shipFrom.data ?? []).filter((a) => a.afrotransactPickup.enabled).map((a) => ({ id: a.id, label: `${a.label} · ${a.city}, ${a.state}` }));
  return (
    <Sheet open={listing !== null} onOpenChange={(open) => (open ? undefined : onClose())} title={listing ? t('sc.ff.title', { title: listing.title }) : ''} description={t('sc.ff.description')}>
      {offers.isPending && listing ? <Spinner /> : null}
      {offers.isError ? <ErrorState error={offers.error} onRetry={() => void offers.refetch()} /> : null}
      {offers.data ? (
        <div className="flex flex-col gap-3">
          {offers.data.items.length === 0 ? <p className="m-0 text-sm text-muted">{t('sc.ff.noOffers')}</p> : null}
          {offers.data.items.map((o) => (
            <OfferFulfilment key={`${o.offerId}-${o.version}`} offer={o} locations={locations} editable={can('listings')} />
          ))}
          <p className="m-0 text-xs text-muted">{t('sc.ff.listingNote')}</p>
        </div>
      ) : null}
    </Sheet>
  );
}
