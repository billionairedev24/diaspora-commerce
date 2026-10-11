'use client';

import {
  type Address,
  cartKeys,
  checkoutKeys,
  type CheckoutPackage,
  type DeliveryMethod,
  type DeliveryPreferences,
  type DeliverySelection,
  isApiError,
  type OrderConfirmation,
  orderKeys,
  type PaymentMethod,
  type Wallet
} from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, CheckCircle, Circle, CircleNotch, House, Key, LockSimple, Plus, ShieldCheck, Skeleton, Storefront, Truck, useToast, Warning } from '@afrotransact/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { type ReactNode, useEffect, useMemo, useRef, useState } from 'react';

import { AddressDialog } from '@/components/account/AddressDialog';
import { PaymentChip } from '@/components/payments/PaymentChip';
import { type PaymentElementChange, PaymentElementField, type PaymentElementHandle, REDIRECTED } from '@/components/payments/PaymentElementField';
import { QuickPay } from '@/components/payments/QuickPay';
import { useCartPreorderCopy } from '@/components/preorder/CartPreorder';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';
import { type ConfirmResult, confirmWithSavedMethod, stripePaymentMethodTypes } from '@/lib/payments/stripe';
import { readAttribution } from '@/lib/preorder/attribution';

import styles from './Checkout.module.css';

type Choice = { option: DeliveryMethod; pickupPointId?: string; preferences: DeliveryPreferences };

/** How the order is paid: a saved method, a wallet (Express Checkout) or a new method in the Payment Element. */
type PaySpec = { paymentMethodId: string } | { wallet: Wallet } | { newPaymentMethod: true; savePaymentMethod: boolean };

/** Where Stripe sends the shopper back after a redirect (3-D Secure page, Cash App, Affirm, bank): checks the payment. */
export const checkoutReturnUrl = (orderNumber: string) => `${window.location.origin}/checkout/complete?order=${encodeURIComponent(orderNumber)}`;

const NEW_METHOD_TYPES = ['card', 'link', 'cashapp', 'affirm', 'klarna', 'us_bank_account'] as const;
const newMethodLabel = (t: Translator<MessageKey>, type: string | null) =>
  type && (NEW_METHOD_TYPES as readonly string[]).includes(type) ? t(`co.pm.${type as (typeof NEW_METHOD_TYPES)[number]}`) : t('co.newMethodSummary');

const OPTION_ICON: Record<DeliveryMethod, ReactNode> = {
  carrier: <Truck aria-hidden />,
  express: <Truck weight="fill" aria-hidden />,
  afrotransact_delivery: <House aria-hidden />,
  pickup: <Storefront aria-hidden />
};

const addressLine = (a: Address) => `${a.name}, ${a.line1}${a.line2 ? ` ${a.line2}` : ''}, ${a.city}, ${a.state} ${a.zip}`;
const pmSub = (t: Translator<MessageKey>, pm: PaymentMethod) => (pm.expires ? t('pay.expires', { date: pm.expires }) : pm.type === 'us_bank_account' ? t('pay.bankAccount') : t('pay.wallet'));

/** Default choice per package: the first option; AfroTransact Delivery of perishables is handed over with a code. */
function initialChoice(p: CheckoutPackage): Choice {
  return { option: p.deliveryOptions[0]?.key ?? 'carrier', pickupPointId: p.pickupPoints?.[0]?.id, preferences: { handover: 'door', contact: 'text' } };
}

function Step({ n, title, done, onChange, children }: { n: number; title: string; done: boolean; onChange?: () => void; children: ReactNode }) {
  const t = useT();
  return (
    <section className={styles.step} aria-labelledby={`step-${n}`}>
      <div className={styles.stepHead}>
        <span className={done ? styles.numDone : styles.num} aria-hidden>
          {done ? <CheckCircle weight="fill" /> : n}
        </span>
        <h2 id={`step-${n}`} className={styles.stepTitle}>
          {title}
        </h2>
        {done && onChange && (
          <button type="button" className="link-button" onClick={onChange} aria-label={t('co.changeAria', { step: title })}>
            {t('co.change')}
          </button>
        )}
      </div>
      {children}
    </section>
  );
}

