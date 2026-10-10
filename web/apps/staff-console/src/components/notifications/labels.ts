import { type NotificationCategory, type NotificationChannel, type NotificationSample, type RecordTone } from '@afrotransact/api-client';

import { type MessageKey } from '@/i18n/t';

export const CHANNEL_LABEL: Record<NotificationChannel, MessageKey> = {
  email: 'st.ntf.channel.email',
  sms: 'st.ntf.channel.sms',
  push: 'st.ntf.channel.push',
  chat: 'st.ntf.channel.chat'
};

export const CATEGORY_LABEL: Record<NotificationCategory, MessageKey> = {
  security: 'st.ntf.category.security',
  transactional: 'st.ntf.category.transactional',
  reminder: 'st.ntf.category.reminder',
  marketing: 'st.ntf.category.marketing'
};

export const CATEGORY_TONE: Record<NotificationCategory, RecordTone> = { security: 'brand', transactional: 'info', reminder: 'good', marketing: 'warn' };

export const AREA_LABEL: Record<NotificationSample['area'], MessageKey> = {
  account: 'st.ntf.area.account',
  orders: 'st.ntf.area.orders',
  seller: 'st.ntf.area.seller',
  payouts: 'st.ntf.area.payouts',
  bookings: 'st.ntf.area.bookings',
  trust: 'st.ntf.area.trust',
  retail: 'st.ntf.area.retail',
  marketing: 'st.ntf.area.marketing',
  security: 'st.ntf.area.security',
  other: 'st.ntf.area.other'
};

export const SOURCE_LABEL: Record<NotificationSample['source'], MessageKey> = {
  event: 'st.ntf.source.event',
  request: 'st.ntf.source.request',
  sample: 'st.ntf.source.sample',
  none: 'st.ntf.source.none'
};

/** Label of the subject field per channel (email subject, push title, WhatsApp approved template name). */
export const SUBJECT_LABEL: Record<NotificationChannel, MessageKey> = {
  email: 'st.ntf.field.subject',
  sms: 'st.ntf.field.subject',
  push: 'st.ntf.field.title',
  chat: 'st.ntf.field.waTemplate'
};

/** Label of the text field per channel. */
export const TEXT_LABEL: Record<NotificationChannel, MessageKey> = {
  email: 'st.ntf.field.textPart',
  sms: 'st.ntf.field.message',
  push: 'st.ntf.field.body',
  chat: 'st.ntf.field.waParams'
};

export const variantId = (v: { locale: string; channel: NotificationChannel }) => `${v.locale}:${v.channel}`;
