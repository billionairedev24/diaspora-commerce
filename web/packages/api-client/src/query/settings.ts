import { queryOptions } from '@tanstack/react-query';

import { type SettingsApiClient } from '../settings-client';
import { accountKeys } from './keys';

/* Key factories and queryOptions for the account settings pages (all three apps). They hang under accountKeys.all, so
   signing out drops them with every other personal query. */

const MINUTE = 60_000;

export const settingsKeys = {
  all: [...accountKeys.all, 'settings'] as const,
  profile: () => [...settingsKeys.all, 'profile'] as const,
  security: () => [...settingsKeys.all, 'security'] as const,
  activity: () => [...settingsKeys.all, 'sign-in-activity'] as const,
  notifications: () => [...settingsKeys.all, 'notification-preferences'] as const,
  alerts: () => [...settingsKeys.all, 'alerts'] as const,
  preferences: () => [...settingsKeys.all, 'preferences'] as const
};

export const settingsQueries = {
  profile: (api: SettingsApiClient) => queryOptions({ queryKey: settingsKeys.profile(), queryFn: ({ signal }) => api.getAccountProfile(signal), staleTime: MINUTE }),
  security: (api: SettingsApiClient) => queryOptions({ queryKey: settingsKeys.security(), queryFn: ({ signal }) => api.getSecurity(signal), staleTime: 30_000 }),
  activity: (api: SettingsApiClient) => queryOptions({ queryKey: settingsKeys.activity(), queryFn: ({ signal }) => api.getSignInActivity(signal), staleTime: 30_000 }),
  notifications: (api: SettingsApiClient) =>
    queryOptions({ queryKey: settingsKeys.notifications(), queryFn: ({ signal }) => api.getNotificationPreferences(signal), staleTime: MINUTE }),
  alerts: (api: SettingsApiClient) => queryOptions({ queryKey: settingsKeys.alerts(), queryFn: ({ signal }) => api.getAlertPreferences(signal), staleTime: MINUTE }),
  preferences: (api: SettingsApiClient) =>
    queryOptions({ queryKey: settingsKeys.preferences(), queryFn: ({ signal }) => api.getAccountPreferences(signal), staleTime: 5 * MINUTE })
};