/** AfroTransact Delivery from the seller's store (S255): the quoted charge, distance and drive time, or why this address
 *  can't be served — with "remove from this order" and "change address". */
function LocalDeliveryQuote({ pkg, onRemove, onChangeAddress, removing }: { pkg: CheckoutPackage; onRemove: () => void; onChangeAddress: () => void; removing: boolean }) {
  const fmt = useFormatters();
  const t = useT();
  const ld = pkg.localDelivery;
  if (!ld) return null;
  const city = ld.fromCity || pkg.seller.city;
  if (ld.available) {
    return (
      <div className={styles.localQuote} data-testid="local-delivery-quote">
        <strong>{t('ld.title', { city })}</strong>
        {ld.chargeMinor !== null && ld.roadMiles !== null ? (
          <span>
            {t('ld.quote', { miles: fmt.number(ld.roadMiles, { maximumFractionDigits: 1 }), city, minutes: Math.round(ld.durationMinutes ?? 0) })} ·{' '}
            {t('ld.charge', { amount: fmt.money(ld.chargeMinor) })}
          </span>
        ) : (
          <span className={styles.muted}>{ld.message ?? t('ld.pricedLater')}</span>
        )}
      </div>
    );
  }
  return (
    <div className={styles.localProblem} role="group" aria-labelledby={`ld-${pkg.packageId}`} data-testid="local-delivery-problem">
      <strong id={`ld-${pkg.packageId}`}>
        <Warning weight="fill" aria-hidden /> {ld.unavailableReason === 'OUT_OF_RANGE' ? t('ld.outOfRangeTitle') : t('ld.unavailableTitle')}
      </strong>
      {ld.message && <p className={styles.localMessage}>{ld.message}</p>}
      <div className={styles.localActions}>
        <Button size="sm" variant="secondary" loading={removing} onClick={onRemove} aria-label={t('ld.removeAria', { seller: pkg.seller.name })}>
          {t('ld.remove')}
        </Button>
        <Button size="sm" variant="neutral" onClick={onChangeAddress}>
          {t('ld.changeAddress')}
        </Button>
      </div>
    </div>
  );
}

