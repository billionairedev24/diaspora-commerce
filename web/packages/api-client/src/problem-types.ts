/* RFC 9457 problem types: one stable identifier base in every environment and runtime (Go go/pkg/problem.TypeBase,
   Java ProblemTypes; docs/engineering/environments.md). Types are identifiers, never fetched. */

/** Base of every problem type URI. */
export const PROBLEM_TYPE_BASE = 'https://developers.afrotransact.com/problems/';

/** Problem type for a code as the gateway BFF builds it: UPSTREAM_UNAVAILABLE -> <base>bff.upstream-unavailable. */
export function bffProblemType(code: string): string {
  return `${PROBLEM_TYPE_BASE}bff.${code.toLowerCase().replace(/_/g, '-')}`;
}
