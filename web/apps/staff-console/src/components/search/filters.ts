import { useMemo } from 'react';

/* The shared header's choices live in the address bar: ?market=us&period=30 (or period=custom&from=…&to=…). */

export type Period = '7' | '30' | '90' | 'custom';
export const PERIODS: Period[] = ['7', '30', '90', 'custom'];

const DAY = /^\d{4}-\d{2}-\d{2}$/;
const DAY_MS = 86_400_000;
/** The search service answers at most this many days. */
export const MAX_DAYS = 366;

export type DateProblem = 'format' | 'order' | 'long' | null;

export interface SearchFilters {
  market: string | null;
  period: Period;
  /** The days (UTC, YYYY-MM-DD) the reports cover; empty while a custom range is invalid. */
  from: string;
  to: string;
  problem: DateProblem;
}

/** Today in UTC: the search service counts days in UTC. */
export const todayUtc = (): string => new Date().toISOString().slice(0, 10);

export function addDays(day: string, n: number): string {
  return new Date(Date.parse(`${day}T00:00:00Z`) + n * DAY_MS).toISOString().slice(0, 10);
}

/** The first and last day of a preset (it ends today). */
export function presetRange(period: '7' | '30' | '90', today: string = todayUtc()): { from: string; to: string } {
  return { from: addDays(today, -(Number(period) - 1)), to: today };
}

/** Checks a custom range. */
export function customProblem(from: string, to: string): DateProblem {
  if (!DAY.test(from) || !DAY.test(to) || Number.isNaN(Date.parse(from)) || Number.isNaN(Date.parse(to))) return 'format';
  if (from > to) return 'order';
  if ((Date.parse(to) - Date.parse(from)) / DAY_MS >= MAX_DAYS) return 'long';
  return null;
}

/** The filters from the URL (the market falls back to `defaultMarket`, the period to 30 days). */
export function useSearchFilters(params: { get(name: string): string | null }, defaultMarket: string | null): SearchFilters {
  const raw = params.get('period');
  const period: Period = raw === '7' || raw === '90' || raw === 'custom' ? raw : '30';
  const market = params.get('market') || defaultMarket;
  const from = params.get('from') ?? '';
  const to = params.get('to') ?? '';
  return useMemo(() => {
    if (period !== 'custom') return { market, period, ...presetRange(period), problem: null };
    const problem = customProblem(from, to);
    return { market, period, from: problem ? '' : from, to: problem ? '' : to, problem };
  }, [market, period, from, to]);
}
