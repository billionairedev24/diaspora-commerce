/* Profile & sync (design: SYNC / PROFILE): who you are, the outbox and refused actions, an offline switch, sign out. */
import { localeInfo } from '@afrotransact/i18n';
import { CheckCircleIcon as CheckCircle, ClockIcon as Clock, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useState } from 'react';
import { StyleSheet, Switch, View } from 'react-native';

import { mockRevokeAccess } from '@/api/mock/server';
import { EnvRibbon } from '@/brand/EnvRibbon';
import { env } from '@/config/env';
import { type MessageKey, type Translator, useFormatters, useLocale, useT } from '@/i18n';
import { EN_MESSAGES } from '@/i18n/catalogs';
import { useDriver } from '@/session/DriverProvider';
import { c, sp, t } from '@/theme';
import { DriverScreen } from '@/ui/DriverScreen';
import { LanguageSheet } from '@/ui/LanguageSheet';
import { AppText, Button, Card, SectionLabel } from '@/ui/primitives';

/** Outbox event type → its label key (unknown types show as sent). */
function eventLabel(tr: Translator<MessageKey>, type: string): string {
  const key = `dr.event.${type.replace('.', '_')}`;
  return key in EN_MESSAGES ? tr(key as MessageKey) : type;
}

export default function Profile() {
  const fmt = useFormatters();
  const tr = useT();
  const { user, sync, engine, paused, setPaused, signOut } = useDriver();
  const out = sync.outbox;
  const [language, setLanguage] = useState(false);
  const locale = useLocale();
  return (
    <DriverScreen title={tr('dr.profile.profileSync')} inTabs>
      <Card>
        <AppText variant="heading">{user?.name}</AppText>
        <AppText variant="small" color={c.textMuted}>{user?.email}</AppText>
        <AppText variant="caption" color={c.textMuted}>{tr('dr.profile.permissions', { list: user?.perms.join(', ') || '—' })}</AppText>
      </Card>
      <View style={styles.row}>
        <View style={{ flex: 1 }}>
          <AppText variant="bodyStrong">{tr('dr.profile.online')}</AppText>
          <AppText variant="small" color={c.textMuted}>{paused ? tr('dr.profile.paused') : sync.online ? tr('dr.profile.sending') : tr('dr.profile.noSignal')}</AppText>
        </View>
        <Switch accessibilityLabel="Online" value={!paused} onValueChange={(v) => setPaused(!v)} trackColor={{ true: c.success, false: t.ink['200'] }} thumbColor={c.surface} />
      </View>
      {out.issues.length ? (
        <View style={{ gap: sp['2'] }}>
          <SectionLabel>{tr('dr.profile.refusedByDispatch')}</SectionLabel>
          {out.issues.map((i) => (
            <Card key={`${i.eventId}-${i.sequence}`} style={{ borderColor: c.danger }}>
              <View style={styles.row}>
                <WarningCircle size={20} color={c.danger} />
                <AppText variant="bodyStrong" style={{ flex: 1 }}>{eventLabel(tr, i.type)} · {i.outcome === 'CONFLICT' ? tr('dr.profile.conflict') : i.outcome === 'GAP' ? tr('dr.profile.missingEvent') : tr('dr.profile.rejected')}</AppText>
              </View>
              <AppText variant="small">{i.message}</AppText>
              <AppText variant="caption" color={c.textMuted}>{i.code ?? ''} · #{i.sequence}</AppText>
              <Button size="md" kind="ghost" label={tr('dr.profile.dismiss')} onPress={() => engine.dismissIssue(i.eventId)} />
            </Card>
          ))}
        </View>
      ) : null}
      <View style={{ gap: sp['2'] }}>
        <View style={styles.between}>
          <SectionLabel>{tr('dr.profile.outbox')}</SectionLabel>
          <AppText variant="caption" color={c.textMuted}>{out.lastSyncAt ? tr('dr.profile.lastSync', { time: fmt.time(out.lastSyncAt) }) : tr('dr.profile.neverSynced')}</AppText>
        </View>
        {out.events.length === 0 ? (
          <View style={styles.row}><CheckCircle size={20} weight="fill" color={c.success} /><AppText>{tr('dr.profile.everythingIsSynced')}</AppText></View>
        ) : out.events.map((e) => (
          <View key={e.eventId} style={styles.row}>
            <Clock size={18} color={c.warning} />
            <View style={{ flex: 1 }}>
              <AppText variant="small" style={{ fontWeight: t.fontWeight.semibold }}>{eventLabel(tr, e.type)}</AppText>
              <AppText variant="caption" color={c.textMuted}>#{e.sequence} · {fmt.time(e.capturedAt)} · {e.eventId.slice(0, 8)}</AppText>
            </View>
            <AppText variant="caption" color={c.warning}>{tr('dr.profile.queued')}</AppText>
          </View>
        ))}
        {sync.lastError ? <AppText variant="small" color={c.danger}>{sync.lastError}</AppText> : null}
        <Button kind="outline" label={sync.syncing ? tr('dr.profile.syncing') : tr('dr.profile.syncNow')} disabled={!sync.online || sync.syncing} onPress={() => void engine.flush()} />
      </View>
      {env.mocks && user ? <Button kind="ghost" label={tr('dr.profile.devSimulateAccessRevoked')} onPress={() => { mockRevokeAccess(user.sub); void engine.record({ type: 'session.signed_in' }); }} /> : null}
      <Button kind="outline" label={`${tr('dr.profile.language')} · ${localeInfo(locale).name}`} onPress={() => setLanguage(true)} />
      <LanguageSheet visible={language} onClose={() => setLanguage(false)} title={tr('dr.profile.chooseLanguage')} rtlNote={tr('dr.profile.rtlRestart')} />
      <Button kind="danger" label={tr('dr.profile.signOut')} onPress={() => void signOut()} />
      <EnvRibbon />
      <AppText variant="caption" color={c.textMuted} style={{ textAlign: 'center' }}>{tr('dr.profile.device', { id: out.deviceId.slice(0, 12), env: env.appEnv })}</AppText>
    </DriverScreen>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: sp['3'] },
  between: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }
});
