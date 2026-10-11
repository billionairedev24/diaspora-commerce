import { type HelpTopicKey, isHelpTopicKey } from '@afrotransact/api-client';

/** Help articles per page of the help center list (the next page loads as the shopper scrolls). */
export const HELP_PAGE_SIZE = 12;

/** The topic in the URL (?topic=SELLING, any case), or undefined for every topic. */
export function helpTopicOf(raw: string | null | undefined): HelpTopicKey | undefined {
  const v = (raw ?? '').toUpperCase();
  return isHelpTopicKey(v) ? v : undefined;
}
