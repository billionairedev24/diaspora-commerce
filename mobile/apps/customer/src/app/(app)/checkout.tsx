/* Checkout (S165). Built from the BFF checkout summary: address, per-package delivery option (+ delivery preferences
   for AfroTransact Delivery), payment and totals. Saved cards place the order directly (3-D Secure handled with
   handleNextAction); Apple Pay / Google Pay and new cards or bank accounts use Stripe's PaymentSheet in deferred-intent
   mode built from the summary total. Orders over $100 and wallet payments are confirmed with Face ID / passcode. */
import { formatMoney, isApiError } from '@afrotransact/api-client';
import { useQueryClient } from '@tanstack/react-query';
import * as Crypto from 'expo-crypto';
import { useRouter } from 'expo-router';
import { AppleLogoIcon as AppleLogo, CircleIcon as Circle, CreditCardIcon as CreditCard, GoogleLogoIcon as GoogleLogo, LockSimpleIcon as LockSimple, RadioButtonIcon as RadioButton, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Platform, StyleSheet, TextInput, View } from 'react-native';

import { useApi } from '@/api/ApiProvider';
import { accountKeys, cartKeys, checkoutKeys } from '@/api/keys';
import { useCheckoutSummary } from '@/api/queries';
import { type CheckoutSummary } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import { buildPlaceOrderRequest, defaultChoices, idempotencyKey, keyAfterFailure, type PackageChoice, paymentSheetIntent, requiresDeviceAuth, totalFor, validateChoices } from '@/domain/checkout';
import { usePayments } from '@/payments/usePayments';
import { c, sp, t } from '@/theme';
import { PaymentMark } from '@/ui/PaymentMarks';
import { AppText, Banner, Button, Card, Chip, Empty, KeyValue, Loading, SectionLabel } from '@/ui/primitives';
import { ErrorView } from '@/ui/QueryStates';
import { Screen, SubHeader } from '@/ui/Screen';
import { useToast } from '@/ui/Toast';

type PayChoice = { kind: 'saved'; id: string } | { kind: 'sheet'; wallet: 'apple_pay' | 'google_pay' | null };

const HANDOVER = [
  { key: 'hand', label: 'Hand it to me' },
  { key: 'door', label: 'Leave at the door' },
  { key: 'desk', label: 'Front desk or neighbour' }
] as const;
const CONTACT = [
  { key: 'text', label: 'Text on arrival' },
  { key: 'call', label: 'Call on arrival' },
  { key: 'none', label: 'Don’t contact me' }
] as const;

function Radio({ on }: { on: boolean }) {
  return on ? <RadioButton size={22} weight="fill" color={c.text} /> : <Circle size={22} color={c.textMuted} />;
}

