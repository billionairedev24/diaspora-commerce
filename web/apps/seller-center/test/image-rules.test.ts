import { describe, expect, it } from 'vitest';

import { checkVars, extraPhotoCount, mainRuleStates, verdictOf, visibleChecks } from '@/components/listings/form/image-rules';
import { emptyForm, type FormImage, fromDetail, fromDraftPayload, MIN_IMAGE_PX, toDraftPayload, toProductDraft, validateForm } from '@/components/listings/form-model';
import { CATALOG_FILES } from '@/i18n/catalogs';
import { EN_MESSAGES, translate } from '@/i18n/t';

const image = (over: Partial<FormImage> = {}): FormImage => ({ id: 'i1', previewUrl: 'blob:a', url: 'https://img/a.webp', width: 1600, height: 1600, bytes: 1000, contentType: 'image/webp', status: 'ready',
  alt: '', slot: 'MAIN', verdict: 'PASS', checks: [], ...over });
const check = (code: string, level: 'INFO' | 'WARN' | 'HOLD' | 'REJECT', params: Record<string, unknown> = {}) => ({ code, level, params, message: '' });

describe('image cards (S391): the verdict of each picture', () => {
  it('reads media’s verdict, and falls back to the card’s own state for an image stored before the rules', () => {
    expect(verdictOf(image({ verdict: 'WARN' }))).toBe('WARN');
    expect(verdictOf(image({ verdict: null, status: 'ready' }))).toBe('PASS');
    expect(verdictOf(image({ verdict: null, status: 'rejected' }))).toBe('REJECT');
    expect(verdictOf(image({ verdict: null, status: 'held' }))).toBe('HOLD');
    expect(verdictOf(image({ verdict: null, status: 'low' }))).toBe('REJECT');
    expect(verdictOf(image({ status: 'uploading' }))).toBeNull();
    expect(verdictOf(image({ status: 'error' }))).toBeNull();
  });

  it('shows the findings that matter and explains an old image that is too small by the size rule', () => {
    expect(visibleChecks(image({ verdict: 'WARN', checks: [check('LARGER_BETTER', 'INFO'), check('FILL_OK_NEAR', 'WARN')] })).map((c) => c.code)).toEqual(['FILL_OK_NEAR']);
    const old = visibleChecks(image({ verdict: null, status: 'low', width: 640, height: 480 }));
    expect(old.map((c) => c.code)).toEqual(['MAIN_TOO_SMALL']);
    expect(checkVars(old[0]!, image({ width: 640, height: 480 }))).toMatchObject({ w: 640, h: 480, px: MIN_IMAGE_PX });
  });

  it('gives each message its numbers, from media or from the picture', () => {
    expect(checkVars(check('MAIN_TOO_SMALL', 'REJECT', { w: 800, h: 600, px: 1000 }))).toMatchObject({ w: 800, h: 600, px: 1000 });
    expect(checkVars(check('FILL_LOW', 'REJECT', { fill: 85, actual: 52 }))).toMatchObject({ fill: 85, actual: 52 });
    expect(checkVars(check('FILE_TOO_BIG', 'REJECT', { mb: 8 }))).toMatchObject({ mb: 8 });
    expect(checkVars(check('LOW_RES', 'WARN'), image({ width: 700, height: 700 }))).toMatchObject({ w: 700, h: 700, px: MIN_IMAGE_PX, min: 500 });
  });

  it('has a message in every language for every code media can send, and says how to fix a rejection', () => {
    const codes = ['MAIN_TOO_SMALL', 'TOO_SMALL', 'LOW_RES', 'LARGER_BETTER', 'NOT_WHITE', 'FILL_LOW', 'FILL_OK_NEAR', 'BORDER', 'PLACEHOLDER', 'FORMAT', 'FILE_TOO_BIG', 'NOT_AN_IMAGE', 'BLUR', 'ASPECT',
      'HELD_TEXT', 'HELD_MISMATCH', 'HELD_DUPLICATE', 'HELD_PROPS', 'HELD_REVIEW', 'PROHIBITED', 'MAIN_PROPS', 'REVIEW_REJECTED', 'NOT_VERIFIED', 'FETCH_FAILED', 'FETCH_PRIVATE', 'NO_MAIN_IMAGE'];
    for (const [locale, file] of Object.entries(CATALOG_FILES)) {
      for (const code of codes) expect(typeof file[`sc.img.check.${code}`], `${locale}: ${code}`).toBe('string');
      for (const v of ['PASS', 'WARN', 'HOLD', 'REJECT']) expect(typeof file[`sc.img.verdict.${v}`], `${locale}: ${v}`).toBe('string');
      for (const k of ['sc.pf.images.tooSmall', 'sc.pf.err.imagesMainSmall']) expect(typeof file[k], `${locale}: ${k}`).toBe('string');
    }
    expect(translate('en', 'sc.img.check.MAIN_TOO_SMALL', { px: 1000, w: 640, h: 480 })).toBe(
      'This photo is too small. The main photo must be at least 1000 pixels on its longest side so shoppers can zoom. Yours is 640 by 480.');
    expect(EN_MESSAGES['sc.img.check.NOT_WHITE']).toMatch(/Retake it or edit the background/);
    expect(EN_MESSAGES['sc.img.check.FILL_LOW']).toMatch(/Crop closer/);
  });
});

