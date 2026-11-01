/* Under the sign-in form: "New to AfroTransact? Create your account" and the legal line. Creating an account and the
   legal pages open identity's hosted page and the web app, as before. */
import { Pressable, StyleSheet, View } from 'react-native';

import { type SignInIntent } from '@/auth/oidc';
import { env } from '@/config/env';
import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { AppText } from '@/ui/primitives';

import { openWeb } from './openWeb';


export function SignInFooter({ busy, onRun }: { busy: string | null; onRun: (key: string, intent: SignInIntent) => void }) {
  const tr = useT();
  return (
    <View style={{ gap: sp['4'] }}>
      <View style={styles.create}>
        <AppText variant="small" color={c.textMuted}>{tr('cu.signIn.newToAfrotransact')}</AppText>
        <Pressable
          accessibilityRole="link"
          accessibilityState={{ disabled: !!busy, busy: busy === 'signup' }}
          onPress={busy ? undefined : () => onRun('signup', { kind: 'sign-up' })}
          hitSlop={8}
          style={styles.link}
        >
          <AppText variant="small" style={styles.linkText}>{tr('cu.signIn.createYourAccount')}</AppText>
        </Pressable>
      </View>
      <AppText variant="caption" color={c.textMuted} style={{ textAlign: 'center' }}>
        {tr('cu.signIn.agreePrefix')}{' '}
        <AppText variant="caption" accessibilityRole="link" style={styles.linkText} onPress={() => openWeb('/legal/conditions-of-use')}>{tr('cu.signIn.conditionsOfUse')}</AppText>{' '}
        {tr('cu.signIn.and')}{' '}
        <AppText variant="caption" accessibilityRole="link" style={styles.linkText} onPress={() => openWeb('/legal/privacy')}>{tr('cu.signIn.privacyNotice')}</AppText>.
      </AppText>
      {env.mocks ? <AppText variant="caption" color={c.textSubtle} style={{ textAlign: 'center' }}>{tr('cu.signIn.devMocksAreOnSign')}</AppText> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  create: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', alignItems: 'center', gap: sp['1'] },
  link: { minHeight: t.size.touch, justifyContent: 'center' },
  linkText: { fontWeight: t.fontWeight.semibold, textDecorationLine: 'underline' }
});
