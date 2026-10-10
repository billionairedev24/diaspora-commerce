'use client';

import { isApiError, orderKeys, RETURN_REASONS, type ReturnableLine, returnKeys, type ReturnMethod, type ReturnReason, type ReturnSummary, SELLER_FAULT_REASONS } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, CheckCircle, QrCode, Skeleton } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { type FormEvent, useMemo, useState } from 'react';

import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import { AccountCrumb } from './AccountCrumb';
import styles from './Pages.module.css';
import { nextStep } from './ReturnsView';

interface Pick {
  on: boolean;
  quantity: number;
  reason: ReturnReason | '';
}

const reasonKey = (r: ReturnReason) => `ret.reason.${r}` as MessageKey;
const methodKey = (m: ReturnMethod) => `ret.method.${m}` as MessageKey;

/** Start a return (S148, tax-returns): pick delivered items, a reason each and a method; food can only be reported. */
export function StartReturnView({ orderNumber }: { orderNumber: string }) {
  const fmt = useFormatters();
  const t = useT();
  const api = browserApi();
  const qc = useQueryClient();
  const elig = useQuery(shopperQueries.returnable(api, orderNumber));
  const [shipmentId, setShipmentId] = useState<string | null>(null);
  const [picks, setPicks] = useState<Record<string, Pick>>({});
  const [method, setMethod] = useState<ReturnMethod | null>(null);
  const [comment, setComment] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<ReturnSummary | null>(null);
  // One idempotency key per attempt: a double click or a retry replays the same RMA.
  const [key] = useState(() => `ret-${crypto.randomUUID()}`);

  const shipments = elig.data?.shipments ?? [];
  const active = shipments.find((s) => s.shipmentId === shipmentId) ?? shipments.find((s) => s.lines.some((l) => l.eligible));
  const chosen: Array<{ line: ReturnableLine; pick: Pick }> = (active?.lines ?? []).filter((l) => picks[l.lineId]?.on).map((l) => ({ line: l, pick: picks[l.lineId] as Pick }));
  const methods = useMemo(() => {
    if (!chosen.length) return [] as ReturnMethod[];
    return chosen.reduce<ReturnMethod[]>((acc, c, i) => (i === 0 ? c.line.methods : acc.filter((m) => c.line.methods.includes(m))), []);
  }, [chosen]);
  const effectiveMethod = method && methods.includes(method) ? method : methods[0] ?? null;
  const estimate = chosen.reduce((s, c) => s + c.line.unitPriceMinor * c.pick.quantity, 0);

  const start = useMutation({
    mutationFn: () =>
      api.startReturn(
        {
          orderNumber,
          shipmentId: active?.shipmentId ?? '',
          method: effectiveMethod ?? 'DROP_OFF',
          lines: chosen.map((c) => ({ lineId: c.line.lineId, quantity: c.pick.quantity, reason: c.pick.reason as ReturnReason, comment: comment.trim() || undefined }))
        },
        key
      ),
    onSuccess: async (r) => {
      setDone(r);
      await Promise.all([qc.invalidateQueries({ queryKey: returnKeys.all }), qc.invalidateQueries({ queryKey: orderKeys.returnable(orderNumber) })]);
    },
    onError: (e) => setError(isApiError(e) ? (Object.values(e.fieldErrors)[0] ?? e.message) : t('auth.genericError'))
  });

  const set = (lineId: string, patch: Partial<Pick>) => setPicks((p) => ({ ...p, [lineId]: { on: false, quantity: 1, reason: '', ...p[lineId], ...patch } }));

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!chosen.length) return setError(t('ret.chooseOne'));
    if (chosen.some((c) => !c.pick.reason)) return setError(t('ret.chooseReason'));
    if (!effectiveMethod) return setError(t('ret.noCommonMethod'));
    start.mutate();
  };

  if (done) {
    return (
      <div className={styles.page}>
        <AccountCrumb>{t('account.returns')}</AccountCrumb>
        <section className={styles.card} role="status" data-testid="return-started">
          <h1 className={styles.h2}>
            <CheckCircle weight="fill" aria-hidden /> {t('ret.started', { number: done.number ?? done.rmaId })}
          </h1>
          <p className={styles.success}>{t('ret.refund', { amount: fmt.money(done.refundMinor) })}</p>
          <p className={styles.muted}>{nextStep(done, t, (iso) => (iso ? fmt.date(iso, 'dayMonthShort') : ''))}</p>
          {done.qrPayload && (
            <p className={styles.muted}>
              <QrCode aria-hidden /> {t('ret.qr')} <span className={styles.code}>{done.qrPayload}</span>
            </p>
          )}
          {done.handoverCode && (
            <p className={styles.muted}>
              {t('ret.handover')} <span className={styles.code}>{done.handoverCode}</span>
            </p>
          )}
          <div className={styles.actions}>
            <Link href="/account/returns" className={buttonClassName({ size: 'sm' })}>
              {t('ret.seeReturns')}
            </Link>
          </div>
        </section>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <AccountCrumb>{t('account.returns')}</AccountCrumb>
      <h1 className={styles.h1}>{t('ret.startTitle', { number: orderNumber })}</h1>
      {elig.isPending && <Skeleton height={160} />}
      {elig.isError && <p className={styles.error} role="alert">{isApiError(elig.error) && elig.error.status === 404 ? t('ord.notFound') : t('acct.loadError')}</p>}
      {elig.isSuccess && !active && <p className={styles.muted}>{t('ret.nothingDelivered')}</p>}
      {active && (
        <form className={styles.form} onSubmit={submit} noValidate>
          {shipments.length > 1 && (
            <label className={styles.form}>
              {t('ret.shipment')}
              <select value={active.shipmentId} onChange={(e) => { setShipmentId(e.target.value); setPicks({}); }}>
                {shipments.map((s) => (
                  <option key={s.shipmentId} value={s.shipmentId}>
                    {s.sellerName}
                  </option>
                ))}
              </select>
            </label>
          )}
          <section className={styles.card} aria-label={t('ret.items')}>
            <ul className={styles.list}>
              {active.lines.map((l) => {
                const p = picks[l.lineId];
                const reasons = l.sellerFaultOnly ? RETURN_REASONS.filter((r) => SELLER_FAULT_REASONS.includes(r)) : RETURN_REASONS;
                return (
                  <li key={l.lineId} className={styles.row} data-testid="returnable-line">
                    <input type="checkbox" checked={p?.on ?? false} disabled={!l.eligible} onChange={(e) => set(l.lineId, { on: e.target.checked })} aria-label={t('ret.pickAria', { title: l.title })} />
                    <span className={styles.form} style={{ gap: 'var(--at-space-1)' }}>
                      <strong>{l.title}</strong>
                      <span className={styles.muted}>
                        {fmt.money(l.unitPriceMinor)} · {l.eligible ? (l.reportProblemOnly ? t('ret.reportOnly') : t('ret.until', { date: l.windowEndsAt ? new Date(l.windowEndsAt).toLocaleDateString(fmt.tag, { month: 'short', day: 'numeric' }) : '' })) : (l.ineligibleReason ?? t('ret.notReturnable'))}
                      </span>
                      {p?.on && (
                        <span className={styles.actions}>
                          <select aria-label={t('ret.reasonAria', { title: l.title })} value={p.reason} onChange={(e) => set(l.lineId, { reason: e.target.value as ReturnReason })}>
                            <option value="">{l.reportProblemOnly ? t('ret.whatWentWrong') : t('ret.chooseReasonOption')}</option>
                            {reasons.map((r) => (
                              <option key={r} value={r}>
                                {t(reasonKey(r))}
                              </option>
                            ))}
                          </select>
                          {l.maxQuantity > 1 && (
                            <select aria-label={t('ret.qtyAria', { title: l.title })} value={p.quantity} onChange={(e) => set(l.lineId, { quantity: Number(e.target.value) })}>
                              {Array.from({ length: l.maxQuantity }, (_, i) => i + 1).map((n) => (
                                <option key={n} value={n}>
                                  {t('ret.qty', { n })}
                                </option>
                              ))}
                            </select>
                          )}
                        </span>
                      )}
                    </span>
                    <span />
                  </li>
                );
              })}
            </ul>
          </section>
          {methods.length > 0 && (
            <fieldset className={styles.card}>
              <legend className={styles.h2}>{t('ret.how')}</legend>
              {methods.map((m) => (
                <label key={m} className={styles.check} aria-label={t(methodKey(m))}>
                  <input type="radio" name="method" checked={effectiveMethod === m} onChange={() => setMethod(m)} />
                  <span>
                    <strong>{t(methodKey(m))}</strong>
                    <br />
                    <span className={styles.muted}>{t(`${methodKey(m)}.hint` as MessageKey)}</span>
                  </span>
                </label>
              ))}
            </fieldset>
          )}
          <label className={styles.form}>
            {t('ret.comment')}
            <textarea rows={2} maxLength={500} value={comment} onChange={(e) => setComment(e.target.value)} />
          </label>
          {chosen.length > 0 && <p className={styles.muted}>{t('ret.estimate', { amount: fmt.money(estimate) })}</p>}
          {error && <p className={styles.error} role="alert">{error}</p>}
          <div className={styles.actions}>
            <Button type="submit" loading={start.isPending}>
              {chosen.some((c) => c.line.reportProblemOnly) ? t('ret.report') : t('ret.start')}
            </Button>
            <Link href="/account/returns">{t('common.cancel')}</Link>
          </div>
        </form>
      )}
    </div>
  );
}

