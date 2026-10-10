/* Cart grouped by who ships it (design: CART). Pinned title; lines scroll; checkout bar pinned above the tab bar. */
import { formatMoney } from '@afrotransact/api-client';
import { useRouter } from 'expo-router';
import { LockSimpleIcon as LockSimple, ShoppingCartIcon as ShoppingCart } from 'phosphor-react-native';
import { StyleSheet, View } from 'react-native';

import { useCart, useCartActions } from '@/api/queries';
import { ProductImage, QtyStepper } from '@/features/shop/components';
import { c, sp, t } from '@/theme';
import { AppText, Button, Empty, KeyValue, Loading, SectionLabel } from '@/ui/primitives';
import { Screen } from '@/ui/Screen';

export default function CartScreen() {
  const router = useRouter();
  const cart = useCart();
  const { update, remove } = useCartActions();
  const data = cart.data;
  const header = <View style={styles.header}><AppText variant="display" accessibilityRole="header">Cart</AppText></View>;
  if (cart.isLoading) return <Screen inTabs header={header}><Loading /></Screen>;
  if (!data || data.itemCount === 0) {
    return (
      <Screen inTabs header={header}>
        <Empty icon={ShoppingCart} title="Your cart is empty" action={{ label: 'Start shopping', onPress: () => router.navigate('/') }} />
      </Screen>
    );
  }
  const nudge = data.packages.find((p) => p.freeShippingNudge)?.freeShippingNudge;
  return (
    <Screen
      inTabs
      header={header}
      footer={<Button icon={LockSimple} label={`Checkout · ${formatMoney(data.subtotalMinor, data.currency)}`} onPress={() => router.push('/checkout')} disabled={data.selectedCount === 0} />}
    >
      {data.packages.map((pkg) => (
        <View key={pkg.packageId} style={styles.group}>
          <SectionLabel>{pkg.heading.toUpperCase()}</SectionLabel>
          {pkg.lines.map((l) => (
            <View key={l.lineId} style={styles.line}>
              <ProductImage title={l.title} size={72} />
              <View style={{ flex: 1, gap: 4 }}>
                <AppText numberOfLines={2}>{l.title}</AppText>
                <AppText variant="bodyStrong">{formatMoney(l.lineTotalMinor, data.currency)}</AppText>
                <View style={styles.lineFoot}>
                  <QtyStepper qty={l.quantity} trashAtOne onChange={(n) => (n <= 0 ? remove.mutate(l.lineId) : update.mutate({ lineId: l.lineId, quantity: Math.min(n, l.maxQuantity) }))} />
                  <AppText variant="caption" color={c.success}>{pkg.delivery.label}</AppText>
                </View>
                {l.stockStatus === 'LOW_STOCK' ? <AppText variant="caption" color={c.lowStock}>Only {l.availableQty} left — order soon</AppText> : null}
              </View>
            </View>
          ))}
        </View>
      ))}
      <View style={styles.summary}>
        <KeyValue k={`Subtotal (${data.itemCount} items)`} v={formatMoney(data.subtotalMinor, data.currency)} strong />
        <KeyValue k="Delivery" v={nudge ? 'Calculated at checkout' : 'Free'} />
        <AppText variant="caption" color={c.textMuted}>{nudge ? `Add ${formatMoney(nudge.remainingMinor, data.currency)} for free delivery` : 'You get free same-day delivery'}</AppText>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: { paddingHorizontal: sp['4'], paddingVertical: sp['2'], backgroundColor: c.canvas },
  group: { gap: sp['3'] },
  line: { flexDirection: 'row', gap: sp['3'] },
  lineFoot: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: sp['2'] },
  summary: { gap: sp['2'], padding: sp['4'], borderRadius: t.radius.lg, backgroundColor: c.surfaceMuted }
});
