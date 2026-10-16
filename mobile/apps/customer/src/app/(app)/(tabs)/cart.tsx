/* Cart grouped by who ships it (design: CART). Pinned title; lines scroll; checkout bar pinned above the tab bar. */
import { useRouter } from 'expo-router';
import { LockSimpleIcon as LockSimple, ShoppingCartIcon as ShoppingCart } from 'phosphor-react-native';
import { StyleSheet, View } from 'react-native';

import { useCart, useCartActions } from '@/api/queries';
import { ProductImage, QtyStepper } from '@/features/shop/components';
import { useFormatters, useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { AppText, Button, Empty, KeyValue, Loading, SectionLabel } from '@/ui/primitives';
import { ErrorView, useRefresh } from '@/ui/QueryStates';
import { Screen } from '@/ui/Screen';

export default function CartScreen() {
  const fmt = useFormatters();
  const tr = useT();
  const router = useRouter();
  const cart = useCart();
  const { update, remove } = useCartActions();
  const data = cart.data;
  const refresh = useRefresh(cart);
  const header = <View style={styles.header}><AppText variant="display" accessibilityRole="header">{tr('cu.cart.cart')}</AppText></View>;
  if (cart.isPending) return <Screen inTabs header={header}><Loading /></Screen>;
  if (cart.isError && !data) return <Screen inTabs header={header} refresh={refresh}><ErrorView error={cart.error} onRetry={() => void cart.refetch()} retrying={cart.isFetching} /></Screen>;
  if (!data || data.itemCount === 0) {
    return (
      <Screen inTabs header={header} refresh={refresh}>
        <Empty icon={ShoppingCart} title={tr('cu.cart.yourCartIsEmpty')} action={{ label: 'Start shopping', onPress: () => router.navigate('/') }} />
      </Screen>
    );
  }
  const nudge = data.packages.find((p) => p.freeShippingNudge)?.freeShippingNudge;
  return (
    <Screen
      inTabs
      refresh={refresh}
      header={header}
      footer={<Button icon={LockSimple} label={tr('cu.cart.checkout', { amount: fmt.money(data.subtotalMinor, data.currency) })} onPress={() => router.push('/checkout')} disabled={data.selectedCount === 0} />}
    >
      {data.packages.map((pkg) => (
        <View key={pkg.packageId} style={styles.group}>
          <SectionLabel>{pkg.heading.toUpperCase()}</SectionLabel>
          {pkg.lines.map((l) => (
            <View key={l.lineId} style={styles.line}>
              <ProductImage title={l.title} size={72} />
              <View style={{ flex: 1, gap: 4 }}>
                <AppText numberOfLines={2}>{l.title}</AppText>
                <AppText variant="bodyStrong">{fmt.money(l.lineTotalMinor, data.currency)}</AppText>
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
        <KeyValue k={tr('cu.cart.subtotal', { count: data.itemCount })} v={fmt.money(data.subtotalMinor, data.currency)} strong />
        <KeyValue k={tr('cu.cart.delivery')} v={nudge ? tr('cu.cart.calculatedAtCheckout') : tr('cu.cart.free')} />
        <AppText variant="caption" color={c.textMuted}>{nudge ? tr('cu.cart.addForFree', { amount: fmt.money(nudge.remainingMinor, data.currency) }) : tr('cu.cart.freeSameDay')}</AppText>
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
