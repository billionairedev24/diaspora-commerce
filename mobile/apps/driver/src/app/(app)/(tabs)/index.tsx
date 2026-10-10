/* Today (design: HOME): inspection → load-out → route → end shift, each step unlocking the next. */
import { useRouter } from 'expo-router';
import { CaretRightIcon as CaretRight, CheckIcon as Check, ClipboardTextIcon as ClipboardText, FlagCheckeredIcon as FlagCheckered, PackageIcon as Package, PathIcon as Path, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { Pressable, StyleSheet, View } from 'react-native';

import { type Phase, phases, primaryAction } from '@/domain/shift';
import { useDriver } from '@/session/DriverProvider';
import { c, sp, t } from '@/theme';
import { DriverScreen } from '@/ui/DriverScreen';
import { AppText, Banner, Button, Empty, Loading } from '@/ui/primitives';
import { useToast } from '@/ui/Toast';

const ICON: Record<Phase, typeof Check> = { inspect: ClipboardText, loadout: Package, route: Path, end: FlagCheckered };

export default function Today() {
  const router = useRouter();
  const toast = useToast();
  const { user, sync, engine } = useDriver();
  const r = sync.route;
  if (!r) {
    return (
      <DriverScreen title="Today" inTabs>
        {sync.online ? <Loading /> : <Empty icon={Path} title="No route on this phone yet" body="Connect to the internet to download today’s route." />}
      </DriverScreen>
    );
  }
  const open = (p: Phase) => {
    if (p === 'inspect') router.push('/inspect');
    if (p === 'loadout') router.push('/scan/loadout');
    if (p === 'route') router.navigate('/route');
    if (p === 'end') router.push('/end');
  };
  const primary = primaryAction(r);
  const onPrimary = async () => {
    if (primary.target === 'start-route') {
      await engine.record({ type: 'loadout.completed' }).catch(() => undefined);
      await engine.record({ type: 'route.started' });
      toast('Route started — tap “I’ve arrived” when you get there');
      router.navigate('/route');
      return;
    }
    if (primary.target !== 'none') open(primary.target);
  };
  const pkgs = r.packages.length;
  return (
    <DriverScreen title="Today" inTabs footer={<Button label={primary.label} disabled={primary.disabled} onPress={() => void onPrimary()} />}>
      <View style={styles.hello}>
        <AppText variant="title">Hi {user?.name.split(' ')[0]}</AppText>
        <AppText color={c.textMuted}>{r.zone} · {r.window} · {r.stops.length} stops · {pkgs} packages</AppText>
        <AppText variant="small" color={c.textMuted}>Vehicle {r.vehicleLabel} · from {r.depotName}</AppText>
      </View>
      {r.blocked ? <Banner tone="danger" icon={WarningCircle}>Defect sent to dispatch — a replacement vehicle will be assigned. You can’t start this route.</Banner> : null}
      {sync.outbox.issues.length ? (
        <Pressable accessibilityRole="button" onPress={() => router.navigate('/profile')}>
          <Banner tone="danger" icon={WarningCircle}>{sync.outbox.issues.length} action{sync.outbox.issues.length === 1 ? ' was' : 's were'} refused by dispatch. Tap to review.</Banner>
        </Pressable>
      ) : null}
      <View style={{ gap: sp['2'] }}>
        {phases(r).map((p) => {
          const I = p.done ? Check : ICON[p.key];
          return (
            <Pressable key={p.key} accessibilityRole="button" accessibilityState={{ disabled: p.locked }} disabled={p.locked} onPress={() => open(p.key)} style={[styles.phase, p.locked ? { backgroundColor: c.surfaceSunken, opacity: 0.6 } : null]}>
              <View style={[styles.dot, { backgroundColor: p.done ? t.green['600'] : c.surfaceMuted }]}><I size={18} weight={p.done ? 'bold' : 'regular'} color={p.done ? c.textInverse : c.text} /></View>
              <View style={{ flex: 1 }}>
                <AppText variant="bodyStrong">{p.label}</AppText>
                <AppText variant="small" color={c.textMuted}>{p.note}</AppText>
              </View>
              <CaretRight size={16} color={c.textMuted} />
            </Pressable>
          );
        })}
      </View>
    </DriverScreen>
  );
}

const styles = StyleSheet.create({
  hello: { gap: 2 },
  phase: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], padding: sp['3'], borderRadius: t.radius.lg, borderWidth: 1, borderColor: c.border, backgroundColor: c.surface },
  dot: { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center' }
});
