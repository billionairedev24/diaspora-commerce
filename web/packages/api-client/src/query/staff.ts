import { keepPreviousData, queryOptions } from '@tanstack/react-query';

import { type ResourceKey } from '../schemas/staff';
import { type ResourceQuery, type StaffApiClient } from '../staff-client';

/* TanStack Query key factories for the staff console. Everything hangs off ['staff'] so signing out (or a role change)
   invalidates the whole family; each resource has its own branch so an action refreshes its table and sheet only. */

export const staffKeys = {
  all: ['staff'] as const,
  session: () => [...staffKeys.all, 'session'] as const,
  dashboard: (rangeDays: number) => [...staffKeys.all, 'dashboard', rangeDays] as const,
  resource: (resource: ResourceKey) => [...staffKeys.all, 'resource', resource] as const,
  page: (resource: ResourceKey, q: ResourceQuery) => [...staffKeys.resource(resource), 'page', q] as const,
  record: (resource: ResourceKey, id: string) => [...staffKeys.resource(resource), 'record', id] as const,
  exports: () => [...staffKeys.all, 'exports'] as const,
  exportJob: (id: string) => [...staffKeys.exports(), id] as const,
  customers: (q: string) => [...staffKeys.all, 'support', 'customers', q] as const,
  customer: (id: string) => [...staffKeys.all, 'support', 'customer', id] as const,
  fees: () => [...staffKeys.all, 'fees'] as const,
  feeOrgs: (q: string) => [...staffKeys.fees(), 'orgs', q] as const
};

const MINUTE = 60_000;

/** queryOptions for every staff screen (same keys, fetchers and stale times on server and client). */
export const staffQueries = {
  session: (api: StaffApiClient) => queryOptions({ queryKey: staffKeys.session(), queryFn: ({ signal }) => api.getSession(signal), staleTime: MINUTE }),
  dashboard: (api: StaffApiClient, rangeDays: number) =>
    queryOptions({ queryKey: staffKeys.dashboard(rangeDays), queryFn: ({ signal }) => api.getDashboard(rangeDays, signal), staleTime: MINUTE }),
  page: (api: StaffApiClient, resource: ResourceKey, q: ResourceQuery) =>
    queryOptions({
      queryKey: staffKeys.page(resource, q),
      queryFn: ({ signal }) => api.listResource(resource, q, signal),
      placeholderData: keepPreviousData,
      staleTime: 15_000
    }),
  record: (api: StaffApiClient, resource: ResourceKey, id: string) =>
    queryOptions({ queryKey: staffKeys.record(resource, id), queryFn: ({ signal }) => api.getRecord(resource, id, signal), staleTime: 10_000 }),
  exports: (api: StaffApiClient) => queryOptions({ queryKey: staffKeys.exports(), queryFn: ({ signal }) => api.listExports(signal), staleTime: 5_000 }),
  exportJob: (api: StaffApiClient, id: string) =>
    queryOptions({ queryKey: staffKeys.exportJob(id), queryFn: ({ signal }) => api.getExport(id, signal), staleTime: 0 }),
  customers: (api: StaffApiClient, q: string) =>
    queryOptions({ queryKey: staffKeys.customers(q), queryFn: ({ signal }) => api.searchCustomers(q, signal), staleTime: 30_000 }),
  customer: (api: StaffApiClient, id: string) =>
    queryOptions({ queryKey: staffKeys.customer(id), queryFn: ({ signal }) => api.getCustomer(id, signal), staleTime: 15_000 }),
  fees: (api: StaffApiClient) => queryOptions({ queryKey: staffKeys.fees(), queryFn: ({ signal }) => api.getFees(signal), staleTime: 15_000 }),
  feeOrgs: (api: StaffApiClient, q: string) =>
    queryOptions({ queryKey: staffKeys.feeOrgs(q), queryFn: ({ signal }) => api.searchFeeOrgs(q, signal), staleTime: 30_000 })
};
