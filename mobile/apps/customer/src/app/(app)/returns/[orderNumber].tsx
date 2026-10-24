/* Start a return (design: RETURN, S166/S114): items of one shipment, a reason and a method → RMA with a QR code.
   Food shows "report a problem" instead of a return; a return can be cancelled until the items are dropped off. */
import { formatMoney, isApiError } from '@afrotransact/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { CheckCircleIcon as CheckCircle, CheckSquareIcon as CheckSquare, CircleIcon as Circle, QrCodeIcon as QrCode, RadioButtonIcon as RadioButton, SquareIcon as Square, TruckIcon as Truck, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useMemo, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import QRCode from 'react-native-qrcode-svg';

import { useApi } from '@/api/ApiProvider';
import { accountKeys } from '@/api/keys';
import { usePaymentMethods, useReturnEligibility } from '@/api/queries';
import type { ReturnableLine, ReturnMethod, ReturnReason, ReturnSummary } from '@/api/types';
import { actionable, commonMethods, lineTotal, METHOD_COPY, reasonsFor, returnDoneText, returnQrPayload, validateReturn } from '@/domain/returns';
import { c, sp, t } from '@/theme';
import { AppText, Banner, Button, Card, Chip, Loading } from '@/ui/primitives';
import { Screen, SubHeader } from '@/ui/Screen';

const dayOf = (iso?: string | null): string => (iso ? new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) : '');

