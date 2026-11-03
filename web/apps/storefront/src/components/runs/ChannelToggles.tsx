'use client';

import { RUN_CHANNELS, type RunChannel, type RunConsent, type RunSubscription } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { cx } from '@afrotransact/ui';

import { type MessageKey, useT } from '@/i18n/t';

import styles from './Runs.module.css';

const LABEL: Record<RunChannel, MessageKey> = {
  EMAIL: 'runs.channel.EMAIL',
  SMS: 'runs.channel.SMS',
  PUSH: 'runs.channel.PUSH',
  WHATSAPP: 'runs.channel.WHATSAPP'
};
const HINT: Record<RunChannel, MessageKey> = {
  EMAIL: 'runs.channel.EMAIL.hint',
  SMS: 'runs.channel.SMS.hint',
  PUSH: 'runs.channel.PUSH.hint',
  WHATSAPP: 'runs.channel.WHATSAPP.hint'
};

const consentOf = (sub: RunSubscription | undefined, channel: RunChannel): RunConsent | undefined => sub?.consents.find((c) => c.channel === channel);

/**
 * One checkbox per way to hear about a location's delivery runs. Each is its own consent: turning one on records it
 * (when, and where it was given), turning one off records the withdrawal. SMS and WhatsApp need a verified mobile
 * number; WhatsApp messages are only sent from approved templates to shoppers who opted in here.
 */
export function ChannelToggles({
  locationName,
  subscription,
  pending,
  onToggle
}: {
  locationName: string;
  subscription: RunSubscription | undefined;
  /** The channel being saved right now (its checkbox waits). */
  pending: RunChannel | null;
  onToggle: (channel: RunChannel, on: boolean) => void;
}) {
  const t = useT();
  const fmt = useFormatters();
  return (
    <fieldset className={styles.channels} data-testid={`channels-${locationName}`}>
      <legend className="at-visually-hidden">{t('runs.channels.legend', { place: locationName })}</legend>
      {RUN_CHANNELS.map((channel) => {
        const c = consentOf(subscription, channel);
        const on = c?.granted === true;
        return (
          <div key={channel} className={cx(styles.channel, on && styles.channelOn)}>
            <label className={styles.channelRow}>
              <input type="checkbox" checked={on} disabled={pending === channel} onChange={(e) => onToggle(channel, e.currentTarget.checked)} />
              {t(LABEL[channel])}
            </label>
            <p className={styles.hint}>{t(HINT[channel])}</p>
            {on && c?.consentedAt ? <p className={styles.record}>{t('runs.consent.on', { at: fmt.date(c.consentedAt, 'medium') })}</p> : null}
            {!on && c?.withdrawnAt ? <p className={styles.record}>{t('runs.consent.off', { at: fmt.date(c.withdrawnAt, 'medium') })}</p> : null}
          </div>
        );
      })}
    </fieldset>
  );
}
