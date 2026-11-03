/* "Still there?" bottom sheet: only when staff configured a warning for the customer app (warnSeconds > 0), only while the
   app is in the foreground and unlocked (the lock overlay is never covered by it). Stay signed in counts as activity;
   Sign out uses the normal sign-out. Closing the sheet by tapping outside is the same as staying. */
import { StyleSheet, View } from 'react-native';

import { useAuth } from '@/auth/AuthProvider';
import { useT } from '@/i18n';
import { sp } from '@/theme';
import { AppText, Button } from '@/ui/primitives';
import { Sheet } from '@/ui/Sheet';

export function IdleWarning() {
  const tr = useT();
  const { phase, sessionWarning, staySignedIn, signOut } = useAuth();
  const visible = phase === 'unlocked' && sessionWarning !== null;
  return (
    <Sheet visible={visible} onClose={staySignedIn}>
      <AppText variant="heading" accessibilityRole="header">{tr('cu.session.warnTitle')}</AppText>
      <AppText accessibilityLiveRegion="polite">{tr('cu.session.warnBody', { n: sessionWarning?.secondsLeft ?? 0 })}</AppText>
      <View style={styles.actions}>
        <Button label={tr('cu.session.stay')} onPress={staySignedIn} />
        <Button kind="outline" label={tr('cu.session.signOut')} onPress={() => void signOut()} />
      </View>
    </Sheet>
  );
}

const styles = StyleSheet.create({ actions: { gap: sp['2'], marginTop: sp['2'] } });
