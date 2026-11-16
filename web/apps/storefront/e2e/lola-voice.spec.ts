import { expect, type Page, test } from '@playwright/test';

/* Lola dictation (browser SpeechRecognition, stubbed here so the run is deterministic and needs no microphone). The mic only fills
   the input box; nothing is sent until the shopper presses Send. Without SpeechRecognition (Firefox) it is disabled with a reason. */

const STUB = `
  class FakeRecognition {
    constructor() { window.__rec = this; this.lang = ''; }
    start() { window.__recStarted = (window.__recStarted || 0) + 1; }
    stop() { if (this.onend) this.onend(); }
    abort() {}
  }
  window.SpeechRecognition = FakeRecognition;
  window.webkitSpeechRecognition = FakeRecognition;
`;
const NONE = 'delete window.SpeechRecognition; delete window.webkitSpeechRecognition;';

const open = async (page: Page) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Chat with Lola' }).click();
  const panel = page.getByRole('dialog', { name: 'Lola' });
  await expect(panel).toBeVisible();
  return panel;
};

for (const [width, height] of [[1280, 900], [390, 844]] as const) {
  test(`${width}: dictation fills the box live, Esc stops it first, nothing is sent`, async ({ page }) => {
    await page.addInitScript(STUB);
    await page.setViewportSize({ width, height });
    const panel = await open(page);
    const mic = panel.getByTestId('mic-button');
    const box = await mic.boundingBox();
    expect(Math.round(box!.width)).toBeGreaterThanOrEqual(44);
    expect(Math.round(box!.height)).toBeGreaterThanOrEqual(44);
    await expect(mic).toHaveAttribute('aria-pressed', 'false');
    await mic.click();
    await expect(mic).toHaveAttribute('aria-pressed', 'true');
    await expect(panel.getByTestId('mic-status')).toHaveText('Listening…');
    await expect(panel.getByTestId('mic-notice')).toContainText('AfroTransact keeps no audio.');
    await page.evaluate(() => {
      const w = window as unknown as { __rec: { onresult: (e: unknown) => void } };
      w.__rec.onresult({ resultIndex: 0, results: [{ isFinal: false, length: 1, 0: { transcript: 'jollof for 20' } }] });
    });
    const input = panel.getByRole('textbox', { name: 'Message Lola' });
    await expect(input).toHaveValue('jollof for 20');
    await page.keyboard.press('Escape');
    await expect(panel).toBeVisible();
    await expect(mic).toHaveAttribute('aria-pressed', 'false');
    await expect(input).toBeFocused();
    await expect(input).toHaveValue('jollof for 20');
    await expect(panel.getByTestId('lola-answer')).toHaveCount(1); // only the greeting: never auto-sent
  });
}

test('RTL: the mic stays inside the composer', async ({ page }) => {
  await page.addInitScript(STUB);
  const panel = await open(page);
  await page.evaluate(() => document.documentElement.setAttribute('dir', 'rtl'));
  const p = await panel.boundingBox();
  const m = await panel.getByTestId('mic-button').boundingBox();
  expect(m!.x).toBeGreaterThanOrEqual(p!.x);
  expect(m!.x + m!.width).toBeLessThanOrEqual(p!.x + p!.width);
});

test('unsupported browser (no SpeechRecognition): disabled mic with a one-line reason, typing works', async ({ page }) => {
  await page.addInitScript(NONE);
  const panel = await open(page);
  const mic = panel.getByTestId('mic-button');
  await expect(mic).toHaveAttribute('aria-disabled', 'true');
  await expect(panel.getByTestId('mic-status')).toHaveText('Voice input isn’t available in this browser. You can still type.');
  await panel.getByRole('textbox', { name: 'Message Lola' }).fill('hello');
  await expect(panel.getByRole('button', { name: 'Send' })).toBeEnabled();
});

test('permission denied: the mic is disabled and says how to allow it', async ({ page }) => {
  await page.addInitScript(STUB);
  const panel = await open(page);
  await panel.getByTestId('mic-button').click();
  await page.evaluate(() => {
    const w = window as unknown as { __rec: { onerror: (e: unknown) => void } };
    w.__rec.onerror({ error: 'not-allowed' });
  });
  await expect(panel.getByTestId('mic-button')).toHaveAttribute('aria-disabled', 'true');
  await expect(panel.getByTestId('mic-status')).toContainText('Microphone access is blocked');
});
