'use client';

import { type AlertChannel, type AlertPreference, type AlertPreferences, type AlertTopic, type NotificationPreferences, type QuietHours, settingsKeys, settingsQueries } from '@afrotransact/api-client';
import { Button, useToast } from '@afrotransact/ui';
import { BellRinging, ChatText, DeviceMobile, EnvelopeSimple, MegaphoneSimple } from '@phosphor-icons/react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ReactNode, useMemo, useState } from 'react';

import { useAccountEnv } from './context';
import { type AccountMessageKey, useAccountT } from './i18n/t';
import { Card, errorText, LoadError, Loading, PageHead, Row, styles, Switch } from './parts';

const CHANNELS: AlertChannel[] = ['email', 'sms'];
const keyOf = (topic: AlertTopic, channel: AlertChannel) => `${topic}/${channel}`;

/** The alert matrix as edited on screen: only changed switches are sent. */
export function alertChanges(server: AlertPreference[], draft: Map<string, boolean>): Array<{ topic: AlertTopic; channel: AlertChannel; enabled: boolean }> {
  return server.filter((p) => !p.locked && draft.has(keyOf(p.topic, p.channel)) && draft.get(keyOf(p.topic, p.channel)) !== p.enabled)
    .map((p) => ({ topic: p.topic, channel: p.channel, enabled: draft.get(keyOf(p.topic, p.channel)) ?? p.enabled }));
}

