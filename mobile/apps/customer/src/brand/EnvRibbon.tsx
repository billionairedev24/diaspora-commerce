/* Non-prod builds only: a small pill naming the environment (LOCAL, DEV, UAT) inside the app — the launcher name and
   the headers always say just "AfroTransact". */
import { StyleSheet, View } from 'react-native';

import { env } from '@/config/env';
import { t } from '@/theme';
import { AppText } from '@/ui/primitives';

export function envRibbonLabel(appEnv: string): string | null {
  return appEnv === 'prod' ? null : appEnv.toUpperCase();
}

export function EnvRibbon() {
  const label = envRibbonLabel(env.appEnv);
  if (!label) return null;
  return (
    <View style={styles.pill} accessibilityLabel={`${label} environment`}>
      <AppText variant="label" color={t.gold['500']}>{label}{env.mocks ? ' · MOCKS' : ''}</AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  pill: { alignSelf: 'center', paddingHorizontal: t.space['3'], paddingVertical: t.space['1'], borderRadius: t.radius.pill, backgroundColor: t.ink['1000'] }
});
