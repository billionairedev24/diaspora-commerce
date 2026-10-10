/* Your orders (design: ORDERS): track, return items, buy again. */
import { formatMoney } from '@afrotransact/api-client';
import { useRouter } from 'expo-router';
import { ArrowUUpLeftIcon as ArrowUUpLeft, CheckCircleIcon as CheckCircle, ReceiptIcon as Receipt, TruckIcon as Truck } from 'phosphor-react-native';
import { FlatList, StyleSheet, View } from 'react-native';

import { useOrders, useReturns } from '@/api/queries';
import { stepIndex } from '@/domain/tracking';
import { c, sp } from '@/theme';
import { AppText, Button, Card, Empty, Loading } from '@/ui/primitives';
import { Screen, SubHeader } from '@/ui/Screen';

export default function Orders() {
  const router = useRouter();
  const orders = useOrders();
  const returns = useReturns();
  return (
    <Screen header={<SubHeader title="Your orders" />} body="fixed">
      <FlatList
        data={orders.data ?? []}
        keyExtractor={(o) => o.orderNumber}
        contentContainerStyle={styles.list}
        showsVerticalScrollIndicator={false}
        ListEmptyComponent={orders.isLoading ? <Loading /> : <Empty icon={Receipt} title="No orders yet" action={{ label: 'Start shopping', onPress: () => router.navigate('/') }} />}
        renderItem={({ item: o }) => {
          const delivered = o.shipments.every((s) => stepIndex(s.status) >= 4);
          const refunded = o.status === 'refunded';
          const ret = returns.data?.find((r) => r.orderNumber === o.orderNumber);
          const StatusIcon = refunded || ret ? ArrowUUpLeft : delivered ? CheckCircle : Truck;
          const status = refunded ? 'Refunded' : ret ? 'Return started' : delivered ? `Delivered ${new Date(o.placedAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}` : 'On the way';
          return (
            <Card>
              <View style={styles.row}>
                <StatusIcon size={24} weight={delivered && !ret && !refunded ? 'fill' : 'regular'} color={delivered && !ret && !refunded ? c.success : ret ? c.warning : c.text} />
                <View style={{ flex: 1 }}>
                  <AppText variant="bodyStrong">{status}</AppText>
                  <AppText variant="small" color={c.textMuted}>{o.orderNumber} · {formatMoney(o.totalMinor, o.currency)}</AppText>
                </View>
              </View>
              <AppText variant="small" numberOfLines={2}>{o.shipments.map((s) => s.items).join(' · ')}</AppText>
              <View style={styles.actions}>
                {!delivered && !refunded ? <Button size="md" label="Track package" onPress={() => router.push(`/orders/${o.orderNumber}`)} /> : null}
                {delivered && !refunded ? <Button size="md" kind="outline" label={ret ? 'View return QR' : 'Return items'} onPress={() => router.push(`/returns/${o.orderNumber}`)} /> : null}
                {delivered || refunded ? <Button size="md" kind="outline" label="Order details" onPress={() => router.push(`/orders/${o.orderNumber}`)} /> : null}
              </View>
            </Card>
          );
        }}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  list: { padding: sp['4'], gap: sp['3'] },
  row: { flexDirection: 'row', alignItems: 'center', gap: sp['3'] },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: sp['2'] }
});
