/* Sign-in & security (design: SECURITY): Face ID on/off, change passcode, sign out everywhere. */
import * as WebBrowser from 'expo-web-browser';
import { KeyIcon as Key, PasswordIcon as Password, ScanSmileyIcon as ScanSmiley } from 'phosphor-react-native';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { useAuth } from '@/auth/AuthProvider';
import { env } from '@/config/env';
import { PasscodeSetupView } from '@/features/lock/PasscodeSetupView';
import { c, sp } from '@/theme';
import { AppText, Button, SectionLabel } from '@/ui/primitives';
import { Screen, SubHeader } from '@/ui/Screen';
import { Sheet } from '@/ui/Sheet';
import { useToast } from '@/ui/Toast';

export default function Security() {
  const { faceId, biometryLabel, setFaceId, savePasscode, confirmWithDevice, signOut, user } = useAuth();
  const toast = useToast();
  const [changing, setChanging] = useState(false);
  const [confirmAll, setConfirmAll] = useState(false);
  if (changing) {
    return <PasscodeSetupView header={<SubHeader title="Change passcode" onBack={() => setChanging(false)} />} stepLabel="CHANGE PASSCODE" onDone={async (pin) => { await savePasscode(pin); setChanging(false); toast('Passcode updated'); }} />;
  }
  return (
    <Screen header={<SubHeader title="Sign-in & security" />}>
      <SectionLabel>UNLOCK & SIGN-IN</SectionLabel>
      <Row icon={ScanSmiley} title={`${biometryLabel} unlock`} detail={faceId ? 'On · also confirms payments over $100' : 'Off · passcode only'} action={faceId ? 'Turn off' : 'Turn on'}
        onPress={() => void setFaceId(!faceId).then((ok) => toast(ok ? `${biometryLabel} is ${faceId ? 'off' : 'on'}` : `${biometryLabel} isn’t available`))} />
      <Row icon={Password} title="App passcode" detail="6 digits · set on this phone" action="Change" onPress={() => void confirmWithDevice('Change your passcode').then((ok) => ok && setChanging(true))} />
      <Row icon={Key} title="Password & passkeys" detail={user?.email ?? ''} action="Manage" onPress={() => void WebBrowser.openBrowserAsync(`${env.oidcIssuer}/webauthn/register`)} />
      <Button kind="outline" label="Sign out of all devices" onPress={() => setConfirmAll(true)} />
      <Sheet visible={confirmAll} onClose={() => setConfirmAll(false)}>
        <AppText variant="heading">Sign out everywhere?</AppText>
        <AppText color={c.textMuted}>Ends this session and removes {biometryLabel} and your passcode from this phone. Other devices sign out when their session is revoked.</AppText>
        <Button kind="danger" label="Sign out of all devices" onPress={() => { setConfirmAll(false); void signOut({ flash: 'Signed out on all devices' }); }} />
        <Button kind="ghost" label="Cancel" onPress={() => setConfirmAll(false)} />
      </Sheet>
    </Screen>
  );
}

function Row({ icon: I, title, detail, action, onPress }: { icon: typeof Key; title: string; detail: string; action: string; onPress: () => void }) {
  return (
    <View style={styles.row}>
      <I size={24} color={c.text} />
      <View style={{ flex: 1 }}>
        <AppText variant="bodyStrong">{title}</AppText>
        <AppText variant="small" color={c.textMuted}>{detail}</AppText>
      </View>
      <Button size="md" kind="outline" label={action} onPress={onPress} />
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], paddingVertical: sp['2'] }
});
