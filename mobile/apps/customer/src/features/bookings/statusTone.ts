import { c, t } from '@/theme';

/** Badge colours per bookings-service status. */
export function statusTone(status: string | undefined): { fg: string; bg: string } {
  const s = (status ?? '').toUpperCase();
  if (s === 'CONFIRMED' || s === 'COMPLETED') return { fg: c.success, bg: c.successBg };
  if (s.startsWith('RESCHEDULE') || s === 'REQUESTED' || s === 'PENDING_PAYMENT') return { fg: c.warning, bg: c.warningBg };
  if (s.startsWith('CANCELLED') || s === 'DECLINED' || s === 'NO_SHOW') return { fg: c.danger, bg: c.dangerBg };
  return { fg: c.textMuted, bg: t.ink['100'] };
}
