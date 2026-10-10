/* Bottom tabs (design): Today, Route, Scan, Profile — fixed; the Scan tab opens the scan that applies right now. */
import { Tabs, useRouter } from 'expo-router';
import { BarcodeIcon as Barcode, HouseIcon as House, type Icon, PathIcon as Path, UserCircleIcon as UserCircle } from 'phosphor-react-native';
import { type ComponentProps } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { loadOutDone } from '@/domain/shift';
import { useDriver } from '@/session/DriverProvider';
import { c, sp, t } from '@/theme';
import { AppText } from '@/ui/primitives';
import { useToast } from '@/ui/Toast';

type TabBarProps = Parameters<NonNullable<ComponentProps<typeof Tabs>['tabBar']>>[0];

const TABS: Record<string, { label: string; icon: Icon }> = {
  index: { label: 'Today', icon: House },
  route: { label: 'Route', icon: Path },
  scan: { label: 'Scan', icon: Barcode },
  profile: { label: 'Profile', icon: UserCircle }
};

function TabBar({ state, navigation }: TabBarProps) {
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const toast = useToast();
  const { sync } = useDriver();
  const route = sync.route;
  const open = (name: string) => {
    if (name === 'route' && route?.status !== 'IN_PROGRESS') return toast('Pass inspection and load-out first');
    if (name === 'scan') {
      if (!route || route.inspection !== 'PASSED') return toast('Nothing to scan right now');
      if (!loadOutDone(route)) return router.push('/scan/loadout');
      const here = route.stops.find((s) => s.status === 'ARRIVED');
      if (here) return router.push(`/scan/${here.kind === 'PICKUP' ? 'pickup' : 'drop'}-${here.id}`);
      return toast('Nothing to scan right now');
    }
    navigation.navigate(name);
  };
  return (
    <View style={[styles.bar, { paddingBottom: Math.max(insets.bottom, sp['2']) }]} accessibilityRole="tablist">
      {state.routes.map((r, i) => {
        const meta = TABS[r.name];
        if (!meta) return null;
        const focused = state.index === i;
        return (
          <Pressable key={r.key} accessibilityRole="tab" accessibilityState={{ selected: focused }} accessibilityLabel={meta.label} onPress={() => open(r.name)} style={styles.tab}>
            <meta.icon size={24} color={focused ? c.text : c.textMuted} weight={focused ? 'fill' : 'regular'} />
            <AppText variant="caption" color={focused ? c.text : c.textMuted} style={{ fontWeight: focused ? t.fontWeight.bold : t.fontWeight.medium }}>{meta.label}</AppText>
          </Pressable>
        );
      })}
    </View>
  );
}

export default function TabsLayout() {
  return (
    <Tabs screenOptions={{ headerShown: false }} tabBar={(p) => <TabBar {...p} />}>
      <Tabs.Screen name="index" />
      <Tabs.Screen name="route" />
      <Tabs.Screen name="scan" />
      <Tabs.Screen name="profile" />
    </Tabs>
  );
}

const styles = StyleSheet.create({
  bar: { flexDirection: 'row', borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.border, backgroundColor: c.canvas, paddingTop: sp['2'] },
  tab: { flex: 1, alignItems: 'center', gap: 2, minHeight: t.size.touch }
});
