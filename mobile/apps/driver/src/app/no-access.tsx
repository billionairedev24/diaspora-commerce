/* S081 "No driver access" (design 2.4): a valid staff account without the driver permission (or access that was just
   removed). Says why, who to ask (dispatch), and offers Sign out. Queued events stay on the phone and still sync with
   this token (decideAccess semantics are unchanged). */
import { WarningIcon as Warning } from 'phosphor-react-native';
import { StyleSheet, View } from 'react-native';

import { env } from '@/config/env';
import { DispatchActions } from '@/features/auth/DispatchHelp';
import { useT } from '@/i18n';
import { useDriver } from '@/session/DriverProvider';
import { c, sp } from '@/theme';
import { DriverScreen } from '@/ui/DriverScreen';
import { AppText, Banner, Button } from '@/ui/primitives';

export default function NoAccess() {
  const tr = useT();
  const { denied, user, signOut, sync, engine } = useDriver();
  const pending = sync.outbox.events.length;
  const revoked = !!denied?.revoked;
  return (
    <DriverScreen
      title={tr('dr.noAccess.access')}
      footer={
        <>
          <Button label={tr('dr.noAccess.signOut')} onPress={() => void signOut()} />
          <View style={styles.contact}><DispatchActions /></View>
        </>
      }
    >
      <View style={styles.hero}>
        <View style={styles.badge} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
          <Warning size={36} weight="bold" color={c.danger} />
        </View>
        <AppText variant="display" style={styles.centre} accessibilityRole="header">{tr('dr.noAccess.cantDrive')}</AppText>
        <AppText style={styles.centre}>{revoked ? tr('dr.noAccess.reasonRevoked') : tr('dr.noAccess.reasonDenied', { email: user?.email || user?.name || '' })}</AppText>
        <AppText variant="small" color={c.textMuted} style={styles.centre}>{tr('dr.noAccess.askDispatch')}</AppText>
        {/* The administrator's hint (which role or permission to grant) is for engineers: not shown in production. */}
        {env.appEnv !== 'prod' && denied?.grantHint ? <AppText variant="caption" color={c.textSubtle} style={styles.centre}>{denied.grantHint}</AppText> : null}
      </View>
      {pending ? <Banner tone="warning">{tr(pending === 1 ? 'dr.noAccess.unsyncedOne' : 'dr.noAccess.unsyncedMany', { count: pending })}</Banner> : null}
      {pending && !revoked ? <Button kind="outline" label={tr('dr.noAccess.syncNow')} onPress={() => void engine.flush()} /> : null}
    </DriverScreen>
  );
}

const styles = StyleSheet.create({
  hero: { alignItems: 'center', gap: sp['3'], paddingTop: sp['8'] },
  badge: { width: 72, height: 72, borderRadius: 36, backgroundColor: c.dangerBg, alignItems: 'center', justifyContent: 'center' },
  centre: { textAlign: 'center' },
  contact: { alignItems: 'center' }
});
