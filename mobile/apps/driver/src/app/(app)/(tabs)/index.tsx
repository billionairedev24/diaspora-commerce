/* Today (design: HOME): inspection → load-out → route → end shift, each step unlocking the next. The start-of-shift
   greeting sits on a brand hero card (the welcome imagery, faded to black). */
import { useRouter } from 'expo-router';
import { CaretRightIcon as CaretRight, CheckIcon as Check, ClipboardTextIcon as ClipboardText, FlagCheckeredIcon as FlagCheckered, PackageIcon as Package, PathIcon as Path, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { Pressable, StyleSheet, View } from 'react-native';

import { DriverHeroArt } from '@/brand/DriverHeroArt';
import { Hero } from '@/brand/Hero';
import heroPhoto from '@/brand/heroPhoto';
import { type Phase, phases, primaryAction } from '@/domain/shift';
import { useT } from '@/i18n';
import { useDriver } from '@/session/DriverProvider';
import { c, sp, t } from '@/theme';
import { DriverScreen } from '@/ui/DriverScreen';
import { AppText, Banner, Button, Empty, Loading } from '@/ui/primitives';
import { useToast } from '@/ui/Toast';
import { useRouteRefresh } from '@/ui/useRouteRefresh';

const ICON: Record<Phase, typeof Check> = { inspect: ClipboardText, loadout: Package, route: Path, end: FlagCheckered };

export default function Today() {
  const tr = useT();
  const router = useRouter();
  const toast = useToast();
  const { user, sync, engine } = useDriver();
  const refresh = useRouteRefresh();
  const r = sync.route;
  if (!r) {
    const load = sync.routeLoad;
    return (
      <DriverScreen title={tr('dr.index.today')} inTabs refresh={refresh}>
        {!sync.online ? (
          <Empty icon={Path} title={tr('dr.index.noRouteOnThisPhone')} body="Connect to the internet to download today’s route." />
        ) : load.status === 'failed' ? (
          <Empty icon={WarningCircle} title="We couldn’t load your route" body={`${load.error ?? 'Try again.'} Pull down or tap Try again.`} action={{ label: 'Try again', onPress: refresh.onRefresh }} />
        ) : load.status === 'none' ? (
          <Empty icon={Path} title="No route assigned yet" body="Dispatch hasn’t assigned you a route for today. Pull down to check again." action={{ label: 'Check again', onPress: refresh.onRefresh }} />
        ) : (
          <Loading />
        )}
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
      toast(tr('dr.index.routeStarted'));
      router.navigate('/route');
      return;
    }
    if (primary.target !== 'none') open(primary.target);
  };
  const pkgs = r.packages.length;
  return (
    <DriverScreen title={tr('dr.index.today')} inTabs refresh={refresh} footer={<Button label={primary.label} disabled={primary.disabled} onPress={() => void onPrimary()} />}>
      <View style={styles.shift}>
        <Hero photo={heroPhoto} art={<DriverHeroArt crop="wide" />} accessibilityLabel="Your shift">
          <View style={styles.hello}>
            <AppText variant="title" color={c.textInverse}>Hi {user?.name.split(' ')[0]}</AppText>
            <AppText color={c.headerMuted}>{r.zone} · {r.window} · {r.stops.length} stops · {pkgs} packages</AppText>
            <AppText variant="small" color={t.gold['500']}>Vehicle {r.vehicleLabel} · from {r.depotName}</AppText>
          </View>
        </Hero>
      </View>
      {r.blocked ? <Banner tone="danger" icon={WarningCircle}>{tr('dr.index.defectSentToDispatchA')}</Banner> : null}
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
  shift: { height: 196, borderRadius: t.radius.lg * 1.5, overflow: 'hidden' },
  hello: { gap: 2, padding: sp['4'] },
  phase: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], padding: sp['3'], borderRadius: t.radius.lg, borderWidth: 1, borderColor: c.border, backgroundColor: c.surface },
  dot: { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center' }
});
