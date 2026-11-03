import { type Translator } from '@afrotransact/i18n';

import { type MessageKey } from '@/i18n/t';
import { type MaintenanceStatus } from '@/lib/maintenance';

import { type MaintenanceScreenProps } from './MaintenanceScreen';

const FALLBACK_TITLE = 'We’ll be right back';

/** The maintenance screen's props for the server-side status (an expected time that has passed counts as unknown). */
export function maintenanceProps(m: MaintenanceStatus, locale: string, t: Translator<MessageKey>): MaintenanceScreenProps {
  const back = m.expectedBackAt && new Date(m.expectedBackAt).getTime() > Date.now() ? m.expectedBackAt : undefined;
  return {
    title: m.title || FALLBACK_TITLE,
    message: m.message ?? '',
    expectedBackAt: back,
    expectedTemplate: t('maintenance.expected', { time: '{time}' }),
    workingLabel: t('maintenance.working'),
    checkLabel: t('maintenance.check'),
    locale
  };
}
