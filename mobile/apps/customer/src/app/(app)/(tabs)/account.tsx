/* Account (design: ACCOUNT): orders preview, settings groups, Lock app, Sign out (removes Face ID and passcode). */
import { localeInfo } from '@afrotransact/i18n';
import { useRouter } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';
import { BellIcon as Bell, CalendarIcon as Calendar, CaretRightIcon as CaretRight, CheckCircleIcon as CheckCircle, DesktopIcon as Desktop, GlobeIcon as Globe, type Icon, LockSimpleIcon as LockSimple, MapPinIcon as MapPin, QuestionIcon as Question, ReceiptIcon as Receipt, ShieldCheckIcon as ShieldCheck, SignOutIcon as SignOut, StorefrontIcon as Storefront, TruckIcon as Truck, WalletIcon as Wallet } from 'phosphor-react-native';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { useAddresses, useMe, useMyBookings, useOrders, usePaymentMethods } from '@/api/queries';
import { useAuth } from '@/auth/AuthProvider';
import { EnvRibbon } from '@/brand/EnvRibbon';
import { env } from '@/config/env';
import { backOfficeLabel, backOfficeUrl } from '@/domain/backOffices';
import { isActive } from '@/domain/bookings';
import { stepIndex } from '@/domain/tracking';
import { useLocale, useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { LanguageSheet } from '@/ui/LanguageSheet';
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
  const tr = useT();
  const router = useRouter();
  const { user, faceId, biometryLabel, lock, signOut } = useAuth();
  const orders = useOrders();
  const addresses = useAddresses();
  const pms = usePaymentMethods();
  const bookings = useMyBookings();
  // Seller Center, Provider Hub, staff console: web apps, opened through the storefront's /back-office link.
  const backOffices = useMe().data?.user?.backOffices ?? [];
  const [confirm, setConfirm] = useState(false);
  const [language, setLanguage] = useState(false);
  const locale = useLocale();
  const upcoming = (bookings.data ?? []).filter((b) => isActive(b.status)).length;
  const header = <View style={styles.header}><AppText variant="display" accessibilityRole="header">{tr('cu.account.account')}</AppText></View>;

  return (
    <Screen inTabs header={header}>
      <View style={styles.profile}>
        <View style={styles.avatar}><AppText variant="title" color={c.textInverse}>{user?.initials}</AppText></View>
        <View style={{ flex: 1 }}>
          <AppText variant="heading">{tr('cu.account.hi', { name: user?.first ?? '' })}</AppText>
          <AppText variant="small" color={c.textMuted}>{user?.email}</AppText>
          <AppText variant="caption" color={c.textMuted}>{tr('cu.account.signedInWith', { unlock: faceId ? tr('cu.account.biometricUnlock', { method: biometryLabel }) : tr('cu.account.passcodeUnlock') })}</AppText>
        </View>
      </View>
      <View style={{ gap: sp['2'] }}>
        <View style={styles.between}>
          <AppText variant="heading">{tr('cu.account.yourOrders')}</AppText>
          <AppText variant="bodyStrong" accessibilityRole="button" onPress={() => router.push('/orders')} style={{ textDecorationLine: 'underline' }}>{tr('cu.account.seeAll')}</AppText>
        </View>
        {(orders.data ?? []).slice(0, 2).map((o) => {
          const done = o.shipments.every((s) => stepIndex(s.status) >= 4);
          return (
            <Pressable key={o.orderNumber} accessibilityRole="button" onPress={() => router.push(`/orders/${o.orderNumber}`)} style={styles.order}>
              {done ? <CheckCircle size={22} weight="fill" color={c.success} /> : <Truck size={22} color={c.text} />}
              <View style={{ flex: 1 }}>
                <AppText variant="bodyStrong">{done ? tr('cu.account.delivered') : tr('cu.account.onTheWay')}</AppText>
                <AppText variant="caption" color={c.textMuted} numberOfLines={1}>{o.orderNumber} · {o.shipments.map((s) => s.items).join(', ')}</AppText>
              </View>
              <CaretRight size={16} color={c.textMuted} />
            </Pressable>
          );
        })}
      </View>
      <View style={styles.group}>
        <SectionLabel>{tr('cu.account.shopping')}</SectionLabel>
        <Row icon={Receipt} label={tr('cu.account.yourOrders')} value={String(orders.data?.length ?? '')} onPress={() => router.push('/orders')} />
        <Row icon={Calendar} label={tr('cu.account.bookings')} value={upcoming ? tr('cu.account.upcoming', { n: upcoming }) : tr('cu.account.none')} onPress={() => router.push('/bookings')} />
      </View>
      <View style={styles.group}>
        <SectionLabel>{tr('cu.account.profile')}</SectionLabel>
        <Row icon={MapPin} label={tr('cu.account.addresses')} value={String(addresses.data?.length ?? '')} onPress={() => router.push('/addresses')} />
        <Row icon={Wallet} label={tr('cu.account.paymentMethods')} value={String(pms.data?.length ?? '')} onPress={() => router.push('/payments')} />
      </View>
      {backOffices.length > 0 ? (
        <View style={styles.group}>
          <SectionLabel>{tr('cu.account.backOffices')}</SectionLabel>
          {backOffices.map((b) => (
            <Row key={b.app} icon={b.app === 'staff-console' ? Desktop : b.app === 'provider-hub' ? Calendar : Storefront} label={tr(backOfficeLabel(b))}
              onPress={() => void WebBrowser.openBrowserAsync(backOfficeUrl(env.webUrl, b))} />
          ))}
        </View>
      ) : null}
      <View style={styles.group}>
        <SectionLabel>{tr('cu.account.settings')}</SectionLabel>
        <Row icon={Bell} label={tr('cu.account.notifications')} onPress={() => router.push('/settings/notifications')} />
        <Row icon={ShieldCheck} label={tr('cu.account.signInSecurity')} value={faceId ? biometryLabel : tr('cu.account.passcode')} onPress={() => router.push('/settings/security')} />
        <Row icon={Globe} label={tr('cu.account.language')} value={localeInfo(locale).name} onPress={() => setLanguage(true)} />
        <Row icon={Question} label={tr('cu.account.helpSupport')} onPress={() => void WebBrowser.openBrowserAsync(`${env.webUrl}/help`)} />
      </View>
      <Button kind="outline" icon={LockSimple} label={tr('cu.account.lockApp')} onPress={lock} />
      <Button kind="outline" icon={SignOut} label={tr('cu.account.signOut')} onPress={() => setConfirm(true)} />
      <EnvRibbon />
      <AppText variant="caption" color={c.textMuted} style={{ textAlign: 'center' }}>{tr('cu.account.version', { version: '2.0.0', env: env.appEnv })}</AppText>
      <LanguageSheet visible={language} onClose={() => setLanguage(false)} title={tr('cu.account.chooseLanguage')} rtlNote={tr('cu.account.rtlRestart')} />
      <Sheet visible={confirm} onClose={() => setConfirm(false)}>
        <AppText variant="heading">{tr('cu.account.signOutOfAfrotransact')}</AppText>
        <AppText color={c.textMuted}>{tr('cu.account.signOutBody', { method: biometryLabel })}</AppText>
        <Button kind="danger" label={tr('cu.account.signOut')} onPress={() => { setConfirm(false); void signOut({ flash: '' }); }} />
        <Button kind="ghost" label={tr('cu.account.cancel')} onPress={() => setConfirm(false)} />
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
