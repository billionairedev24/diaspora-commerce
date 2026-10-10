'use client';

import {
  type Address,
  cartKeys,
  checkoutKeys,
  type CheckoutPackage,
  type DeliveryMethod,
  type DeliveryPreferences,
  type DeliverySelection,
  formatMoney,
  isApiError,
  type OrderConfirmation,
  orderKeys,
  type PaymentMethod,
  type Wallet
} from '@afrotransact/api-client';
import { Button, buttonClassName, CheckCircle, Circle, CircleNotch, House, Key, LockSimple, Plus, ShieldCheck, Skeleton, Storefront, Truck } from '@afrotransact/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { type ReactNode, useEffect, useMemo, useRef, useState } from 'react';

import { AddressDialog } from '@/components/account/AddressDialog';
import { AddPaymentMethodDialog } from '@/components/payments/AddPaymentMethodDialog';
import { PaymentChip } from '@/components/payments/PaymentChip';
import { QuickPay } from '@/components/payments/QuickPay';
import { t } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';
import { type ConfirmResult, confirmWithSavedMethod } from '@/lib/payments/stripe';

import styles from './Checkout.module.css';

type Choice = { option: DeliveryMethod; pickupPointId?: string; preferences: DeliveryPreferences };

const OPTION_ICON: Record<DeliveryMethod, ReactNode> = {
  carrier: <Truck aria-hidden />,
  express: <Truck weight="fill" aria-hidden />,
  afrotransact_delivery: <House aria-hidden />,
  pickup: <Storefront aria-hidden />
};

const addressLine = (a: Address) => `${a.name}, ${a.line1}${a.line2 ? ` ${a.line2}` : ''}, ${a.city}, ${a.state} ${a.zip}`;
const pmSub = (pm: PaymentMethod) => (pm.expires ? t('pay.expires', { date: pm.expires }) : pm.type === 'us_bank_account' ? t('pay.bankAccount') : t('pay.wallet'));

/** Default choice per package: the first option; AfroTransact Delivery of perishables is handed over with a code. */
function initialChoice(p: CheckoutPackage): Choice {
  return { option: p.deliveryOptions[0]?.key ?? 'carrier', pickupPointId: p.pickupPoints?.[0]?.id, preferences: { handover: 'door', contact: 'text' } };
}