function PackageDelivery({
  pkg,
  n,
  total,
  choice,
  onChoice,
  onRemove,
  onChangeAddress,
  removing
}: {
  pkg: CheckoutPackage;
  n: number;
  total: number;
  choice: Choice;
  onChoice: (c: Choice) => void;
  onRemove: () => void;
  onChangeAddress: () => void;
  removing: boolean;
}) {
  const fmt = useFormatters();
  const t = useT();
  const undeliverable = pkg.localDelivery?.available === false;
  const perishable = pkg.lines.some((l) => l.perishable);
  const atd = choice.option === 'afrotransact_delivery';
  const handover = perishable && atd ? 'hand' : (choice.preferences.handover ?? 'door');
  const needsCode = (atd || choice.option === 'pickup') && (perishable || handover === 'hand');
  const prefs = (p: Partial<DeliveryPreferences>) => onChoice({ ...choice, preferences: { ...choice.preferences, ...p } });
  const selected = pkg.deliveryOptions.find((o) => o.key === choice.option);
  return (
    <div className={styles.package} data-testid="checkout-package">
      <div className={styles.packageHead}>
        <strong className={styles.arriving}>{t('cart.arriving', { eta: selected?.eta ?? pkg.delivery.label })}</strong>
        <span className={styles.muted}>
          {pkg.heading} · {t('cart.package', { n, total })}
        </span>
      </div>
      <div className={styles.packageBody}>
        <ul className={styles.items}>
          {pkg.lines.map((l) => (
            <li key={l.lineId} className={styles.item}>
              <span className={styles.thumb} aria-hidden />
              <span>
                {l.title}
                <br />
                <span className={styles.muted}>{t('co.qtySoldBy', { qty: l.quantity, seller: pkg.seller.name })}</span>
              </span>
              <strong>{fmt.money(l.lineTotalMinor)}</strong>
            </li>
          ))}
        </ul>
        <LocalDeliveryQuote pkg={pkg} onRemove={onRemove} onChangeAddress={onChangeAddress} removing={removing} />
        <fieldset className={styles.options} disabled={undeliverable}>
          <legend className={styles.legend}>{t('co.deliveryOption')}</legend>
          {pkg.deliveryOptions.map((o) => (
            <div key={o.key}>
              <label className={styles.option}>
                <input type="radio" name={`opt-${pkg.packageId}`} checked={choice.option === o.key} onChange={() => onChoice({ ...choice, option: o.key })} />
                <span>
                  {OPTION_ICON[o.key]} <strong className={styles.eta}>{o.eta}</strong>
                  <br />
                  {undeliverable ? t('ld.notAvailableOption') : <>{o.label} · {o.free || o.feeMinor === 0 ? t('co.free') : fmt.money(o.feeMinor)}</>}
                  {o.cutoffLabel && choice.option === o.key && <span className={styles.cutoff}> · {o.cutoffLabel}</span>}
                </span>
              </label>
              {o.key === 'pickup' && choice.option === 'pickup' && (
                <div className={styles.points}>
                  {(pkg.pickupPoints ?? []).map((pt) => (
                    <label key={pt.id} className={styles.option}>
                      <input type="radio" name={`pt-${pkg.packageId}`} checked={choice.pickupPointId === pt.id} onChange={() => onChoice({ ...choice, pickupPointId: pt.id })} />
                      <span>
                        <strong>{pt.name}</strong> <span className={styles.muted}>· {pt.setBy === 'seller' ? t('co.setBySeller') : t('co.setByPlatform')}</span>
                        <br />
                        {pt.address}
                        <br />
                        <span className={styles.muted}>
                          {pt.hours} · {pt.ready}
                        </span>
                      </span>
                    </label>
                  ))}
                </div>
              )}
            </div>
          ))}
        </fieldset>
        {atd && !undeliverable && (
          <fieldset className={styles.prefs}>
            <legend className={styles.legend}>{t('co.prefs')}</legend>
            {(['hand', 'door', 'desk'] as const).map((h) => (
              <label key={h} className={styles.option} aria-label={t(`co.handover.${h}`)}>
                <input type="radio" name={`ho-${pkg.packageId}`} checked={handover === h} disabled={perishable && h !== 'hand'} onChange={() => prefs({ handover: h })} />
                <span>
                  <strong>{t(`co.handover.${h}`)}</strong>
                  <br />
                  <span className={styles.muted}>{t(`co.handover.${h}.hint`)}</span>
                </span>
              </label>
            ))}
            {handover === 'door' && (
              <select aria-label={t('co.safePlace')} value={choice.preferences.safePlace ?? ''} onChange={(e) => prefs({ safePlace: e.target.value })}>
                <option value="">{t('co.safePlace')}</option>
                {['Front door', 'Back porch', 'Garage', 'Mailroom'].map((p) => (
                  <option key={p} value={p}>
                    {p}
                  </option>
                ))}
              </select>
            )}
            <label className={styles.field}>
              {t('co.instructions')}
              <textarea rows={2} maxLength={240} placeholder={t('co.instructionsPlaceholder')} value={choice.preferences.instructions ?? ''} onChange={(e) => prefs({ instructions: e.target.value })} />
            </label>
            <label className={styles.field}>
              {t('co.contact')}
              <select value={choice.preferences.contact ?? 'text'} onChange={(e) => prefs({ contact: e.target.value as DeliveryPreferences['contact'] })}>
                <option value="text">{t('co.contact.text')}</option>
                <option value="call">{t('co.contact.call')}</option>
                <option value="none">{t('co.contact.none')}</option>
              </select>
            </label>
          </fieldset>
        )}
        {needsCode && (
          <p className={styles.codeNote}>
            <Key aria-hidden /> {perishable ? t('co.codePerishable') : t('co.codeHand')}
          </p>
        )}
      </div>
    </div>
  );
}