export default function Checkout() {
  const router = useRouter();
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const payments = usePayments();
  const { confirmWithDevice } = useAuth();
  const [addressId, setAddressId] = useState<string | null>(null);
  const summary = useCheckoutSummary(addressId);
  const s = summary.data;
  const [choices, setChoices] = useState<Record<string, PackageChoice>>({});
  const [pay, setPay] = useState<PayChoice | null>(null);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const key = useRef<string | null>(null);
  /** The order the current key created (asked about after a failure before a new key is made). */
  const placedNumber = useRef<string | null>(null);

  useEffect(() => {
    if (!s) return;
    setChoices((cur) => (Object.keys(cur).length ? cur : defaultChoices(s)));
    setAddressId((cur) => cur ?? s.addresses.find((a) => a.isDefault)?.id ?? s.addresses[0]?.id ?? null);
    setPay((cur) => cur ?? (s.paymentMethods.find((p) => p.isDefault) ? { kind: 'saved', id: s.paymentMethods.find((p) => p.isDefault)?.id ?? '' } : { kind: 'sheet', wallet: Platform.OS === 'ios' ? 'apple_pay' : 'google_pay' }));
  }, [s]);

  const total = useMemo(() => (s ? totalFor(s, choices) : 0), [s, choices]);
  const address = s?.addresses.find((a) => a.id === addressId);

  if (summary.isPending) return <Screen header={<SubHeader title="Checkout" />}><Loading /></Screen>;
  if (!s) {
    return (
      <Screen header={<SubHeader title="Checkout" />}>
        {isApiError(summary.error) && summary.error.status < 500 ? (
          <Empty icon={WarningCircle} title={summary.error.status === 409 ? 'Your cart is empty' : 'Checkout isn’t available right now'} body={summary.error.message} action={{ label: 'Keep shopping', onPress: () => router.navigate('/') }} />
        ) : (
          <ErrorView error={summary.error} onRetry={() => void summary.refetch()} retrying={summary.isFetching} />
        )}
      </Screen>
    );
  }

  const setChoice = (pkgId: string, patch: Partial<PackageChoice>) => setChoices((cur) => ({ ...cur, [pkgId]: { ...(cur[pkgId] ?? { option: 'carrier' }), ...patch } }));

  const place = async (sum: CheckoutSummary) => {
    setErr('');
    if (!address) return setErr('Add a delivery address first.');
    const invalid = validateChoices(sum, choices);
    if (invalid) return setErr(invalid);
    if (!pay) return setErr('Choose a payment method.');
    const wallet = pay.kind === 'sheet' ? pay.wallet : null;
    if (requiresDeviceAuth(total, wallet) && !(await confirmWithDevice(`Confirm your order of ${formatMoney(total, sum.currency)}`))) return;
    key.current ??= idempotencyKey(() => Crypto.randomUUID());
    const idem = key.current;
    setBusy(true);
    try {
      let orderNumber: string;
      if (pay.kind === 'saved') {
        const conf = await api.checkout.placeOrder(buildPlaceOrderRequest(address.id, choices, { paymentMethodId: pay.id }), idem);
        placedNumber.current = conf.orderNumber;
        if (conf.status === 'requires_action' && conf.paymentClientSecret) {
          const r = await payments.handleNextAction(conf.paymentClientSecret);
          if (!r.ok) throw new Error(r.message);
        }
        orderNumber = conf.orderNumber;
      } else {
        const intent = paymentSheetIntent(sum, total);
        const r = await payments.payWithSheet({
          amountMinor: intent.amount,
          currency: intent.currencyCode,
          placeOrder: async (paymentMethodId) => {
            const conf = await api.checkout.placeOrder(buildPlaceOrderRequest(address.id, choices, { paymentMethodId }), idem);
            placedNumber.current = conf.orderNumber;
            return conf;
          }
        });
        if (r.status === 'cancelled') return;
        if (r.status === 'failed') throw new Error(r.message);
        orderNumber = r.confirmation.orderNumber;
      }
      key.current = null;
      placedNumber.current = null;
      void qc.invalidateQueries({ queryKey: cartKeys.all });
      void qc.invalidateQueries({ queryKey: accountKeys.orders() });
      void qc.invalidateQueries({ queryKey: checkoutKeys.all });
      toast('Order placed');
      router.replace({ pathname: '/orders/[orderNumber]', params: { orderNumber, placed: '1' } });
    } catch (e) {
      // Keep the key for a retry unless the attempt is definitely over (then the next tap is a new purchase).
      const known = placedNumber.current;
      key.current = await keyAfterFailure(key.current, e, known ? () => api.checkout.status(known) : undefined);
      if (!key.current) placedNumber.current = null;
      setErr(isApiError(e) ? e.message : e instanceof Error ? e.message : 'Payment failed. Try again.');
    } finally {
      setBusy(false);
    }
  };

  const walletLabel = Platform.OS === 'ios' ? 'Apple Pay' : 'Google Pay';
  const isWallet = pay?.kind === 'sheet' && pay.wallet;
  const footer = (
    <Button
      kind={isWallet ? 'dark' : 'primary'}
      icon={isWallet ? (Platform.OS === 'ios' ? AppleLogo : GoogleLogo) : LockSimple}
      label={isWallet ? `Pay with ${walletLabel} · ${formatMoney(total, s.currency)}` : `Place order · ${formatMoney(total, s.currency)}`}
      loading={busy}
      onPress={() => void place(s)}
    />
  );

  return (
    <Screen header={<SubHeader title="Checkout" />} footer={footer}>
      <Card onPress={() => router.push('/addresses')} accessibilityLabel="Change delivery address">
        <SectionLabel>DELIVER TO</SectionLabel>
        {address ? (
          <View style={styles.between}>
            <AppText style={{ flex: 1 }}>
              <AppText variant="bodyStrong">{address.name}</AppText>{'\n'}{address.line1}{address.line2 ? `, ${address.line2}` : ''}{'\n'}{address.city}, {address.state} {address.zip}
            </AppText>
            <AppText variant="bodyStrong" style={{ textDecorationLine: 'underline' }}>Change</AppText>
          </View>
        ) : <AppText>Add an address</AppText>}
      </Card>

      {s.packages.map((p) => {
        const ch = choices[p.packageId];
        return (
          <View key={p.packageId} style={{ gap: sp['2'] }}>
            <SectionLabel>{p.heading.toUpperCase()} · {p.itemCount} ITEM{p.itemCount === 1 ? '' : 'S'}</SectionLabel>
            {p.deliveryOptions.map((o) => (
              <Card key={o.key} selected={ch?.option === o.key} onPress={() => setChoice(p.packageId, { option: o.key, ...(o.key !== 'pickup' ? { pickupPointId: undefined } : {}) })} accessibilityLabel={`${o.label}, ${o.eta}`}>
                <View style={styles.between}>
                  <View style={{ flex: 1 }}>
                    <AppText variant="bodyStrong">{o.eta}</AppText>
                    <AppText variant="small" color={c.textMuted}>{o.label} · {o.free ? 'Free' : formatMoney(o.feeMinor, s.currency)}</AppText>
                  </View>
                  <Radio on={ch?.option === o.key} />
                </View>
              </Card>
            ))}
            {ch?.option === 'pickup' ? (
              <View style={{ gap: sp['2'] }}>
                {(p.pickupPoints ?? []).map((pp) => (
                  <Card key={pp.id} selected={ch.pickupPointId === pp.id} onPress={() => setChoice(p.packageId, { pickupPointId: pp.id })}>
                    <AppText variant="bodyStrong">{pp.name}</AppText>
                    <AppText variant="small" color={c.textMuted}>{pp.address} · {pp.hours}</AppText>
                    <AppText variant="caption" color={c.success}>Ready {pp.ready.toLowerCase()}</AppText>
                  </Card>
                ))}
              </View>
            ) : null}
            {ch?.option === 'afrotransact_delivery' ? (
              <View style={styles.prefs}>
                <AppText variant="small" style={{ fontWeight: t.fontWeight.semibold }}>Delivery preference</AppText>
                <View style={styles.chips}>
                  {HANDOVER.map((h) => <Chip key={h.key} label={h.label} selected={(ch.preferences?.handover ?? 'hand') === h.key} onPress={() => setChoice(p.packageId, { preferences: { ...ch.preferences, handover: h.key } })} />)}
                </View>
                <View style={styles.chips}>
                  {CONTACT.map((h) => <Chip key={h.key} label={h.label} selected={(ch.preferences?.contact ?? 'text') === h.key} onPress={() => setChoice(p.packageId, { preferences: { ...ch.preferences, contact: h.key } })} />)}
                </View>
                <TextInput
                  value={ch.preferences?.instructions ?? ''}
                  onChangeText={(v) => setChoice(p.packageId, { preferences: { ...ch.preferences, instructions: v.slice(0, 240) } })}
                  placeholder="Gate code, where to leave packages…"
                  placeholderTextColor={c.textSubtle}
                  accessibilityLabel="Delivery instructions"
                  multiline
                  style={styles.textarea}
                />
                {(ch.preferences?.handover ?? 'hand') === 'hand' ? <AppText variant="caption" color={c.textMuted}>You’ll get a 4-digit delivery code to share with your driver at the door.</AppText> : null}
              </View>
            ) : null}
          </View>
        );
      })}

      <View style={{ gap: sp['2'] }}>
        <View style={styles.between}>
          <SectionLabel>PAYMENT</SectionLabel>
          <AppText variant="small" accessibilityRole="button" style={{ textDecorationLine: 'underline', fontWeight: t.fontWeight.semibold }} onPress={() => router.push('/payments/add')}>Add payment method</AppText>
        </View>
        <Card selected={pay?.kind === 'sheet' && !!pay.wallet} onPress={() => setPay({ kind: 'sheet', wallet: Platform.OS === 'ios' ? 'apple_pay' : 'google_pay' })} accessibilityLabel={walletLabel}>
          <View style={styles.between}>
            {Platform.OS === 'ios' ? <AppleLogo size={22} weight="fill" color={c.text} /> : <GoogleLogo size={22} color={c.text} />}
            <AppText style={{ flex: 1 }}>{walletLabel}</AppText>
            <Radio on={pay?.kind === 'sheet' && !!pay.wallet} />
          </View>
        </Card>
        {s.paymentMethods.map((m) => {
          const on = pay?.kind === 'saved' && pay.id === m.id;
          return (
            <Card key={m.id} selected={on} onPress={() => setPay({ kind: 'saved', id: m.id })} accessibilityLabel={m.label}>
              <View style={styles.between}>
                <PaymentMark method={m} height={24} />
                <AppText style={{ flex: 1 }}>{m.label}</AppText>
                <Radio on={on} />
              </View>
            </Card>
          );
        })}
        <Card selected={pay?.kind === 'sheet' && !pay.wallet} onPress={() => setPay({ kind: 'sheet', wallet: null })} accessibilityLabel="Another card or bank account">
          <View style={styles.between}>
            <CreditCard size={22} color={c.text} />
            <AppText style={{ flex: 1 }}>Another card or US bank account</AppText>
            <Radio on={pay?.kind === 'sheet' && !pay.wallet} />
          </View>
        </Card>
      </View>

      <View style={styles.summary}>
        <KeyValue k="Items" v={formatMoney(s.subtotalMinor, s.currency)} />
        <KeyValue k="Delivery" v={total - s.subtotalMinor - s.taxMinor ? formatMoney(total - s.subtotalMinor - s.taxMinor, s.currency) : 'Free'} />
        <KeyValue k={`Estimated tax (${s.taxRateLabel})`} v={formatMoney(s.taxMinor, s.currency)} />
        <KeyValue k="Total" v={formatMoney(total, s.currency)} strong />
        <AppText variant="caption" color={c.textMuted}>One charge, split automatically between AfroTransact and each seller.</AppText>
      </View>
      {err ? <Banner tone="danger" icon={WarningCircle}>{err}</Banner> : null}
      {payments.mode === 'mock' ? <AppText variant="caption" color={c.textSubtle}>Dev mocks: Stripe is simulated.</AppText> : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  between: { flexDirection: 'row', alignItems: 'center', gap: sp['3'] },
  prefs: { gap: sp['2'], padding: sp['3'], borderRadius: t.radius.md, backgroundColor: c.surfaceMuted },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: sp['2'] },
  textarea: { minHeight: 64, borderWidth: 1, borderColor: c.borderStrong, borderRadius: t.radius.md, padding: sp['3'], fontSize: t.fontSize.base, color: c.text, backgroundColor: c.surface, textAlignVertical: 'top' },
  summary: { gap: sp['2'], padding: sp['4'], borderRadius: t.radius.lg, backgroundColor: c.surfaceMuted }
});
