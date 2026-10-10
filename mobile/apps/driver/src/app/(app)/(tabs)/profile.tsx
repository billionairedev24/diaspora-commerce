/* Profile & sync (design: SYNC / PROFILE): who you are, the outbox and refused actions, an offline switch, sign out. */
import { CheckCircleIcon as CheckCircle, ClockIcon as Clock, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { StyleSheet, Switch, View } from 'react-native';

import { mockRevokeAccess } from '@/api/mock/server';
import { env } from '@/config/env';
import { useDriver } from '@/session/DriverProvider';
import { c, sp, t } from '@/theme';
import { DriverScreen } from '@/ui/DriverScreen';
import { AppText, Button, Card, SectionLabel } from '@/ui/primitives';

const EVENT_LABEL: Record<string, string> = {
  'session.signed_in': 'Signed in', 'inspection.submitted': 'Inspection submitted', 'loadout.scanned': 'Loaded at FC', 'loadout.completed': 'Load-out complete', 'route.started': 'Route started',
  'stop.arrived': 'Arrived', 'stop.scanned': 'Package scanned', 'pickup.confirmed': 'Picked up', 'pickup.problem': 'Pickup problem', 'customer.contacted': 'Customer contacted',
  'delivery.completed': 'Delivered', 'delivery.problem': 'Delivery problem', 'return.scanned': 'Returned to FC', 'shift.ended': 'Shift ended', 'sos.raised': 'SOS raised'
};

export default function Profile() {
  const { user, sync, engine, paused, setPaused, signOut } = useDriver();
  const out = sync.outbox;
  return (
    <DriverScreen title="Profile & sync" inTabs>
      <Card>
        <AppText variant="heading">{user?.name}</AppText>
        <AppText variant="small" color={c.textMuted}>{user?.email}</AppText>
        <AppText variant="caption" color={c.textMuted}>Permissions: {user?.perms.join(', ') || '—'}</AppText>
      </Card>
      <View style={styles.row}>
        <View style={{ flex: 1 }}>
          <AppText variant="bodyStrong">Online</AppText>
          <AppText variant="small" color={c.textMuted}>{paused ? 'Paused — actions queue on this phone' : sync.online ? 'Actions send as you go' : 'No signal — actions queue on this phone'}</AppText>
        </View>
        <Switch accessibilityLabel="Online" value={!paused} onValueChange={(v) => setPaused(!v)} trackColor={{ true: c.success, false: t.ink['200'] }} thumbColor={c.surface} />
      </View>
      {out.issues.length ? (
        <View style={{ gap: sp['2'] }}>
          <SectionLabel>REFUSED BY DISPATCH</SectionLabel>
          {out.issues.map((i) => (
            <Card key={`${i.eventId}-${i.sequence}`} style={{ borderColor: c.danger }}>
              <View style={styles.row}>
                <WarningCircle size={20} color={c.danger} />
                <AppText variant="bodyStrong" style={{ flex: 1 }}>{EVENT_LABEL[i.type] ?? i.type} · {i.outcome === 'CONFLICT' ? 'conflict' : i.outcome === 'GAP' ? 'missing event' : 'rejected'}</AppText>
              </View>
              <AppText variant="small">{i.message}</AppText>
              <AppText variant="caption" color={c.textMuted}>{i.code ?? ''} · #{i.sequence}</AppText>
              <Button size="md" kind="ghost" label="Dismiss" onPress={() => engine.dismissIssue(i.eventId)} />
            </Card>
          ))}
        </View>
      ) : null}
      <View style={{ gap: sp['2'] }}>
        <View style={styles.between}>
          <SectionLabel>OUTBOX</SectionLabel>
          <AppText variant="caption" color={c.textMuted}>{out.lastSyncAt ? `Last sync ${new Date(out.lastSyncAt).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })}` : 'Never synced'}</AppText>
        </View>
        {out.events.length === 0 ? (
          <View style={styles.row}><CheckCircle size={20} weight="fill" color={c.success} /><AppText>Everything is synced.</AppText></View>
        ) : out.events.map((e) => (
          <View key={e.eventId} style={styles.row}>
            <Clock size={18} color={c.warning} />
            <View style={{ flex: 1 }}>
              <AppText variant="small" style={{ fontWeight: t.fontWeight.semibold }}>{EVENT_LABEL[e.type] ?? e.type}</AppText>
              <AppText variant="caption" color={c.textMuted}>#{e.sequence} · {new Date(e.capturedAt).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })} · {e.eventId.slice(0, 8)}</AppText>
            </View>
            <AppText variant="caption" color={c.warning}>queued</AppText>
          </View>
        ))}
        {sync.lastError ? <AppText variant="small" color={c.danger}>{sync.lastError}</AppText> : null}
        <Button kind="outline" label={sync.syncing ? 'Syncing…' : 'Sync now'} disabled={!sync.online || sync.syncing} onPress={() => void engine.flush()} />
      </View>
      {env.mocks && user ? <Button kind="ghost" label="Dev: simulate access revoked" onPress={() => { mockRevokeAccess(user.sub); void engine.record({ type: 'session.signed_in' }); }} /> : null}
      <Button kind="danger" label="Sign out" onPress={() => void signOut()} />
      <AppText variant="caption" color={c.textMuted} style={{ textAlign: 'center' }}>Device {out.deviceId.slice(0, 12)} · {env.appEnv}</AppText>
    </DriverScreen>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: sp['3'] },
  between: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }
});
