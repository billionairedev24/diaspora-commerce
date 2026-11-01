/* "or continue with" Google / Apple / Facebook, then the passkey button. Each provider button follows the provider's
   rules for a neutral, outlined button: its own mark plus its name, nothing recoloured. These open identity's hosted
   page (OIDC), exactly as before. Only the pressed control shows busy; the others are disabled. */
import { FingerprintIcon as Fingerprint } from 'phosphor-react-native';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';

import { type SignInIntent } from '@/auth/oidc';
import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { AppText, Button } from '@/ui/primitives';
import { type ProviderId,ProviderMark } from '@/ui/ProviderMarks';

const PROVIDERS: Array<{ id: ProviderId; name: string }> = [
  { id: 'google', name: 'Google' },
  { id: 'apple', name: 'Apple' },
  { id: 'facebook', name: 'Facebook' }
];

export function ProviderButtons({ busy, onRun }: { busy: string | null; onRun: (key: string, intent: SignInIntent) => void }) {
  const tr = useT();
  return (
    <View style={{ gap: sp['4'] }}>
      <View style={styles.divider} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
        <View style={styles.rule} />
        <AppText variant="small" color={c.textMuted}>{tr('cu.signIn.orContinueWith')}</AppText>
        <View style={styles.rule} />
      </View>
      <View style={styles.providers}>
        {PROVIDERS.map((p) => {
          const loading = busy === p.id;
          const off = !!busy;
          return (
            <Pressable
              key={p.id}
              accessibilityRole="button"
              accessibilityLabel={tr('cu.signIn.continueWith', { provider: p.name })}
              accessibilityState={{ disabled: off, busy: loading }}
              onPress={off ? undefined : () => onRun(p.id, { kind: 'provider', provider: p.id })}
              style={({ pressed }) => [styles.provider, { opacity: off && !loading ? 0.5 : pressed ? 0.85 : 1 }]}
            >
              {loading ? <ActivityIndicator color={c.text} /> : <ProviderMark provider={p.id} />}
              <AppText variant="small" numberOfLines={1} style={{ fontWeight: t.fontWeight.semibold }}>{p.name}</AppText>
            </Pressable>
          );
        })}
      </View>
      <Button kind="outline" icon={Fingerprint} label={tr('cu.signIn.signInWithAPasskey')} onPress={() => onRun('passkey', { kind: 'sign-in' })} loading={busy === 'passkey'} disabled={!!busy && busy !== 'passkey'} />
    </View>
  );
}

const styles = StyleSheet.create({
  divider: { flexDirection: 'row', alignItems: 'center', gap: sp['3'] },
  rule: { flex: 1, height: StyleSheet.hairlineWidth, backgroundColor: c.borderStrong },
  providers: { flexDirection: 'row', gap: sp['2'] },
  provider: { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: sp['2'], minHeight: t.size.controlLg + 4, paddingHorizontal: sp['2'], borderWidth: 1, borderColor: c.borderStrong, borderRadius: t.radius.pill, backgroundColor: c.surface }
});
