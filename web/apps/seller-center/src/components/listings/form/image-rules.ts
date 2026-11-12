import { type ImageCheck } from '@afrotransact/api-client';

import { EXTRA_PHOTOS_TARGET, type FormImage, MAIN_FILL_PERCENT, MIN_IMAGE_PX } from '../form-model';

/* Pure rules of the product form's image cards (S391): what an image's verdict and checks mean for the card, the live list of the
   main photo rules, the vars a message needs and the extra-photo count. Media (S388) decides; this file only reads its answer. */

export type Verdict = 'PASS' | 'WARN' | 'HOLD' | 'REJECT';

/** The card's verdict: media's, else from the card's own state (an image stored before the rules is a pass). */
export function verdictOf(im: FormImage): Verdict | null {
  if (im.status === 'uploading' || im.status === 'error') return null;
  if (im.verdict) return im.verdict;
  if (im.status === 'rejected') return 'REJECT';
  if (im.status === 'held') return 'HOLD';
  return im.status === 'low' ? 'REJECT' : 'PASS';
}

/** The findings to show on the card: media's checks that matter (INFO ones never change the verdict and are not shown), or the size rule alone for an image media did not judge. */
export function visibleChecks(im: FormImage): ImageCheck[] {
  const checks = (im.checks ?? []).filter((c) => c.level !== 'INFO');
  if (checks.length || im.verdict) return checks;
  return im.status === 'low' ? [{ code: 'MAIN_TOO_SMALL', level: 'REJECT', params: { w: im.width, h: im.height, px: MIN_IMAGE_PX }, message: '' }] : [];
}

const num = (v: unknown): number | undefined => (typeof v === 'number' && Number.isFinite(v) ? v : undefined);

/** The numbers a message needs (w, h, px, min, fill, actual, mb), from the check's params with the form's own as fallback. */
export function checkVars(check: ImageCheck, im?: FormImage): Record<string, number> {
  const p = check.params;
  return {
    w: num(p.w) ?? im?.width ?? 0,
    h: num(p.h) ?? im?.height ?? 0,
    px: num(p.px) ?? num(p.min) ?? MIN_IMAGE_PX,
    min: num(p.min) ?? 500,
    fill: num(p.fill) ?? MAIN_FILL_PERCENT,
    actual: num(p.actual) ?? 0,
    mb: num(p.mb) ?? 8
  };
}

export type RuleState = 'ok' | 'fail' | 'warn' | 'pending' | 'todo';
export type RuleId = 'size' | 'white' | 'fill' | 'content';
export const RULE_IDS: RuleId[] = ['size', 'white', 'fill', 'content'];

const codesOf = (im: FormImage): Set<string> => new Set((im.checks ?? []).map((c) => c.code));
const HELD = ['HELD_TEXT', 'HELD_MISMATCH', 'HELD_DUPLICATE', 'HELD_PROPS', 'HELD_REVIEW'];
const CONTENT_FAIL = ['BORDER', 'PLACEHOLDER', 'MAIN_PROPS', 'PROHIBITED', 'REVIEW_REJECTED'];

/**
 * The live list of the main photo rules for the image in the main position. Before an upload every rule is "not checked yet". The size rule
 * is known from the picture's own size; the others come from media's verdict for the MAIN slot. A photo that was judged as an extra photo and
 * then made the main one is checked against the other three rules when the listing is submitted.
 */
export function mainRuleStates(main: FormImage | undefined): Record<RuleId, RuleState> {
  const todo: Record<RuleId, RuleState> = { size: 'todo', white: 'todo', fill: 'todo', content: 'todo' };
  if (!main || main.status === 'error') return todo;
  if (main.status === 'uploading') return { size: 'pending', white: 'pending', fill: 'pending', content: 'pending' };
  const codes = codesOf(main);
  const longest = Math.max(main.width, main.height);
  const small = codes.has('MAIN_TOO_SMALL') || codes.has('TOO_SMALL') || main.status === 'low' || (longest > 0 && longest < MIN_IMAGE_PX);
  const size: RuleState = small ? 'fail' : longest > 0 || main.verdict ? 'ok' : 'todo';
  if (main.slot !== 'MAIN' || !main.verdict) return { ...todo, size };
  const held = HELD.some((c) => codes.has(c));
  return {
    size,
    white: codes.has('NOT_WHITE') ? 'fail' : 'ok',
    fill: codes.has('FILL_LOW') ? 'fail' : codes.has('FILL_OK_NEAR') ? 'warn' : 'ok',
    content: CONTENT_FAIL.some((c) => codes.has(c)) ? 'fail' : held ? 'pending' : 'ok'
  };
}

/** Extra photos (everything after the main one) that are in the listing or waiting for review; rejected and failed ones do not count. */
export function extraPhotoCount(images: FormImage[]): number {
  return images.slice(1).filter((i) => i.status === 'ready' || i.status === 'held' || i.status === 'low').length;
}

export { EXTRA_PHOTOS_TARGET };
