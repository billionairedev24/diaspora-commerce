/* Appointment (design: BOOKING DETAIL, S167): status, time, actions by status, exact refund before cancelling,
   payment breakdown, policy snapshot and history. */
import { formatMoney, isApiError } from '@afrotransact/api-client';
import { useQueryClient } from '@tanstack/react-query';
import * as Crypto from 'expo-crypto';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { ArrowRightIcon as ArrowRight, CalendarBlankIcon as CalendarBlank, HashIcon as Hash, MapPinIcon as MapPin } from 'phosphor-react-native';
import { useState } from 'react';
import { Share, StyleSheet, View } from 'react-native';

import { useApi } from '@/api/ApiProvider';
import { bookingKeys } from '@/api/keys';
import { useBooking, useBookingHistory, useCancellationQuote } from '@/api/queries';
import { type BookingAction, bookingActions, cancelText, formatWhen } from '@/domain/bookings';
import { statusTone } from '@/features/bookings/statusTone';
import { c, sp, t } from '@/theme';
import { AppText, Button, type ButtonKind, Card, KeyValue, Loading } from '@/ui/primitives';
import { Screen, SubHeader } from '@/ui/Screen';
import { useToast } from '@/ui/Toast';

export default function BookingDetail() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const router = useRouter();
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const booking = useBooking(id ?? '');
  const history = useBookingHistory(id ?? '');
  const [confirming, setConfirming] = useState(false);
  const quote = useCancellationQuote(id ?? '', confirming);
  const [busy, setBusy] = useState(false);
  const b = booking.data;
  const header = <SubHeader title="Appointment" onBack={() => router.navigate('/bookings')} />;
  if (!b) return <Screen header={header}><Loading /></Screen>;
  const cur = b.service?.currency ?? 'USD';
  const tone = statusTone(b.status);

  const run = async (fn: () => Promise<unknown>, msg: string) => {
    setBusy(true);
    try {
      await fn();
      toast(msg);
      void qc.invalidateQueries({ queryKey: bookingKeys.all });
    } catch (e) {
      toast(isApiError(e) ? e.message : 'Something went wrong');
    } finally {
      setBusy(false);
      setConfirming(false);
    }
  };

  const ACTIONS: Record<BookingAction, { label: string; kind: ButtonKind; onPress: () => void }> = {
    'accept-new-time': { label: 'Accept new time', kind: 'primary', onPress: () => void run(() => api.bookings.acceptNewTime(b.id ?? ''), 'New time confirmed') },
    'decline-new-time': { label: 'Decline · full refund', kind: 'outline', onPress: () => void run(() => api.bookings.declineNewTime(b.id ?? ''), 'Declined · full refund on its way') },
    reschedule: { label: 'Reschedule', kind: 'outline', onPress: () => router.push({ pathname: '/book/[serviceId]', params: { serviceId: b.service?.serviceId ?? '', orgId: b.orgId ?? '', bookingId: b.id ?? '' } }) },
    'add-to-calendar': { label: 'Add to calendar', kind: 'outline', onPress: () => void Share.share({ message: `${b.service?.name} with ${b.service?.providerName} — ${formatWhen(b.startAt ?? '')} (${b.reference ?? ''})` }) },
    cancel: { label: 'Cancel booking', kind: 'outline', onPress: () => setConfirming(true) },
    'book-again': { label: 'Book again', kind: 'primary', onPress: () => router.push({ pathname: '/book/[serviceId]', params: { serviceId: b.service?.serviceId ?? '', orgId: b.orgId ?? '' } }) }
  };
  const actions = bookingActions(b.status);
  const pay = b.payment;
  const money: Array<[string, string]> = [
    ['Price', formatMoney(b.service?.priceMinor ?? 0, cur)],
    ['Deposit', `${formatMoney(b.service?.depositMinor ?? 0, cur)}${pay?.depositCapturedMinor ? ' · paid' : pay?.authorizedMinor ? ' · on hold' : ''}`],
    ['Balance', pay?.balanceCapturedMinor ? `${formatMoney(pay.balanceCapturedMinor, cur)} · paid` : `${formatMoney(b.service?.balanceMinor ?? 0, cur)} · after appointment`],
    ...(pay?.refundedMinor ? [['Refunded', formatMoney(pay.refundedMinor, cur)] as [string, string]] : [])
  ];

  return (
    <Screen header={header}>
      <View style={{ gap: 4 }}>
        <View style={[styles.pill, { backgroundColor: tone.bg }]}><AppText variant="caption" color={tone.fg} style={{ fontWeight: t.fontWeight.bold }}>{b.statusLabel}</AppText></View>
        <AppText variant="title">{b.service?.name}</AppText>
        <AppText color={c.textMuted}>{b.service?.providerName}</AppText>
      </View>
      <View style={{ gap: sp['2'] }}>
        <Fact icon={CalendarBlank} text={`${formatWhen(b.startAt ?? '')} – ${new Date(b.endAt ?? '').toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })}`} />
        {b.proposedStartAt ? <Fact icon={ArrowRight} text={`${b.status === 'RESCHEDULE_PROPOSED' ? 'Proposed' : 'Requested'}: ${formatWhen(b.proposedStartAt)}`} /> : null}
        {b.location ? <Fact icon={MapPin} text={b.location} /> : null}
        <Fact icon={Hash} text={b.reference ?? b.id ?? ''} />
      </View>
      {actions.length && !confirming ? <View style={styles.actions}>{actions.map((a) => <Button key={a} size="md" kind={ACTIONS[a].kind} label={ACTIONS[a].label} onPress={ACTIONS[a].onPress} disabled={busy} style={a === 'cancel' ? { borderColor: c.danger } : undefined} />)}</View> : null}
      {confirming ? (
        <Card style={{ borderColor: c.danger }}>
          <AppText variant="heading">Cancel this booking?</AppText>
          {quote.isLoading ? <Loading /> : <AppText>{quote.data ? cancelText(quote.data) : 'We couldn’t load the refund amount. Try again.'}</AppText>}
          <Button kind="danger" label="Yes, cancel" loading={busy} disabled={!quote.data} onPress={() => void run(() => api.bookings.cancel(b.id ?? '', `cx_${Crypto.randomUUID().replace(/-/g, '')}`), 'Booking cancelled')} />
          <Button kind="ghost" label="Keep booking" onPress={() => setConfirming(false)} />
        </Card>
      ) : null}
      <Card>
        <AppText variant="bodyStrong">Payment</AppText>
        {money.map(([k, v]) => <KeyValue key={k} k={k} v={v} />)}
      </Card>
      <AppText variant="small"><AppText variant="small" style={{ fontWeight: t.fontWeight.bold }}>{b.policy?.label} policy.</AppText> {b.policy?.text}</AppText>
      <View style={{ gap: sp['2'] }}>
        <AppText variant="bodyStrong">Updates</AppText>
        {(history.data ?? []).map((h, i) => (
          <View key={`${h.at}-${i}`} style={styles.hist}>
            <View style={[styles.dot, { backgroundColor: h.by === 'provider' ? c.link : h.by === 'customer' ? t.gold['500'] : t.ink['400'] }]} />
            <View style={{ flex: 1 }}>
              <AppText variant="small">{h.action}</AppText>
              <AppText variant="caption" color={c.textMuted}>{new Date(h.at ?? '').toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })} · {h.by}</AppText>
            </View>
          </View>
        ))}
      </View>
    </Screen>
  );
}

function Fact({ icon: I, text }: { icon: typeof Hash; text: string }) {
  return <View style={{ flexDirection: 'row', alignItems: 'center', gap: sp['2'] }}><I size={18} color={c.text} /><AppText style={{ flex: 1 }}>{text}</AppText></View>;
}

const styles = StyleSheet.create({
  pill: { alignSelf: 'flex-start', paddingHorizontal: sp['2'], paddingVertical: 2, borderRadius: t.radius.pill },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: sp['2'] },
  hist: { flexDirection: 'row', gap: sp['3'], alignItems: 'flex-start' },
  dot: { width: 10, height: 10, borderRadius: 5, marginTop: 5 }
});
