'use client';

import { WhatsAppChatButton } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { usePathname } from 'next/navigation';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { siteQueries } from '@/lib/api/queries';

const ORDER_PATH = /^\/orders\/(AT-[A-Z0-9-]+)/i;

/** "Chat with us on WhatsApp": a quick chat with support, shown once the support phone is set in Staff › Site settings. */
export function SupportChat() {
  const t = useT();
  const pathname = usePathname();
  const { data } = useQuery(siteQueries.config(browserApi()));
  const phone = data?.contacts.phoneE164 ?? '';
  const order = ORDER_PATH.exec(pathname)?.[1];
  if (!phone) return null;
  return <WhatsAppChatButton phone={phone} label={t('chat.whatsapp')} newTabLabel={t('footer.newTab')} message={order ? t('chat.whatsappOrderMessage', { order: order.toUpperCase() }) : t('chat.whatsappMessage')} />;
}
