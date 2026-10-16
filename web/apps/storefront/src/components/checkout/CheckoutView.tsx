'use client';

import {
  type Address,
  cartKeys,
  checkoutKeys,
  type CheckoutPackage,
  definitiveFailure,
  type DeliverySelection,
  isApiError,
  type OrderConfirmation,
  orderKeys,
} from '@afrotransact/api-client';
import { Button, buttonClassName, LockSimple, Plus, Skeleton, useToast } from '@afrotransact/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { type ReactNode, useEffect, useMemo, useRef, useState } from 'react';

import { AddressDialog } from '@/components/account/AddressDialog';
import { type PaymentElementChange, type PaymentElementHandle, REDIRECTED } from '@/components/payments/PaymentElementField';
import { useCartPreorderCopy } from '@/components/preorder/CartPreorder';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';
import { type ConfirmResult, confirmWithSavedMethod, stripePaymentMethodTypes } from '@/lib/payments/stripe';
import { readAttribution } from '@/lib/preorder/attribution';

import styles from './Checkout.module.css';
import { CheckoutOrderPanel } from './CheckoutOrderPanel';
import { type Choice, feeOf, initialChoice, PackageCard } from './PackageCard';
import { newMethodName, PaymentStep } from './PaymentStep';

/** How the order is paid: saved card or new details via the Payment Element. */
type PaySpec = { paymentMethodId: string } | { newPaymentMethod: true; savePaymentMethod: boolean };

/** Where Stripe sends the shopper back after a redirect (3-D Secure page, Cash App, Affirm, bank): checks the payment. */
export const checkoutReturnUrl = (orderNumber: string) => `${window.location.origin}/checkout/complete?order=${encodeURIComponent(orderNumber)}`;

const addressLine = (a: Address) => `${a.line1}${a.line2 ? ` ${a.line2}` : ''}, ${a.city}, ${a.state} ${a.zip}`;
/** A checkout section: numbered heading, optional action, body. */
function Section({ n, id, title, action, children }: { n: number; id: string; title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section className={styles.section} aria-labelledby={id}>
      <div className={styles.sectionHead}>
        <span className={styles.num} aria-hidden>
          {n}
        </span>
        <h2 id={id} className={styles.sectionTitle}>
          {title}
        </h2>
        {action}
      </div>
      {children}
    </section>
  );
}

/**
 * Checkout (S047, S048, S282): two columns from 1000px. The main column is address and delivery or pickup per seller
 * group; the sticky right rail holds line items, totals, payment (Stripe Payment Element) and an optional
 * checkout_cross_sell placement from content. Below 1000px the rail follows delivery choices.
 *
 * Payment is Stripe Elements on the deferred-intent flow: the Payment Element is created from the order total and
 * updated as delivery choices change; Place order runs elements.submit(), places the order (orders creates the
 * PaymentIntent, manual capture), confirms it with stripe.confirmPayment({ elements, clientSecret }) and lets orders
 * continue; redirect-based methods come back to /checkout/complete. Saved methods confirm with their PaymentMethod id.
 *
 * No double charge (docs/engineering/payments-idempotency.md): one Idempotency-Key per purchase attempt, kept until the
 * order is placed or has definitely failed (a 4xx about it, or orders reports the order failed). After a timeout, a
 * 5xx or a Stripe confirmation error the known order is checked first (getCheckoutStatus) and the next Place order
 * replays the same order and PaymentIntent. A ref (not state) makes Place order single-flight.
 */
