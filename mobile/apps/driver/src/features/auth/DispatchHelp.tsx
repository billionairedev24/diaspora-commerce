/* "Locked out or need an account? Contact dispatch." Drivers are staff: there is no create-account and no in-app
   password reset (no API), so help goes to dispatch. The contact action shows only when the build configures a dispatch
   phone or email (EXPO_PUBLIC_DISPATCH_PHONE / EXPO_PUBLIC_DISPATCH_EMAIL): no number is baked into the app. */
import { Linking, Pressable, StyleSheet, View } from 'react-native';

import { env } from '@/config/env';
import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { AppText } from '@/ui/primitives';

function Action({ label, url }: { label: string; url: string }) {
  return (
    <Pressable accessibilityRole="link" accessibilityLabel={label} onPress={() => void Linking.openURL(url).catch(() => undefined)} hitSlop={8} style={styles.action}>
      <AppText variant="small" style={styles.link}>{label}</AppText>
    </Pressable>
  );
}

/** The contact actions, only for what the build configures (nothing when neither is set). */
export function DispatchActions() {
  const tr = useT();
  const { dispatchPhone, dispatchEmail } = env;
  return (
    <>
      {dispatchPhone ? <Action label={tr('dr.signIn.callDispatch', { phone: dispatchPhone })} url={`tel:${dispatchPhone.replace(/[^\d+]/g, '')}`} /> : null}
      {dispatchEmail ? <Action label={tr('dr.signIn.emailDispatch')} url={`mailto:${dispatchEmail}`} /> : null}
    </>
  );
}

export function DispatchHelp() {
  const tr = useT();
  return (
    <View style={styles.box}>
      <AppText variant="small" color={c.textMuted} style={styles.text}>{tr('dr.signIn.helpLine')}</AppText>
      <DispatchActions />
    </View>
  );
}

const styles = StyleSheet.create({
  box: { alignItems: 'center', gap: sp['1'] },
  text: { textAlign: 'center' },
  action: { minHeight: t.size.touch, justifyContent: 'center' },
  link: { fontWeight: t.fontWeight.bold, textDecorationLine: 'underline' }
});