describe('the live list of the main photo rules', () => {
  it('starts with every rule not checked, and the rules are checked while the picture uploads', () => {
    expect(mainRuleStates(undefined)).toEqual({ size: 'todo', white: 'todo', fill: 'todo', content: 'todo' });
    expect(mainRuleStates(image({ status: 'uploading', verdict: null }))).toEqual({ size: 'pending', white: 'pending', fill: 'pending', content: 'pending' });
  });

  it('passes a good photo and names each rule a bad one breaks', () => {
    expect(mainRuleStates(image())).toEqual({ size: 'ok', white: 'ok', fill: 'ok', content: 'ok' });
    const bad = image({ width: 640, height: 480, status: 'rejected', verdict: 'REJECT', url: null,
      checks: [check('MAIN_TOO_SMALL', 'REJECT'), check('NOT_WHITE', 'REJECT'), check('FILL_LOW', 'REJECT'), check('BORDER', 'REJECT')] });
    expect(mainRuleStates(bad)).toEqual({ size: 'fail', white: 'fail', fill: 'fail', content: 'fail' });
    expect(mainRuleStates(image({ verdict: 'WARN', checks: [check('FILL_OK_NEAR', 'WARN')] })).fill).toBe('warn');
    expect(mainRuleStates(image({ status: 'held', verdict: 'HOLD', checks: [check('HELD_TEXT', 'HOLD')] })).content).toBe('pending');
  });

  it('knows the size of any picture, and leaves the rest to the submit for a photo that was judged as an extra one', () => {
    expect(mainRuleStates(image({ slot: 'OTHER', verdict: 'PASS', width: 800, height: 700 }))).toEqual({ size: 'fail', white: 'todo', fill: 'todo', content: 'todo' });
    expect(mainRuleStates(image({ slot: 'OTHER', verdict: 'PASS' }))).toEqual({ size: 'ok', white: 'todo', fill: 'todo', content: 'todo' });
  });
});

describe('the extra-photo meter', () => {
  it('counts the photos after the main one that are in the listing or waiting, never the rejected or failed ones', () => {
    const list = [image(), image({ id: '2' }), image({ id: '3', status: 'held', verdict: 'HOLD' }), image({ id: '4', status: 'rejected', verdict: 'REJECT', url: null }), image({ id: '5', status: 'error' }), image({ id: '6', status: 'uploading' })];
    expect(extraPhotoCount(list)).toBe(2);
    expect(extraPhotoCount([image()])).toBe(0);
    expect(extraPhotoCount([])).toBe(0);
  });
});