export function CheckoutView() {
  const t = useT();
  const api = browserApi();
  const qc = useQueryClient();
  const toast = useToast();
  const router = useRouter();
  const [addressId, setAddressId] = useState<string | null>(null);
  const [choices, setChoices] = useState<Record<string, Choice>>({});
  const [addressOpen, setAddressOpen] = useState(false);
  const [pmId, setPmId] = useState<string | null>(null);
  const [payWith, setPayWith] = useState<'saved' | 'new' | null>(null);
  const [save, setSave] = useState(false);
  const [newMethod, setNewMethod] = useState<PaymentElementChange>({ complete: false, empty: true, type: null });
  const payEl = useRef<PaymentElementHandle>(null);
  const [couponDraft, setCouponDraft] = useState('');
  const [appliedCoupon, setAppliedCoupon] = useState('');
  /** Sent to the BFF so shipping, tax and coupons match the selected delivery option per package. */
  const [packageDeliveryKey, setPackageDeliveryKey] = useState<string | undefined>();
  const summary = useQuery(shopperQueries.checkoutSummary(api, addressId, packageDeliveryKey, appliedCoupon || undefined));
  const s = summary.data;
  const [addrDialog, setAddrDialog] = useState<{ open: boolean; address: Address | null }>({ open: false, address: null });
  const [placing, setPlacing] = useState(-1);
  const [error, setError] = useState<string | null>(null);
  const keyRef = useRef<string>(crypto.randomUUID());
  /** Single-flight guard, set synchronously before any await (state updates are too late for a double click). */
  const busyRef = useRef(false);
  /** The order the current key created, once orders answered (checked after an error before a new key is made). */
  const orderRef = useRef<string | null>(null);
  // Saved addresses and payment methods load by default (the default one preselected).
  useEffect(() => {
    if (!s) return;
    if (!addressId) {
      const def = s.addresses.find((a) => a.isDefault) ?? s.addresses[0];
      if (def) setAddressId(def.id);
      else setAddressOpen(true);
    }
    if (!pmId || !s.paymentMethods.some((p) => p.id === pmId)) setPmId((s.paymentMethods.find((p) => p.isDefault) ?? s.paymentMethods[0])?.id ?? null);
    // Saved methods are listed (another method is entered in the modal); the inline form is only for a first method.
    setPayWith((w) => (!s.paymentMethods.length ? 'new' : w === 'new' ? 'new' : 'saved'));
    setChoices((c) => {
      const next = { ...c };
      for (const p of s.packages) {
        const cur = next[p.packageId];
        if (!cur || !p.deliveryOptions.some((o) => o.key === cur.option)) next[p.packageId] = initialChoice(p);
        else if (cur.option === 'pickup' && !p.pickupPoints?.some((x) => x.id === cur.pickupPointId)) next[p.packageId] = { ...cur, pickupPointId: p.pickupPoints?.[0]?.id };
      }
      return next;
    });
  }, [s, addressId, pmId]);

  const deliveryKeyOf = (pkgs: CheckoutPackage[], ch: Record<string, Choice>) =>
    pkgs.map((p) => `${p.packageId}:${(ch[p.packageId] ?? initialChoice(p)).option}`).join(',');

  useEffect(() => {
    if (!s?.packages.length) return;
    const key = deliveryKeyOf(s.packages, choices);
    setPackageDeliveryKey((prev) => (prev === key ? prev : key));
  }, [s, choices]);

  const setPackageChoice = (packageId: string, choice: Choice) => {
    setChoices((prev) => {
      const next = { ...prev, [packageId]: choice };
      if (s?.packages.length) setPackageDeliveryKey(deliveryKeyOf(s.packages, next));
      return next;
    });
  };

  const address = s?.addresses.find((a) => a.id === addressId) ?? null;
  const pm = s?.paymentMethods.find((p) => p.id === pmId) ?? null;
  const selectedShipping = useMemo(() => (s ? s.packages.reduce((sum, p) => sum + feeOf(p, choices[p.packageId]), 0) : 0), [s, choices]);
  const pickups = s ? s.packages.filter((p) => choices[p.packageId]?.option === 'pickup').length : 0;
  /** Optimistic total: shipping follows UI choices immediately; tax and coupon discounts follow the BFF summary. */
  const total = useMemo(() => {
    if (!s) return 0;
    const ship = Math.max(0, selectedShipping - (s.shippingDiscountMinor ?? 0));
    return s.subtotalMinor - (s.discountMinor ?? 0) + ship + s.taxMinor;
  }, [s, selectedShipping]);
  const itemCount = s?.packages.reduce((n, p) => n + p.itemCount, 0) ?? 0;
  const busy = placing >= 0;
  // A pre-order package whose window is not open (upcoming or closed) blocks the order (S273, 422 orders.preorder-not-open).
  const preBlock = (s?.packages ?? []).find((p) => p.preorder)?.preorder ?? null;
  const preCopy = useCartPreorderCopy(preBlock);
  const preLocked = !!preBlock && preBlock.stage !== 'OPEN';
  // Pre-order windows may restrict payment methods; the PaymentIntent and the Elements must use the same list.
  const allowedByWindow = (s?.packages ?? []).flatMap((p) => (p.preorder ? [p.preorder.allowedMethods.join(',')] : [])).join('|');
  const methodTypes = useMemo(() => (allowedByWindow ? stripePaymentMethodTypes(allowedByWindow.split('|').map((w) => w.split(','))) : undefined), [allowedByWindow]);
  const paymentChosen = payWith === 'new' || (payWith === 'saved' && !!pm);
  // AfroTransact Delivery the chosen address can't be served by blocks the order until removed or picked up instead (S255).
  const undeliverable = (s?.packages ?? []).some((p) => p.localDelivery?.available === false && (choices[p.packageId]?.option ?? initialChoice(p).option) !== 'pickup');
  const blocked = undeliverable || preLocked;
  const [removing, setRemoving] = useState<string | null>(null);
  const removePackage = async (p: CheckoutPackage) => {
    setRemoving(p.packageId);
    try {
      for (const l of p.lines) await api.updateCartLine(l.lineId, { selected: false });
      toast.show(t('ld.removed'));
      await Promise.all([qc.invalidateQueries({ queryKey: cartKeys.all }), qc.invalidateQueries({ queryKey: checkoutKeys.all })]);
    } catch (e) {
      setError(isApiError(e) ? e.message : t('auth.genericError'));
    } finally {
      setRemoving(null);
    }
  };
  const changeAddress = () => {
    setError(null);
    setAddressOpen(true);
    document.getElementById('co-address')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  const selections = (): DeliverySelection[] =>
    (s?.packages ?? []).map((p) => {
      const c = choices[p.packageId] ?? initialChoice(p);
      const perishable = p.lines.some((l) => l.perishable);
      return {
        packageId: p.packageId,
        option: c.option,
        pickupPointId: c.option === 'pickup' ? (c.pickupPointId ?? p.pickupPoints?.[0]?.id) : undefined,
        preferences: c.option === 'afrotransact_delivery' ? { ...c.preferences, handover: perishable ? 'hand' : c.preferences.handover, safePlace: c.preferences.safePlace || undefined, instructions: c.preferences.instructions?.trim() || undefined } : undefined
      };
    });

  /** Starts a purchase attempt unless one is running (synchronous: a second click in the same tick is ignored). */
  const begin = (): boolean => {
    if (busyRef.current) return false;
    busyRef.current = true;
    setError(null);
    setPlacing(0);
    return true;
  };
  const end = () => {
    busyRef.current = false;
    setPlacing(-1);
  };

  const placed = async (status: OrderConfirmation) => {
    setPlacing(3);
    qc.setQueryData(cartKeys.current(), (old: any) =>
      old
        ? {
            ...old,
            packages: [],
            itemCount: 0,
            selectedCount: 0,
            subtotalMinor: 0,
            allSelected: false,
            sellerCount: 0
          }
        : old
    );
    await Promise.all([qc.invalidateQueries({ queryKey: cartKeys.all }), qc.invalidateQueries({ queryKey: orderKeys.all })]);
    router.push(`/orders?placed=${encodeURIComponent(status.orderNumber)}`);
  };

  /**
   * Ends a failed attempt. The key is kept unless the attempt is definitely over: a 4xx about it, or orders reports
   * the known order failed when asked (it may also turn out placed after all, e.g. the answer was lost).
   */
  const fail = async (e: unknown) => {
    let over = definitiveFailure(e);
    const known = orderRef.current;
    if (!over && known) {
      try {
        // Read-only: what orders knows of the attempt (placed after all, still awaiting payment, or failed → 4xx).
        const status = await api.getCheckoutStatus(known);
        if (status.status === 'placed') return placed(status);
      } catch (check) {
        over = definitiveFailure(check);
      }
    }
    if (over) {
      keyRef.current = crypto.randomUUID();
      orderRef.current = null;
    }
    end();
    setError(
      isApiError(e)
        ? (e.fieldErrors.couponCode ?? Object.values(e.fieldErrors)[0] ?? e.message)
        : e instanceof Error
          ? e.message
          : t('auth.genericError')
    );
    void qc.invalidateQueries({ queryKey: checkoutKeys.all });
  };

  /** Places (or, with the same key, replays) the order. Call after begin(). */
  const place = async (pay: PaySpec): Promise<OrderConfirmation | null> => {
    if (!address) return (end(), setAddressOpen(true), setError(t('co.needAddress')), null);
    try {
      // Last-touch channel (?src=&cr=&po= or the pre-order placement clicked) is stored on the order.
      const attribution = readAttribution() ?? undefined;
      const couponCode = s?.coupon?.applicable ? s.coupon.code : undefined;
      const conf = await api.placeOrder({ addressId: address.id, ...pay, deliverySelections: selections(), attribution, couponCode }, keyRef.current);
      orderRef.current = conf.orderNumber;
      return conf;
    } catch (e) {
      await fail(e);
      return null;
    }
  };

  const finish = async (conf: OrderConfirmation, confirmed: ConfirmResult) => {
    if (!confirmed.ok) return fail(new Error(confirmed.message));
    if (confirmed.status === REDIRECTED) return; // the browser is on its way to /checkout/complete
    try {
      setPlacing(2);
      let status = conf.status === 'placed' ? conf : await api.confirmCheckoutPayment(conf.orderNumber);
      for (let i = 0; status.status === 'processing' && i < 10; i += 1) {
        await new Promise((r) => setTimeout(r, 1000));
        status = await api.getCheckoutStatus(conf.orderNumber);
      }
      await placed(status);
    } catch (e) {
      await fail(e);
    }
  };

  const placeWithSaved = async () => {
    if (!pm) return setError(t('co.needPayment'));
    if (!begin()) return;
    const conf = await place({ paymentMethodId: pm.id });
    if (!conf) return;
    setPlacing(1);
    const confirmed = conf.paymentClientSecret
      ? await confirmWithSavedMethod(s?.publishableKey ?? '', conf.paymentClientSecret, pm.id, checkoutReturnUrl(conf.orderNumber))
      : ({ ok: true } as const);
    await finish(conf, confirmed);
  };

  // Stripe's deferred-intent order: elements.submit() → order + PaymentIntent (orders) → stripe.confirmPayment().
  const placeWithElement = async () => {
    const el = payEl.current;
    if (!el) return setError(t('co.needPayment'));
    if (!begin()) return;
    const submitted = await el.submit();
    if (!submitted.ok) {
      end();
      setError(submitted.message);
      document.getElementById('co-payment')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      return;
    }
    const conf = await place({ newPaymentMethod: true, savePaymentMethod: save });
    if (!conf) return;
    setPlacing(1);
    // A decline or failed authentication is shown in the Payment Element itself.
    const confirmed = conf.paymentClientSecret ? await el.confirm(conf.paymentClientSecret, checkoutReturnUrl(conf.orderNumber)) : ({ ok: true } as const);
    await finish(conf, confirmed);
  };

  /** Typing into the Payment Element means paying with a new method instead of a saved one. */
  const onNewMethod = (c: PaymentElementChange) => {
    setNewMethod(c);
  };

  if (summary.isPending) return <Skeleton height={420} />;
  if (summary.isError) {
    const empty = isApiError(summary.error) && summary.error.status === 409;
    return (
      <div className={styles.empty}>
        <h1 className={styles.title}>{empty ? t('co.emptyTitle') : t('co.errorTitle')}</h1>
        <p className={styles.muted}>{empty ? t('co.emptyBody') : t('acct.loadError')}</p>
        <Link href="/cart" className={buttonClassName()}>
          {t('common.goToCart')}
        </Link>
      </div>
    );
  }
  if (!s) return null;

  const addressList = addressOpen || !address;
  // The BFF sends a rate ('8.25% TX') or, before an address is known, its own sentence: never repeat it.
  const taxNote = s.taxMinor
    ? /\d\s*%/.test(s.taxRateLabel)
      ? t('co.taxExplainRate', { rate: s.taxRateLabel })
      : t('co.taxExplain')
    : /\d\s*%/.test(s.taxRateLabel)
      ? t('co.taxNoteRate', { rate: s.taxRateLabel })
      : s.taxRateLabel || t('co.taxNote');

  return (
    <div className={styles.layout}>
      <header className={styles.head}>
        <h1 className={styles.title}>{t('co.title')}</h1>
        <span className={styles.headMeta}>
          <LockSimple weight="fill" aria-hidden /> {t('co.items', { n: itemCount })}
        </span>
      </header>

      <div className={styles.mainCol}>
        <Section
          n={1}
          id="co-address"
          title={t('co.step.address')}
        >
          {!addressOpen && address ? (
            <div data-testid="checkout-address" className={styles.choiceCompact}>
              <div className={styles.choiceText}>
                <strong className={styles.name}>{address.name}</strong>
                <span>{addressLine(address)}</span>
                <span className={styles.meta}>{address.phone}</span>
                {address.isDefault && <span className={styles.tag}>{t('pay.default')}</span>}
              </div>
              <div className={styles.currentActions}>
                <button
                  type="button"
                  className="link-button"
                  onClick={() => setAddrDialog({ open: true, address })}
                >
                  {t('common.edit')}
                </button>
                <button
                  type="button"
                  className="link-button"
                  aria-label="Change Delivery address"
                  onClick={() => setAddressOpen(true)}
                >
                  {t('co.change')}
                </button>
              </div>
            </div>
          ) : (
            <div className={styles.choices} data-testid="checkout-address">
              {s.addresses.length === 0 && <p className={styles.muted}>{t('co.noAddresses')}</p>}
              {s.addresses.map((a) => (
                <label key={a.id} className={styles.choice} data-testid="checkout-address-option">
                  <input
                    type="radio"
                    name="shipaddr"
                    role="radio"
                    checked={a.id === addressId}
                    onChange={() => setAddressId(a.id)}
                    aria-label={`${a.name} ${addressLine(a)}`}
                  />
                  <span className={styles.choiceText}>
                    <strong className={styles.name}>{a.name}</strong> <span>{addressLine(a)}</span>
                    <span className={styles.meta}>{a.phone}</span>
                  </span>
                  {a.isDefault && <span className={styles.tag}>{t('pay.default')}</span>}
                  <button
                    type="button"
                    className="link-button"
                    onClick={() => setAddrDialog({ open: true, address: a })}
                  >
                    {t('common.edit')}
                  </button>
                </label>
              ))}
              <div className={styles.rowActions}>
                <button type="button" className={styles.addRow} onClick={() => setAddrDialog({ open: true, address: null })}>
                  <Plus aria-hidden /> {t('co.addAddress')}
                </button>
                <Button onClick={() => { setError(null); setAddressOpen(false); }} disabled={!address}>
                  {t('co.useAddress')}
                </Button>
              </div>
            </div>
          )}
        </Section>

        <Section n={2} id="co-payment-title" title={t('co.step.payment')}>
          <PaymentStep
            s={s}
            total={total}
            pmId={pmId}
            setPmId={(id) => {
              setPmId(id);
              setPayWith('saved');
              setError(null);
            }}
            payWith={payWith}
            setPayWith={setPayWith}
            newMethod={newMethod}
            save={save}
            setSave={setSave}
            onNewMethod={onNewMethod}
            payEl={payEl}
            methodTypes={methodTypes}
          />
        </Section>

        <Section n={3} id="co-delivery" title={t('co.step.delivery')} action={<span className={styles.muted}>{t('co.sellers', { n: s.sellerCount })}</span>}>
          <div className={styles.packages}>
            {s.packages.map((p, i) => (
              <PackageCard
                key={p.packageId}
                pkg={p}
                n={i + 1}
                total={s.packages.length}
                choice={choices[p.packageId] ?? initialChoice(p)}
                onChoice={(c) => setPackageChoice(p.packageId, c)}
                onRemove={() => void removePackage(p)}
                onChangeAddress={changeAddress}
                removing={removing === p.packageId}
              />
            ))}
          </div>
        </Section>
      </div>

      <CheckoutOrderPanel
        s={s}
        packages={s.packages}
        pickups={pickups}
        shippingMinor={selectedShipping}
        total={total}
        itemCount={itemCount}
        taxNote={taxNote}
        paymentChosen={paymentChosen}
        pmLabel={payWith === 'saved' ? pm?.label : newMethodName(newMethod.type)}
        addressOk={!!address}
        blocked={blocked}
        preLocked={preLocked}
        preLockedText={preCopy?.copy.lockedText}
        undeliverable={undeliverable}
        addressList={addressList}
        busy={busy}
        placing={placing}
        error={error}
        onPlace={() => void (payWith === 'new' || !pm ? placeWithElement() : placeWithSaved())}
        couponDraft={couponDraft}
        setCouponDraft={setCouponDraft}
        onApplyCoupon={() => {
          setError(null);
          setAppliedCoupon(couponDraft.trim().toUpperCase());
        }}
        onRemoveCoupon={() => {
          setError(null);
          setAppliedCoupon('');
          setCouponDraft('');
        }}
      />

      <AddressDialog open={addrDialog.open} onOpenChange={(o) => setAddrDialog((d) => ({ ...d, open: o }))} address={addrDialog.address} onSaved={(a) => setAddressId(a.id)} />
      <span hidden data-testid="checkout-pay-with">
        {payWith === 'new' ? (newMethod.type ?? 'new') : (pm?.id ?? '')}
      </span>
    </div>
  );
}
