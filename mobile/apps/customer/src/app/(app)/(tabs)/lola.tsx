/* Lola chat (design: LOLA): pinned header, messages scroll, composer + suggestion chips pinned above the tab bar. */
import { formatMoney } from '@afrotransact/api-client';
import { useRouter } from 'expo-router';
import { NotePencilIcon as NotePencil, PaperPlaneRightIcon as PaperPlaneRight, SparkleIcon as Sparkle } from 'phosphor-react-native';
import { useRef, useState } from 'react';
import { FlatList, KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { useSearch } from '@/api/queries';
import { useAuth } from '@/auth/AuthProvider';
import { LOLA_PROMPTS, lolaGreeting, type LolaReply,lolaReply } from '@/domain/lola';
import { useQuantity } from '@/features/shop/cartHooks';
import { c, sp, t } from '@/theme';
import { AppText, Chip, IconButton } from '@/ui/primitives';
import { Screen } from '@/ui/Screen';

type Msg = { id: number; from: 'me' | 'lola' } & LolaReply;

function CardAdd({ atin, offerId }: { atin: string; offerId: string }) {
  const { qty, setQty } = useQuantity(atin, offerId);
  return <Chip label={qty ? 'In cart' : 'Add'} selected={qty > 0} onPress={() => (qty ? undefined : setQty(1))} />;
}

export default function Lola() {
  const router = useRouter();
  const { user } = useAuth();
  const catalogue = useSearch('', 60);
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [draft, setDraft] = useState('');
  const [typing, setTyping] = useState(false);
  const list = useRef<FlatList<Msg>>(null);
  const all: Msg[] = [{ id: 0, from: 'lola', text: lolaGreeting(user?.first ?? 'there') }, ...msgs];

  const send = (text: string) => {
    const v = text.trim();
    if (!v || typing) return;
    setMsgs((m) => [...m, { id: Date.now(), from: 'me', text: v }]);
    setDraft('');
    setTyping(true);
    setTimeout(() => {
      setMsgs((m) => [...m, { id: Date.now() + 1, from: 'lola', ...lolaReply(v) }]);
      setTyping(false);
      list.current?.scrollToEnd({ animated: true });
    }, 700);
  };

  const header = (
    <View style={styles.header}>
      <View style={styles.avatar}><Sparkle size={18} weight="fill" color={c.badgeAiText} /></View>
      <View style={{ flex: 1 }}>
        <AppText variant="bodyStrong">Lola</AppText>
        <AppText variant="caption" color={c.success}>● Online · shopping assistant</AppText>
      </View>
      <IconButton icon={NotePencil} label="New chat" onPress={() => setMsgs([])} />
    </View>
  );

  const footer = (
    <View style={{ gap: sp['2'] }}>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: sp['2'] }}>
        {LOLA_PROMPTS.map((p) => <Chip key={p} label={p} onPress={() => send(p)} />)}
      </ScrollView>
      <View style={styles.composer}>
        <TextInput value={draft} onChangeText={setDraft} onSubmitEditing={() => send(draft)} placeholder="Message Lola" placeholderTextColor={c.textSubtle} accessibilityLabel="Message Lola" returnKeyType="send" style={styles.input} />
        <Pressable accessibilityRole="button" accessibilityLabel="Send" onPress={() => send(draft)} style={[styles.send, { backgroundColor: draft.trim() ? c.actionPrimary : t.ink['100'] }]}>
          <PaperPlaneRight size={18} weight="fill" color={c.text} />
        </Pressable>
      </View>
    </View>
  );

  return (
    <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <Screen inTabs header={header} footer={footer} body="fixed">
        <FlatList
          ref={list}
          data={all}
          keyExtractor={(m) => String(m.id)}
          contentContainerStyle={{ padding: sp['4'], gap: sp['3'] }}
          showsVerticalScrollIndicator={false}
          onContentSizeChange={() => list.current?.scrollToEnd({ animated: false })}
          ListFooterComponent={typing ? <AppText variant="small" color={c.textMuted}>Lola is typing…</AppText> : null}
          renderItem={({ item: m }) => (
            <View style={{ alignItems: m.from === 'me' ? 'flex-end' : 'flex-start', gap: sp['2'] }}>
              <View style={[styles.bubble, m.from === 'me' ? styles.me : styles.them]}>
                <AppText color={m.from === 'me' ? c.textInverse : c.text}>{m.text}</AppText>
              </View>
              {m.atins?.map((atin) => {
                const p = catalogue.data?.hits.find((x) => x.atin === atin);
                if (!p) return null;
                return (
                  <View key={atin} style={styles.card}>
                    <AppText variant="small" style={{ flex: 1 }} onPress={() => router.push(`/product/${atin}`)}>{p.title}{'\n'}<AppText variant="small" style={{ fontWeight: t.fontWeight.bold }}>{formatMoney(p.priceMinor, p.currency)}</AppText></AppText>
                    <CardAdd atin={atin} offerId={p.offerId} />
                  </View>
                );
              })}
              {m.action ? <Chip label={m.action.label} onPress={() => router.push(m.action?.href ?? '/')} /> : null}
            </View>
          )}
        />
      </Screen>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], paddingHorizontal: sp['4'], paddingVertical: sp['2'], borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: c.border, backgroundColor: c.canvas },
  avatar: { width: 36, height: 36, borderRadius: 18, backgroundColor: c.badgeAiBg, alignItems: 'center', justifyContent: 'center' },
  bubble: { maxWidth: '85%', paddingVertical: 10, paddingHorizontal: 14 },
  me: { backgroundColor: t.ink['1000'], borderRadius: 18, borderBottomRightRadius: 4 },
  them: { backgroundColor: c.surfaceMuted, borderRadius: 18, borderBottomLeftRadius: 4 },
  card: { flexDirection: 'row', alignItems: 'center', gap: sp['2'], width: '85%', padding: sp['3'], borderRadius: t.radius.md, borderWidth: 1, borderColor: c.border },
  composer: { flexDirection: 'row', alignItems: 'center', gap: sp['2'] },
  input: { flex: 1, height: t.size.controlLg, borderRadius: t.radius.pill, paddingHorizontal: sp['4'], backgroundColor: c.surfaceMuted, fontSize: t.fontSize.base, color: c.text },
  send: { width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center' }
});
