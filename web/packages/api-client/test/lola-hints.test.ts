import { describe, expect, it } from 'vitest';

import { LOLA_MOCK_SUGGESTIONS } from '../src/mocks/lola';

/** The owner does not want festival-specific copy in Lola's hints; they show what Lola can do instead. */
export const FESTIVAL_DENY_LIST = /\b(diwali|divali|deepavali|eid|ramadan|holi|hanukkah|diya|diyas)\b/i;

describe('mock Lola suggestions', () => {
  it('are capability hints without festival names', () => {
    expect(LOLA_MOCK_SUGGESTIONS.length).toBeGreaterThanOrEqual(4);
    for (const s of LOLA_MOCK_SUGGESTIONS) expect(s).not.toMatch(FESTIVAL_DENY_LIST);
    expect(LOLA_MOCK_SUGGESTIONS).toEqual(expect.arrayContaining(['Track my order', 'Jollof for 20', 'Book knotless braids', 'Compare prices']));
  });
});
