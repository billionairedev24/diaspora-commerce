import * as WebBrowser from 'expo-web-browser';

import { env } from '@/config/env';

/** Opens a page of the web app (legal pages, password reset on identity's page) in the in-app browser sheet. */
export function openWeb(path: string): void {
  void WebBrowser.openBrowserAsync(`${env.webUrl}${path}`);
}