describe('submitting with the main photo as it is', () => {
  const problem = (images: FormImage[]) => validateForm({ ...emptyForm(), images }, undefined)['pf-images'];

  it('blocks a rejected main photo, with the way to fix it', () => {
    expect(problem([image({ status: 'rejected', verdict: 'REJECT', url: null })])?.key).toBe('sc.pf.err.imagesMainRejected');
  });

  it('lets a rejected extra photo go: it has no URL and is not sent', () => {
    expect(problem([image(), image({ id: '2', status: 'rejected', verdict: 'REJECT', url: null })])).toBeUndefined();
  });

  it('allows a warning, and a photo we are checking that has an address', () => {
    expect(problem([image({ verdict: 'WARN', checks: [check('FILL_OK_NEAR', 'WARN')] })])).toBeUndefined();
    expect(problem([image({ status: 'held', verdict: 'HOLD' })])).toBeUndefined();
  });

  it('waits for a held main photo that has neither an address nor an asset id', () => {
    expect(problem([image({ status: 'held', verdict: 'HOLD', url: null })])?.key).toBe('sc.pf.err.imagesHeldNoUrl');
  });

  it('lets a held main photo go with its asset id: the listing is submitted and waits for our answer', () => {
    expect(problem([image({ status: 'held', verdict: 'HOLD', url: null, assetId: 'ast_1' })])).toBeUndefined();
  });

  it('says a main photo under 1000 px is too small, for an image media did not judge', () => {
    const p = problem([image({ status: 'low', verdict: null, width: 800, height: 800 })]);
    expect(p?.key).toBe('sc.pf.err.imagesMainSmall');
    expect(p?.vars).toEqual({ px: MIN_IMAGE_PX });
  });
});

describe('a photo we are checking is sent by its asset id', () => {
  const held = image({ status: 'held', verdict: 'HOLD', url: null, assetId: 'ast_1', previewUrl: 'blob:b' });

  it('submits the asset id instead of an address and keeps the other fields out', () => {
    const draft = toProductDraft({ ...emptyForm(), images: [held, image({ id: '2', alt: ' a bowl ' })] }, undefined);
    expect(draft.images[0]).toEqual({ url: '', assetId: 'ast_1', contentType: '' });
    expect(draft.images[1]).toMatchObject({ url: 'https://img/a.webp', alt: 'a bowl' });
    expect(draft.images[1]).not.toHaveProperty('assetId');
  });

  it('never sends a rejected photo and counts a held one as added', () => {
    const rejected = image({ id: '3', status: 'rejected', verdict: 'REJECT', url: null });
    expect(toProductDraft({ ...emptyForm(), images: [held, rejected] }, undefined).images).toHaveLength(1);
  });

  it('keeps it in a draft and reopens it as a photo we are checking', () => {
    const back = fromDraftPayload(toDraftPayload({ ...emptyForm(), images: [held] }));
    expect(back.images[0]).toMatchObject({ status: 'held', url: null, assetId: 'ast_1', verdict: 'HOLD' });
  });

  it('reopens a saved listing whose photo is held', () => {
    const detail = { productId: 'p', atin: 'A', status: 'IN_REVIEW', statusLabel: '', moderationNote: null, categoryId: null, categoryRequestId: null, categoryPath: '', productType: 'T', title: 'x', attributes: {},
      images: [{ url: '', assetId: 'ast_9', held: true, contentType: '' }], variationTheme: '', variants: [], preorderEligible: false, listing: { condition: 'NEW', keywords: [], fulfilment: 'SELLER', packaging: null, handlingDays: 2, shipFromId: null, sizeChartId: null, fitNote: null, sellerBehaviour: {} }, currency: 'USD', version: 1, updatedAt: '' };
    expect(fromDetail(detail as never, undefined).images[0]).toMatchObject({ status: 'held', assetId: 'ast_9', url: null });
  });

  it('says it in words in five languages', () => {
    for (const [locale, file] of Object.entries(CATALOG_FILES)) {
      expect(typeof file['sc.pf.images.checkingPhoto'], locale).toBe('string');
    }
    expect(EN_MESSAGES['sc.img.verdict.HOLD']).toBe('We are checking your photo');
  });
});