function AlertsForm({ data }: { data: AlertPreferences }) {
  const t = useAccountT();
  const { api } = useAccountEnv();
  const qc = useQueryClient();
  const toast = useToast();
  const [draft, setDraft] = useState(() => new Map<string, boolean>());
  const [quiet, setQuiet] = useState<QuietHours>(data.quietHours);
  const [error, setError] = useState<string | null>(null);
  const topics = useMemo(() => [...new Set(data.preferences.map((p) => p.topic))], [data.preferences]);
  const pref = (topic: AlertTopic, channel: AlertChannel) => data.preferences.find((p) => p.topic === topic && p.channel === channel);
  const value = (topic: AlertTopic, channel: AlertChannel) => draft.get(keyOf(topic, channel)) ?? pref(topic, channel)?.enabled ?? true;
  const changes = alertChanges(data.preferences, draft);
  const quietChanged = quiet.enabled !== data.quietHours.enabled || quiet.start !== data.quietHours.start || quiet.end !== data.quietHours.end;
  const save = useMutation({
    mutationFn: () => api.saveAlertPreferences({ preferences: changes, ...(quietChanged ? { quietHours: quiet } : {}) }),
    onSuccess: (saved) => {
      qc.setQueryData(settingsKeys.alerts(), saved);
      setDraft(new Map());
      setError(null);
      toast.show(t('notify.saved'));
    },
    onError: (e) => setError(errorText(t, e))
  });
  const label = (topic: AlertTopic) => t(`notify.topic.${topic}` as AccountMessageKey);
  return (
    <>
      <Card id="alerts" testId="alerts" title={t('notify.alerts')} lead={t('notify.alertsLead')}>
        <table className={styles.matrix}>
          <thead>
            <tr>
              <th scope="col">{t('notify.topic')}</th>
              <th scope="col" className={styles.chan}>
                {t('notify.email')}
              </th>
              <th scope="col" className={styles.chan}>
                {t('notify.sms')}
              </th>
            </tr>
          </thead>
          <tbody>
            {topics.map((topic) => (
              <tr key={topic} data-testid={`alert-${topic}`}>
                <th scope="row" style={{ fontWeight: 'normal' }}>
                  <span className={styles.rowText}>
                    <span className={styles.rowTitle}>{label(topic)}</span>
                    <span className={styles.rowSub}>{t(`notify.topic.${topic}.sub` as AccountMessageKey)}</span>
                  </span>
                </th>
                {CHANNELS.map((channel) => {
                  const p = pref(topic, channel);
                  return (
                    <td key={channel} className={styles.chan}>
                      {p?.locked ? (
                        <span className={styles.always} title={t('notify.alwaysHint')}>
                          {t('notify.always')}
                        </span>
                      ) : (
                        <Switch
                          checked={value(topic, channel)}
                          label={t('notify.toggle', { topic: label(topic), channel: channel === 'email' ? t('notify.email') : t('notify.sms') })}
                          onChange={(v) => setDraft((d) => new Map(d).set(keyOf(topic, channel), v))}
                        />
                      )}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
      <Card id="quiet" testId="quiet-hours" title={t('notify.quiet')} lead={t('notify.quietLead')}>
        <div className={styles.cardBody}>
          <div className={styles.switchRow}>
            <span className={styles.rowTitle}>{t('notify.quietOn')}</span>
            <Switch checked={quiet.enabled} label={t('notify.quietOn')} onChange={(v) => setQuiet((q) => ({ ...q, enabled: v }))} />
          </div>
          <div className={styles.times}>
            <label>
              {t('notify.quietFrom')}
              <input type="time" value={quiet.start} disabled={!quiet.enabled} onChange={(e) => setQuiet((q) => ({ ...q, start: e.target.value }))} />
            </label>
            <label>
              {t('notify.quietTo')}
              <input type="time" value={quiet.end} disabled={!quiet.enabled} onChange={(e) => setQuiet((q) => ({ ...q, end: e.target.value }))} />
            </label>
          </div>
        </div>
      </Card>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <div className={`${styles.actions} ${styles.actionsEnd}`}>
        <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!changes.length && !quietChanged}>
          {t('common.save')}
        </Button>
      </div>
    </>
  );
}

/** Back-office notifications: the role's alert topics per channel and quiet hours (notifications service). */
export function AlertsScreen({ heading = true }: { heading?: boolean }) {
  const t = useAccountT();
  const { api } = useAccountEnv();
  const q = useQuery(settingsQueries.alerts(api));
  return (
    <div className={styles.page} data-testid="notifications-screen">
      {heading ? <PageHead title={t('notify.title')} lead={t('notify.lead')} /> : null}
      {q.isPending ? <Loading height={320} /> : null}
      {q.isError ? (
        <div className={styles.card}>
          <LoadError onRetry={() => void q.refetch()} />
        </div>
      ) : null}
      {q.data ? <AlertsForm key={JSON.stringify(q.data)} data={q.data} /> : null}
    </div>
  );
}

/**
 * Storefront notifications: the account's channels (order and delivery updates) and marketing consent, with the
 * privacy links next to the opt-in.
 */
export function CustomerNotificationsScreen({ links, heading = true }: { links: { privacyChoices: string; smsTerms: string; privacy: string }; heading?: boolean }) {
  const t = useAccountT();
  const { api, Link } = useAccountEnv();
  const qc = useQueryClient();
  const toast = useToast();
  const q = useQuery(settingsQueries.notifications(api));
  const save = useMutation({
    mutationFn: (p: NotificationPreferences) => api.saveNotificationPreferences(p),
    onSuccess: (p) => { qc.setQueryData(settingsKeys.notifications(), p); toast.show(t('contact.saved')); },
    onError: (e) => toast.show(errorText(t, e), 'error')
  });
  const p = q.data;
  const toggle = (k: keyof NotificationPreferences, v: boolean) => p && save.mutate({ ...p, [k]: v });
  const rows: Array<{ k: keyof NotificationPreferences; icon: ReactNode; title: AccountMessageKey; sub: AccountMessageKey }> = [
    { k: 'email', icon: <EnvelopeSimple />, title: 'contact.email', sub: 'contact.emailSub' },
    { k: 'sms', icon: <ChatText />, title: 'contact.sms', sub: 'contact.smsSub' },
    { k: 'push', icon: <DeviceMobile />, title: 'contact.push', sub: 'contact.pushSub' }
  ];
  return (
    <div className={styles.page} data-testid="notifications-screen">
      {heading ? <PageHead title={t('notify.title')} lead={t('notify.lead')} /> : null}
      {q.isPending ? <Loading height={220} /> : null}
      {q.isError ? (
        <div className={styles.card}>
          <LoadError onRetry={() => void q.refetch()} />
        </div>
      ) : null}
      {p ? (
        <>
          <Card id="contact" title={t('contact.title')} lead={t('contact.lead')} badge={<BellRinging aria-hidden />}>
            <ul className={styles.list}>
              {rows.map((r) => (
                <Row key={r.k} icon={r.icon} title={t(r.title)} sub={t(r.sub)} actions={<Switch checked={p[r.k]} label={t(r.title)} disabled={save.isPending} onChange={(v) => toggle(r.k, v)} />} />
              ))}
            </ul>
          </Card>
          <Card
            id="marketing"
            testId="marketing"
            title={t('marketing.title')}
            lead={t('marketing.lead')}
            note={
              <>
                {t('marketing.consent')} <Link href={links.privacyChoices}>{t('marketing.choices')}</Link> · <Link href={links.smsTerms}>{t('marketing.smsTerms')}</Link> ·{' '}
                <Link href={links.privacy}>{t('marketing.privacy')}</Link>
              </>
            }
          >
            <ul className={styles.list}>
              <Row icon={<MegaphoneSimple />} title={t('marketing.optIn')} actions={<Switch checked={p.marketingOptIn} label={t('marketing.optIn')} disabled={save.isPending} onChange={(v) => toggle('marketingOptIn', v)} />} />
            </ul>
          </Card>
        </>
      ) : null}
    </div>
  );
}
