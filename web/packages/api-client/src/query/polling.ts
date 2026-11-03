/* Background polling and the session timer. A poll is a request the person did not make, so it must send
   `X-Background: 1` (BACKGROUND, see http.ts): the BFF then does not count it as activity and a tab left open still
   times out. Every query that refreshes by itself is built here, in the query factories, and its fetcher passes
   BACKGROUND. Components never set `refetchInterval` themselves (test/background-polling.test.ts scans for it). */

/** Milliseconds until the next refresh given the data so far, or false to stop polling. */
export type PollRule<T> = (data: T | undefined) => number | false;

/** The `refetchInterval` option for a poll rule (nothing without one). */
export function pollingOf<T>(rule: PollRule<T> | undefined): { refetchInterval?: (query: { state: { data: T | undefined } }) => number | false } {
  return rule ? { refetchInterval: (query) => rule(query.state.data) } : {};
}

/** Poll every `ms` while `active(data)` holds (a job still running, refunds still settling); stop afterwards. */
export function pollWhile<T>(ms: number, active: (data: T | undefined) => boolean): PollRule<T> {
  return (data) => (active(data) ? ms : false);
}
