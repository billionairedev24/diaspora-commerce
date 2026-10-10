/* Product page (design: PRODUCT): pinned back/share header; details scroll; add-to-cart bar pinned at the bottom. */
import { formatMoney } from '@afrotransact/api-client';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { ArrowUUpLeftIcon as ArrowUUpLeft, CalendarCheckIcon as CalendarCheck, CaretLeftIcon as CaretLeft, ExportIcon as Export, LightningIcon as Lightning, PackageIcon as Package, PlusIcon as Plus, ShoppingCartIcon as ShoppingCart } from 'phosphor-react-native';
import { Share, StyleSheet, View } from 'react-native';

import { useAddresses, useProduct } from '@/api/queries';
import { env } from '@/config/env';
import { useQuantity } from '@/features/shop/cartHooks';
import { ProductImage, QtyStepper } from '@/features/shop/components';
import { c, sp, t } from '@/theme';
import { AppText, Button, IconButton, Loading } from '@/ui/primitives';
import { Screen } from '@/ui/Screen';

export default function ProductScreen() {
  const { atin } = useLocalSearchParams<{ atin: string }>();
  const router = useRouter();
  const product = useProduct(atin ?? '');
  const addresses = useAddresses();
  const d = product.data;
  const offer = d?.buyBox ?? null;
  const { qty, setQty } = useQuantity(atin ?? '', offer?.offerId ?? '');
  const def = addresses.data?.find((a) => a.isDefault);

  const header = (
    <View style={styles.header}>
      <IconButton icon={CaretLeft} label="Back" onPress={() => (router.canGoBack() ? router.back() : router.navigate('/'))} />
      <IconButton icon={Export} label="Share" onPress={() => void Share.share({ message: `${env.webUrl}/p/${atin}` })} />
    </View>
  );
  if (!d) return <Screen header={header}>{product.isError ? <AppText>We couldn’t load this product.</AppText> : <Loading />}</Screen>;

  const footer = !offer ? (
    <Button kind="muted" label="Currently unavailable" onPress={() => undefined} disabled />
  ) : qty > 0 ? (
    <View style={styles.bar}>
      <QtyStepper qty={qty} onChange={setQty} />
      <Button kind="dark" icon={ShoppingCart} label="Go to cart" onPress={() => router.push('/cart')} style={{ flex: 1 }} />
    </View>
  ) : (
    <Button icon={Plus} label={`Add to cart · ${formatMoney(offer.priceMinor, offer.currency)}`} onPress={() => setQty(1)} />
  );

  return (
    <Screen header={header} footer={footer}>
      <ProductImage title={d.product.title} height={240} />
      <View style={{ gap: 4 }}>
        <AppText variant="small" color={c.link}>{d.soldBy}</AppText>
        <AppText variant="title" accessibilityRole="header">{d.product.title}</AppText>
        <AppText variant="small" color={c.textMuted}>★ {d.product.rating} · {d.product.reviewCount.toLocaleString('en-US')} reviews</AppText>
      </View>
      {offer ? (
        <View style={styles.price}>
          <AppText variant="display">{formatMoney(offer.priceMinor, offer.currency)}</AppText>
          <AppText variant="small" color={c.textMuted}>{d.unitPriceLabel}</AppText>
        </View>
      ) : null}
      <View style={styles.facts}>
        {offer ? <Fact icon={Lightning} text={`${offer.delivery.label} to ${def ? `${def.city} ${def.zip}` : 'you'}`} strong /> : null}
        <Fact icon={Package} text={d.shipsFrom} />
        {d.product.bullets[1] ? <Fact icon={CalendarCheck} text={d.product.bullets[1]} /> : null}
        <Fact icon={ArrowUUpLeft} text={d.returnsPolicy} />
        {offer?.stockStatus === 'LOW_STOCK' ? <AppText variant="small" color={c.lowStock}>Only {offer.availableQty} left — order soon</AppText> : null}
      </View>
      <AppText>{d.product.bullets[0]}</AppText>
    </Screen>
  );
}

function Fact({ icon: I, text, strong }: { icon: typeof Package; text: string; strong?: boolean }) {
  return (
    <View style={{ flexDirection: 'row', gap: sp['2'], alignItems: 'center' }}>
      <I size={18} color={strong ? c.success : c.text} />
      <AppText variant={strong ? 'bodyStrong' : 'small'} color={strong ? c.success : c.text} style={{ flex: 1 }}>{text}</AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', justifyContent: 'space-between', paddingHorizontal: sp['4'], paddingVertical: sp['2'], backgroundColor: c.canvas },
  price: { flexDirection: 'row', alignItems: 'baseline', gap: sp['2'] },
  facts: { gap: sp['2'], padding: sp['4'], borderRadius: t.radius.lg, borderWidth: 1, borderColor: c.border },
  bar: { flexDirection: 'row', alignItems: 'center', gap: sp['3'] }
});
