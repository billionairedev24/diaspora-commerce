import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { expect, type Page, test } from '@playwright/test';

/*
 * One screenshot test per story in the static build's index.json. Each story is loaded on its own (fresh page), its
 * `play` function runs, and the frame is captured once Storybook reports the render finished, web fonts are loaded and
 * animations are stopped. Opt a story out with the `skip-visual` tag.
 */
const here = dirname(fileURLToPath(import.meta.url));
const staticDir = resolve(here, '..', process.env.STORYBOOK_STATIC_DIR ?? 'storybook-static');

interface IndexEntry {
  id: string;
  title: string;
  name: string;
  type: 'story' | 'docs';
  tags?: string[];
}

function storiesFromIndex(): IndexEntry[] {
  let raw: string;
  try {
    raw = readFileSync(resolve(staticDir, 'index.json'), 'utf8');
  } catch {
    throw new Error(`${staticDir}/index.json not found: run \`pnpm --filter @afrotransact/ui build-storybook\` first.`);
  }
  const index = JSON.parse(raw) as { entries: Record<string, IndexEntry> };
  return Object.values(index.entries)
    .filter((e) => e.type === 'story' && !e.tags?.includes('skip-visual'))
    .sort((a, b) => a.id.localeCompare(b.id));
}

/** Story-level viewport global → page size (matches .storybook/preview.tsx `parameters.viewport.options`). */
const VIEWPORTS: Record<string, { width: number; height: number }> = {
  mobile: { width: 375, height: 740 },
  tablet: { width: 900, height: 1000 }
};

type RenderState = { phase?: string; viewport?: string };

async function waitForStory(page: Page): Promise<RenderState> {
  const handle = await page.waitForFunction(
    () => {
      const preview = (window as unknown as { __STORYBOOK_PREVIEW__?: { storyRenders?: { phase?: string; story?: { storyGlobals?: { viewport?: { value?: string } } } }[] } })
        .__STORYBOOK_PREVIEW__;
      const render = preview?.storyRenders?.at(-1);
      const phase = render?.phase;
      if (phase === 'finished' || phase === 'errored' || phase === 'aborted') return { phase, viewport: render?.story?.storyGlobals?.viewport?.value };
      if (document.body.classList.contains('sb-show-errordisplay')) return { phase: 'errored' };
      return null;
    },
    undefined,
    { timeout: 20_000 }
  );
  return (await handle.jsonValue()) as RenderState;
}

for (const story of storiesFromIndex()) {
  test(`${story.title} › ${story.name}`, async ({ page }) => {
    // The a11y scan is the test runner's job; skipping it here keeps the visual run fast.
    await page.goto(`/iframe.html?id=${encodeURIComponent(story.id)}&viewMode=story&globals=a11y.manual:!true`);
    const state = await waitForStory(page);
    expect(state.phase, `story ${story.id} did not render`).toBe('finished');

    const size = state.viewport ? VIEWPORTS[state.viewport] : undefined;
    if (size) await page.setViewportSize(size);

    await page.evaluate(async () => {
      await document.fonts.ready;
      await Promise.all(
        Array.from(document.images)
          .filter((img) => !img.complete)
          .map(
            (img) =>
              new Promise((done) => {
                img.addEventListener('load', done, { once: true });
                img.addEventListener('error', done, { once: true });
              })
          )
      );
    });

    await expect(page).toHaveScreenshot(`${story.id}.png`);
  });
}
