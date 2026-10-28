/* Your orders (design: ORDERS): track, get help with an order, buy again. A delivered order has no self-service return:
   "Get help" opens the storefront's help flow (describe the problem, add photos) and staff resolve it. */
import { formatMoney } from '@afrotransact/api-client';
import { useRouter } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';
import { CheckCircleIcon as CheckCircle, ReceiptIcon as Receipt, TruckIcon as Truck } from 'phosphor-react-native';
import { FlatList, StyleSheet, View } from 'react-native';

import { useOrders } from '@/api/queries';
import { env } from '@/config/env';
import { stepIndex } from '@/domain/tracking';
import { c, sp } from '@/theme';
import { AppText, Button, Card, Empty, Loading } from '@/ui/primitives';
import { ErrorView, refreshControl, useRefresh } from '@/ui/QueryStates';
import { Screen, SubHeader } from '@/ui/Screen';

export default function Orders() {
  const router = useRouter();
  const orders = useOrders();
  const refresh = useRefresh(orders);
  return (
    <Screen header={<SubHeader title="Your orders" />} body="fixed">
      <FlatList
        data={orders.data ?? []}
        keyExtractor={(o) => o.orderNumber}
        contentContainerStyle={styles.list}
        showsVerticalScrollIndicator={false}
        refreshControl={refreshControl(refresh)}
        ListEmptyComponent={orders.isPending ? <Loading /> : orders.isError ? <ErrorView error={orders.error} onRetry={() => void orders.refetch()} retrying={orders.isFetching} /> : <Empty icon={Receipt} title="No orders yet" action={{ label: 'Start shopping', onPress: () => router.navigate('/') }} />}
        renderItem={({ item: o }) => {
          const delivered = o.shipments.every((s) => stepIndex(s.status) >= 4);
          const refunded = o.status === 'refunded';
          const StatusIcon = delivered ? CheckCircle : Truck;
          const status = refunded ? 'Refunded' : delivered ? `Delivered ${new Date(o.placedAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}` : 'On the way';
          return (
            <Card>
              <View style={styles.row}>
                <StatusIcon size={24} weight={delivered && !refunded ? 'fill' : 'regular'} color={delivered && !refunded ? c.success : c.text} />
                <View style={{ flex: 1 }}>
                  <AppText variant="bodyStrong">{status}</AppText>
                  <AppText variant="small" color={c.textMuted}>{o.orderNumber} · {formatMoney(o.totalMinor, o.currency)}</AppText>
                </View>
              </View>
              <AppText variant="small" numberOfLines={2}>{o.shipments.map((s) => s.items).join(' · ')}</AppText>
              <View style={styles.actions}>
                {!delivered && !refunded ? <Button size="md" label="Track package" onPress={() => router.push(`/orders/${o.orderNumber}`)} /> : null}
                {delivered && !refunded ? <Button size="md" kind="outline" label="Get help" onPress={() => void WebBrowser.openBrowserAsync(`${env.webUrl}/account/help/new?order=${encodeURIComponent(o.orderNumber)}`)} /> : null}
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
