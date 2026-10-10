/* Inbox (design: INBOX): notifications delivered to this device; tapping opens the linked screen. */
import * as Notifications from 'expo-notifications';
import { useRouter } from 'expo-router';
import { BellIcon as Bell, GearIcon as Gear } from 'phosphor-react-native';
import { useEffect, useState } from 'react';
import { Platform, Pressable, StyleSheet, View } from 'react-native';

import { type PushData, routeForNotification } from '@/domain/notifications';
import { c, sp, t } from '@/theme';
import { AppText, Button, Empty } from '@/ui/primitives';
import { Screen, SubHeader } from '@/ui/Screen';

interface Item { id: string; title: string; body: string; at: number; data: PushData }

export default function Inbox() {
  const router = useRouter();
  const [items, setItems] = useState<Item[]>([]);
  useEffect(() => {
    if (Platform.OS === 'web') return;
    const load = () =>
      void Notifications.getPresentedNotificationsAsync().then((ns) =>
        setItems(ns.map((n) => ({ id: n.request.identifier, title: n.request.content.title ?? '', body: n.request.content.body ?? '', at: n.date, data: (n.request.content.data ?? {}) as PushData })).sort((a, b) => b.at - a.at))
      );
    load();
    const sub = Notifications.addNotificationReceivedListener(load);
    return () => sub.remove();
  }, []);
  return (
    <Screen header={<SubHeader title="Inbox" action={items.length ? { label: 'Mark all read', onPress: () => { void Notifications.dismissAllNotificationsAsync(); setItems([]); } } : undefined} />}
      footer={<Button kind="outline" icon={Gear} label="Notification settings" onPress={() => router.push('/settings/notifications')} />}>
      {items.length === 0 ? <Empty icon={Bell} title="You’re all caught up" body="Order, delivery and booking updates show up here." /> : null}
      {items.map((n) => (
        <Pressable key={n.id} accessibilityRole="button" onPress={() => router.push(routeForNotification(n.data) as never)} style={styles.row}>
          <View style={styles.icon}><Bell size={20} color={c.text} /></View>
          <View style={{ flex: 1 }}>
            <AppText variant="bodyStrong">{n.title}</AppText>
            <AppText variant="small" color={c.textMuted}>{n.body}</AppText>
          </View>
          <AppText variant="caption" color={c.textMuted}>{new Date(n.at).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })}</AppText>
        </Pressable>
      ))}
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', gap: sp['3'], paddingVertical: sp['3'], borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: c.border },
  icon: { width: 40, height: 40, borderRadius: 20, backgroundColor: t.ink['100'], alignItems: 'center', justifyContent: 'center' }
});
