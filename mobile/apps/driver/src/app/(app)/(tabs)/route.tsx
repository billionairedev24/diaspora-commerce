/* Route (design: ROUTE): progress and stops (pickups first, retry-later stops stay open). */
import { useRouter } from 'expo-router';
import { HouseLineIcon as HouseLine, StorefrontIcon as Storefront } from 'phosphor-react-native';
import { FlatList, Pressable, StyleSheet, View } from 'react-native';

import { doneStops, onBoard, perishableAt, stopPackages } from '@/domain/shift';
import { STOP_COLOR, STOP_LABEL } from '@/features/route/labels';
import { useT } from '@/i18n';
import { useDriver } from '@/session/DriverProvider';
import { c, sp, t } from '@/theme';
import { DriverScreen } from '@/ui/DriverScreen';
import { AppText, Loading } from '@/ui/primitives';

export default function RouteTab() {
  const tr = useT();
  const router = useRouter();
  const r = useDriver().sync.route;
  if (!r) return <DriverScreen title={tr('dr.route.route')} inTabs><Loading /></DriverScreen>;
  const done = doneStops(r);
  return (
    <DriverScreen title={tr('dr.route.route')} inTabs body="fixed">
      <FlatList
        data={r.stops}
        keyExtractor={(s) => s.id}
        contentContainerStyle={{ padding: sp['4'], gap: sp['2'] }}
        showsVerticalScrollIndicator={false}
        ListHeaderComponent={
          <View style={{ gap: sp['2'], marginBottom: sp['2'] }}>
            <View style={styles.between}>
              <AppText variant="bodyStrong">{done} of {r.stops.length} stops done</AppText>
              <AppText variant="small" color={c.textMuted}>{onBoard(r)} on board</AppText>
            </View>
            <View style={styles.track}><View style={[styles.fill, { width: `${Math.round((done / Math.max(1, r.stops.length)) * 100)}%` }]} /></View>
          </View>
        }
        renderItem={({ item: s, index }) => {
          const pickup = s.kind === 'PICKUP';
          const closed = s.status === 'DONE' || s.status === 'FAILED';
          const I = pickup ? Storefront : HouseLine;
          return (
            <Pressable accessibilityRole="button" accessibilityLabel={`Stop ${index + 1}, ${s.name}, ${STOP_LABEL[s.status]}`} onPress={() => router.push(`/stop/${s.id}`)} style={[styles.stop, s.status === 'ARRIVED' ? { borderColor: t.gold['500'] } : null]}>
              <View style={[styles.icon, { backgroundColor: s.status === 'DONE' ? t.green['600'] : s.status === 'FAILED' ? c.danger : pickup ? t.gold['500'] : t.ink['1000'] }]}>
                <I size={18} color={pickup && !closed ? c.text : c.textInverse} />
              </View>
              <View style={{ flex: 1 }}>
                <AppText variant="bodyStrong">{index + 1}. {pickup ? tr('dr.route.pickUp') : tr('dr.route.deliver')} · {s.name}</AppText>
                <AppText variant="small" color={c.textMuted}>{s.addressLine}, {s.city} {s.postalCode}</AppText>
                <AppText variant="caption" color={c.textMuted}>{s.window} · {stopPackages(r, s).length} pkg{perishableAt(r, s) ? ' · perishable' : ''}</AppText>
              </View>
              <AppText variant="caption" color={STOP_COLOR[s.status]} style={{ fontWeight: t.fontWeight.bold }}>{STOP_LABEL[s.status]}</AppText>
            </Pressable>
          );
        }}
      />
    </DriverScreen>
  );
}

const styles = StyleSheet.create({
  between: { flexDirection: 'row', justifyContent: 'space-between' },
  track: { height: 6, borderRadius: 3, backgroundColor: t.ink['100'], overflow: 'hidden' },
  fill: { height: 6, backgroundColor: t.green['600'] },
  stop: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], padding: sp['3'], borderRadius: t.radius.lg, borderWidth: 1, borderColor: c.border },
  icon: { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center' }
});
