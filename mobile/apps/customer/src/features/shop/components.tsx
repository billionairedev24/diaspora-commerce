import { formatMoney, type ProductSummary } from '@afrotransact/api-client';
import { useRouter } from 'expo-router';
import { MinusIcon as Minus, PlusIcon as Plus, TrashIcon as Trash } from 'phosphor-react-native';
import { useEffect, useState } from 'react';
import { FlatList, Image, Pressable, StyleSheet, useWindowDimensions, View } from 'react-native';

import { c, sp, t } from '@/theme';
import { AppText } from '@/ui/primitives';
import { productVisual } from '@/ui/productVisual';

import { useQuantity } from './cartHooks';

/**
 * The product photo (`uri` from the catalogue); while it loads, when there is none, or if it fails, the tinted icon tile
 * keeps the layout steady. The photo is described by the product title for screen readers.
 */
export function ProductImage({ title, uri, size = 64, height }: { title: string; uri?: string | null; size?: number; height?: number }) {
  const v = productVisual(title);
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [uri]);
  const box = { width: height ? undefined : size, height: height ?? size };
  const showPhoto = !!uri && !failed;
  return (
    <View style={[styles.img, { backgroundColor: v.tint }, box]}>
      {showPhoto ? (
        <Image
          source={{ uri }}
          accessibilityLabel={title}
          resizeMode="cover"
          onError={() => setFailed(true)}
          style={[StyleSheet.absoluteFill, styles.photo]}
        />
      ) : (
        <v.icon size={(height ?? size) * 0.42} color={t.ink['800']} />
      )}
    </View>
  );
}

/** Product page photos: a swipeable row with page dots; one tile (or the placeholder) when there are fewer than two. */
export function ProductGallery({ title, images, height = 260 }: { title: string; images: Array<{ url: string | null; alt: string }>; height?: number }) {
  const { width } = useWindowDimensions();
  const pageWidth = width - sp['4'] * 2;
  const shots = images.filter((i) => !!i.url);
  const [page, setPage] = useState(0);
  if (shots.length < 2) return <ProductImage title={title} uri={shots[0]?.url} height={height} />;
  return (
    <View style={{ gap: sp['2'] }}>
      <FlatList
        data={shots}
        horizontal
        pagingEnabled
        showsHorizontalScrollIndicator={false}
        keyExtractor={(i, n) => `${i.url}-${n}`}
        getItemLayout={(_, index) => ({ length: pageWidth, offset: pageWidth * index, index })}
        onMomentumScrollEnd={(e) => setPage(Math.round(e.nativeEvent.contentOffset.x / pageWidth))}
        renderItem={({ item }) => <View style={{ width: pageWidth }}><ProductImage title={item.alt || title} uri={item.url} height={height} /></View>}
        accessibilityLabel={`${title} photos`}
      />
      <View style={styles.dots} accessibilityLabel={`Photo ${page + 1} of ${shots.length}`}>
        {shots.map((_, n) => <View key={n} style={[styles.dot, n === page ? styles.dotOn : null]} />)}
      </View>
    </View>
  );
}

export function QtyStepper({ qty, onChange, trashAtOne = false }: { qty: number; onChange: (n: number) => void; trashAtOne?: boolean }) {
  const DecIcon = trashAtOne && qty === 1 ? Trash : Minus;
  return (
    <View style={styles.stepper}>
      <Pressable accessibilityRole="button" accessibilityLabel={qty === 1 ? 'Remove' : 'Remove one'} onPress={() => onChange(qty - 1)} style={styles.stepBtn}>
        <DecIcon size={16} color={c.text} />
      </Pressable>
      <AppText variant="bodyStrong" style={{ minWidth: 16, textAlign: 'center' }}>{qty}</AppText>
      <Pressable accessibilityRole="button" accessibilityLabel="Add one" onPress={() => onChange(qty + 1)} style={styles.stepBtn}>
        <Plus size={16} color={c.text} />
      </Pressable>
    </View>
  );
}

/** "Today by 8 pm" tile: image, name, price and an add / stepper control. */
export function ProductTile({ p }: { p: ProductSummary }) {
  const router = useRouter();
  const { qty, setQty } = useQuantity(p.atin, p.offerId);
  return (
    <View style={styles.tile}>
      <Pressable accessibilityRole="button" accessibilityLabel={p.title} onPress={() => router.push(`/product/${p.atin}`)}>
        <ProductImage title={p.title} uri={p.imageUrl} height={130} />
      </Pressable>
      <AppText variant="small" numberOfLines={2}>{p.title}</AppText>
      <View style={styles.row}>
        <AppText variant="bodyStrong">{formatMoney(p.priceMinor, p.currency)}</AppText>
        {qty > 0 ? (
          <QtyStepper qty={qty} onChange={setQty} />
        ) : (
          <Pressable accessibilityRole="button" accessibilityLabel={`Add ${p.title} to cart`} onPress={() => setQty(1)} style={styles.addBtn}>
            <Plus size={18} color={c.actionPrimaryText} />
          </Pressable>
        )}
      </View>
    </View>
  );
}

export function ProductRow({ p, onPress }: { p: ProductSummary; onPress: () => void }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={p.title} onPress={onPress} style={styles.resultRow}>
      <ProductImage title={p.title} uri={p.imageUrl} size={88} />
      <View style={{ flex: 1, gap: 2 }}>
        <AppText numberOfLines={2}>{p.title}</AppText>
        <AppText variant="caption" color={c.textMuted}>{p.sellerName} · ★ {p.rating}</AppText>
        <AppText variant="bodyStrong">{formatMoney(p.priceMinor, p.currency)}</AppText>
        <AppText variant="caption" color={c.success}>{p.delivery.label}</AppText>
        {p.availableQty === 0 ? <AppText variant="caption" color={c.textMuted}>Currently unavailable</AppText> : null}
        {p.lowStock ? <AppText variant="caption" color={c.lowStock}>Only {p.availableQty} left — order soon</AppText> : null}
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  img: { borderRadius: t.radius.md, alignItems: 'center', justifyContent: 'center', overflow: 'hidden' },
  dots: { flexDirection: 'row', justifyContent: 'center', gap: 6 },
  dot: { width: 6, height: 6, borderRadius: 3, backgroundColor: c.border },
  dotOn: { backgroundColor: c.text },
  photo: { borderRadius: t.radius.md },
  stepper: { flexDirection: 'row', alignItems: 'center', borderRadius: t.radius.pill, backgroundColor: c.actionPrimary },
  stepBtn: { width: 34, height: 34, alignItems: 'center', justifyContent: 'center' },
  tile: { flex: 1, gap: 6 },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', minHeight: 36 },
  addBtn: { width: 36, height: 36, borderRadius: 18, backgroundColor: c.actionPrimary, alignItems: 'center', justifyContent: 'center' },
  resultRow: { flexDirection: 'row', gap: sp['3'], paddingVertical: sp['3'], borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: c.border }
});
