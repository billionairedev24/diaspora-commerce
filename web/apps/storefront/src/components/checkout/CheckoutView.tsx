'use client';

import {
  type Address,
  cartKeys,
  checkoutKeys,
  type CheckoutPackage,
  definitiveFailure,
  type DeliverySelection,
  isApiError,
  legalHref,
  type OrderConfirmation,
  orderKeys,
  type PaymentMethod
} from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, CardBrandRow, CheckCircle, Circle, CircleNotch, LockSimple, Plus, PoweredByStripe, ShieldCheck, Skeleton, useToast } from '@afrotransact/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { type ReactNode, useEffect, useMemo, useRef, useState } from 'react';

import { AddressDialog } from '@/components/account/AddressDialog';
import { PaymentChip } from '@/components/payments/PaymentChip';
import { type PaymentElementChange, PaymentElementField, type PaymentElementHandle, REDIRECTED } from '@/components/payments/PaymentElementField';
import { useCartPreorderCopy } from '@/components/preorder/CartPreorder';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';
import { type ConfirmResult, confirmWithSavedMethod, stripePaymentMethodTypes } from '@/lib/payments/stripe';
import { readAttribution } from '@/lib/preorder/attribution';

import styles from './Checkout.module.css';
import { type Choice, feeOf, initialChoice, PackageCard } from './PackageCard';

/** How the order is paid: a saved method, or a new method in the Payment Element. */
type PaySpec = { paymentMethodId: string } | { newPaymentMethod: true; savePaymentMethod: boolean };

/** Where Stripe sends the shopper back after a redirect (3-D Secure page, Cash App, Affirm, bank): checks the payment. */
export const checkoutReturnUrl = (orderNumber: string) => `${window.location.origin}/checkout/complete?order=${encodeURIComponent(orderNumber)}`;

const addressLine = (a: Address) => `${a.line1}${a.line2 ? ` ${a.line2}` : ''}, ${a.city}, ${a.state} ${a.zip}`;
const pmSub = (t: Translator<MessageKey>, pm: PaymentMethod) => (pm.expires ? t('pay.expires', { date: pm.expires }) : pm.type === 'us_bank_account' ? t('pay.bankAccount') : t('pay.wallet'));

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

const STEPS = ['co.placing.payment', 'co.placing.confirm', 'co.placing.stock', 'co.placing.split'] as const;