function Step({ n, title, done, onChange, children }: { n: number; title: string; done: boolean; onChange?: () => void; children: ReactNode }) {
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

function PackageDelivery({ pkg, n, total, choice, onChoice }: { pkg: CheckoutPackage; n: number; total: number; choice: Choice; onChoice: (c: Choice) => void }) {
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
              <strong>{formatMoney(l.lineTotalMinor)}</strong>
            </li>
          ))}
        </ul>
        <fieldset className={styles.options}>
          <legend className={styles.legend}>{t('co.deliveryOption')}</legend>
          {pkg.deliveryOptions.map((o) => (
            <div key={o.key}>
              <label className={styles.option}>
                <input type="radio" name={`opt-${pkg.packageId}`} checked={choice.option === o.key} onChange={() => onChoice({ ...choice, option: o.key })} />
                <span>
                  {OPTION_ICON[o.key]} <strong className={styles.eta}>{o.eta}</strong>
                  <br />
                  {o.label} · {o.free || o.feeMinor === 0 ? t('co.free') : formatMoney(o.feeMinor)}
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
        {atd && (
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
 * Three-step checkout (S047, S048): delivery address, payment (quick pay, saved methods, add via Stripe) and items with
 * a delivery choice per package (carrier, AfroTransact Delivery, pickup — promise dates from the BFF). Each step folds
 * to a summary with Change; the order summary stays on the right. Placing the order starts the orders saga, confirms
 * the PaymentIntent with Stripe and lets orders continue.
 */
export function CheckoutView() {
  const api = browserApi();
  const qc = useQueryClient();
  const router = useRouter();
  const [addressId, setAddressId] = useState<string | null>(null);
  const summary = useQuery(shopperQueries.checkoutSummary(api, addressId));
  const s = summary.data;
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [pmId, setPmId] = useState<string | null>(null);
  const [choices, setChoices] = useState<Record<string, Choice>>({});
  const [addrDialog, setAddrDialog] = useState<{ open: boolean; address: Address | null }>({ open: false, address: null });
  const [pmDialog, setPmDialog] = useState(false);
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

  const place = async (wallet?: Wallet): Promise<OrderConfirmation | null> => {
    if (!address) return (setStep(1), setError(t('co.needAddress')), null);
    if (!wallet && !pm) return (setStep(2), setError(t('co.needPayment')), null);
    setError(null);
    setPlacing(0);
    try {
      return await api.placeOrder({ addressId: address.id, paymentMethodId: wallet ? undefined : (pm?.id ?? undefined), wallet, deliverySelections: selections() }, keyRef.current);
    } catch (e) {
      fail(e);
      return null;
    }
  };

  const finish = async (conf: OrderConfirmation, confirmed: ConfirmResult) => {
    if (!confirmed.ok) return fail(new Error(confirmed.message));
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
    const conf = await place();
    if (!conf || !pm) return;
    setPlacing(1);
    const confirmed = conf.paymentClientSecret ? await confirmWithSavedMethod(s?.publishableKey ?? '', conf.paymentClientSecret, pm.id) : ({ ok: true } as const);
    await finish(conf, confirmed);
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
          {t('soon.cart')}
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

        <Step n={2} title={step > 2 && pm ? t('co.payingWith') : t('co.choosePayment')} done={step > 2 && !!pm} onChange={() => setStep(2)}>
          {step < 2 && <p className={styles.muted}>{t('co.afterAddress')}</p>}
          {step > 2 && pm && (
            <p className={styles.summaryLine}>
              <PaymentChip method={pm} /> {pm.label} <span className={styles.muted}>· {pmSub(pm)}</span>
              <br />
              <span className={styles.muted}>{t('co.billingSame')}</span>
            </p>
          )}
          {step === 2 && (
            <div className={styles.choices}>
              <QuickPay
                publishableKey={s.publishableKey ?? ''}
                amountMinor={total}
                disabled={busy}
                onPay={async (w) => {
                  const conf = await place(w);
                  walletConf.current = conf;
                  if (conf) setPlacing(1);
                  return conf?.paymentClientSecret ?? null;
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
                  <input type="radio" name="paym" checked={p.id === pmId} onChange={() => setPmId(p.id)} />
                  <PaymentChip method={p} />
                  <span className={styles.choiceText}>
                    <strong>{p.label}</strong> <span className={styles.muted}>{pmSub(p)}</span>
                  </span>
                  {p.isDefault && <span className={styles.tag}>{t('pay.default')}</span>}
                </label>
              ))}
              <button type="button" className={styles.addRow} onClick={() => setPmDialog(true)}>
                <Plus aria-hidden /> {t('co.addPayment')}
              </button>
              <Button onClick={() => { setError(null); setStep(3); }} disabled={!pm}>
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
              <PackageDelivery key={p.packageId} pkg={p} n={i + 1} total={s.packages.length} choice={choices[p.packageId] ?? initialChoice(p)} onChoice={(c) => setChoices((x) => ({ ...x, [p.packageId]: c }))} />
            ))
          )}
        </Step>
      </div>

      <aside className={styles.aside} aria-label={t('co.orderSummary')}>
        <Button size="lg" block onClick={() => void placeWithCard()} loading={busy} disabled={step < 3 || !pm || !address} data-place-order="1">
          {busy ? t('co.placing') : t('co.place')}
        </Button>
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
          <dd>{formatMoney(s.subtotalMinor)}</dd>
          <dt>{t('co.shipping')}</dt>
          <dd>{shipping === 0 ? t('co.free') : formatMoney(shipping)}</dd>
          <dt>{t('co.tax')}</dt>
          <dd>{s.taxMinor ? formatMoney(s.taxMinor) : s.taxRateLabel}</dd>
          <dt className={styles.grand}>{t('co.total')}</dt>
          <dd className={styles.grand}>{formatMoney(total)}</dd>
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
      <AddPaymentMethodDialog open={pmDialog} onOpenChange={setPmDialog} onSaved={(p) => { if (p) setPmId(p.id); }} />
    </div>
  );
}
