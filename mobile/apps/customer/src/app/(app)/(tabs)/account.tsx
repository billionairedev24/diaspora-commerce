/* Account (design: ACCOUNT): orders preview, settings groups, Lock app, Sign out (removes Face ID and passcode). */
import { useRouter } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';
import { BellIcon as Bell, CalendarIcon as Calendar, CaretRightIcon as CaretRight, CheckCircleIcon as CheckCircle, type Icon, LockSimpleIcon as LockSimple, MapPinIcon as MapPin, QuestionIcon as Question, ReceiptIcon as Receipt, ShieldCheckIcon as ShieldCheck, SignOutIcon as SignOut, TruckIcon as Truck, WalletIcon as Wallet } from 'phosphor-react-native';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { useAddresses, useMyBookings, useOrders, usePaymentMethods } from '@/api/queries';
import { useAuth } from '@/auth/AuthProvider';
import { env } from '@/config/env';
import { isActive } from '@/domain/bookings';
import { stepIndex } from '@/domain/tracking';
import { c, sp, t } from '@/theme';
import { AppText, Button, SectionLabel } from '@/ui/primitives';
import { Screen } from '@/ui/Screen';
import { Sheet } from '@/ui/Sheet';

function Row({ icon: I, label, value, onPress }: { icon: Icon; label: string; value?: string; onPress: () => void }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={label} onPress={onPress} style={styles.row}>
      <I size={22} color={c.text} />
      <AppText style={{ flex: 1 }}>{label}</AppText>
      {value ? <AppText variant="small" color={c.textMuted}>{value}</AppText> : null}
      <CaretRight size={16} color={c.textMuted} />
    </Pressable>
  );
}

export default function Account() {
  const router = useRouter();
  const { user, faceId, biometryLabel, lock, signOut } = useAuth();
  const orders = useOrders();
  const addresses = useAddresses();
  const pms = usePaymentMethods();
  const bookings = useMyBookings();
  const [confirm, setConfirm] = useState(false);
  const upcoming = (bookings.data ?? []).filter((b) => isActive(b.status)).length;
  const header = <View style={styles.header}><AppText variant="display" accessibilityRole="header">Account</AppText></View>;

  return (
    <Screen inTabs header={header}>
      <View style={styles.profile}>
        <View style={styles.avatar}><AppText variant="title" color={c.textInverse}>{user?.initials}</AppText></View>
        <View style={{ flex: 1 }}>
          <AppText variant="heading">Hi, {user?.first}</AppText>
          <AppText variant="small" color={c.textMuted}>{user?.email}</AppText>
          <AppText variant="caption" color={c.textMuted}>Signed in with AfroTransact · {faceId ? `${biometryLabel} unlock` : 'passcode unlock'}</AppText>
        </View>
      </View>
      <View style={{ gap: sp['2'] }}>
        <View style={styles.between}>
          <AppText variant="heading">Your orders</AppText>
          <AppText variant="bodyStrong" accessibilityRole="button" onPress={() => router.push('/orders')} style={{ textDecorationLine: 'underline' }}>See all</AppText>
        </View>
        {(orders.data ?? []).slice(0, 2).map((o) => {
          const done = o.shipments.every((s) => stepIndex(s.status) >= 4);
          return (
            <Pressable key={o.orderNumber} accessibilityRole="button" onPress={() => router.push(`/orders/${o.orderNumber}`)} style={styles.order}>
              {done ? <CheckCircle size={22} weight="fill" color={c.success} /> : <Truck size={22} color={c.text} />}
              <View style={{ flex: 1 }}>
                <AppText variant="bodyStrong">{done ? 'Delivered' : 'On the way'}</AppText>
                <AppText variant="caption" color={c.textMuted} numberOfLines={1}>{o.orderNumber} · {o.shipments.map((s) => s.items).join(', ')}</AppText>
              </View>
              <CaretRight size={16} color={c.textMuted} />
            </Pressable>
          );
        })}
      </View>
      <View style={styles.group}>
        <SectionLabel>SHOPPING</SectionLabel>
        <Row icon={Receipt} label="Your orders" value={String(orders.data?.length ?? '')} onPress={() => router.push('/orders')} />
        <Row icon={Calendar} label="Bookings" value={upcoming ? `${upcoming} upcoming` : 'None'} onPress={() => router.push('/bookings')} />
      </View>
      <View style={styles.group}>
        <SectionLabel>PROFILE</SectionLabel>
        <Row icon={MapPin} label="Addresses" value={String(addresses.data?.length ?? '')} onPress={() => router.push('/addresses')} />
        <Row icon={Wallet} label="Payment methods" value={String(pms.data?.length ?? '')} onPress={() => router.push('/payments')} />
      </View>
      <View style={styles.group}>
        <SectionLabel>SETTINGS</SectionLabel>
        <Row icon={Bell} label="Notifications" onPress={() => router.push('/settings/notifications')} />
        <Row icon={ShieldCheck} label="Sign-in & security" value={faceId ? biometryLabel : 'Passcode'} onPress={() => router.push('/settings/security')} />
        <Row icon={Question} label="Help & support" onPress={() => void WebBrowser.openBrowserAsync(`${env.webUrl}/help`)} />
      </View>
      <Button kind="outline" icon={LockSimple} label="Lock app" onPress={lock} />
      <Button kind="outline" icon={SignOut} label="Sign out" onPress={() => setConfirm(true)} />
      <AppText variant="caption" color={c.textMuted} style={{ textAlign: 'center' }}>AfroTransact 2.0.0 · {env.appEnv}</AppText>
      <Sheet visible={confirm} onClose={() => setConfirm(false)}>
        <AppText variant="heading">Sign out of AfroTransact?</AppText>
        <AppText color={c.textMuted}>{biometryLabel} and your passcode are removed from this phone. Your cart and orders stay on your account.</AppText>
        <Button kind="danger" label="Sign out" onPress={() => { setConfirm(false); void signOut({ flash: '' }); }} />
        <Button kind="ghost" label="Cancel" onPress={() => setConfirm(false)} />
      </Sheet>
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: { paddingHorizontal: sp['4'], paddingVertical: sp['2'], backgroundColor: c.canvas },
  profile: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], padding: sp['4'], borderRadius: t.radius.lg, backgroundColor: c.surfaceMuted },
  avatar: { width: 56, height: 56, borderRadius: 28, backgroundColor: t.ink['1000'], alignItems: 'center', justifyContent: 'center' },
  between: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  order: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], paddingVertical: sp['2'] },
  group: { gap: sp['1'] },
  row: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], minHeight: t.size.touch + 4, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: c.border }
});
