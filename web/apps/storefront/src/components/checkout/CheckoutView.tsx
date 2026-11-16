'use client';

import {
  type Address,
type Cart,  cartKeys,
  type CheckoutBlockedLine,
  checkoutKeys,
  type CheckoutPackage,
  creditSplit,
  definitiveFailure,
  deliveryQuoteKeys,
  type DeliverySelection,
  isApiError,
  isCartMismatch,
  type OrderConfirmation,
  orderKeys, randomUuid,
  type TotalChange,
  totalChangeOf } from '@afrotransact/api-client';
import { getTelemetry } from '@afrotransact/telemetry';
import { Button, Plus, Skeleton, useToast } from '@afrotransact/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { useEffect, useMemo, useRef, useState } from 'react';

import { AddressDialog } from '@/components/account/AddressDialog';
import { type PaymentElementChange, type PaymentElementHandle, type PaymentElementStatus, REDIRECTED, stripeJsDown } from '@/components/payments/PaymentElementField';
import { useCartPreorderCopy } from '@/components/preorder/CartPreorder';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';
import { useCart } from '@/lib/cart/useCart';
import { type ConfirmResult, confirmWithSavedMethod, stripePaymentMethodTypes } from '@/lib/payments/stripe';
import { readAttribution } from '@/lib/preorder/attribution';

import { AppliedQuoteNotice, quotedPackage, quotedShipping, useAppliedQuote } from './AppliedQuote';
import { AskForQuote } from './AskForQuote';
import styles from './Checkout.module.css';
import { CheckoutOrderPanel } from './CheckoutOrderPanel';
import { CheckoutState } from './CheckoutState';
import { CheckoutStep, type StepState } from './CheckoutStep';
import { type Choice, feeOf, initialChoice, lineIssue, PackageCard, placeable, promiseOf } from './PackageCard';
import { PaymentStep } from './PaymentStep';
import { PromoCode } from './PromoCode';

/** How the order is paid: saved card or new details via the Payment Element. */
type PaySpec = { paymentMethodId: string } | { newPaymentMethod: true; savePaymentMethod: boolean } | Record<string, never>;

/** Where Stripe sends the shopper back after a redirect (3-D Secure page, Cash App, Affirm, bank): checks the payment. */
export const checkoutReturnUrl = (orderNumber: string) => `${window.location.origin}/checkout/complete?order=${encodeURIComponent(orderNumber)}`;