const STEPS = ['co.placing.payment', 'co.placing.confirm', 'co.placing.stock', 'co.placing.split'] as const;

/**
 * Three-step checkout (S047, S048, S282): delivery address, payment and items with a delivery choice per package
 * (carrier, AfroTransact Delivery, pickup — promise dates from the BFF). Each step folds to a summary with Change; the
 * order summary stays on the right.
 *
 * Payment is Stripe Elements on the deferred-intent flow: the Express Checkout Element (Apple Pay, Google Pay, Link) at
 * the top, then the shopper's saved methods, then "Use a new payment method" with the Payment Element inline (created
 * from the cart total, updated as delivery choices change; "Save for future purchases" sets setupFutureUsage). Place
 * order runs elements.submit(), places the order (orders creates the PaymentIntent, manual capture), confirms it with
 * stripe.confirmPayment({ elements, clientSecret }) and lets orders continue; redirect-based methods come back to
 * /checkout/complete. Saved methods confirm with their PaymentMethod id as before.
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
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [pmId, setPmId] = useState<string | null>(null);
  const [payWith, setPayWith] = useState<'saved' | 'new' | null>(null);
  const [save, setSave] = useState(false);
  const [newMethod, setNewMethod] = useState<PaymentElementChange>({ complete: false, type: null });
  const payEl = useRef<PaymentElementHandle>(null);
  const [choices, setChoices] = useState<Record<string, Choice>>({});
  const [addrDialog, setAddrDialog] = useState<{ open: boolean; address: Address | null }>({ open: false, address: null });
  const [placing, setPlacing] = useState(-1);
  const [error, setError] = useState<string | null>(null);
  const keyRef = useRef<string>(crypto.randomUUID());

  // Saved addresses and payment methods load by default (the default one preselected).
  useEffect(() => {
    if (!s) return;
    if (!addressId) {
      const def = s.addresses.find((a) => a.isDefault) ?? s.addresses[0];
      if (def) setAddressId(def.id);
    }
    if (!pmId || !s.paymentMethods.some((p) => p.id === pmId)) setPmId((s.paymentMethods.find((p) => p.isDefault) ?? s.paymentMethods[0])?.id ?? null);
    setPayWith((w) => w ?? (s.paymentMethods.length ? 'saved' : 'new'));
    setChoices((c) => {
      const next = { ...c };
      for (const p of s.packages) {
        const cur = next[p.packageId];
        if (!cur || !p.deliveryOptions.some((o) => o.key === cur.option)) next[p.packageId] = initialChoice(p);
      }
      return next;
    });
  }, [s, addressId, pmId]);

  const address = s?.addresses.find((a) => a.id === addressId) ?? null;
  const pm = s?.paymentMethods.find((p) => p.id === pmId) ?? null;
  const shipping = useMemo(
    () => (s ? s.packages.reduce((sum, p) => sum + (p.deliveryOptions.find((o) => o.key === choices[p.packageId]?.option)?.feeMinor ?? p.deliveryOptions[0]?.feeMinor ?? 0), 0) : 0),
    [s, choices]
  );
  const total = (s?.subtotalMinor ?? 0) + shipping + (s?.taxMinor ?? 0);
  const itemCount = s?.packages.reduce((n, p) => n + p.itemCount, 0) ?? 0;
  const busy = placing >= 0;
  // AfroTransact Delivery packages the chosen address can't be served by block the order until removed (S255).
  // A pre-order package whose window is not open (upcoming or closed) blocks the order too (S273, 422 orders.preorder-not-open).
  const preBlock = (s?.packages ?? []).find((p) => p.preorder)?.preorder ?? null;
  const preCopy = useCartPreorderCopy(preBlock);
  const preLocked = !!preBlock && preBlock.stage !== 'OPEN';
  // Pre-order windows may restrict payment methods; the PaymentIntent and the Elements must use the same list.
  const allowedByWindow = (s?.packages ?? []).flatMap((p) => (p.preorder ? [p.preorder.allowedMethods.join(',')] : [])).join('|');
  const methodTypes = useMemo(() => (allowedByWindow ? stripePaymentMethodTypes(allowedByWindow.split('|').map((w) => w.split(','))) : undefined), [allowedByWindow]);
  const paymentChosen = payWith === 'new' || (payWith === 'saved' && !!pm);
  const blocked = (s?.packages ?? []).some((p) => p.localDelivery?.available === false) || preLocked;
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
    setStep(1);
  };

  const selections = (): DeliverySelection[] =>
    (s?.packages ?? []).map((p) => {
      const c = choices[p.packageId] ?? initialChoice(p);
      const perishable = p.lines.some((l) => l.perishable);
      return {
        packageId: p.packageId,
        option: c.option,
        pickupPointId: c.option === 'pickup' ? c.pickupPointId : undefined,
        preferences: c.option === 'afrotransact_delivery' ? { ...c.preferences, handover: perishable ? 'hand' : c.preferences.handover, safePlace: c.preferences.safePlace || undefined, instructions: c.preferences.instructions?.trim() || undefined } : undefined
      };
    });

  const fail = (e: unknown) => {
    setPlacing(-1);
    keyRef.current = crypto.randomUUID();
    setError(isApiError(e) ? (Object.values(e.fieldErrors)[0] ?? e.message) : e instanceof Error ? e.message : t('auth.genericError'));
    void qc.invalidateQueries({ queryKey: checkoutKeys.all });
  };

  const place = async (pay: PaySpec): Promise<OrderConfirmation | null> => {
    if (!address) return (setStep(1), setError(t('co.needAddress')), null);
    setError(null);
    setPlacing(0);
    try {
      // Last-touch channel (?src=&cr=&po= or the pre-order placement clicked) is stored on the order.
      const attribution = readAttribution() ?? undefined;
      return await api.placeOrder({ addressId: address.id, ...pay, deliverySelections: selections(), attribution }, keyRef.current);
    } catch (e) {
      fail(e);
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
      setPlacing(3);
      await Promise.all([qc.invalidateQueries({ queryKey: cartKeys.all }), qc.invalidateQueries({ queryKey: orderKeys.all })]);
      router.push(`/orders?placed=${encodeURIComponent(status.orderNumber)}`);
    } catch (e) {
      fail(e);
    }
  };

  const placeWithCard = async () => {
    if (!pm) return (setStep(2), setError(t('co.needPayment')));
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
    if (!el) return (setStep(2), setError(t('co.needPayment')));
    setError(null);
    const submitted = await el.submit();
    if (!submitted.ok) return (setStep(2), setError(submitted.message));
    const conf = await place({ newPaymentMethod: true, savePaymentMethod: save });
    if (!conf) return;
    setPlacing(1);
    const confirmed = conf.paymentClientSecret ? await el.confirm(conf.paymentClientSecret, checkoutReturnUrl(conf.orderNumber)) : ({ ok: true } as const);
    // A decline or failed authentication is shown in the Payment Element: reopen it so the shopper can fix or switch.
    if (!confirmed.ok) setStep(2);
    await finish(conf, confirmed);
  };

  const confirmPaymentChoice = async () => {
    setError(null);
    if (payWith === 'new') {
      // Validates the Payment Element now, so problems show next to the fields before the review step.
      const submitted = await payEl.current?.submit();
      if (!submitted?.ok) return;
    }
    setStep(3);
  };

  const walletConf = useRef<OrderConfirmation | null>(null);

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

  return (
    <div className={styles.layout}>
      <div className={styles.main}>
        <div className={styles.head}>
          <h1 className={styles.title}>{t('co.title')}</h1>
          <span className={styles.muted}>
            <LockSimple weight="fill" aria-hidden /> {t('co.items', { n: itemCount })}
          </span>
        </div>

        <Step n={1} title={step > 1 && address ? t('co.deliveringTo') : t('co.chooseAddress')} done={step > 1 && !!address} onChange={() => setStep(1)}>
          {step > 1 && address ? (
            <p className={styles.summaryLine}>
              {addressLine(address)}
              <br />
              <span className={styles.muted}>{address.phone}</span>
            </p>
          ) : (
            <div className={styles.choices}>
              {s.addresses.length === 0 && <p className={styles.muted}>{t('co.noAddresses')}</p>}
              {s.addresses.map((a) => (
                <label key={a.id} className={styles.choice} data-testid="checkout-address">
                  <input type="radio" name="shipaddr" checked={a.id === addressId} onChange={() => setAddressId(a.id)} />
                  <span className={styles.choiceText}>
                    <strong>{a.name}</strong> {a.line1}
                    {a.line2 ? ` ${a.line2}` : ''}, {a.city}, {a.state} {a.zip}
                    <br />
                    <span className={styles.muted}>{a.phone}</span>
                  </span>
                  {a.isDefault && <span className={styles.tag}>{t('pay.default')}</span>}
                  <button type="button" className="link-button" onClick={() => setAddrDialog({ open: true, address: a })}>
                    {t('common.edit')}
                  </button>
                </label>
              ))}
              <button type="button" className={styles.addRow} onClick={() => setAddrDialog({ open: true, address: null })}>
                <Plus aria-hidden /> {t('co.addAddress')}
              </button>
              <Button onClick={() => { setError(null); setStep(2); }} disabled={!address}>
                {t('co.useAddress')}
              </Button>
            </div>
          )}
        </Step>

        <Step n={2} title={step > 2 && paymentChosen ? t('co.payingWith') : t('co.choosePayment')} done={step > 2 && paymentChosen} onChange={() => setStep(2)}>
          {step < 2 && <p className={styles.muted}>{t('co.afterAddress')}</p>}
          {step > 2 && payWith === 'saved' && pm && (
            <p className={styles.summaryLine}>
              <PaymentChip method={pm} /> {pm.label} <span className={styles.muted}>· {pmSub(t, pm)}</span>
              <br />
              <span className={styles.muted}>{t('co.billingSame')}</span>
            </p>
          )}
          {step > 2 && payWith === 'new' && (
            <p className={styles.summaryLine} data-testid="checkout-new-method-summary">
              <strong>{newMethodLabel(t, newMethod.type)}</strong> <span className={styles.muted}>· {t('co.newMethodSummary')}</span>
              {save && <span className={styles.muted}>· {t('co.saveForLater')}</span>}
            </p>
          )}
          {/* Mounted from step 2 on and only hidden afterwards: Place order submits and confirms this Payment Element. */}
          {step >= 2 && (
            <div className={styles.choices} hidden={step !== 2}>
              <QuickPay
                publishableKey={s.publishableKey ?? ''}
                amountMinor={total}
                currency={s.currency}
                paymentMethodTypes={methodTypes}
                disabled={busy || preLocked}
                onPay={async (w) => {
                  const conf = await place({ wallet: w });
                  walletConf.current = conf;
                  if (conf) setPlacing(1);
                  return conf?.paymentClientSecret ? { clientSecret: conf.paymentClientSecret, returnUrl: checkoutReturnUrl(conf.orderNumber) } : null;
                }}
                onConfirmed={(r) => { if (walletConf.current) void finish(walletConf.current, r); else if (!r.ok) fail(new Error(r.message)); }}
              />
              <div className={styles.or}>
                <span />
                {t('co.orSaved')}
                <span />
              </div>
              {s.paymentMethods.map((p) => (
                <label key={p.id} className={styles.choice} data-testid="checkout-payment">
                  <input type="radio" name="paym" checked={payWith === 'saved' && p.id === pmId} onChange={() => { setPayWith('saved'); setPmId(p.id); }} />
                  <PaymentChip method={p} />
                  <span className={styles.choiceText}>
                    <strong>{p.label}</strong> <span className={styles.muted}>{pmSub(t, p)}</span>
                  </span>
                  {p.isDefault && <span className={styles.tag}>{t('pay.default')}</span>}
                </label>
              ))}
              <label className={styles.choice} data-testid="checkout-new-method">
                <input type="radio" name="paym" checked={payWith === 'new'} onChange={() => setPayWith('new')} />
                <Plus aria-hidden />
                <span className={styles.choiceText}>
                  <strong>{t('co.newMethod')}</strong> <span className={styles.muted}>{t('co.newMethodHint')}</span>
                </span>
              </label>
              {payWith === 'new' && (
                <div className={styles.newMethod}>
                  <PaymentElementField
                    ref={payEl}
                    publishableKey={s.publishableKey ?? ''}
                    amountMinor={total}
                    currency={s.currency}
                    save={save}
                    paymentMethodTypes={methodTypes}
                    onChange={setNewMethod}
                  />
                  <label className={styles.save}>
                    <input type="checkbox" checked={save} onChange={(e) => setSave(e.target.checked)} />
                    <span>
                      {t('co.saveForLater')}
                      <small>{t('co.saveForLaterHint')}</small>
                    </span>
                  </label>
                </div>
              )}
              <Button onClick={() => void confirmPaymentChoice()} disabled={!paymentChosen}>
                {t('co.usePayment')}
              </Button>
              <span className={styles.muted}>
                <ShieldCheck aria-hidden /> {t('pay.storedByStripe')}
              </span>
            </div>
          )}
        </Step>

        <Step n={3} title={t('co.review')} done={false}>
          {step < 3 ? (
            <p className={styles.muted}>{t('co.afterPayment')}</p>
          ) : (
            s.packages.map((p, i) => (
              <PackageDelivery
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
            ))
          )}
        </Step>
      </div>

      <aside className={styles.aside} aria-label={t('co.orderSummary')}>
        <Button size="lg" block onClick={() => void (payWith === 'new' ? placeWithElement() : placeWithCard())} loading={busy} disabled={step < 3 || !paymentChosen || !address || blocked} data-place-order="1">
          {busy ? t('co.placing') : preLocked ? t('preorder.locked.button') : t('co.place')}
        </Button>
        {preLocked && preCopy?.copy.lockedText && (
          <p className={styles.fine} role="note" data-testid="preorder-checkout-locked">
            {preCopy.copy.lockedText}
          </p>
        )}
        {blocked && !preLocked && step === 3 && (
          <p className={styles.fine} data-testid="local-delivery-blocked">
            {t('ld.blocked')}
          </p>
        )}
        <span className={styles.fine}>
          {t('co.agreePrefix')} <Link href="/legal/conditions-of-use">{t('co.conditions')}</Link> {t('co.and')} <Link href="/legal/privacy">{t('co.privacy')}</Link>.
        </span>
        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}
        <strong>{t('co.orderSummary')}</strong>
        <span className={styles.muted}>{t('co.sellers', { n: s.sellerCount })}</span>
        <dl className={styles.totals}>
          <dt>{t('co.itemsTotal', { n: itemCount })}</dt>
          <dd>{fmt.money(s.subtotalMinor)}</dd>
          <dt>{t('co.shipping')}</dt>
          <dd>{shipping === 0 ? t('co.free') : fmt.money(shipping)}</dd>
          <dt>{t('co.tax')}</dt>
          <dd>{s.taxMinor ? fmt.money(s.taxMinor) : s.taxRateLabel}</dd>
          <dt className={styles.grand}>{t('co.total')}</dt>
          <dd className={styles.grand}>{fmt.money(total)}</dd>
        </dl>
        {busy && (
          <ol className={styles.steps} aria-live="polite">
            {STEPS.map((k, i) => (
              <li key={k}>
                {i < placing ? <CheckCircle weight="fill" aria-hidden /> : i === placing ? <CircleNotch className={styles.spin} aria-hidden /> : <Circle aria-hidden />} {t(k)}
              </li>
            ))}
          </ol>
        )}
        <span className={styles.fine}>
          <LockSimple weight="fill" aria-hidden /> {t('co.stripeNote')}
        </span>
      </aside>

      <AddressDialog open={addrDialog.open} onOpenChange={(o) => setAddrDialog((d) => ({ ...d, open: o }))} address={addrDialog.address} onSaved={(a) => setAddressId(a.id)} />
    </div>
  );
}
