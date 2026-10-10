/* Home (design: HOME): pinned logo header; inner content scrolls. */
import { useRouter } from 'expo-router';
import { BarcodeIcon as Barcode, BasketIcon as Basket, BellIcon as Bell, CaretDownIcon as CaretDown, CaretRightIcon as CaretRight, DropHalfIcon as DropHalf, FireIcon as Fire, type Icon, MagnifyingGlassIcon as MagnifyingGlass, MapPinIcon as MapPin, ScissorsIcon as Scissors, SparkleIcon as Sparkle, TruckIcon as Truck, TShirtIcon as TShirt } from 'phosphor-react-native';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { useAddresses, useOrders, useSearch } from '@/api/queries';
import { useAuth } from '@/auth/AuthProvider';
import { headline, stepIndex } from '@/domain/tracking';
import { ProductImage, ProductTile } from '@/features/shop/components';
import { type MessageKey, useFormatters, useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { LogoMark } from '@/ui/Logo';
import { AppText, IconButton } from '@/ui/primitives';
import { Screen } from '@/ui/Screen';

const CATS: Array<{ label: MessageKey; icon: Icon; bg: string; q: string | null }> = [
  { label: 'cu.index.groceries', icon: Basket, bg: t.gold['100'], q: '' },
  { label: 'cu.index.beauty', icon: DropHalf, bg: t.ink['50'], q: 'soap' },
  { label: 'cu.index.fashion', icon: TShirt, bg: t.gold['50'], q: 'kente' },
  { label: 'cu.index.spices', icon: Fire, bg: t.green['50'], q: 'spice' },
  { label: 'cu.index.services', icon: Scissors, bg: t.ink['100'], q: null }
];

export default function Home() {
  const fmt = useFormatters();
  const tr = useT();
  const router = useRouter();
  const { user } = useAuth();
  const products = useSearch('');
  const orders = useOrders();
  const addresses = useAddresses();
  const def = addresses.data?.find((a) => a.isDefault) ?? addresses.data?.[0];
  const active = orders.data?.find((o) => o.shipments.some((s) => stepIndex(s.status) < 4 && !['returned', 'cancelled'].includes(s.status)) && o.status !== 'refunded');
  const hits = products.data?.hits ?? [];
  const again = hits.filter((p) => ['ATBASMAT05', 'ATSHITO001', 'ATGARI0004', 'ATSOAP0007'].includes(p.atin)).concat(hits).slice(0, 4);
  const sameDay = hits.filter((p) => p.delivery.days === 0).slice(0, 4);

  const header = (
    <View style={styles.header}>
      <LogoMark size={32} />
      <AppText variant="heading" style={{ flex: 1, fontWeight: t.fontWeight.black }}>afrotransact</AppText>
      <IconButton icon={Bell} label={tr('cu.index.inbox')} onPress={() => router.push('/inbox')} />
      <Pressable accessibilityRole="button" accessibilityLabel="Account" onPress={() => router.push('/account')} style={styles.avatar}>
        <AppText variant="small" color={c.textInverse} style={{ fontWeight: t.fontWeight.bold }}>{user?.initials ?? ''}</AppText>
      </Pressable>
    </View>
  );

  return (
    <Screen inTabs header={header}>
      <Pressable accessibilityRole="button" onPress={() => router.push('/addresses')} style={styles.deliver}>
        <MapPin size={18} color={c.text} />
        <AppText variant="small" color={c.textMuted}>Deliver to {user?.first} ·</AppText>
        <AppText variant="small" style={{ fontWeight: t.fontWeight.bold }}>{def ? `${def.city} ${def.zip}` : tr('cu.index.addAddress')}</AppText>
        <CaretDown size={14} color={c.text} />
      </Pressable>
      <Pressable accessibilityRole="search" onPress={() => router.push('/search')} style={styles.search}>
        <MagnifyingGlass size={20} color={c.textMuted} />
        <AppText color={c.textSubtle} style={{ flex: 1 }}>{tr('cu.index.searchShitoGariBraiders')}</AppText>
        <Barcode size={20} color={c.textMuted} />
      </Pressable>
      {active ? (
        <Pressable accessibilityRole="button" onPress={() => router.push(`/orders/${active.orderNumber}`)} style={styles.active}>
          <Truck size={26} color={t.gold['500']} />
          <View style={{ flex: 1, gap: 2 }}>
            <AppText variant="bodyStrong" color={c.textInverse}>{headline(active.shipments[0]?.status ?? active.status)}</AppText>
            <AppText variant="caption" color={t.ink['200']}>{tr('cu.index.orderLine', { number: active.orderNumber, amount: fmt.money(active.totalMinor, active.currency) })}</AppText>
          </View>
          <CaretRight size={18} color={c.textInverse} />
        </Pressable>
      ) : null}
      <View style={styles.cats}>
        {CATS.map((x) => (
          <Pressable key={x.label} accessibilityRole="button" accessibilityLabel={tr(x.label)} onPress={() => (x.q === null ? router.push('/services') : router.push({ pathname: '/search', params: { q: x.q } }))} style={styles.cat}>
            <View style={[styles.catIcon, { backgroundColor: x.bg }]}><x.icon size={24} color={c.text} /></View>
            <AppText variant="caption">{tr(x.label)}</AppText>
          </Pressable>
        ))}
      </View>
      <View style={styles.hero}>
        <AppText variant="label" color={t.gold['500']}>{tr('cu.index.justLanded')}</AppText>
        <AppText variant="title" color={c.textInverse}>{tr('cu.index.freshFromAccra')}</AppText>
        <AppText variant="small" color={t.ink['200']}>{tr('cu.index.shitoPalmOilAndJollof')}</AppText>
      </View>
      <Pressable accessibilityRole="button" onPress={() => router.push('/lola')} style={styles.lola}>
        <View style={styles.lolaIcon}><Sparkle size={20} weight="fill" color={c.badgeAiText} /></View>
        <View style={{ flex: 1 }}>
          <AppText variant="bodyStrong">{tr('cu.index.askLola')}</AppText>
          <AppText variant="small" color={c.textMuted}>{tr('cu.index.whatDoINeedFor')}</AppText>
        </View>
        <CaretRight size={18} color={c.text} />
      </Pressable>
      <View style={{ gap: sp['2'] }}>
        <View style={styles.sectionHead}><AppText variant="heading">{tr('cu.index.buyAgain')}</AppText><AppText variant="caption" color={c.textMuted}>{tr('cu.index.basedOnYourOrders')}</AppText></View>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: sp['3'] }}>
          {again.map((p) => (
            <Pressable key={p.atin} accessibilityRole="button" accessibilityLabel={p.title} onPress={() => router.push(`/product/${p.atin}`)} style={{ width: 110, gap: 4 }}>
              <ProductImage title={p.title} size={110} />
              <AppText variant="caption" numberOfLines={2}>{p.title}</AppText>
              <AppText variant="small" style={{ fontWeight: t.fontWeight.bold }}>{fmt.money(p.priceMinor, p.currency)}</AppText>
            </Pressable>
          ))}
        </ScrollView>
      </View>
      <View style={{ gap: sp['2'] }}>
        <View style={styles.sectionHead}><AppText variant="heading">{tr('cu.index.todayBy8Pm')}</AppText><AppText variant="caption" color={c.textMuted}>Same-day in {def?.city ?? 'Houston'}</AppText></View>
        <View style={styles.grid}>
          {sameDay.map((p) => <View key={p.atin} style={styles.cell}><ProductTile p={p} /></View>)}
        </View>
      </View>
      <Pressable accessibilityRole="button" onPress={() => router.push('/services')} style={styles.lola}>
        <Scissors size={24} color={c.text} />
        <View style={{ flex: 1 }}>
          <AppText variant="bodyStrong">{tr('cu.index.bookABraiderTailorOr')}</AppText>
          <AppText variant="small" color={c.textMuted}>{tr('cu.index.verifiedProsNearYouPay')}</AppText>
        </View>
        <CaretRight size={18} color={c.text} />
      </Pressable>
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', gap: sp['2'], paddingHorizontal: sp['4'], paddingVertical: sp['2'], backgroundColor: c.canvas },
  avatar: { width: 44, height: 44, borderRadius: 22, backgroundColor: t.ink['1000'], alignItems: 'center', justifyContent: 'center' },
  deliver: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  search: { flexDirection: 'row', alignItems: 'center', gap: sp['2'], minHeight: t.size.controlLg, paddingHorizontal: sp['3'], borderRadius: t.radius.pill, borderWidth: 1, borderColor: c.borderStrong },
  active: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], padding: 14, borderRadius: t.radius.md, backgroundColor: t.ink['1000'] },
  cats: { flexDirection: 'row', justifyContent: 'space-between' },
  cat: { alignItems: 'center', gap: 4, width: 64 },
  catIcon: { width: 56, height: 56, borderRadius: 28, alignItems: 'center', justifyContent: 'center' },
  hero: { borderRadius: t.radius.lg, padding: sp['5'], backgroundColor: t.ink['900'], gap: sp['1'] },
  lola: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], padding: 14, borderRadius: t.radius.md, borderWidth: 1, borderColor: c.border },
  lolaIcon: { width: 36, height: 36, borderRadius: 18, backgroundColor: c.badgeAiBg, alignItems: 'center', justifyContent: 'center' },
  sectionHead: { flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between' },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: sp['3'] },
  cell: { width: '47%', flexGrow: 1 }
});
