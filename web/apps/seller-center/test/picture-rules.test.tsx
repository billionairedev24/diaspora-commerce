import { File as NodeFile } from 'node:buffer';

import { PICTURE_PROFILE_KNOBS, type PictureProfile, type PictureRules, summaryOfKnobs } from '@afrotransact/api-client';
import { resetSellerDb, setMockSellerPictureProfile } from '@afrotransact/api-client/mocks/seller';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ImagesField } from '@/components/listings/form/ImagesField';
import { emptyForm, type FormImage, validateForm } from '@/components/listings/form-model';

import { renderScreen } from './render';

globalThis.File = NodeFile as unknown as typeof File;

class FakePicture {
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  naturalWidth = 1600;
  naturalHeight = 1600;
  set src(_v: string) {
    queueMicrotask(() => this.onload?.());
  }
}
vi.stubGlobal('Image', FakePicture);
URL.createObjectURL = () => 'blob:picture';

const rulesOf = (profile: PictureProfile): PictureRules => ({
  profile,
  knobs: PICTURE_PROFILE_KNOBS[profile],
  overridden: [],
  formats: ['image/jpeg', 'image/png', 'image/webp', 'image/avif'],
  version: 0,
  summary: summaryOfKnobs(PICTURE_PROFILE_KNOBS[profile])
});

const image = (over: Partial<FormImage> = {}): FormImage => ({
  id: '1', previewUrl: 'blob:a', url: 'https://img/a.avif', width: 1600, height: 1600, bytes: 100_000, contentType: 'image/avif', status: 'ready', alt: '', slot: 'MAIN', verdict: 'PASS', checks: [], ...over
});

beforeEach(() => {
  resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  globalThis.fetch = vi.fn(async () => new Response(null, { status: 200 })) as typeof fetch;
});
afterEach(() => setMockSellerPictureProfile('strict'));

describe('the form validates pictures with the rule in force', () => {
  const problem = (images: FormImage[], profile: PictureProfile, over?: Partial<PictureRules['knobs']>) =>
    validateForm({ ...emptyForm(), images }, undefined, { pictures: { ...rulesOf(profile), knobs: { ...PICTURE_PROFILE_KNOBS[profile], ...over } } })['pf-images'];

  it('asks for a photo under strict and startup, and not under testing', () => {
    expect(problem([], 'strict')?.key).toBe('sc.pf.err.imagesNone');
    expect(problem([], 'startup')?.key).toBe('sc.pf.err.imagesNone');
    expect(problem([], 'testing')).toBeUndefined();
  });

  it('asks for as many photos as the rule says', () => {
    const p = problem([image()], 'strict', { minImages: 3 });
    expect(p?.key).toBe('sc.pf.err.imagesFew');
    expect(p?.vars).toEqual({ min: 3 });
    expect(problem([image(), image({ id: '2', slot: 'OTHER' }), image({ id: '3', slot: 'OTHER' })], 'strict', { minImages: 3 })).toBeUndefined();
  });

  it('says a main photo is too small with the number of the active rule', () => {
    const small = image({ status: 'low', verdict: null, width: 400, height: 400 });
    expect(problem([small], 'strict')?.vars).toEqual({ px: 1000 });
    expect(problem([small], 'startup')?.vars).toEqual({ px: 500 });
  });

  it('takes an AVIF photo like any other', () => {
    expect(problem([image({ contentType: 'image/avif' })], 'strict')).toBeUndefined();
  });
});

function Harness() {
  const [images, setImages] = useState<FormImage[]>([]);
  return <ImagesField images={images} update={setImages} />;
}

describe('the photo rules popover states the active rule', () => {
  it('lists the strict standard with 1000 px and the white background, and no "rule in force" line', async () => {
    await renderScreen(<Harness />);
    fireEvent.click(await screen.findByRole('button', { name: 'Main photo rules' }));
    const rules = within(screen.getByTestId('main-image-rules'));
    expect(rules.getByText(/At least 1000 pixels on the longest side/)).toBeInTheDocument();
    expect(rules.getByText(/Plain white background/)).toBeInTheDocument();
    expect(rules.queryByTestId('active-photo-rule')).not.toBeInTheDocument();
  });

  it('says "at least 500 pixels" under startup, drops the background and fill rules and states the rule in force', async () => {
    setMockSellerPictureProfile('startup');
    await renderScreen(<Harness />);
    fireEvent.click(await screen.findByRole('button', { name: 'Main photo rules' }));
    await waitFor(() => expect(within(screen.getByTestId('main-image-rules')).getByText(/At least 500 pixels on the longest side/)).toBeInTheDocument());
    const rules = within(screen.getByTestId('main-image-rules'));
    expect(rules.queryByText(/Plain white background/)).not.toBeInTheDocument();
    expect(rules.queryByText(/The product fills at least/)).not.toBeInTheDocument();
    expect(rules.getByTestId('active-photo-rule')).toHaveTextContent('at least 1 photo, at least 500 px on the longest side');
    expect(rules.getByText(/JPG, PNG, WEBP, AVIF · up to 8 MB each/)).toBeInTheDocument();
  });

  it('accepts an AVIF file from the picker and sends it to be checked as image/avif', async () => {
    await renderScreen(<Harness />);
    const input = await screen.findByTestId('image-input');
    expect(input).toHaveAttribute('accept', expect.stringContaining('image/avif'));
    fireEvent.change(input, { target: { files: [new File(['x'], 'tote.avif', { type: 'image/avif' })] } });
    await waitFor(() => expect(screen.getAllByRole('button', { name: /press to edit/ })).toHaveLength(1));
    expect(screen.queryByText(/use JPG, PNG, WebP or AVIF/)).not.toBeInTheDocument();
  });

  it('refuses an SVG file in the picker, under every profile', async () => {
    for (const profile of ['strict', 'startup', 'testing'] as const) {
      setMockSellerPictureProfile(profile);
      const { unmount } = await renderScreen(<Harness />);
      fireEvent.change(await screen.findByTestId('image-input'), { target: { files: [new File(['<svg/>'], 'logo.svg', { type: 'image/svg+xml' })] } });
      expect(await screen.findByText('logo.svg: use JPG, PNG, WebP or AVIF.')).toBeInTheDocument();
      unmount();
    }
  });
});
