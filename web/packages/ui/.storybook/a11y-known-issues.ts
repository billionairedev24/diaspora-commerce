/*
 * Storybook a11y gate: serious/critical axe findings fail the story, except known issues that are ALREADY tracked in
 * tests/e2e/support/a11y-known-issues.ts (owned there, with a note). A Storybook entry maps an e2e entry (rule + note)
 * to the story ids where the same defect shows up. It is only honoured while the e2e entry still exists, so when the
 * fix lands and the e2e entry is deleted, the Storybook allowance disappears with it and the story gates again.
 */
import { KNOWN_A11Y_ISSUES } from '../../../../tests/e2e/support/a11y-known-issues.ts';

/** WCAG 2.2 A/AA rule tags, identical to tests/e2e/support/a11y.ts. */
export const A11Y_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

interface StoryKnownIssue {
  /** axe rule id. */
  rule: string;
  /** `note` of the tests/e2e KNOWN_A11Y_ISSUES entry this is the same defect as. */
  e2eNote: string;
  /** Story ids (from index.json) where it shows up. */
  stories: string[];
}

/*
 * Empty: every serious/critical finding the stories showed on 2026-10-10 (Stars contrast on commerce-primitives--rating,
 * tracked as e2e "Stars empty-star glyphs") has been fixed and its e2e entry removed. Example entry:
 *   { rule: 'color-contrast', e2eNote: 'Stars empty-star glyphs', stories: ['commerce-primitives--rating'] }
 */
export const STORY_KNOWN_ISSUES: StoryKnownIssue[] = [];

interface AxeViolation {
  id: string;
  impact?: string | null;
  help: string;
  nodes: { target: unknown[] }[];
}

/** Entries whose e2e counterpart still exists (i.e. the issue is still open). */
export function activeStoryKnownIssues(): StoryKnownIssue[] {
  return STORY_KNOWN_ISSUES.filter((s) => KNOWN_A11Y_ISSUES.some((k) => k.rule === s.rule && k.note === s.e2eNote));
}

/**
 * Splits serious/critical violations into blocking ones and known (allowed) ones. `stale` lists allowlisted rules for
 * this story that no longer reproduce, so the entry (here and in tests/e2e) can be deleted.
 */
export function blockingViolations(storyId: string, violations: AxeViolation[]): { blocking: string[]; allowed: string[]; stale: string[] } {
  const known = activeStoryKnownIssues().filter((s) => s.stories.includes(storyId));
  const blocking: string[] = [];
  const allowed: string[] = [];
  for (const v of violations) {
    if (v.impact !== 'serious' && v.impact !== 'critical') continue;
    const line = `${v.impact} ${v.id}: ${v.help} → ${v.nodes
      .slice(0, 3)
      .map((n) => n.target.join(' '))
      .join(' | ')}`;
    const k = known.find((x) => x.rule === v.id);
    if (k) allowed.push(`${line} (tracked: ${k.e2eNote})`);
    else blocking.push(line);
  }
  const stale = known.filter((k) => !violations.some((v) => v.id === k.rule)).map((k) => `${k.rule} (${k.e2eNote})`);
  return { blocking, allowed, stale };
}
