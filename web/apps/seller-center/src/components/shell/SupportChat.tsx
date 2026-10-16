'use client';

import { createApiClient } from '@afrotransact/api-client';
import { WhatsAppChatButton } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';

import { useT } from '@/i18n/t';
import { BFF_BASE_PATH } from '@/lib/config';

const api = createApiClient({ baseUrl: BFF_BASE_PATH });

/** "Chat with AfroTransact on WhatsApp": a quick chat with support, shown once the support phone is set in Staff › Site settings. */
export function SupportChat({ orgName }: { orgName?: string }) {
  const t = useT();
  const { data } = useQuery({ queryKey: ['support-contact'], queryFn: ({ signal }) => api.getSiteConfig(signal), staleTime: 10 * 60_000 });
  const phone = data?.contacts.phoneE164 ?? '';
  if (!phone) return null;
  return <WhatsAppChatButton phone={phone} label={t('sc.chat.whatsapp')} newTabLabel={t('sc.chat.newTab')} message={orgName ? t('sc.chat.message', { org: orgName }) : t('sc.chat.messageNoOrg')} />;
}
