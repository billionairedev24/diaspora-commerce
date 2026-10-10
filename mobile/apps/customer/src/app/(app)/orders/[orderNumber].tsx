/* Tracking (design: TRACKING, S166): status steps, driver and the delivery code for hand-delivery / perishables,
   buy again. Refreshes every 30 s; pushes for this order deep-link here. */
import { formatMoney } from '@afrotransact/api-client';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { CheckCircleIcon as CheckCircle, KeyIcon as Key } from 'phosphor-react-native';
import { StyleSheet, View } from 'react-native';

import { useTracking } from '@/api/queries';
import { useAuth } from '@/auth/AuthProvider';
import { headline, showDeliveryCode, stepIndex, TRACKING_STEPS } from '@/domain/tracking';
import { useQuantity } from '@/features/shop/cartHooks';
import { c, sp, t } from '@/theme';
import { AppText, Banner, Button, Card, Loading, SectionLabel } from '@/ui/primitives';
import { Screen, SubHeader } from '@/ui/Screen';

function BuyAgain({ atin }: { atin: string }) {
  const { qty, setQty } = useQuantity(atin, `of_${atin}`);
  return <Button size="md" kind="outline" label={qty ? 'In cart' : 'Buy again'} onPress={() => setQty(qty + 1)} />;
}

export default function Tracking() {
  const { orderNumber, placed } = useLocalSearchParams<{ orderNumber: string; placed?: string }>();
  const router = useRouter();
  const { user } = useAuth();
  const tr = useTracking(orderNumber ?? '');
  const d = tr.data;
  const header = <SubHeader title={`Order ${orderNumber ?? ''}`} onBack={() => router.navigate('/orders')} />;
  if (!d) return <Screen header={header}>{tr.isError ? <Banner tone="danger">Tracking isn’t available for this order yet.</Banner> : <Loading />}</Screen>;

  return (
    <Screen header={header}>
      {placed ? <Banner tone="success" icon={CheckCircle}>Order placed. Receipt sent to {user?.email}.</Banner> : null}
      {d.shipments.map((s) => {
        const step = stepIndex(s.status);
        return (
          <View key={s.shipmentId} style={{ gap: sp['3'] }}>
            <View style={styles.map}>
              <AppText variant="title" color={c.textInverse}>{headline(s.status, s.eta)}</AppText>
              <AppText variant="small" color={t.ink['200']}>{s.label}{s.eta && step < 4 ? ` · ETA ${s.eta}` : ''}</AppText>
            </View>
            <View accessibilityRole="list">
              {TRACKING_STEPS.map((st, i) => (
                <View key={st.key} style={styles.step} accessibilityLabel={`${st.title}${i <= step ? ', done' : ''}`}>
                  <View style={styles.rail}>
                    <View style={[styles.dot, i <= step ? styles.dotOn : null]} />
                    {i < TRACKING_STEPS.length - 1 ? <View style={[styles.line, i < step ? { backgroundColor: t.ink['1000'] } : null]} /> : null}
                  </View>
                  <View style={{ flex: 1, paddingBottom: sp['3'] }}>
                    <AppText variant="bodyStrong" color={i <= step ? c.text : c.textSubtle}>{st.title}</AppText>
                    {i <= step ? <AppText variant="small" color={c.textMuted}>{i === 2 && s.method !== 'afrotransact_delivery' ? s.label : st.detail}</AppText> : null}
                  </View>
                </View>
              ))}
            </View>
            {s.driver || showDeliveryCode(s) ? (
              <Card>
                <View style={styles.driver}>
                  {s.driver ? <View style={styles.avatar}><AppText variant="bodyStrong" color={c.textInverse}>{s.driver.initials}</AppText></View> : <Key size={28} color={c.text} />}
                  <View style={{ flex: 1 }}>
                    <AppText variant="bodyStrong">{s.driver ? `${s.driver.name} · AfroTransact Delivery` : 'AfroTransact Delivery'}</AppText>
                    <AppText variant="small" color={c.textMuted}>{showDeliveryCode(s) ? 'Share this code at the door' : 'Your driver will text on arrival'}</AppText>
                  </View>
                  {showDeliveryCode(s) ? <AppText variant="display" accessibilityLabel={`Delivery code ${s.deliveryCode?.split('').join(' ')}`} style={{ letterSpacing: 4 }}>{s.deliveryCode}</AppText> : null}
                </View>
              </Card>
            ) : null}
            <SectionLabel>IN THIS PACKAGE</SectionLabel>
            {s.items.map((it) => (
              <View key={it.atin} style={styles.item}>
                <AppText style={{ flex: 1 }}>{it.title}{it.quantity > 1 ? ` ×${it.quantity}` : ''}</AppText>
                <AppText variant="small">{formatMoney(it.priceMinor * it.quantity, d.currency)}</AppText>
                <BuyAgain atin={it.atin} />
              </View>
            ))}
            {s.items.some((i) => i.returnable) ? <Button kind="outline" label="Return items" onPress={() => router.push(`/returns/${d.orderNumber}`)} /> : null}
          </View>
        );
      })}
    </Screen>
  );
}

const styles = StyleSheet.create({
  map: { borderRadius: t.radius.lg, padding: sp['5'], backgroundColor: t.ink['900'], gap: sp['1'], minHeight: 120, justifyContent: 'flex-end' },
  step: { flexDirection: 'row', gap: sp['3'] },
  rail: { alignItems: 'center', width: 16 },
  dot: { width: 14, height: 14, borderRadius: 7, borderWidth: 2, borderColor: t.ink['300'], backgroundColor: c.surface },
  dotOn: { borderColor: t.ink['1000'], backgroundColor: t.ink['1000'] },
  line: { flex: 1, width: 2, backgroundColor: t.ink['200'], marginVertical: 2 },
  driver: { flexDirection: 'row', alignItems: 'center', gap: sp['3'] },
  avatar: { width: 44, height: 44, borderRadius: 22, backgroundColor: t.ink['1000'], alignItems: 'center', justifyContent: 'center' },
  item: { flexDirection: 'row', alignItems: 'center', gap: sp['2'] }
});
