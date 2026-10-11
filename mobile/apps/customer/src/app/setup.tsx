/* First run after sign-in (design: Step 3 of 4 · Unlock → Passcode → Step 4 of 4 · Updates). Account creation and
   email verification (steps 1–2) happen on identity's hosted pages during sign-up. Each step is a full-bleed brand
   screen (OnboardingScreen): black canvas, gold medallion, gold call to action. */
import { type BellIcon as Bell, BellRingingIcon as BellRinging, CalendarCheckIcon as CalendarCheck, PackageIcon as Package, ScanSmileyIcon as ScanSmiley, TruckIcon as Truck } from 'phosphor-react-native';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { useAuth } from '@/auth/AuthProvider';
import { Medallion, OnboardingScreen } from '@/brand/OnboardingScreen';
import { PasscodeSetupView } from '@/features/lock/PasscodeSetupView';
import { usePush } from '@/push/PushProvider';
import { c, sp, t } from '@/theme';
import { AppText, Banner, Button } from '@/ui/primitives';
import { useToast } from '@/ui/Toast';

function FaceIdStep() {
  const { setFaceId, biometryLabel } = useAuth();
  const toast = useToast();
  const [msg, setMsg] = useState('');
  return (
    <OnboardingScreen
      step="STEP 3 OF 4 · UNLOCK"
      art={<Medallion icon={ScanSmiley} />}
      title={`Unlock AfroTransact with ${biometryLabel}`}
      body={`Open the app and you’re straight in — no password. ${biometryLabel} also confirms payments over $100 and changes to your account.`}
      footer={
        <>
          <Button label={`Turn on ${biometryLabel}`} onPress={() => void setFaceId(true).then((ok) => (ok ? toast(`${biometryLabel} is set up`) : setMsg(`${biometryLabel} isn’t available on this phone. Use a passcode.`)))} />
          <Button kind="onDark" label="Use passcode only" onPress={() => void setFaceId(false)} />
        </>
      }
    >
      {msg ? <Banner tone="warning">{msg}</Banner> : null}
    </OnboardingScreen>
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
    <OnboardingScreen
      step="STEP 4 OF 4 · UPDATES"
      art={<Medallion icon={BellRinging} />}
      title="Know when your order is at the door"
      footer={
        <>
          <Button label="Allow notifications" onPress={() => void done(true)} />
          <Button kind="onDark" label="Not now" onPress={() => void done(false)} />
        </>
      }
    >
      <View style={styles.rows}>
        <Row icon={Truck} text="Packed, out for delivery, delivered with photo" />
        <Row icon={Package} text="Saved items back in stock" />
        <Row icon={CalendarCheck} text="Booking confirmations from pros" />
      </View>
    </OnboardingScreen>
  );
}

function Row({ icon: Icon, text }: { icon: typeof Bell; text: string }) {
  return (
    <View style={{ flexDirection: 'row', gap: sp['3'], alignItems: 'center' }}>
      <Icon size={22} color={t.gold['500']} />
      <AppText color={c.textInverse} style={{ flex: 1 }}>{text}</AppText>
    </View>
  );
}

export default function Setup() {
  const { setupStep, savePasscode } = useAuth();
  if (setupStep === 'face-id') return <FaceIdStep />;
  if (setupStep === 'passcode') {
    return (
      <OnboardingScreen>
        <PasscodeSetupView stepLabel="STEP 3 OF 4 · PASSCODE" onDone={savePasscode} tone="dark" bare />
      </OnboardingScreen>
    );
  }
  return <NotificationsStep />;
}

const styles = StyleSheet.create({
  rows: { gap: sp['3'], padding: sp['4'], borderRadius: t.radius.lg, backgroundColor: t.ink['900'] }
});