/**
 * Checkout (S047, S048, S282): one page, nothing folded away. The main column holds 1 the delivery address, 2 every
 * seller group with Delivery or Pickup (pickup points per group: AfroTransact counters for platform-fulfilled items,
 * the seller's own locations for theirs) and every item, 3 payment: the shopper's saved methods and Stripe's Payment
 * Element (compact accordion: card, wallets, Link, Klarna, Amazon Pay… as enabled in Stripe), always visible. The
 * right column is a sticky order summary with Place your order.
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
  const fmt = useFormatters();
  const t = useT();
  const api = browserApi();
  const qc = useQueryClient();
  const toast = useToast();
  const router = useRouter();
  const [addressId, setAddressId] = useState<string | null>(null);
  const summary = useQuery(shopperQueries.checkoutSummary(api, addressId));
  const s = summary.data;
  const [addressOpen, setAddressOpen] = useState(false);
  const [pmId, setPmId] = useState<string | null>(null);
  const [payWith, setPayWith] = useState<'saved' | 'new' | null>(null);
  const [save, setSave] = useState(false);
  const [newMethod, setNewMethod] = useState<PaymentElementChange>({ complete: false, empty: true, type: null });
  const payEl = useRef<PaymentElementHandle>(null);
  const [choices, setChoices] = useState<Record<string, Choice>>({});
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
    setPayWith((w) => w ?? (s.paymentMethods.length ? 'saved' : 'new'));
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

  const address = s?.addresses.find((a) => a.id === addressId) ?? null;
  const pm = s?.paymentMethods.find((p) => p.id === pmId) ?? null;
  const shipping = useMemo(() => (s ? s.packages.reduce((sum, p) => sum + feeOf(p, choices[p.packageId]), 0) : 0), [s, choices]);
  const pickups = s ? s.packages.filter((p) => choices[p.packageId]?.option === 'pickup').length : 0;
  const total = (s?.subtotalMinor ?? 0) + shipping + (s?.taxMinor ?? 0);
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
    setError(isApiError(e) ? (Object.values(e.fieldErrors)[0] ?? e.message) : e instanceof Error ? e.message : t('auth.genericError'));
    void qc.invalidateQueries({ queryKey: checkoutKeys.all });
  };

  /** Places (or, with the same key, replays) the order. Call after begin(). */
  const place = async (pay: PaySpec): Promise<OrderConfirmation | null> => {
    if (!address) return (end(), setAddressOpen(true), setError(t('co.needAddress')), null);
    try {
      // Last-touch channel (?src=&cr=&po= or the pre-order placement clicked) is stored on the order.
      const attribution = readAttribution() ?? undefined;
      const conf = await api.placeOrder({ addressId: address.id, ...pay, deliverySelections: selections(), attribution }, keyRef.current);
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
    if (!c.empty) setPayWith('new');
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
  const taxNote = s.taxMinor ? null : /\d\s*%/.test(s.taxRateLabel) ? t('co.taxNoteRate', { rate: s.taxRateLabel }) : s.taxRateLabel || t('co.taxNote');

  return (
    <div className={styles.layout}>
      <div className={styles.main}>
        <div className={styles.head}>
          <h1 className={styles.title}>{t('co.title')}</h1>
          <span className={styles.headMeta}>
            <LockSimple weight="fill" aria-hidden /> {t('co.items', { n: itemCount })}
          </span>
        </div>

        <Section
          n={1}
          id="co-address"
          title={t('co.step.address')}
          action={
            !addressList && (
              <button type="button" className="link-button" onClick={() => setAddressOpen(true)} aria-label={t('co.changeAria', { step: t('co.step.address') })}>
                {t('co.change')}
              </button>
            )
          }
        >
          {!addressList && address ? (
            <p className={styles.addressLine} data-testid="checkout-address">
              <strong>{address.name}</strong>
              <span>{addressLine(address)}</span>
              <span className={styles.muted}>{address.phone}</span>
            </p>
          ) : (
            <div className={styles.choices}>
              {s.addresses.length === 0 && <p className={styles.muted}>{t('co.noAddresses')}</p>}
              {s.addresses.map((a) => (
                <label key={a.id} className={styles.choice} data-testid="checkout-address">
                  <input type="radio" name="shipaddr" checked={a.id === addressId} onChange={() => setAddressId(a.id)} />
                  <span className={styles.choiceText}>
                    <strong>{a.name}</strong> {addressLine(a)}
                    <br />
                    <span className={styles.muted}>{a.phone}</span>
                  </span>
                  {a.isDefault && <span className={styles.tag}>{t('pay.default')}</span>}
                  <button type="button" className="link-button" onClick={() => setAddrDialog({ open: true, address: a })}>
                    {t('common.edit')}
                  </button>
                </label>
              ))}
              <div className={styles.rowActions}>
                <button type="button" className={styles.addRow} onClick={() => setAddrDialog({ open: true, address: null })}>
                  <Plus aria-hidden /> {t('co.addAddress')}
                </button>
                {address && (
                  <Button
                    size="sm"
                    onClick={() => {
                      setError(null);
                      setAddressOpen(false);
                    }}
                  >
                    {t('co.useAddress')}
                  </Button>
                )}
              </div>
            </div>
          )}
        </Section>

        <Section n={2} id="co-items" title={t('co.step.items')} action={<span className={styles.muted}>{t('co.sellers', { n: s.sellerCount })}</span>}>
          <div className={styles.packages}>
            {s.packages.map((p, i) => (
              <PackageCard
                key={p.packageId}
                pkg={p}
                n={i + 1}
                total={s.packages.length}
                choice={choices[p.packageId] ?? initialChoice(p)}
                onChoice={(c) => setChoices((x) => ({ ...x, [p.packageId]: c }))}
                onRemove={() => void removePackage(p)}
                onChangeAddress={changeAddress}
                removing={removing === p.packageId}
              />
            ))}
          </div>
        </Section>

        <Section n={3} id="co-payment" title={t('co.step.payment')} action={<CardBrandRow height={18} className={styles.brands} />}>
          <div className={styles.payment}>
            {s.paymentMethods.length > 0 && (
              <fieldset className={styles.saved}>
                <legend className={styles.legend}>{t('co.savedMethods')}</legend>
                {s.paymentMethods.map((p) => (
                  <label key={p.id} className={styles.choice} data-testid="checkout-payment">
                    <input
                      type="radio"
                      name="paym"
                      checked={payWith === 'saved' && p.id === pmId}
                      onChange={() => {
                        setPayWith('saved');
                        setPmId(p.id);
                      }}
                    />
                    <PaymentChip method={p} />
                    <span className={styles.choiceText}>
                      <strong>{p.label}</strong> <span className={styles.muted}>{pmSub(t, p)}</span>
                    </span>
                    {p.isDefault && <span className={styles.tag}>{t('pay.default')}</span>}
                  </label>
                ))}
              </fieldset>
            )}
            <div className={payWith === 'new' ? styles.newMethodOn : styles.newMethod} data-testid="checkout-new-method">
              <span className={styles.legend}>{s.paymentMethods.length > 0 ? t('co.orNew') : t('co.payWith')}</span>
              <PaymentElementField ref={payEl} publishableKey={s.publishableKey ?? ''} amountMinor={total} currency={s.currency} save={save} paymentMethodTypes={methodTypes} onChange={onNewMethod} />
              <label className={styles.save}>
                <input type="checkbox" checked={save} onChange={(e) => setSave(e.target.checked)} />
                <span>
                  {t('co.saveForLater')}
                  <small>{t('co.saveForLaterHint')}</small>
                </span>
              </label>
            </div>
            <div className={styles.secure}>
              <PoweredByStripe height={24} />
              <span className={styles.secureText}>
                <ShieldCheck aria-hidden /> {t('pay.storedByStripe')}
              </span>
            </div>
          </div>
        </Section>
      </div>

      <aside className={styles.aside} aria-label={t('co.orderSummary')}>
        <div className={styles.summaryCard}>
          <h2 className={styles.summaryTitle}>{t('co.orderSummary')}</h2>
          <dl className={styles.totals} data-testid="order-summary">
            <div className={styles.row}>
              <dt>{t('co.itemsTotal', { n: itemCount })}</dt>
              <dd>{fmt.money(s.subtotalMinor)}</dd>
            </div>
            <div className={styles.row}>
              <dt>{t('co.shippingShort')}</dt>
              <dd>{shipping === 0 ? t('co.free') : fmt.money(shipping)}</dd>
              {pickups > 0 && <dd className={styles.rowNote}>{t('co.pickupNote', { n: pickups })}</dd>}
            </div>
            <div className={styles.row}>
              <dt>{t('co.taxShort')}</dt>
              <dd>{s.taxMinor ? fmt.money(s.taxMinor) : '—'}</dd>
              {taxNote && <dd className={styles.rowNote}>{taxNote}</dd>}
            </div>
            <div className={styles.grandRow}>
              <dt>{t('co.total')}</dt>
              <dd>{fmt.money(total)}</dd>
            </div>
          </dl>
          <Button
            size="lg"
            block
            onClick={() => void (payWith === 'new' || !pm ? placeWithElement() : placeWithSaved())}
            loading={busy}
            disabled={!paymentChosen || !address || addressList || blocked}
            data-place-order="1"
          >
            {busy ? t('co.placing') : preLocked ? t('preorder.locked.button') : t('co.place')}
          </Button>
          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}
          {preLocked && preCopy?.copy.lockedText && (
            <p className={styles.fine} role="note" data-testid="preorder-checkout-locked">
              {preCopy.copy.lockedText}
            </p>
          )}
          {undeliverable && !preLocked && (
            <p className={styles.fine} data-testid="local-delivery-blocked">
              {t('ld.blocked')}
            </p>
          )}
          {addressList && address && <p className={styles.fine}>{t('co.confirmAddress')}</p>}
          {busy && (
            <ol className={styles.steps} aria-live="polite">
              {STEPS.map((k, i) => (
                <li key={k}>
                  {i < placing ? <CheckCircle weight="fill" aria-hidden /> : i === placing ? <CircleNotch className={styles.spin} aria-hidden /> : <Circle aria-hidden />} {t(k)}
                </li>
              ))}
            </ol>
          )}
          <p className={styles.fine}>
            {t('co.agreePrefix')} <Link href={legalHref('conditions-of-use')}>{t('co.conditions')}</Link> {t('co.and')} <Link href={legalHref('privacy')}>{t('co.privacy')}</Link>.{' '}
            {t('co.returnsPrefix')} <Link href={legalHref('returns')}>{t('legal.doc.returns')}</Link>.
          </p>
          {preBlock && (
            <p className={styles.fine} data-testid="preorder-terms-note">
              {t('co.preorderPrefix')} <Link href={legalHref('preorder-delivery-terms')}>{t('legal.doc.preorder-delivery-terms')}</Link>.
            </p>
          )}
          <div className={styles.stripeLine}>
            <PoweredByStripe height={24} />
            <span>{t('co.chargeNote')}</span>
          </div>
        </div>
      </aside>

      <AddressDialog open={addrDialog.open} onOpenChange={(o) => setAddrDialog((d) => ({ ...d, open: o }))} address={addrDialog.address} onSaved={(a) => setAddressId(a.id)} />
      <span hidden data-testid="checkout-pay-with">
        {payWith === 'new' ? (newMethod.type ?? 'new') : (pm?.id ?? '')}
      </span>
    </div>
  );
}
