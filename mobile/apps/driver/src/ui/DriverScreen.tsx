/* Driver app chrome (design: phone header): back, logo, title + "AfroTransact Delivery · <route>", SOS and the sync
   status pill (Synced / Syncing N / Offline · N). Fixed header; only the content scrolls. */
import { useRouter } from 'expo-router';
import { CaretLeftIcon as CaretLeft, CarProfileIcon as CarProfile, CircleNotchIcon as CircleNotch, CloudCheckIcon as CloudCheck, CloudSlashIcon as CloudSlash, FirstAidIcon as FirstAid, ShieldWarningIcon as ShieldWarning, WrenchIcon as Wrench } from 'phosphor-react-native';
import { type ReactNode, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { syncStatus } from '@/domain/outbox';
import { type SosKind } from '@/domain/types';
import { type MessageKey, useT } from '@/i18n';
import { useDriver } from '@/session/DriverProvider';
import { c, sp, t } from '@/theme';

import { LogoMark } from './Logo';
import { AppText, Button } from './primitives';
import { Sheet } from './Sheet';
import { useToast } from './Toast';

const SOS: Array<{ kind: SosKind; label: MessageKey; icon: typeof Wrench }> = [
  { kind: 'BREAKDOWN', label: 'dr.sos.breakdown', icon: Wrench },
  { kind: 'ACCIDENT', label: 'dr.sos.accident', icon: CarProfile },
  { kind: 'UNSAFE', label: 'dr.sos.unsafe', icon: ShieldWarning },
  { kind: 'MEDICAL', label: 'dr.sos.medical', icon: FirstAid }
];

export function SyncPill() {
  const tr = useT();
  const router = useRouter();
  const { sync } = useDriver();
  const s = syncStatus(sync.online, sync.outbox.events.length, sync.syncing);
  const Icon = s.kind === 'offline' ? CloudSlash : s.kind === 'syncing' ? CircleNotch : CloudCheck;
  const bg = s.kind === 'offline' ? t.ink['300'] : s.kind === 'syncing' ? t.gold['300'] : t.gold['500'];
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={tr('dr.sync.status', { label: s.label })} onPress={() => router.navigate('/profile')} style={[styles.pill, { backgroundColor: bg }]}>
      <Icon size={14} weight={s.kind === 'synced' ? 'fill' : 'bold'} color={c.text} />
      <AppText variant="caption" style={{ fontWeight: t.fontWeight.bold }}>{s.label}</AppText>
    </Pressable>
  );
}

export function SosButton() {
  const tr = useT();
  const { engine, sync } = useDriver();
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const raise = async (kind: SosKind) => {
    setOpen(false);
    try {
      await engine.record({ type: 'sos.raised', sos: kind });
      toast(sync.online ? tr('dr.sos.notified') : tr('dr.sos.queued'));
    } catch {
      toast(tr('dr.sos.failed'));
    }
  };
  return (
    <>
      <Pressable accessibilityRole="button" accessibilityLabel={tr('dr.sos.button')} onPress={() => setOpen(true)} style={styles.sos}>
        <AppText variant="caption" color={c.textInverse} style={{ fontWeight: t.fontWeight.black }}>{tr('dr.driverScreen.sos')}</AppText>
      </Pressable>
      <Sheet visible={open} onClose={() => setOpen(false)}>
        <AppText variant="heading">{tr('dr.driverScreen.reportAnIncident')}</AppText>
        <AppText variant="small" color={c.danger}>{tr('dr.driverScreen.inAnEmergencyCall911')}</AppText>
        {SOS.map((o) => <Button key={o.kind} kind="outline" icon={o.icon} label={tr(o.label)} onPress={() => void raise(o.kind)} />)}
        <Button kind="ghost" label={tr('dr.driverScreen.cancel')} onPress={() => setOpen(false)} />
      </Sheet>
    </>
  );
}

export interface DriverScreenProps {
  title: string;
  back?: boolean | (() => void);
  footer?: ReactNode;
  body?: 'scroll' | 'fixed';
  inTabs?: boolean;
  children: ReactNode;
}

export function DriverScreen({ title, back, footer, body = 'scroll', inTabs = false, children }: DriverScreenProps) {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const { sync, phase } = useDriver();
  const onBack = typeof back === 'function' ? back : () => (router.canGoBack() ? router.back() : router.navigate('/'));
  const bottom = inTabs ? 0 : insets.bottom;
  return (
    <View style={styles.root}>
      <View style={{ height: insets.top, backgroundColor: c.canvas }} />
      <View style={styles.header}>
        {back ? (
          <Pressable accessibilityRole="button" accessibilityLabel="Back" onPress={onBack} style={styles.back}>
            <CaretLeft size={20} color={c.text} />
          </Pressable>
        ) : null}
        <LogoMark size={28} />
        <View style={{ flex: 1 }}>
          <AppText variant="bodyStrong" numberOfLines={1} accessibilityRole="header">{title}</AppText>
          <AppText variant="caption" color={c.textMuted} numberOfLines={1}>AfroTransact Delivery{sync.route && phase === 'ready' ? ` · ${sync.route.id}` : ''}</AppText>
        </View>
        {phase === 'ready' ? <><SosButton /><SyncPill /></> : null}
      </View>
      {body === 'scroll' ? (
        <ScrollView style={{ flex: 1 }} contentContainerStyle={[styles.content, { paddingBottom: footer ? sp['4'] : bottom + sp['6'] }]} showsVerticalScrollIndicator={false} keyboardShouldPersistTaps="handled" keyboardDismissMode="on-drag">
          {children}
        </ScrollView>
      ) : (
        <View style={{ flex: 1 }}>{children}</View>
      )}
      {footer ? <View style={[styles.footer, { paddingBottom: bottom + sp['2'] }]}>{footer}</View> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: c.canvas, overflow: 'hidden' },
  header: { flexDirection: 'row', alignItems: 'center', gap: sp['2'], paddingHorizontal: sp['4'], paddingVertical: sp['2'], borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: c.border, backgroundColor: c.canvas },
  back: { width: 44, height: 44, borderRadius: 22, backgroundColor: c.surfaceMuted, alignItems: 'center', justifyContent: 'center' },
  sos: { height: 32, paddingHorizontal: sp['3'], borderRadius: t.radius.pill, backgroundColor: c.danger, alignItems: 'center', justifyContent: 'center' },
  pill: { flexDirection: 'row', alignItems: 'center', gap: 4, height: 32, paddingHorizontal: sp['2'], borderRadius: t.radius.pill },
  content: { padding: sp['4'], gap: sp['4'] },
  footer: { paddingHorizontal: sp['4'], paddingTop: sp['3'], borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.border, backgroundColor: c.canvas, gap: sp['2'] }
});
