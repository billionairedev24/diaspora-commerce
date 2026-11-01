/* First run after sign-in: Face ID → passcode → notifications, three steps ("1 of 3"). Account creation and email
   verification happen on identity's hosted pages during sign-up. Each step is an OnboardingScreen: a black bar with the
   step count, a white body, one gold action and a quiet "Not now" where skipping is allowed (Face ID, notifications).
   The passcode is the fallback for Face ID, so it is not skippable. */
import { type BellIcon as Bell, BellRingingIcon as BellRinging, CalendarCheckIcon as CalendarCheck, PackageIcon as Package, ScanSmileyIcon as ScanSmiley, TruckIcon as Truck } from 'phosphor-react-native';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { useAuth } from '@/auth/AuthProvider';
import { IconTile, OnboardingScreen } from '@/brand/OnboardingScreen';
import { PasscodeSetupView } from '@/features/lock/PasscodeSetupView';
import { useT } from '@/i18n';
import { usePush } from '@/push/PushProvider';
import { c, sp } from '@/theme';
import { AppText, Banner, Button } from '@/ui/primitives';
import { useToast } from '@/ui/Toast';

function FaceIdStep() {
  const tr = useT();
  const { setFaceId, biometryLabel } = useAuth();
  const toast = useToast();
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState<'on' | 'off' | null>(null);
  const turnOn = async () => {
    setBusy('on');
    setMsg('');
    try {
      if (await setFaceId(true)) toast(tr('cu.setup.faceReady', { biometry: biometryLabel }));
      else setMsg(tr('cu.setup.faceUnavailable', { biometry: biometryLabel }));
    } finally {
      setBusy(null);
    }
  };
  const skip = async () => {
    setBusy('off');
    try {
      await setFaceId(false);
    } finally {
      setBusy(null);
    }
  };
  return (
    <OnboardingScreen
      step="face-id"
      art={<IconTile icon={ScanSmiley} />}
      title={tr('cu.setup.faceTitle', { biometry: biometryLabel })}
      body={tr('cu.setup.faceBody', { biometry: biometryLabel })}
      footer={
        <>
          <Button label={tr('cu.setup.faceOn', { biometry: biometryLabel })} onPress={() => void turnOn()} loading={busy === 'on'} disabled={busy === 'off'} />
          <Button kind="ghost" label={tr('cu.setup.notNow')} onPress={() => void skip()} loading={busy === 'off'} disabled={busy === 'on'} />
        </>
      }
    >
      {msg ? <Banner tone="warning">{msg}</Banner> : null}
    </OnboardingScreen>
  );
}

function NotificationsStep() {
  const tr = useT();
  const { finishSetup, user } = useAuth();
  const push = usePush();
  const toast = useToast();
  const [busy, setBusy] = useState<'allow' | 'later' | null>(null);
  const done = async (allow: boolean) => {
    setBusy(allow ? 'allow' : 'later');
    try {
      if (allow) await push.enable();
      else await push.setCategories({ ...push.categories, deals: false, lola: false });
      await finishSetup();
      toast(tr('cu.setup.allSet', { name: user?.first ?? '' }));
    } finally {
      setBusy(null);
    }
  };
  return (
    <OnboardingScreen
      step="notifications"
      art={<IconTile icon={BellRinging} />}
      title={tr('cu.setup.notifTitle')}
      footer={
        <>
          <Button label={tr('cu.setup.notifAllow')} onPress={() => void done(true)} loading={busy === 'allow'} disabled={busy === 'later'} />
          <Button kind="ghost" label={tr('cu.setup.notNow')} onPress={() => void done(false)} loading={busy === 'later'} disabled={busy === 'allow'} />
        </>
      }
    >
      <View style={styles.rows}>
        <Row icon={Truck} text={tr('cu.setup.notifRow1')} />
        <Row icon={Package} text={tr('cu.setup.notifRow2')} />
        <Row icon={CalendarCheck} text={tr('cu.setup.notifRow3')} />
      </View>
    </OnboardingScreen>
  );
}

function Row({ icon: Icon, text }: { icon: typeof Bell; text: string }) {
  return (
    <View style={styles.row}>
      <Icon size={22} color={c.success} />
      <AppText style={{ flex: 1 }}>{text}</AppText>
    </View>
  );
}

export default function Setup() {
  const { setupStep, savePasscode } = useAuth();
  if (setupStep === 'face-id') return <FaceIdStep />;
  if (setupStep === 'passcode') {
    return (
      <OnboardingScreen step="passcode" fixed>
        <PasscodeSetupView onDone={savePasscode} embedded />
      </OnboardingScreen>
    );
  }
  return <NotificationsStep />;
}

const styles = StyleSheet.create({
  rows: { gap: sp['3'], paddingTop: sp['2'] },
  row: { flexDirection: 'row', gap: sp['3'], alignItems: 'center' }
});
