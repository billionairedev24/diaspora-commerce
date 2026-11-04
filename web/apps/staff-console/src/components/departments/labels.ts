import { type DepartmentRequestStatus } from '@afrotransact/api-client';

import { type MessageKey } from '@/i18n/t';

export const STATUS_LABEL: Record<DepartmentRequestStatus, MessageKey> = {
  PENDING: 'st.depts.status.PENDING',
  APPROVED: 'st.depts.status.APPROVED',
  REJECTED: 'st.depts.status.REJECTED'
};
export const STATUS_TONE = { PENDING: 'warn', APPROVED: 'good', REJECTED: 'bad' } as const;

export const PROMPT_LABEL: Record<'FOOD_PERMIT' | 'BEAUTY_LABELLING', MessageKey> = {
  FOOD_PERMIT: 'st.depts.prompt.FOOD_PERMIT',
  BEAUTY_LABELLING: 'st.depts.prompt.BEAUTY_LABELLING'
};
