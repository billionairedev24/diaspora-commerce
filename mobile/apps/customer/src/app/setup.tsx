/* First run after sign-in (design: Step 3 of 4 · Unlock → Passcode → Step 4 of 4 · Updates). Account creation and
   email verification (steps 1–2) happen on identity's hosted pages during sign-up. */
import { type BellIcon as Bell, BellRingingIcon as BellRinging, CalendarCheckIcon as CalendarCheck, PackageIcon as Package, ScanSmileyIcon as ScanSmiley, TruckIcon as Truck } from 'phosphor-react-native';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { useAuth } from '@/auth/AuthProvider';
import { PasscodeSetupView } from '@/features/lock/PasscodeSetupView';
import { usePush } from '@/push/PushProvider';
import { c, sp, t } from '@/theme';
import { AppText, Banner, Button } from '@/ui/primitives';
import { Screen } from '@/ui/Screen';
import { useToast } from '@/ui/Toast';

function Hero({ icon: Icon }: { icon: typeof Bell }) {
  return (
    <View style={styles.hero}>
      <Icon size={56} color={c.text} />
    </View>
  );
}

function FaceIdStep() {
  const { setFaceId, biometryLabel } = useAuth();
  const toast = useToast();
  const [msg, setMsg] = useState('');
  return (
    <Screen footer={
      <>
        <Button label={`Turn on ${biometryLabel}`} onPress={() => void setFaceId(true).then((ok) => (ok ? toast(`${biometryLabel} is set up`) : setMsg(`${biometryLabel} isn’t available on this phone. Use a passcode.`)))} />
        <Button kind="ghost" label="Use passcode only" onPress={() => void setFaceId(false)} />
      </>
    }>
      <AppText variant="label" color={c.textMuted} style={styles.step}>STEP 3 OF 4 · UNLOCK</AppText>
      <Hero icon={ScanSmiley} />
      <AppText variant="display" style={styles.center}>Unlock AfroTransact with {biometryLabel}</AppText>
      <AppText color={c.textMuted} style={styles.center}>Open the app and you’re straight in — no password. {biometryLabel} also confirms payments over $100 and changes to your account.</AppText>
      {msg ? <Banner tone="warning">{msg}</Banner> : null}
    </Screen>
  );
}

function NotificationsStep() {
  const { finishSetup, user } = useAuth();
  const push = usePush();
  const toast = useToast();
  const done = async (allow: boolean) => {
    if (allow) await push.enable();
    else await push.setCategories({ ...push.categories, deals: false, lola: false });
    await finishSetup();
    toast(`You’re all set, ${user?.first ?? ''}`);
  };
  return (
    <Screen footer={
      <>
        <Button label="Allow notifications" onPress={() => void done(true)} />
        <Button kind="ghost" label="Not now" onPress={() => void done(false)} />
      </>
    }>
      <AppText variant="label" color={c.textMuted} style={styles.step}>STEP 4 OF 4 · UPDATES</AppText>
      <Hero icon={BellRinging} />
      <AppText variant="display" style={styles.center}>Know when your order is at the door</AppText>
      <View style={{ gap: sp['3'] }}>
        <Row icon={Truck} text="Packed, out for delivery, delivered with photo" />
        <Row icon={Package} text="Saved items back in stock" />
        <Row icon={CalendarCheck} text="Booking confirmations from pros" />
      </View>
    </Screen>
  );
}

function Row({ icon: Icon, text }: { icon: typeof Bell; text: string }) {
  return (
    <View style={{ flexDirection: 'row', gap: sp['3'], alignItems: 'center' }}>
      <Icon size={22} color={c.text} />
      <AppText style={{ flex: 1 }}>{text}</AppText>
    </View>
  );
}

export default function Setup() {
  const { setupStep, savePasscode } = useAuth();
  if (setupStep === 'face-id') return <FaceIdStep />;
  if (setupStep === 'passcode') return <PasscodeSetupView stepLabel="STEP 3 OF 4 · PASSCODE" onDone={savePasscode} />;
  return <NotificationsStep />;
}

const styles = StyleSheet.create({
  step: { textAlign: 'center', paddingTop: sp['6'] },
  hero: { alignSelf: 'center', width: 112, height: 112, borderRadius: 28, backgroundColor: t.gold['100'], alignItems: 'center', justifyContent: 'center', marginVertical: sp['4'] },
  center: { textAlign: 'center' }
});