export default function StartReturn() {
  const { orderNumber } = useLocalSearchParams<{ orderNumber: string }>();
  const router = useRouter();
  const api = useApi();
  const qc = useQueryClient();
  const elig = useReturnEligibility(orderNumber ?? '');
  const pms = usePaymentMethods();
  const [shipmentId, setShipmentId] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [reason, setReason] = useState<ReturnReason | null>(null);
  const [method, setMethod] = useState<ReturnMethod | null>(null);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<ReturnSummary[] | null>(null);

  const shipments = useMemo(() => elig.data?.shipments ?? [], [elig.data?.shipments]);
  const chosen = useMemo(
    () => shipments.find((s) => s.shipmentId === shipmentId)?.lines.filter((l) => selected.includes(l.lineId)) ?? [],
    [shipments, shipmentId, selected]
  );
  const methods = commonMethods(chosen);
  const effectiveMethod = method && methods.includes(method) ? method : methods[0] ?? null;
  const reasons = reasonsFor(chosen);
  const header = <SubHeader title="Start a return" />;
  const refundTo = pms.data?.find((p) => p.isDefault)?.label ?? 'your original payment';
  const currency = elig.data?.currency ?? 'USD';

  const toggle = (shipment: string, line: ReturnableLine) => {
    const on = selected.includes(line.lineId) && shipmentId === shipment;
    // One return covers one shipment: picking from another shipment starts a new selection.
    setSelected(on ? selected.filter((x) => x !== line.lineId) : shipmentId === shipment ? [...selected, line.lineId] : [line.lineId]);
    setShipmentId(shipment);
    setErr('');
    setReason(null);
  };

  if (done) {
    const first = done[0];
    const m = effectiveMethod ?? 'DROP_OFF';
    const cancel = async () => {
      setBusy(true);
      try {
        await Promise.all(done.map((r) => api.account.cancelReturn(r.rmaId)));
        void qc.invalidateQueries({ queryKey: accountKeys.returns() });
        router.navigate('/orders');
      } catch (e) {
        setErr(isApiError(e) ? e.message : 'We couldn’t cancel the return. Try again.');
        setBusy(false);
      }
    };
    return (
      <Screen header={header} footer={<Button label="Back to orders" onPress={() => router.navigate('/orders')} />}>
        <View style={styles.done}>
          <CheckCircle size={48} weight="fill" color={c.success} />
          <AppText variant="title">{m === 'NO_RETURN' ? 'Problem reported' : 'Return started'}</AppText>
          <AppText color={c.textMuted} style={{ textAlign: 'center' }}>{returnDoneText(m)}</AppText>
          {m !== 'NO_RETURN' ? (
            <View style={styles.qr} accessibilityLabel={`Return QR code ${done.map((r) => r.number ?? r.rmaId).join(', ')}`}>
              <QRCode value={first?.qrPayload ?? returnQrPayload(done.map((r) => r.rmaId), m)} size={200} color={t.ink['1000']} backgroundColor={c.surface} />
            </View>
          ) : null}
          <AppText variant="bodyStrong">{done.map((r) => r.number ?? r.rmaId).join(' · ')}</AppText>
          {first?.dropOffBy ? <AppText variant="small" color={c.textMuted}>Drop it off by {dayOf(first.dropOffBy)}.</AppText> : null}
          {first?.handoverCode ? <AppText variant="small" color={c.textMuted}>Handover code {first.handoverCode}</AppText> : null}
          {done.every((r) => r.cancellable) ? <Button kind="outline" label="Cancel this return" loading={busy} onPress={() => void cancel()} /> : null}
          {err ? <Banner tone="danger" icon={WarningCircle}>{err}</Banner> : null}
        </View>
      </Screen>
    );
  }
  if (elig.isError) return <Screen header={header}><Banner tone="danger" icon={WarningCircle}>We couldn’t check what can be returned. Try again.</Banner></Screen>;
  if (!elig.data) return <Screen header={header}><Loading /></Screen>;

  const submit = async () => {
    const invalid = validateReturn(selected, reason, effectiveMethod);
    if (invalid || !reason || !effectiveMethod || !shipmentId) return setErr(invalid ?? 'Choose a reason.');
    setBusy(true);
    setErr('');
    try {
      const created = await api.account.startReturn(
        { orderNumber: orderNumber ?? '', shipmentId, method: effectiveMethod, lines: chosen.map((l) => ({ lineId: l.lineId, quantity: l.maxQuantity, reason })) },
        `${orderNumber}-${shipmentId}-${selected.slice().sort().join('.')}`
      );
      setMethod(effectiveMethod);
      setDone([created]);
      void qc.invalidateQueries({ queryKey: accountKeys.returns() });
      void qc.invalidateQueries({ queryKey: accountKeys.orders() });
    } catch (e) {
      setErr(isApiError(e) ? e.message : 'We couldn’t start the return. Try again.');
    } finally {
      setBusy(false);
    }
  };
  const reportOnly = chosen.some((l) => l.reportProblemOnly);
  const hasAny = shipments.some((s) => actionable(s.lines).length > 0);

  return (
    <Screen header={header} footer={hasAny ? <Button label={reportOnly ? 'Report a problem' : 'Submit return'} loading={busy} onPress={() => void submit()} /> : undefined}>
      <AppText variant="small" color={c.textMuted}>{orderNumber} · free 30-day returns</AppText>
      {!hasAny ? <Banner tone="info" icon={WarningCircle}>Nothing in this order can be returned right now.</Banner> : null}
      {shipments.map((s) => (
        <View key={s.shipmentId} style={{ gap: sp['2'] }}>
          <AppText variant="heading">{shipments.length > 1 ? `From ${s.sellerName}` : 'Which items?'}</AppText>
          {s.lines.map((l) => {
            const on = shipmentId === s.shipmentId && selected.includes(l.lineId);
            return (
              <Card key={l.lineId} selected={on} onPress={l.eligible ? () => toggle(s.shipmentId, l) : undefined} accessibilityLabel={l.title}>
                <View style={styles.row}>
                  {l.eligible ? (on ? <CheckSquare size={22} weight="fill" color={c.text} /> : <Square size={22} color={c.textMuted} />) : <Square size={22} color={c.border} />}
                  <View style={{ flex: 1 }}>
                    <AppText>{l.title}</AppText>
                    <AppText variant="small" color={c.textMuted}>
                      {!l.eligible ? l.ineligibleReason ?? 'Can’t be returned' : l.reportProblemOnly ? 'Food: report a problem instead of returning it' : l.windowEndsAt ? `Return by ${dayOf(l.windowEndsAt)}` : ''}
                    </AppText>
                  </View>
                  <AppText variant="bodyStrong">{formatMoney(lineTotal(l), currency)}</AppText>
                </View>
              </Card>
            );
          })}
        </View>
      ))}
      {chosen.length > 0 ? (
        <>
          <View style={{ gap: sp['2'] }}>
            <AppText variant="heading">{reportOnly ? 'What went wrong?' : 'Reason'}</AppText>
            <View style={styles.chips}>{reasons.map((r) => <Chip key={r.code} label={r.label} selected={reason === r.code} onPress={() => { setReason(r.code); setErr(''); }} />)}</View>
          </View>
          {methods.length > 0 ? (
            <View style={{ gap: sp['2'] }}>
              <AppText variant="heading">{reportOnly ? 'What happens next' : 'How to send it back'}</AppText>
              {methods.map((m) => (
                <Card key={m} selected={effectiveMethod === m} onPress={() => setMethod(m)} accessibilityLabel={METHOD_COPY[m].title}>
                  <View style={styles.row}>
                    {m === 'DROP_OFF' ? <QrCode size={22} color={c.text} /> : <Truck size={22} color={c.text} />}
                    <View style={{ flex: 1 }}>
                      <AppText variant="bodyStrong">{METHOD_COPY[m].title}</AppText>
                      <AppText variant="small" color={c.textMuted}>{METHOD_COPY[m].detail}</AppText>
                    </View>
                    {effectiveMethod === m ? <RadioButton size={22} weight="fill" color={c.text} /> : <Circle size={22} color={c.textMuted} />}
                  </View>
                </Card>
              ))}
            </View>
          ) : (
            <Banner tone="warning" icon={WarningCircle}>These items can’t be sent back together. Choose fewer items.</Banner>
          )}
          <View style={styles.refund}>
            <AppText>Refund to {refundTo}</AppText>
            <AppText variant="heading">{formatMoney(chosen.reduce((a, l) => a + lineTotal(l), 0), currency)}</AppText>
          </View>
        </>
      ) : null}
      {err ? <Banner tone="danger" icon={WarningCircle}>{err}</Banner> : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: sp['3'] },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: sp['2'] },
  refund: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', padding: sp['4'], borderRadius: t.radius.lg, backgroundColor: c.surfaceMuted },
  done: { alignItems: 'center', gap: sp['3'], paddingTop: sp['6'] },
  qr: { padding: sp['4'], borderRadius: t.radius.lg, borderWidth: 1, borderColor: c.border, backgroundColor: c.surface }
});
