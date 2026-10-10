import { formatMoney, type ProductSummary } from '@afrotransact/api-client';
import { useRouter } from 'expo-router';
import { MinusIcon as Minus, PlusIcon as Plus, TrashIcon as Trash } from 'phosphor-react-native';
import { Pressable, StyleSheet, View } from 'react-native';

import { c, sp, t } from '@/theme';
import { AppText } from '@/ui/primitives';
import { productVisual } from '@/ui/productVisual';

import { useQuantity } from './cartHooks';

export function ProductImage({ title, size = 64, height }: { title: string; size?: number; height?: number }) {
  const v = productVisual(title);
  return (
    <View style={[styles.img, { backgroundColor: v.tint, width: height ? undefined : size, height: height ?? size }]}>
      <v.icon size={(height ?? size) * 0.42} color={t.ink['800']} />
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
        <ProductImage title={p.title} height={130} />
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
      <ProductImage title={p.title} size={88} />
      <View style={{ flex: 1, gap: 2 }}>
        <AppText numberOfLines={2}>{p.title}</AppText>
        <AppText variant="caption" color={c.textMuted}>{p.sellerName} · ★ {p.rating}</AppText>
        <AppText variant="bodyStrong">{formatMoney(p.priceMinor, p.currency)}</AppText>
        <AppText variant="caption" color={c.success}>{p.delivery.label}</AppText>
        {p.lowStock ? <AppText variant="caption" color={c.lowStock}>Only {p.availableQty} left — order soon</AppText> : null}
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  img: { borderRadius: t.radius.md, alignItems: 'center', justifyContent: 'center' },
  stepper: { flexDirection: 'row', alignItems: 'center', borderRadius: t.radius.pill, backgroundColor: c.actionPrimary },
  stepBtn: { width: 34, height: 34, alignItems: 'center', justifyContent: 'center' },
  tile: { flex: 1, gap: 6 },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', minHeight: 36 },
  addBtn: { width: 36, height: 36, borderRadius: 18, backgroundColor: c.actionPrimary, alignItems: 'center', justifyContent: 'center' },
  resultRow: { flexDirection: 'row', gap: sp['3'], paddingVertical: sp['3'], borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: c.border }
});
