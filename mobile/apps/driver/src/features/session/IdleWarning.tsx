/* "Still there?" bottom sheet: only when staff configured a warning for the driver app (warnSeconds > 0), only while the
   app is in the foreground and unlocked, so it never covers the lock screen. Stay signed in counts as activity; Sign out
   is the normal sign-out (last sync, outbox kept). Closing the sheet by tapping outside is the same as staying. */
import { StyleSheet, View } from 'react-native';

import { useT } from '@/i18n';
import { useDriver } from '@/session/DriverProvider';
import { useLock } from '@/session/LockProvider';
import { sp } from '@/theme';
import { AppText, Button } from '@/ui/primitives';
import { Sheet } from '@/ui/Sheet';

export function IdleWarning() {
  const tr = useT();
  const { sessionWarning, staySignedIn, signOut } = useDriver();
  const { phase } = useLock();
  const visible = phase === 'unlocked' && sessionWarning !== null;
  return (
    <Sheet visible={visible} onClose={staySignedIn}>
      <AppText variant="heading" accessibilityRole="header">{tr('dr.session.warnTitle')}</AppText>
      <AppText accessibilityLiveRegion="polite">{tr('dr.session.warnBody', { n: sessionWarning?.secondsLeft ?? 0 })}</AppText>
      <View style={styles.actions}>
        <Button label={tr('dr.session.stay')} onPress={staySignedIn} />
        <Button kind="outline" label={tr('dr.session.signOut')} onPress={() => void signOut()} />
      </View>
    </Sheet>
  );
}

const styles = StyleSheet.create({ actions: { gap: sp['2'], marginTop: sp['2'] } });
