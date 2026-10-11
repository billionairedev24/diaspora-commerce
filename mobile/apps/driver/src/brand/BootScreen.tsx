/* Shown while the app restores the session: the same black canvas and centred gold mark as the native splash
   (app.config.ts SPLASH), so launch goes splash → boot → first screen without a white flash. */
import { ActivityIndicator, StyleSheet, View } from 'react-native';

import { t } from '@/theme';
import { LogoMark } from '@/ui/Logo';

export function BootScreen() {
  return (
    <View style={styles.root} accessibilityRole="progressbar" accessibilityLabel="AfroTransact Delivery is starting" accessibilityState={{ busy: true }}>
      <LogoMark size={132} cutout={t.ink['1000']} />
      <ActivityIndicator color={t.gold['500']} style={styles.spinner} />
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: t.ink['1000'] },
  spinner: { position: 'absolute', bottom: t.space['16'] }
});
