'use client';

import { isLocale, localeCookie } from '@afrotransact/i18n';
import { useRouter } from 'next/navigation';

/** Switches the language: remembers it in the locale cookie and re-renders the server components in it (footer picker, All menu). */
export function useChangeLocale(): (code: string) => void {
  const router = useRouter();
  return (code) => {
    if (!isLocale(code)) return;
    document.cookie = localeCookie(code);
    router.refresh();
  };
}
