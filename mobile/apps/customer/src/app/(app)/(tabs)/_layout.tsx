/* Fixed tab bar (S164): Home, Browse, Lola, Cart (badge), Account. */
import { Tabs } from 'expo-router';
import { HouseIcon as House, type Icon, MagnifyingGlassIcon as MagnifyingGlass, ShoppingCartIcon as ShoppingCart, SparkleIcon as Sparkle, UserIcon as User } from 'phosphor-react-native';
import { type ComponentProps } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useCart } from '@/api/queries';
import { c, sp, t } from '@/theme';
import { AppText } from '@/ui/primitives';

type BottomTabBarProps = Parameters<NonNullable<ComponentProps<typeof Tabs>['tabBar']>>[0];

const ICONS: Record<string, { label: string; icon: Icon }> = {
  index: { label: 'Home', icon: House },
  search: { label: 'Browse', icon: MagnifyingGlass },
  lola: { label: 'Lola', icon: Sparkle },
  cart: { label: 'Cart', icon: ShoppingCart },
  account: { label: 'Account', icon: User }
};

function TabBar({ state, navigation }: BottomTabBarProps) {
  const insets = useSafeAreaInsets();
  const cart = useCart();
  const count = cart.data?.itemCount ?? 0;
  return (
    <View style={[styles.bar, { paddingBottom: Math.max(insets.bottom, sp['2']) }]} accessibilityRole="tablist">
      {state.routes.map((route, i) => {
        const meta = ICONS[route.name];
        if (!meta) return null;
        const focused = state.index === i;
        const color = focused ? t.ink['1000'] : t.ink['500'];
        return (
          <Pressable
            key={route.key}
            accessibilityRole="tab"
            accessibilityState={{ selected: focused }}
            accessibilityLabel={route.name === 'cart' && count ? `Cart, ${count} items` : meta.label}
            onPress={() => {
              const e = navigation.emit({ type: 'tabPress', target: route.key, canPreventDefault: true });
              if (!focused && !e.defaultPrevented) navigation.navigate(route.name);
            }}
            style={styles.tab}
          >
            <meta.icon size={24} color={color} weight={focused ? 'fill' : 'regular'} />
            <AppText variant="caption" color={color} style={{ fontWeight: focused ? t.fontWeight.bold : t.fontWeight.medium }}>{meta.label}</AppText>
            {route.name === 'cart' && count > 0 ? (
              <View style={styles.badge}><AppText variant="caption" color={c.cartBadgeText} style={styles.badgeText}>{count}</AppText></View>
            ) : null}
          </Pressable>
        );
      })}
    </View>
  );
}

export default function TabsLayout() {
  return (
    <Tabs screenOptions={{ headerShown: false }} tabBar={(props) => <TabBar {...props} />}>
      <Tabs.Screen name="index" />
      <Tabs.Screen name="search" />
      <Tabs.Screen name="lola" />
      <Tabs.Screen name="cart" />
      <Tabs.Screen name="account" />
    </Tabs>
  );
}

const styles = StyleSheet.create({
  bar: { flexDirection: 'row', borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.border, backgroundColor: c.canvas, paddingTop: sp['2'] },
  tab: { flex: 1, alignItems: 'center', gap: 2, minHeight: t.size.touch },
  badge: { position: 'absolute', top: -4, left: '55%', minWidth: 18, height: 18, borderRadius: 9, paddingHorizontal: 4, backgroundColor: c.cartBadge, alignItems: 'center', justifyContent: 'center' },
  badgeText: { fontSize: 10, fontWeight: t.fontWeight.bold, lineHeight: 12 }
});
