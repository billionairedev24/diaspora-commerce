'use client';

import { accountQueries, type Offer, productQueries } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, CheckCircle, FulfilmentBadge, ImagePlaceholder, MapPin, Price, QuantityStepper, Sparkle, Stars, TextField } from '@afrotransact/ui';
import { useMutation, useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';

import { PdpPreorderBanner, PdpPreorderBox, PdpStandardDelivery } from '@/components/preorder/PdpPreorder';
import { DeliverToDialog } from '@/components/shell/DeliverToDialog';
import { LolaSheet } from '@/components/shell/LolaSheet';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { siteQueries } from '@/lib/api/queries';
import { lineForOffer, useCart, useCartMutation } from '@/lib/cart/useCart';
import { productHref } from '@/lib/links';
import { useAvailability } from '@/lib/preorder/useAvailability';

import styles from './Product.module.css';

function OfferCartControl({ offer, title, label }: { offer: Offer; title: string; label: string }) {
  const t = useT();
  const { data: cart } = useCart();
  const m = useCartMutation();
  const line = lineForOffer(cart, offer.offerId);
  if (offer.availableQty <= 0) return <span className={styles.offerMeta}>{t('card.unavailable')}</span>;
  if (line)
    return (
      <QuantityStepper
        value={line.quantity}
        max={line.maxQuantity}
        busy={m.isPending}
        itemLabel={title}
        format={(n) => t('card.inCart', { n })}
        onChange={(q) => m.mutate({ kind: 'quantity', lineId: line.lineId, quantity: q, title })}
        onRemove={() => m.mutate({ kind: 'remove', lineId: line.lineId, title })}
      />
    );
  return (
    <Button variant="neutral" size="sm" loading={m.isPending} onClick={() => m.mutate({ kind: 'add', offerId: offer.offerId, quantity: 1, title })} aria-label={label}>
      {t('card.addToCart')}
    </Button>
  );
}

/** Product detail: gallery, details, variant picker, buy box and other sellers. */
export function ProductView({ atin }: { atin: string }) {
  const fmt = useFormatters();
  const t = useT();
  const api = browserApi();
  const router = useRouter();
  const { data } = useQuery(productQueries.detail(api, atin));
  const { data: location } = useQuery(siteQueries.deliveryLocation(api));
  const { data: me } = useQuery(accountQueries.me(api));
  const { data: cart } = useCart();
  const cartMutation = useCartMutation();
  const [img, setImg] = useState(0);
  const [qty, setQty] = useState(1);
  const [locOpen, setLocOpen] = useState(false);
  const [lolaOpen, setLolaOpen] = useState(false);
  const [email, setEmail] = useState('');
  const [emailError, setEmailError] = useState<string | null>(null);
  const alert = useMutation({ mutationFn: (e?: string) => api.createStockAlert(atin, e) });
  const availability = useAvailability(data?.product.preorderEligible);

  if (!data) return <p className={styles.notFound}>{t('pdp.notFound')}</p>;
  const { product: p, buyBox: bb } = data;
  const line = bb ? lineForOffer(cart, bb.offerId) : undefined;
  const inCart = line?.quantity ?? 0;
  const max = bb ? Math.min(bb.availableQty, 99) : 0;
  const images = p.images.length ? p.images : [{ url: null, alt: p.title }];
  const current = images[img] ?? images[0];

  // Pre-order (S273): delivery box by stage; adding is blocked until the window opens / after it closes.
  const pre = availability.state.kind === 'preorder' ? availability.live : null;
  const lockedLabel = availability.locked ? (pre?.copy.pdpAddLabel ?? availability.card?.lockedLabel ?? null) : null;

  const addToCart = (andCheckout = false) => {
    if (!bb || lockedLabel) return;
    const n = Math.max(1, Math.min(qty, max - inCart));
    cartMutation.mutate({ kind: 'add', offerId: bb.offerId, quantity: n, title: p.title }, { onSuccess: () => andCheckout && router.push('/checkout') });
  };

  const notify = () => {
    if (!me?.authenticated && !/^[^\s@]+@[^\s@]+\.[a-z]{2,}$/i.test(email.trim())) {
      setEmailError('Enter a valid email address');
      return;
    }
    setEmailError(null);
    alert.mutate(me?.authenticated ? undefined : email.trim());
  };

  return (
    <div className={`${styles.wrap} full-bleed`}>
      <nav className={styles.crumbs} aria-label={t('acct.breadcrumb')}>
        <button type="button" className="link-button" onClick={() => router.back()}>
          {t('pdp.back')}
        </button>
        <span>
          <Link href={`/s?dept=${encodeURIComponent(p.department)}`}>{p.department}</Link> › <Link href={`/s?community=${encodeURIComponent(p.community)}`}>{p.community}</Link>
        </span>
      </nav>
      <div className={styles.grid}>
        <ul className={styles.thumbs} aria-label={t('pdp.images')}>
          {images.map((im, i) => (
            <li key={i}>
              <button type="button" className={styles.thumb} aria-pressed={i === img} aria-label={t('pdp.image', { n: i + 1, total: images.length })} onMouseEnter={() => setImg(i)} onClick={() => setImg(i)}>
                <ImagePlaceholder src={im.url} alt="" />
              </button>
            </li>
          ))}
        </ul>
        <div className={styles.main}>
          <ImagePlaceholder src={current?.url} alt={current?.alt ?? p.title} caption={t('pdp.photography', { n: img + 1, total: images.length })} />
        </div>
        <div className={styles.info}>
          <h1 className={styles.title}>{p.title}</h1>
          <span className={styles.store}>
            <Link href={`/s?q=${encodeURIComponent(p.brand)}`}>{t('pdp.visitStore', { brand: p.brand })}</Link>
          </span>
          <Stars rating={p.rating} showValue reviewCount={p.reviewCount} reviewsLabel={(c) => t('pdp.ratings', { count: c })} ratingLabel={(r) => t('card.rating', { rating: r })} locale={fmt.tag} />
          <hr className={styles.rule} />
          {bb && (
            <>
              <div className={styles.priceRow}>
                <Price amountMinor={bb.priceMinor} listPriceMinor={bb.listPriceMinor} size="xl" showDeal locale={fmt.tag} listLabel={t('pdp.listLabel')} />
                <span className={styles.unitPrice}>({data.unitPriceLabel})</span>
              </div>
              {bb.listPriceMinor && bb.listPriceMinor > bb.priceMinor && (
                <span className={styles.listPrice}>
                  {t('pdp.listPrice')} <s>{fmt.money(bb.listPriceMinor)}</s>
                </span>
              )}
            </>
          )}
          <dl className={styles.facts}>
            <dt>{t('pdp.community')}</dt>
            <dd>{p.community}</dd>
            <dt>{t('pdp.origin')}</dt>
            <dd>{p.origin}</dd>
            <dt>{t('pdp.size')}</dt>
            <dd>{p.unit}</dd>
            <dt>{t('pdp.brand')}</dt>
            <dd>{p.brand}</dd>
          </dl>
          {p.variants.length > 1 && (
            <nav aria-label={t('pdp.variants')}>
              <strong style={{ fontSize: 'var(--at-text-sm)' }}>{t('pdp.variants')}</strong>
              <ul className={styles.variants} style={{ marginTop: 8 }}>
                {p.variants.map((v) => (
                  <li key={v.atin}>
                    <Link href={productHref(v.atin)} className={styles.variant} aria-current={v.selected ? 'true' : undefined} replace>
                      <span className={v.available ? undefined : styles.variantOff}>{v.label}</span>
                      <strong>{fmt.money(v.priceMinor)}</strong>
                    </Link>
                  </li>
                ))}
              </ul>
            </nav>
          )}
          <hr className={styles.rule} />
          <h2 className={styles.h2}>{t('pdp.about')}</h2>
          <ul className={styles.bullets}>
            {p.bullets.map((b) => (
              <li key={b}>{b}</li>
            ))}
          </ul>
          {data.otherOffers.length > 0 && (
            <section className={styles.others} aria-labelledby="other-sellers">
              <strong id="other-sellers">{t('pdp.otherSellers')}</strong>
              {data.otherOffers.map((o) => (
                <div key={o.offerId} className={styles.offer}>
                  <span className={styles.offerPrice}>
                    {fmt.money(o.priceMinor)} <span className={styles.offerShip}>{t('pdp.shipping', { fee: o.delivery.free ? t('pdp.freeShipping') : fmt.money(o.delivery.feeMinor) })}</span>
                  </span>
                  <span className={styles.offerAction}>
                    <OfferCartControl offer={o} title={p.title} label={`${t('card.addToCart')}: ${o.seller.name}`} />
                  </span>
                  <span className={styles.offerMeta}>
                    {t('pdp.offerMeta', {
                      seller: o.seller.name,
                      fulfilment: o.fulfilment === 'AFROTRANSACT' ? t('search.fulfilled') : o.fulfilment === 'AFROTRANSACT_DELIVERY' ? t('ld.offerFulfilment', { city: o.seller.city }) : 'Ships from seller',
                      eta: o.delivery.label
                    })}
                  </span>
                </div>
              ))}
            </section>
          )}
        </div>
        <aside className={styles.box} aria-label={t('pdp.buyBox')}>
          {bb ? (
            <>
              <Price amountMinor={bb.priceMinor} size="lg" locale={fmt.tag} />
              {pre ? (
                <PdpPreorderBox live={pre} />
              ) : availability.card ? (
                <PdpStandardDelivery availability={availability} eligible={!!p.preorderEligible} />
              ) : (
                <>
                  <FulfilmentBadge
                    fulfilment={bb.fulfilment}
                    shipsFrom={bb.seller.city}
                    localDeliveryLabel={(city) => (city ? t('ld.badge', { city }) : t('ld.badgeNoCity'))}
                  />
                  {bb.fulfilment === 'AFROTRANSACT_DELIVERY' ? (
                    <p className={styles.promise} data-testid="local-delivery-promise">
                      <strong>{bb.delivery.label}</strong>. {t('ld.pdpPromise', { city: bb.seller.city })}
                    </p>
                  ) : (
                    <p className={styles.promise}>
                      {bb.delivery.free ? 'FREE delivery' : `${fmt.money(bb.delivery.feeMinor)} delivery`} <strong>{bb.delivery.label}</strong>
                      {bb.delivery.cutoff ? `. ${bb.delivery.cutoff}` : '.'}
                    </p>
                  )}
                  {bb.delivery.expressLabel && (
                    <p className={styles.express}>
                      {t('pdp.orFastest')} <strong>{bb.delivery.expressLabel}</strong>
                    </p>
                  )}
                </>
              )}
              <button type="button" className={`link-button ${styles.deliverTo}`} onClick={() => setLocOpen(true)}>
                <MapPin aria-hidden />
                {t('header.deliverTo')} {location?.label}
              </button>
              <p className={`${styles.stock} ${bb.stockStatus === 'LOW_STOCK' ? styles.lowStock : styles.inStock}`} data-testid="stock-label">
                {bb.stockStatus === 'LOW_STOCK' ? t('pdp.onlyLeft', { n: bb.availableQty }) : t('pdp.inStock')}
              </p>
              {inCart > 0 && line ? (
                <>
                  <QuantityStepper
                    size="md"
                    block
                    value={inCart}
                    max={lockedLabel ? inCart : line.maxQuantity}
                    busy={cartMutation.isPending}
                    itemLabel={p.title}
                    format={(n) => t('card.inCart', { n })}
                    onChange={(q) => cartMutation.mutate({ kind: 'quantity', lineId: line.lineId, quantity: q, title: p.title })}
                    onRemove={() => cartMutation.mutate({ kind: 'remove', lineId: line.lineId, title: p.title })}
                  />
                  <Link href="/cart" className={styles.inCart}>
                    <CheckCircle weight="fill" aria-hidden />
                    {t('pdp.inYourCart', { n: inCart })}
                  </Link>
                </>
              ) : (
                <>
                  <label className={styles.qty}>
                    {t('pdp.quantity')}
                    <select className={styles.qtySelect} value={qty} onChange={(e) => setQty(Number(e.target.value))}>
                      {Array.from({ length: Math.max(1, Math.min(10, max)) }, (_, i) => i + 1).map((n) => (
                        <option key={n} value={n}>
                          {n}
                        </option>
                      ))}
                    </select>
                  </label>
                  <Button block loading={cartMutation.isPending} disabled={!!lockedLabel} onClick={() => addToCart(false)}>
                    {lockedLabel ?? t('pdp.addToCart')}
                  </Button>
                </>
              )}
              {!lockedLabel && (
                <Button block variant="secondary" onClick={() => (inCart > 0 ? router.push('/checkout') : addToCart(true))}>
                  {t('pdp.buyNow')}
                </Button>
              )}
            </>
          ) : (
            <div className={styles.notify}>
              <p className={styles.unavailable}>
                <strong>{t('pdp.unavailable')}</strong>
              </p>
              <p className={styles.unavailableHint}>{t('pdp.unavailableHint')}</p>
              {!me?.authenticated && !alert.isSuccess && (
                <TextField label={t('pdp.notifyEmail')} type="email" value={email} onChange={(e) => setEmail(e.target.value)} error={emailError} autoComplete="email" />
              )}
              <Button variant="neutral" size="sm" onClick={notify} loading={alert.isPending} disabled={alert.isSuccess} aria-live="polite">
                {alert.isSuccess ? t('pdp.notifyDone') : t('pdp.notify')}
              </Button>
            </div>
          )}
          <dl className={styles.boxFacts}>
            <dt>{t('pdp.shipsFrom')}</dt>
            <dd>{data.shipsFrom}</dd>
            <dt>{t('pdp.soldBy')}</dt>
            <dd className={styles.sellerName}>{data.soldBy}</dd>
            <dt>{t('pdp.returns')}</dt>
            <dd>{data.returnsPolicy}</dd>
            <dt>{t('pdp.payment')}</dt>
            <dd>{t('pdp.secure')}</dd>
          </dl>
          <Button variant="neutral" size="sm" icon={<Sparkle weight="fill" className={styles.sparkle} aria-hidden />} onClick={() => setLolaOpen(true)}>
            {t('pdp.askLola')}
          </Button>
        </aside>
      </div>
      {pre && <PdpPreorderBanner live={pre} />}
      <DeliverToDialog open={locOpen} onOpenChange={setLocOpen} />
      <LolaSheet open={lolaOpen} onOpenChange={setLolaOpen} />
    </div>
  );
}