const addressLine = (a: Address) => `${a.line1}${a.line2 ? ` ${a.line2}` : ''}, ${a.city}, ${a.state} ${a.zip}`;
/**
 * Checkout (S047, S048, S282), the Amazon pattern: two columns from 1000px. The left column is three numbered sections
 * (1 Delivery address, 2 Payment method, 3 Review items and delivery) of which only the first incomplete one is open,
 * the others a one-line summary with Change. The sticky right column is Place your order, the terms line and the order
 * summary. Below 1000px it is one column, the summary after the sections and Place order repeated in a sticky bar. The
 * header and footer are the minimal ones of the (checkout) layout.
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
  /** The section the shopper opened with Change; null = the first incomplete one is open (Amazon behaviour). */
  const [openStep, setOpenStep] = useState<number | null>(null);
  const [pmId, setPmId] = useState<string | null>(null);
  const [payWith, setPayWith] = useState<'saved' | 'new' | null>(null);
  const [save, setSave] = useState(false);
  const [newMethod, setNewMethod] = useState<PaymentElementChange>({ complete: false, empty: true, type: null });
  const payEl = useRef<PaymentElementHandle>(null);
  /** Whether the Payment Element could load; a failure replaces the form with one line and Try again, and Place order waits. */
  const [elementStatus, setElementStatus] = useState<PaymentElementStatus>({ state: 'loading' });
  const [elementAttempt, setElementAttempt] = useState(0);
  /** Pay with AfroTransact credit first (S320); off until the shopper chooses it, so credit is never spent by surprise. */
  const [useCredit, setUseCredit] = useState(false);
  const [couponDraft, setCouponDraft] = useState('');
  const [appliedCoupon, setAppliedCoupon] = useState('');
  /** Sent to the BFF so shipping, tax and coupons match the selected delivery option per package. */
  const [packageDeliveryKey, setPackageDeliveryKey] = useState<string | undefined>();
  /** The delivery quote the shopper came back with (/checkout?quote=<id>, the pay link in the email). */
  const applied = useAppliedQuote();
  const summary = useQuery(shopperQueries.checkoutSummary(api, addressId, packageDeliveryKey, appliedCoupon || undefined));
  const s = summary.data;
  // The cart itself (the header's badge counts every item, the checkout only the ticked ones): tells "empty" from "none selected".
  const cart = useCart();
  const [addrDialog, setAddrDialog] = useState<{ open: boolean; address: Address | null }>({ open: false, address: null });
  const [placing, setPlacing] = useState(-1);
  const [error, setError] = useState<string | null>(null);
  const keyRef = useRef<string>(randomUuid());
  /** Place order was refused because the total would be higher: the new total, confirmed before anything is charged. */
  const [totalChange, setTotalChange] = useState<TotalChange | null>(null);
  /** The lock of the total the shopper confirmed (from the 409): sent instead of the summary's until the summary is read again. */
  const confirmedLock = useRef<string | null>(null);
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

  // The lock on the shown total lasts 15 minutes: read the summary again when it runs out, so what is shown is what will be charged.
  const lockExpiresAt = s?.taxQuoteExpiresAt;
  const refetchSummary = summary.refetch;
  useEffect(() => {
    if (!lockExpiresAt) return;
    const wait = Math.max(0, new Date(lockExpiresAt).getTime() - Date.now());
    const timer = setTimeout(() => void refetchSummary(), Math.min(wait, 2 ** 31 - 1));
    return () => clearTimeout(timer);
  }, [lockExpiresAt, refetchSummary]);

  // Why card payment is not offered is recorded (tag `reason`) so support can tell a blocked Stripe.js from a rejected key.
  const failedReason = elementStatus.state === 'failed' ? elementStatus.reason : null;
  useEffect(() => {
    if (failedReason) getTelemetry().captureMessage('Checkout card payment unavailable', { level: 'warning', tags: { area: 'checkout-payment', reason: failedReason } });
  }, [failedReason]);
  const retryElement = () => {
    setElementStatus({ state: 'loading' });
    setElementAttempt((n) => n + 1);
  };

  const address = s?.addresses.find((a) => a.id === addressId) ?? null;
  const pm = s?.paymentMethods.find((p) => p.id === pmId) ?? null;
  const selectedShipping = useMemo(() => {
    if (!s) return 0;
    // An agreed delivery quote replaces the fee of the delivered packages (orders charges it once, taxed like any shipping).
    if (applied.usable?.amountMinor != null)
      return quotedShipping(s.packages, (p) => choices[p.packageId]?.option, (p) => feeOf(p, choices[p.packageId]), applied.usable.amountMinor);
    return s.packages.reduce((sum, p) => sum + feeOf(p, choices[p.packageId]), 0);
  }, [s, choices, applied.usable]);
  const pickups = s ? s.packages.filter((p) => choices[p.packageId]?.option === 'pickup').length : 0;
  /** Optimistic total: shipping follows UI choices immediately; tax and coupon discounts follow the BFF summary. */
  const total = useMemo(() => {
    if (!s) return 0;
    // The membership's waiver (S323) comes from the summary: it follows the delivery options the BFF last priced.
    const ship = Math.max(0, selectedShipping - (s.shippingDiscountMinor ?? 0) - (s.membershipWaivedMinor ?? 0));
    return s.subtotalMinor - (s.discountMinor ?? 0) + ship + s.taxMinor;
  }, [s, selectedShipping]);
  // Credit first, never more than the balance, at least 0.50 left for the card (the rule payments applies).
  const split = useMemo(() => (s?.credit && useCredit ? creditSplit(s.credit.availableMinor, total) : { appliedMinor: 0, cardDueMinor: total }), [s, useCredit, total]);
  const creditPaysAll = split.appliedMinor > 0 && split.cardDueMinor === 0;
  const itemCount = s?.packages.reduce((n, p) => n + p.itemCount, 0) ?? 0;
  const busy = placing >= 0;
  // A pre-order package whose window is not open (upcoming or closed) blocks the order (S273, 422 orders.preorder-not-open).
  const preBlock = (s?.packages ?? []).find((p) => p.preorder)?.preorder ?? null;
  const preCopy = useCartPreorderCopy(preBlock);
  const preLocked = !!preBlock && preBlock.stage !== 'OPEN';
  // Pre-order windows may restrict payment methods; the PaymentIntent and the Elements must use the same list.
  const allowedByWindow = (s?.packages ?? []).flatMap((p) => (p.preorder ? [p.preorder.allowedMethods.join(',')] : [])).join('|');
  const methodTypes = useMemo(() => (allowedByWindow ? stripePaymentMethodTypes(allowedByWindow.split('|').map((w) => w.split(','))) : undefined), [allowedByWindow]);
  const cardDown = elementStatus.state === 'failed';
  // Valid payment: credit pays it all, a saved method (with Stripe.js up), or a new method whose details are complete.
  const paymentChosen = creditPaysAll || (payWith === 'new' && !cardDown && newMethod.complete) || (payWith === 'saved' && !!pm && !stripeJsDown(elementStatus));
  // Card payment is out and nothing else pays the order: Place order says why it is off.
  const paymentReason = cardDown && !paymentChosen ? t('co.payDown.placeNote') : null;
  // AfroTransact Delivery the chosen address can't be served by blocks the order until removed or picked up instead (S255).
  const undeliverable = (s?.packages ?? []).some((p) => p.localDelivery?.available === false && (choices[p.packageId]?.option ?? initialChoice(p).option) !== 'pickup');
  // Lines without a definite tax decision can't be sold yet: the order can't be placed until the shopper removes them.
  const blockedLines = s?.blockedLines ?? [];
  // No estimate (tax-returns could not answer, an address without a state or ZIP code, no ZIP yet) stops the order too: the shopper
  // is never sent to pay a total whose tax nobody worked out.
  const taxBlocked = blockedLines.length > 0 || (s?.canPlaceOrder === false && !s.deliveryBlockedReason) || (!!s && s.taxEstimateStatus !== 'ESTIMATED');
  // A package outside every delivery zone (quote_required) can't be paid for until the shopper has an agreed delivery quote
  // (/checkout?quote=<id>), picks the package up or changes the address; a delivery fee that could not be read blocks it too
  // (listing rules D20, D21). Never a dead end: the quote box above the packages and pickup are always there.
  const chosenOptions = (s?.packages ?? []).map((p) => p.deliveryOptions.find((o) => o.key === (choices[p.packageId]?.option ?? initialChoice(p).option)));
  const quoteBlocked = chosenOptions.some((o) => o?.key === 'quote_required') && !applied.usable;
  const feeUnknown = chosenOptions.some((o) => o?.feeUnknown);
  // An item out of stock, or wanted in a larger quantity than is left, is said on the item itself and stops the order.
  const stockIssue = (s?.packages ?? []).some((p) => p.lines.some((l) => ['out', 'short'].includes(lineIssue(l, blockedLines)?.kind ?? '')));
  const deliveryReason = quoteBlocked ? t('co.quote.blocked') : feeUnknown ? t('co.fee.unknownBlocked') : stockIssue ? t('co.line.placeHint') : null;
  const blocked = undeliverable || preLocked || taxBlocked || quoteBlocked || feeUnknown || stockIssue;
  const [removing, setRemoving] = useState<string | null>(null);
  const [removingLine, setRemovingLine] = useState<string | null>(null);
  /** Takes the line out of this order (it stays in the cart, as for an undeliverable package). */
  const removeBlocked = async (l: Pick<CheckoutBlockedLine, 'lineId'>) => {
    setRemovingLine(l.lineId);
    try {
      await api.updateCartLine(l.lineId, { selected: false });
      toast.show(t('ld.removed'));
      await Promise.all([qc.invalidateQueries({ queryKey: cartKeys.all }), qc.invalidateQueries({ queryKey: checkoutKeys.all })]);
    } catch (e) {
      setError(isApiError(e) ? e.message : t('auth.genericError'));
    } finally {
      setRemovingLine(null);
    }
  };
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
    setOpenStep(1);
    document.getElementById('co-address')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };
  /** Takes one item out of this order (it stays in the cart): the bin of its quantity selector and the blocked-item Remove. */
  const removeLineById = (lineId: string) => void removeBlocked({ lineId });

  const selections = (): DeliverySelection[] =>
    (s?.packages ?? []).map((p) => {
      const c = choices[p.packageId] ?? initialChoice(p);
      const perishable = p.lines.some((l) => l.perishable);
      return {
        packageId: p.packageId,
        option: placeable(c.option, !!applied.usable),
        pickupPointId: c.option === 'pickup' ? (c.pickupPointId ?? p.pickupPoints?.[0]?.id) : undefined,
        quoteId: applied.usable && quotedPackage(p, c.option) ? applied.usable.id : undefined,
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
    qc.setQueryData(cartKeys.current(), (old: Cart | undefined) =>
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
      keyRef.current = randomUuid();
      orderRef.current = null;
    }
    end();
    const changed = totalChangeOf(e);
    if (changed) {
      // Nothing was charged. Show the new total and ask first; the summary is read again so the page shows the same numbers.
      setTotalChange(changed);
      confirmedLock.current = changed.taxQuoteId ?? null;
      void qc.invalidateQueries({ queryKey: checkoutKeys.all });
      return;
    }
    setError(
      isCartMismatch(e)
        ? t('co.cartChanged')
        : isApiError(e) && e.code === 'CHECKOUT_ITEM_BLOCKED'
          ? t('co.blocked.placeError')
          : isApiError(e) && (e.code === 'STOCK_LIMIT' || e.code === 'OUT_OF_STOCK' || e.code === 'STOCK_CHANGED')
            ? t('co.stockChanged')
            : isApiError(e)
              ? (e.fieldErrors.couponCode ?? Object.values(e.fieldErrors)[0] ?? e.message)
              : e instanceof Error
                ? e.message
                : t('auth.genericError')
    );
    void qc.invalidateQueries({ queryKey: checkoutKeys.all });
    // A void quote (changed, expired, used) is shown as such; the shopper asks for a new one.
    if (isApiError(e) && e.code === 'DELIVERY_QUOTE_VOID') void qc.invalidateQueries({ queryKey: deliveryQuoteKeys.all });
  };

  /** Places (or, with the same key, replays) the order. Call after begin(). */
  const place = async (pay: PaySpec): Promise<OrderConfirmation | null> => {
    if (!address) return (end(), setAddressOpen(true), setError(t('co.needAddress')), null);
    try {
      // Last-touch channel (?src=&cr=&po= or the pre-order placement clicked) is stored on the order.
      const attribution = readAttribution() ?? undefined;
      const couponCode = s?.coupon?.applicable ? s.coupon.code : undefined;
      const conf = await api.placeOrder(
        { addressId: address.id, ...pay, useCredit: split.appliedMinor > 0 ? true : undefined, deliverySelections: selections(), attribution, couponCode, taxQuoteId: confirmedLock.current ?? s?.taxQuoteId },
        keyRef.current
      );
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

  /** Credit pays the whole order: no payment method, no Stripe confirmation. */
  const placeWithCredit = async () => {
    if (!begin()) return;
    const conf = await place({});
    if (!conf) return;
    await finish(conf, { ok: true });
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

  // Never an order for a cart that was not read: a skeleton while it loads, a plain error with Try again when it failed.
  if (summary.isPending) return <div aria-busy="true" data-testid="checkout-loading"><Skeleton height={420} /></div>;
  // 409: the cart has nothing selected to check out (the BFF's answer for an empty order); any other failure is a real error.
  const nothingToBuy = summary.isError && isApiError(summary.error) && summary.error.status === 409;
  if (summary.isError && !nothingToBuy) return <CheckoutState kind="error" retrying={summary.isFetching} onRetry={() => void summary.refetch()} />;
  // A cart with nothing ticked (or nothing in it) has no order: no "0 items", no total, no payment or tax.
  if (nothingToBuy || (s && s.packages.length === 0 && !busy)) {
    if (cart.isPending) return <div aria-busy="true" data-testid="checkout-loading"><Skeleton height={420} /></div>;
    if (cart.isError) return <CheckoutState kind="error" retrying={cart.isFetching} onRetry={() => void cart.refetch()} />;
    return <CheckoutState kind={cart.data.itemCount > 0 ? 'none-selected' : 'empty'} />;
  }
  if (!s) return null;

  const addressList = addressOpen || !address;
  const placeNow = () => void (creditPaysAll ? placeWithCredit() : payWith === 'new' || !pm ? placeWithElement() : placeWithSaved());
  /** The shopper confirmed the new total: place again (a new Idempotency-Key was made when the 409 came), carrying its lock. */
  const confirmTotal = () => {
    if (!totalChange) return;
    // The summary read again after the 409 has a lock bound to the inputs on screen; the problem's lock carries the same numbers.
    confirmedLock.current = s.totalMinor === totalChange.totalMinor && s.taxQuoteId ? s.taxQuoteId : (totalChange.taxQuoteId ?? null);
    setTotalChange(null);
    placeNow();
  };
  const dismissTotal = () => {
    confirmedLock.current = null;
    setTotalChange(null);
  };

  // Which section is open: the one the shopper asked for with Change, else the first incomplete one (3 when 1 and 2 are done).
  const savedPaymentOk = creditPaysAll || (payWith === 'saved' && !!pm && !stripeJsDown(elementStatus));
  const auto = addressList ? 1 : !savedPaymentOk ? 2 : 3;
  const current = openStep ?? auto;
  // A new card with its details complete keeps its form open (the Payment Element stays on screen to be confirmed) and the items
  // section opens beside it, so the shopper can still review the order before placing it.
  const newCardReady = payWith === 'new' && paymentChosen && !addressList && openStep === null;
  const stateOf = (n: number): StepState => (current === n || (n === 3 && newCardReady) ? 'open' : n < auto || (n === 2 && savedPaymentOk) ? 'done' : 'pending');
  const open = (n: number) => () => {
    setError(null);
    setOpenStep(n);
    if (n === 1) setAddressOpen(true);
  };
  const needReason = !address ? t('co.needAddress') : addressList ? t('co.confirmAddress') : !paymentChosen && !cardDown ? (payWith === 'new' ? t('co.needCard') : t('co.needPayment')) : null;
  const firstPromise = s.packages.length === 1 ? promiseOf(s.packages[0] as CheckoutPackage, choices[s.packages[0]?.packageId ?? ''] ?? initialChoice(s.packages[0] as CheckoutPackage)) : null;
  const itemsSummary = [t('co.items', { n: itemCount }), t('co.sellers', { n: s.sellerCount }), firstPromise?.text ? (firstPromise.pickup ? t('co.pickupReady', { ready: firstPromise.text }) : t('cart.arrives', { eta: firstPromise.text })) : ''].filter(Boolean).join(' · ');
  const paymentSummary = creditPaysAll ? t('co.credit.payingAll') : payWith === 'saved' && pm ? `${pm.label}${pm.expires ? ` · ${t('pay.expires', { date: pm.expires })}` : ''}` : t('co.needPayment');

  return (
    <div className={styles.layout}>
      <div className={styles.mainCol}>
        <AppliedQuoteNotice applied={applied} />
        {address && !applied.usable && s.packages.some((p) => p.deliveryOptions.some((o) => (o.key as string) === 'quote_required')) && (
          <AskForQuote addressId={address.id} place={`${address.city}, ${address.state} ${address.zip}`} />
        )}

        <CheckoutStep
          n={1}
          id="co-address"
          title={t('co.step.address')}
          state={stateOf(1)}
          onChange={open(1)}
          summary={
            address ? (
              <p className={styles.plain} data-testid="checkout-address">
                <strong>{address.name}</strong>
                <span>{addressLine(address)}</span>
              </p>
            ) : null
          }
        >
          <div className={styles.choices} data-testid="checkout-address">
            {s.addresses.length === 0 && <p className={styles.muted}>{t('co.noAddresses')}</p>}
            {s.addresses.map((a) => (
              <label key={a.id} className={styles.choice} data-testid="checkout-address-option">
                <input type="radio" name="shipaddr" checked={a.id === addressId} onChange={() => setAddressId(a.id)} aria-label={`${a.name} ${addressLine(a)}`} />
                <span className={styles.choiceText}>
                  <span>
                    <strong className={styles.name}>{a.name}</strong>
                    {a.isDefault && <span className={styles.tag}> {t('pay.default')}</span>}
                  </span>
                  <span>{addressLine(a)}</span>
                  <span className={styles.meta}>{a.phone}</span>
                </span>
                <button type="button" className="link-button" onClick={() => setAddrDialog({ open: true, address: a })}>
                  {t('common.edit')}
                </button>
              </label>
            ))}
            <div className={styles.rowActions}>
              <button type="button" className={styles.addRow} onClick={() => setAddrDialog({ open: true, address: null })}>
                <Plus aria-hidden /> {t('co.addAddress')}
              </button>
              <Button
                onClick={() => {
                  setError(null);
                  setAddressOpen(false);
                  setOpenStep(null);
                }}
                disabled={!address}
              >
                {t('co.useAddress')}
              </Button>
            </div>
          </div>
        </CheckoutStep>

        <CheckoutStep n={2} id="co-payment-title" title={t('co.step.payment')} state={stateOf(2)} onChange={open(2)} keepMounted summary={savedPaymentOk ? <p className={styles.plain}>{paymentSummary}</p> : null}>
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
            elementStatus={elementStatus}
            onElementStatus={setElementStatus}
            elementAttempt={elementAttempt}
            onRetryElement={retryElement}
            methodTypes={methodTypes}
            credit={s.credit && s.credit.availableMinor > 0 ? { ...s.credit, ...split, use: useCredit, setUse: setUseCredit } : null}
          />
          {openStep === 2 && savedPaymentOk && (
            <div>
              <Button onClick={() => setOpenStep(null)}>{t('co.usePayment')}</Button>
            </div>
          )}
          <PromoCode
            coupon={s.coupon}
            draft={couponDraft}
            setDraft={setCouponDraft}
            busy={busy}
            onApply={() => {
              setError(null);
              setAppliedCoupon(couponDraft.trim().toUpperCase());
            }}
            onRemove={() => {
              setError(null);
              setAppliedCoupon('');
              setCouponDraft('');
            }}
          />
        </CheckoutStep>

        <CheckoutStep n={3} id="co-delivery" title={t('co.step.delivery')} state={stateOf(3)} onChange={open(3)} alwaysSummary summary={<p className={styles.plain}>{itemsSummary}</p>}>
          <div className={styles.packages}>
            {s.packages.map((p, i) => (
              <PackageCard
                key={p.packageId}
                pkg={p}
                n={i + 1}
                total={s.packages.length}
                choice={choices[p.packageId] ?? initialChoice(p)}
                blocked={blockedLines}
                onChoice={(c) => setPackageChoice(p.packageId, c)}
                onRemove={() => void removePackage(p)}
                onRemoveLine={removeLineById}
                removingLine={removingLine}
                onChangeAddress={changeAddress}
                removing={removing === p.packageId}
              />
            ))}
          </div>
        </CheckoutStep>
      </div>

      <CheckoutOrderPanel
        s={s}
        pickups={pickups}
        shippingMinor={selectedShipping}
        total={total}
        itemCount={itemCount}
        paymentChosen={paymentChosen}
        needReason={needReason}
        creditMinor={split.appliedMinor}
        addressOk={!!address}
        blocked={blocked}
        preLocked={preLocked}
        preLockedText={preCopy?.copy.lockedText}
        preRun={preBlock?.kind === 'RUN' ? preBlock : null}
        undeliverable={undeliverable}
        taxBlocked={taxBlocked}
        deliveryReason={deliveryReason}
        paymentReason={paymentReason}
        addressList={addressList}
        busy={busy}
        placing={placing}
        error={error}
        errorCartLink={error !== null && error === t('co.cartChanged')}
        onPlace={placeNow}
        taxPending={summary.isFetching}
        onRetryTax={() => void summary.refetch()}
        retryingTax={summary.isFetching}
        onEditAddress={() => address && setAddrDialog({ open: true, address })}
        totalChange={totalChange}
        onConfirmTotal={confirmTotal}
        onDismissTotal={dismissTotal}
      />

      <AddressDialog open={addrDialog.open} onOpenChange={(o) => setAddrDialog((d) => ({ ...d, open: o }))} address={addrDialog.address} onSaved={(a) => setAddressId(a.id)} />
      <span hidden data-testid="checkout-pay-with">
        {payWith === 'new' ? (newMethod.type ?? 'new') : (pm?.id ?? '')}
      </span>
    </div>
  );
}
