/* Choose a time (design: PICK A TIME, S167): days from the provider's live calendar, open slots, deposit summary.
   New bookings pay the deposit with Stripe's PaymentSheet on the PaymentIntent bookings-service creates; with
   ?bookingId= this screen reschedules instead. */
import { formatMoney, isApiError } from '@afrotransact/api-client';
import { useQueryClient } from '@tanstack/react-query';
import * as Crypto from 'expo-crypto';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { ArrowsClockwiseIcon as ArrowsClockwise, CalendarCheckIcon as CalendarCheck, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useMemo, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { useApi } from '@/api/ApiProvider';
import { bookingKeys } from '@/api/keys';
import { useBooking, usePaymentMethods, useProvider, useServiceDays, useServiceSlots, useServiceSummary } from '@/api/queries';
import { useAuth } from '@/auth/AuthProvider';
import { bookButtonLabel, bookingFinePrint, formatWhen } from '@/domain/bookings';
import { usePayments } from '@/payments/usePayments';
import { c, sp, t } from '@/theme';
import { AppText, Banner, Button, KeyValue, Loading } from '@/ui/primitives';
import { Screen, SubHeader } from '@/ui/Screen';
import { useToast } from '@/ui/Toast';

const DEPOSIT_DEVICE_AUTH_MINOR = 100_00;

export default function PickTime() {
  const { serviceId, orgId, bookingId } = useLocalSearchParams<{ serviceId: string; orgId?: string; bookingId?: string }>();
  const router = useRouter();
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const payments = usePayments();
  const { user, confirmWithDevice } = useAuth();
  const summary = useServiceSummary(serviceId ?? '');
  const provider = useProvider(orgId ?? '');
  const days = useServiceDays(serviceId ?? '');
  const existing = useBooking(bookingId ?? '');
  const pms = usePaymentMethods();
  const [day, setDay] = useState<string | null>(null);
  const [start, setStart] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const firstOpen = useMemo(() => days.data?.find((d) => (d.slots ?? 0) > 0)?.date ?? null, [days.data]);
  const selectedDay = day ?? firstOpen;
  const slots = useServiceSlots(serviceId ?? '', selectedDay);
  const s = summary.data;
  const reschedule = !!bookingId;
  const instant = s?.instantBooking ?? provider.data?.instantBooking ?? false;
  const tz = provider.data?.timeZone;
  const cur = s?.currency ?? 'USD';
  const payWith = pms.data?.find((p) => p.isDefault)?.label ?? 'Card or wallet (Stripe)';

  const go = async () => {
    if (!start || !s) return setErr('Pick a time first.');
    setErr('');
    setBusy(true);
    try {
      if (reschedule && bookingId) {
        const b = await api.bookings.reschedule(bookingId, start);
        void qc.invalidateQueries({ queryKey: bookingKeys.all });
        toast(b.status === 'RESCHEDULE_REQUESTED' ? `New time sent to ${s.providerName}` : 'Booking moved');
        router.replace(`/bookings/${bookingId}`);
        return;
      }
      if ((s.depositNowMinor ?? 0) > DEPOSIT_DEVICE_AUTH_MINOR && !(await confirmWithDevice(`Pay a ${formatMoney(s.depositNowMinor ?? 0, cur)} deposit`))) return;
      const res = await api.bookings.create(
        { serviceId: serviceId ?? '', startAt: start, mode: 'IN_PERSON', customer: { name: `${user?.first ?? ''} ${user?.last ?? ''}`.trim(), email: user?.email, phone: user?.phone || undefined }, notes: note || undefined, channel: 'app' },
        `bk_${Crypto.randomUUID().replace(/-/g, '')}`
      );
      const b = res.booking;
      if (!b?.id) throw new Error('Booking failed');
      if (b.payment?.clientSecret) {
        const paid = await payments.payDeposit(b.payment.clientSecret);
        if (paid === 'cancelled') { setErr('Deposit not paid — your slot is held for a few minutes.'); return; }
        if (typeof paid === 'object') throw new Error(paid.error);
        await api.bookings.syncPayment(b.id);
      }
      void qc.invalidateQueries({ queryKey: bookingKeys.all });
      toast(b.status === 'CONFIRMED' ? 'You’re booked' : `Request sent to ${s.providerName}`);
      router.replace(`/bookings/${b.id}`);
    } catch (e) {
      if (isApiError(e) && e.code === 'bookings.slot-taken') { setStart(null); void qc.invalidateQueries({ queryKey: bookingKeys.slots(serviceId ?? '', selectedDay ?? '') }); }
      setErr(isApiError(e) ? e.message : e instanceof Error ? e.message : 'Booking failed. Try again.');
    } finally {
      setBusy(false);
    }
  };

  const label = reschedule ? (instant ? 'Move booking' : 'Ask to move') : bookButtonLabel(instant, s?.depositNowMinor ?? 0, cur, !!start);
  const footer = <Button icon={reschedule ? ArrowsClockwise : CalendarCheck} kind={start ? 'primary' : 'muted'} label={start ? label : 'Pick a time'} loading={busy} onPress={() => void go()} />;
  if (!s || !days.data) return <Screen header={<SubHeader title="Choose a time" />}><Loading /></Screen>;
  const month = selectedDay ? new Date(`${selectedDay}T12:00:00`).toLocaleDateString('en-US', { month: 'long', year: 'numeric' }) : '';

  return (
    <Screen header={<SubHeader title={reschedule ? 'Reschedule' : 'Choose a time'} />} footer={footer}>
      <View style={[styles.head, reschedule ? { backgroundColor: t.gold['50'] } : null]}>
        <AppText variant="bodyStrong">{reschedule ? `Move: ${existing.data?.service?.name ?? s.serviceName}` : s.serviceName}</AppText>
        <AppText variant="small" color={c.textMuted}>{reschedule && existing.data?.startAt ? `Currently ${formatWhen(existing.data.startAt, tz)}` : `${s.providerName} · ${s.durationMinutes} min · ${formatMoney(s.priceMinor ?? 0, cur)}`}</AppText>
      </View>
      <View style={styles.between}>
        <AppText variant="bodyStrong">{month}</AppText>
        <AppText variant="caption" color={c.textMuted}>{(tz ?? '').replace('America/', '').replace('_', ' ')} time</AppText>
      </View>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: sp['2'] }}>
        {days.data.map((d) => {
          const on = d.date === selectedDay;
          const none = !(d.slots ?? 0);
          const date = new Date(`${d.date}T12:00:00`);
          return (
            <Pressable key={d.date} disabled={none} accessibilityRole="button" accessibilityState={{ selected: on, disabled: none }} accessibilityLabel={date.toDateString()} onPress={() => { setDay(d.date ?? null); setStart(null); setErr(''); }} style={[styles.day, on ? styles.dayOn : null, none ? { opacity: 0.35 } : null]}>
              <AppText variant="caption">{date.toLocaleDateString('en-US', { weekday: 'short' })}</AppText>
              <AppText variant="heading">{date.getDate()}</AppText>
              <View style={[styles.dot, { backgroundColor: none ? 'transparent' : c.success }]} />
            </Pressable>
          );
        })}
      </ScrollView>
      {slots.isLoading ? <Loading /> : (slots.data ?? []).length === 0 ? <AppText color={c.textMuted}>No open times this day.</AppText> : (
        <View style={styles.slots}>
          {(slots.data ?? []).map((x) => {
            const on = x.start === start;
            return (
              <Pressable key={x.start} accessibilityRole="button" accessibilityState={{ selected: on }} onPress={() => { setStart(x.start ?? null); setErr(''); }} style={[styles.slot, on ? styles.slotOn : null]}>
                <AppText variant="small" style={{ fontWeight: on ? t.fontWeight.bold : t.fontWeight.regular }}>{new Date(x.start ?? '').toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', ...(tz ? { timeZone: tz } : {}) })}</AppText>
              </Pressable>
            );
          })}
        </View>
      )}
      {start ? (
        <View style={{ gap: sp['3'] }}>
          {!reschedule ? <TextInput value={note} onChangeText={setNote} placeholder="Note for the provider (optional)" placeholderTextColor={c.textSubtle} accessibilityLabel="Note" multiline style={styles.note} /> : null}
          <View style={styles.summary}>
            {reschedule ? (
              <>
                <KeyValue k="New time" v={formatWhen(start, tz)} />
                <KeyValue k="Price" v={`${formatMoney(existing.data?.service?.priceMinor ?? s.priceMinor ?? 0, cur)} · unchanged`} />
              </>
            ) : (
              <>
                <KeyValue k="When" v={formatWhen(start, tz)} />
                <KeyValue k="Price" v={formatMoney(s.priceMinor ?? 0, cur)} />
                <KeyValue k="Deposit now" v={formatMoney(s.depositNowMinor ?? 0, cur)} strong />
                <KeyValue k="After appointment" v={formatMoney(s.balanceLaterMinor ?? 0, cur)} />
                <KeyValue k="Pay with" v={payWith} />
              </>
            )}
          </View>
          <AppText variant="small"><AppText variant="small" style={{ fontWeight: t.fontWeight.bold }}>{s.policy} cancellation.</AppText> {s.policyText}</AppText>
          <AppText variant="caption" color={c.textMuted}>{reschedule ? (instant ? 'Confirmed straight away if the time is still open.' : `${s.providerName} confirms the new time. Your original booking stands until then.`) : bookingFinePrint(instant, s.providerName ?? 'the provider')}</AppText>
        </View>
      ) : null}
      {err ? <Banner tone="danger" icon={WarningCircle}>{err}</Banner> : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  head: { padding: sp['4'], borderRadius: t.radius.lg, backgroundColor: c.surfaceMuted, gap: 2 },
  between: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline' },
  day: { width: 56, paddingVertical: sp['2'], alignItems: 'center', gap: 2, borderRadius: t.radius.md, borderWidth: 1, borderColor: c.border, backgroundColor: c.surface },
  dayOn: { borderColor: t.ink['1000'], backgroundColor: c.actionPrimary },
  dot: { width: 6, height: 6, borderRadius: 3 },
  slots: { flexDirection: 'row', flexWrap: 'wrap', gap: sp['2'] },
  slot: { minWidth: 92, alignItems: 'center', paddingVertical: sp['3'], borderRadius: t.radius.md, borderWidth: 1, borderColor: c.border },
  slotOn: { borderColor: t.ink['1000'], backgroundColor: c.warningBg },
  note: { minHeight: 56, borderWidth: 1, borderColor: c.borderStrong, borderRadius: t.radius.md, padding: sp['3'], fontSize: t.fontSize.base, color: c.text, textAlignVertical: 'top' },
  summary: { gap: sp['2'], padding: sp['4'], borderRadius: t.radius.lg, backgroundColor: c.surfaceMuted }
});
