/* S081 "No driver access": who can grant it; queued events stay on the phone and still sync with this token. */
import { LockKeyIcon as LockKey } from 'phosphor-react-native';
import { View } from 'react-native';

import { useDriver } from '@/session/DriverProvider';
import { c, sp } from '@/theme';
import { DriverScreen } from '@/ui/DriverScreen';
import { AppText, Banner, Button } from '@/ui/primitives';

export default function NoAccess() {
  const { denied, signOut, sync, engine } = useDriver();
  const pending = sync.outbox.events.length;
  return (
    <DriverScreen title="Access">
      <View style={{ alignItems: 'center', gap: sp['3'], paddingTop: sp['8'] }}>
        <LockKey size={56} weight="fill" color={c.text} />
        <AppText variant="display" accessibilityRole="header">No driver access</AppText>
        <AppText style={{ textAlign: 'center' }}>{denied?.message}</AppText>
        <AppText variant="small" color={c.textMuted} style={{ textAlign: 'center' }}>{denied?.grantHint}</AppText>
      </View>
      {pending ? <Banner tone="warning">{pending} unsynced event{pending === 1 ? '' : 's'} kept on this phone. They’re sent as soon as the phone is online and you’re signed in.</Banner> : null}
      {pending && !denied?.revoked ? <Button kind="outline" label="Sync now" onPress={() => void engine.flush()} /> : null}
      <Button label="Back to sign in" onPress={() => void signOut()} />
    </DriverScreen>
  );
}
