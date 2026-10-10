import { type StopStatus } from '@/domain/types';
import { c } from '@/theme';

export const STOP_LABEL: Record<StopStatus, string> = { PENDING: 'Next', ARRIVED: 'On site', DONE: 'Done', FAILED: 'Failed', RETRY: 'Retry later', SKIPPED: 'Skipped', REASSIGNED: 'Reassigned', CANCELLED: 'Cancelled' };
export const STOP_COLOR: Record<StopStatus, string> = { PENDING: c.textMuted, ARRIVED: c.info, DONE: c.success, FAILED: c.danger, RETRY: c.warning, SKIPPED: c.warning, REASSIGNED: c.textMuted, CANCELLED: c.textMuted };
