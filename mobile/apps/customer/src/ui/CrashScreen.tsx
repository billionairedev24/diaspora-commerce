/* Shown by the root ErrorBoundary after a crash (the error is already reported to telemetry). */
import { WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { View } from 'react-native';

import { useT } from '@/i18n';
import { c, sp } from '@/theme';
import { AppText, Button } from '@/ui/primitives';

export function CrashScreen({ onRetry }: { onRetry: () => void }) {
  const tr = useT();
  return (
    <View accessibilityRole="alert" style={{ flex: 1, alignItems: 'center', justifyContent: 'center', gap: sp['3'], padding: sp['6'], backgroundColor: c.canvas }}>
      <WarningCircle size={48} color={c.danger} />
      <AppText variant="heading" accessibilityRole="header">{tr('cu.crash.title')}</AppText>
      <AppText color={c.textMuted} style={{ textAlign: 'center' }}>{tr('cu.crash.body')}</AppText>
      <Button label={tr('cu.crash.retry')} onPress={onRetry} />
    </View>
  );
}
