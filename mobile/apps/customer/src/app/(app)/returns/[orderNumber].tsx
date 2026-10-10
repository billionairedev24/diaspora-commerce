/* Start a return (design: RETURN, S166): items, reason, method → RMA with a QR code. */
import { formatMoney, isApiError } from '@afrotransact/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { CheckCircleIcon as CheckCircle, CheckSquareIcon as CheckSquare, CircleIcon as Circle, QrCodeIcon as QrCode, RadioButtonIcon as RadioButton, SquareIcon as Square, TruckIcon as Truck, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import QRCode from 'react-native-qrcode-svg';

import { useApi } from '@/api/ApiProvider';
import { accountKeys } from '@/api/keys';
import { usePaymentMethods, useReturns, useTracking } from '@/api/queries';
import { refundTotal, RETURN_METHODS, RETURN_REASONS, returnDoneText, type ReturnMethod, returnQrPayload, type ReturnReasonCode, validateReturn } from '@/domain/returns';
import { c, sp, t } from '@/theme';
import { AppText, Banner, Button, Card, Chip, Loading } from '@/ui/primitives';
import { Screen, SubHeader } from '@/ui/Screen';

export default function StartReturn() {
  const { orderNumber } = useLocalSearchParams<{ orderNumber: string }>();
  const router = useRouter();
  const api = useApi();
  const qc = useQueryClient();
  const tr = useTracking(orderNumber ?? '');
  const existing = useReturns();
  const pms = usePaymentMethods();
  const [selected, setSelected] = useState<string[]>([]);
  const [reason, setReason] = useState<ReturnReasonCode | null>(null);
  const [method, setMethod] = useState<ReturnMethod>('ups');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string[] | null>(null);

  const items = (tr.data?.shipments ?? []).flatMap((s) => s.items.filter((i) => i.returnable)).map((i) => ({ atin: i.atin, title: i.title, priceMinor: i.priceMinor * i.quantity }));
  const prior = (existing.data ?? []).filter((r) => r.orderNumber === orderNumber).map((r) => r.rmaId);
  const rmas = done ?? (prior.length ? prior : null);
  const header = <SubHeader title="Start a return" />;
  const refundTo = pms.data?.find((p) => p.isDefault)?.label ?? 'your original payment';

  if (rmas) {
    return (
      <Screen header={header} footer={<Button label="Back to orders" onPress={() => router.navigate('/orders')} />}>
        <View style={styles.done}>
          <CheckCircle size={48} weight="fill" color={c.success} />
          <AppText variant="title">Return started</AppText>
          <AppText color={c.textMuted} style={{ textAlign: 'center' }}>{returnDoneText(method)}</AppText>
          <View style={styles.qr} accessibilityLabel={`Return QR code ${rmas.join(', ')}`}>
            <QRCode value={returnQrPayload(rmas, method)} size={200} color={t.ink['1000']} backgroundColor={c.surface} />
          </View>
          <AppText variant="bodyStrong">{rmas.join(' · ')}</AppText>
        </View>
      </Screen>
    );
  }
  if (!tr.data) return <Screen header={header}><Loading /></Screen>;

  const submit = async () => {
    const invalid = validateReturn(selected, reason);
    if (invalid || !reason) return setErr(invalid ?? 'Choose a reason.');
    setBusy(true);
    setErr('');
    try {
      const created = await Promise.all(selected.map((atin) => api.account.startReturn({ orderNumber: orderNumber ?? '', atin, reason })));
      setDone(created.map((r) => r.rmaId));
      void qc.invalidateQueries({ queryKey: accountKeys.returns() });
      void qc.invalidateQueries({ queryKey: accountKeys.orders() });
    } catch (e) {
      setErr(isApiError(e) ? e.message : 'We couldn’t start the return. Try again.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Screen header={header} footer={<Button label="Submit return" loading={busy} onPress={() => void submit()} />}>
      <AppText variant="small" color={c.textMuted}>{orderNumber} · free 30-day returns</AppText>
      <View style={{ gap: sp['2'] }}>
        <AppText variant="heading">Which items?</AppText>
        {items.map((i) => {
          const on = selected.includes(i.atin);
          return (
            <Card key={i.atin} selected={on} onPress={() => { setSelected((s) => (on ? s.filter((x) => x !== i.atin) : [...s, i.atin])); setErr(''); }} accessibilityLabel={i.title}>
              <View style={styles.row}>
                {on ? <CheckSquare size={22} weight="fill" color={c.text} /> : <Square size={22} color={c.textMuted} />}
                <AppText style={{ flex: 1 }}>{i.title}</AppText>
                <AppText variant="bodyStrong">{formatMoney(i.priceMinor, tr.data.currency)}</AppText>
              </View>
            </Card>
          );
        })}
      </View>
      <View style={{ gap: sp['2'] }}>
        <AppText variant="heading">Reason</AppText>
        <View style={styles.chips}>{RETURN_REASONS.map((r) => <Chip key={r.code} label={r.label} selected={reason === r.code} onPress={() => { setReason(r.code); setErr(''); }} />)}</View>
      </View>
      <View style={{ gap: sp['2'] }}>
        <AppText variant="heading">How to send it back</AppText>
        {RETURN_METHODS.map((m) => (
          <Card key={m.key} selected={method === m.key} onPress={() => setMethod(m.key)} accessibilityLabel={m.title}>
            <View style={styles.row}>
              {m.key === 'ups' ? <QrCode size={22} color={c.text} /> : <Truck size={22} color={c.text} />}
              <View style={{ flex: 1 }}>
                <AppText variant="bodyStrong">{m.title}</AppText>
                <AppText variant="small" color={c.textMuted}>{m.detail}</AppText>
              </View>
              {method === m.key ? <RadioButton size={22} weight="fill" color={c.text} /> : <Circle size={22} color={c.textMuted} />}
            </View>
          </Card>
        ))}
      </View>
      <View style={styles.refund}>
        <AppText>Refund to {refundTo}</AppText>
        <AppText variant="heading">{formatMoney(refundTotal(items, selected), tr.data.currency)}</AppText>
      </View>
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
