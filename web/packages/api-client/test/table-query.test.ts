import { describe, expect, it } from 'vitest';

import { matchesSearch, parseSort, queryRows, searchTerms } from '../src/mocks/table';
import { tableSearchParams } from '../src/table-query';

const cols = [
  { key: 'number', kind: 'mono' },
  { key: 'customer', kind: 'strong' },
  { key: 'totalMinor', kind: 'money' },
  { key: 'placedAt', kind: 'datetime' },
  { key: 'status', kind: 'status' }
];
const rows = [
  { id: 'a', cells: { number: 'AT-10', customer: 'Ada Obi', totalMinor: 123456, placedAt: '2026-10-11T14:05:00Z', status: 'AWAITING_PICKUP' } },
  { id: 'b', cells: { number: 'AT-9', customer: 'Bola Ade', totalMinor: 500, placedAt: '2026-09-01T10:00:00Z', status: 'PAID' } },
  { id: 'c', cells: { number: 'AT-100', customer: 'Chidi', totalMinor: null, placedAt: null, status: 'CANCELLED' } }
];

describe('table query (mock BFF, mirrors the Go domain)', () => {
  it('matches every column as the table shows it', () => {
    for (const q of ['ada obi', '1,234.56', '$1,234', 'oct 11, 2026', '2026-10-11', 'awaiting pickup', 'at-10']) {
      expect(matchesSearch(cols, rows[0]!, searchTerms(q)), q).toBe(true);
    }
    expect(matchesSearch(cols, rows[0]!, searchTerms('ada zed'))).toBe(false);
  });

  it('sorts naturally and typed, empty cells last both ways', () => {
    const sortBy = (s: string) => queryRows(rows, cols, '', parseSort([s], () => true) ?? []).map((r) => r.id);
    expect(sortBy('number,asc')).toEqual(['b', 'a', 'c']);
    expect(sortBy('totalMinor,desc')).toEqual(['a', 'b', 'c']);
    expect(sortBy('totalMinor,asc')).toEqual(['b', 'a', 'c']);
    expect(sortBy('placedAt,desc')).toEqual(['a', 'b', 'c']);
  });

  it('rejects unknown columns and directions; repeats sort in the query string', () => {
    expect(parseSort(['nope,asc'], (k) => k === 'number')).toBeNull();
    expect(parseSort(['number,sideways'], () => true)).toBeNull();
    expect(tableSearchParams({ q: 'ada', page: 0, cursor: undefined }, ['total,desc', 'placedAt,asc']).toString()).toBe('q=ada&page=0&sort=total%2Cdesc&sort=placedAt%2Casc');
  });
});
