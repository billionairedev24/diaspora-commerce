import { File as NodeFile } from 'node:buffer';

import { resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ImagesField } from '@/components/listings/form/ImagesField';
import { emptyForm, type FormImage, validateForm } from '@/components/listings/form-model';
import { ImageTasksBanner } from '@/components/listings/ImageTasksBanner';
import { ListingsView } from '@/components/listings/ListingsView';
import { ProductForm } from '@/components/listings/ProductForm';

import { renderScreen } from './render';
import { nav } from './setup';

globalThis.File = NodeFile as unknown as typeof File;

/* jsdom loads no pictures and has no object URLs: a picture is 1600 by 1600 and its upload goes through. */
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

beforeEach(() => {
  resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  nav.push.mockReset();
  nav.replace.mockReset();
  nav.search = new URLSearchParams();
  globalThis.fetch = vi.fn(async () => new Response(null, { status: 200 })) as typeof fetch;
});

function Harness({ initial = [] }: { initial?: FormImage[] }) {
  const [images, setImages] = useState<FormImage[]>(initial);
  const problem = validateForm({ ...emptyForm(), images }, undefined)['pf-images'];
  return (
    <>
      <ImagesField images={images} update={setImages} context={{ title: 'Raw Shea Butter', brand: 'Kente Naturals', productTypeCode: 'beauty' }} />
      <output data-testid="submit-problem">{problem?.key ?? 'none'}</output>
    </>
  );
}

const photo = (name: string) => new File(['x'], name, { type: 'image/jpeg' });
const upload = (...names: string[]) => fireEvent.change(screen.getByTestId('image-input'), { target: { files: names.map(photo) } });
const thumbs = () => within(screen.getByRole('list', { name: /Product photos/ })).getAllByRole('button', { name: /press to edit/ });
const openRules = () => {
  fireEvent.click(screen.getByRole('button', { name: 'Main photo rules' }));
  return within(screen.getByTestId('main-image-rules'));
};
const stateOfRule = (rules: ReturnType<typeof openRules>, text: RegExp) => rules.getByText(text).closest('li')?.getAttribute('data-state');

describe('photo slots and the main photo rules (S391)', () => {
  it('is a compact row with an empty main slot, no big drop zone and no always-visible rules', async () => {
    await renderScreen(<Harness />);
    const group = await screen.findByRole('group', { name: /Photos/ });
    expect(within(group).getByText('First photo is the main one')).toBeInTheDocument();
    expect(within(group).getByRole('button', { name: 'Add main photo' })).toBeInTheDocument();
    expect(screen.queryByText(/Drop images here/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Plain white background/)).not.toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
    expect(screen.getByTestId('image-input')).toHaveAttribute('accept', 'image/jpeg,image/png,image/webp');
  });

  it('keeps the main photo rules in an info popover that opens by click and by keyboard focus and closes with Escape, each rule with its live state', async () => {
    await renderScreen(<Harness />);
    const info = await screen.findByRole('button', { name: 'Main photo rules' });
    expect(info).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(info);
    expect(info).toHaveAttribute('aria-expanded', 'true');
    const rules = within(screen.getByTestId('main-image-rules'));
    expect(rules.getByText('Main photo rules')).toBeInTheDocument();
    expect(rules.getByText(/At least 1000 pixels on the longest side/)).toBeInTheDocument();
    expect(rules.getByText(/Plain white background/)).toBeInTheDocument();
    expect(rules.getByText(/The product fills at least 85% of the photo/)).toBeInTheDocument();
    expect(rules.getByText(/No text, logos, watermarks or borders/)).toBeInTheDocument();
    expect(rules.getAllByText('Not checked yet')).toHaveLength(4);
    expect(rules.getByText(/JPG, PNG, WEBP · up to 8 MB each/)).toBeInTheDocument();
    fireEvent.keyDown(info, { key: 'Escape' });
    expect(screen.queryByTestId('main-image-rules')).not.toBeInTheDocument();
    expect(info).toHaveFocus();
    fireEvent.keyDown(info, { key: 'Enter' });
    fireEvent.click(info);
    expect(screen.getByTestId('main-image-rules')).toBeInTheDocument();
  });

  it('passes a good main photo: a Ready dot, no reason under it, every rule passed, submit is not blocked', async () => {
    await renderScreen(<Harness />);
    upload('shea-front.jpg');
    await waitFor(() => expect(thumbs()).toHaveLength(1));
    await waitFor(() => expect(screen.getByTestId('image-verdict')).toHaveTextContent('Ready'), { timeout: 3000 });
    expect(screen.getByTestId('image-verdict').querySelector('[aria-hidden="true"]')).not.toBeNull();
    expect(screen.queryByTestId('photo-details')).not.toBeInTheDocument();
    expect(openRules().getAllByText('Passed')).toHaveLength(4);
    expect(screen.getByTestId('submit-problem')).toHaveTextContent('none');
    expect(screen.getByRole('button', { name: 'Add more photos' })).toBeInTheDocument();
  });

  it('rejects a small main photo: a short reason under the thumbnail, the full reason announced, Needs fixing in the rules, and submit is blocked', async () => {
    await renderScreen(<Harness />);
    upload('small-front.jpg');
    const findings = await screen.findByRole('alert', { name: 'What we found on image 1' }, { timeout: 3000 });
    expect(findings).toHaveTextContent('This photo is too small. The main photo must be at least 1000 pixels on its longest side so shoppers can zoom. Yours is 640 by 480.');
    expect(screen.getByText('Under 1000 px')).toBeInTheDocument();
    expect(screen.getByTestId('image-verdict')).toHaveTextContent('Not accepted');
    const rules = openRules();
    expect(stateOfRule(rules, /At least 1000 pixels/)).toBe('fail');
    expect(rules.getAllByText('Needs fixing').length).toBeGreaterThan(0);
    expect(screen.getByTestId('submit-problem')).toHaveTextContent('sc.pf.err.imagesMainRejected');
    // Remove it: the way to fix is to upload another one.
    fireEvent.click(screen.getByRole('button', { name: 'Remove image 1' }));
    expect(screen.queryByTestId('image-verdict')).not.toBeInTheDocument();
  });

  it('says what is wrong with the background and the fill in the seller’s words, and the rules say which rule failed', async () => {
    await renderScreen(<Harness />);
    upload('white-underfill-front.jpg');
    const findings = await screen.findByRole('alert', { name: 'What we found on image 1' }, { timeout: 3000 });
    expect(findings).toHaveTextContent('The main photo needs a plain white background (pure white, RGB 255, 255, 255). Retake it or edit the background and upload it again.');
    expect(findings).toHaveTextContent('The product is too small in the photo. It should fill at least 85% of the height or width. Crop closer and upload again.');
    expect(screen.getByText('Background is not plain white')).toBeInTheDocument();
    const rules = openRules();
    expect(stateOfRule(rules, /Plain white background/)).toBe('fail');
    expect(stateOfRule(rules, /The product fills at least/)).toBe('fail');
    expect(stateOfRule(rules, /At least 1000 pixels/)).toBe('ok');
  });

  it('accepts a warning with a notice, and does not block submit', async () => {
    await renderScreen(<Harness />);
    upload('fill-front.jpg');
    const finding = await screen.findByText('The product could fill more of the photo. 85% or more looks best.', undefined, { timeout: 3000 });
    expect(finding.closest('[role="status"]')).not.toBeNull();
    expect(screen.getByTestId('image-verdict')).toHaveTextContent('Ready, and it can be better');
    expect(screen.getByText('This photo is accepted and you can submit. A better photo sells better.')).toBeInTheDocument();
    expect(screen.getByTestId('submit-problem')).toHaveTextContent('none');
    expect(stateOfRule(openRules(), /The product fills at least/)).toBe('warn');
  });

  it('holds a photo for review: says so, lets the seller submit, and the listing waits', async () => {
    await renderScreen(<Harness />);
    upload('held-front.jpg');
    const finding = await screen.findByText(/We are checking this photo for text, logos or watermarks/, undefined, { timeout: 3000 });
    expect(finding.textContent).toMatch(/We usually answer within 2 business days/);
    expect(screen.getByTestId('image-verdict')).toHaveTextContent('We are checking your photo');
    expect(screen.getByText('We are checking this photo')).toBeInTheDocument();
    expect(screen.getByText('You can submit. The listing waits until we have checked this photo.')).toBeInTheDocument();
    expect(screen.getByTestId('submit-problem')).toHaveTextContent('none');
    expect(stateOfRule(openRules(), /No text, logos/)).toBe('pending');
  });

  it('judges the first picture as the main one and the next as extra photos, and counts the extra ones', async () => {
    await renderScreen(<Harness />);
    upload('shea-front.jpg', 'small-side.jpg', 'shea-back.jpg', 'shea-top.jpg');
    await waitFor(() => expect(screen.getAllByTestId('image-verdict')).toHaveLength(4), { timeout: 4000 });
    // The small extra photo is judged by the extra photo rule (500 px), not the main one: its message says so.
    expect(await screen.findByText('This photo is too small. Use a photo that is at least 500 pixels wide and tall.')).toBeInTheDocument();
    // Rejected extra photos do not block submit and do not count: the main one passes, two extra ones are in.
    expect(screen.getByTestId('submit-problem')).toHaveTextContent('none');
    expect(screen.getByText('4 of 10 photos · 2 of 7 extra photos suggested')).toBeInTheDocument();
    expect(thumbs()).toHaveLength(4);
  });

  it('lets a thumbnail be made the main photo and moved from its details', async () => {
    await renderScreen(<Harness />);
    upload('shea-front.jpg', 'shea-back.jpg');
    await waitFor(() => expect(screen.getAllByTestId('image-verdict')).toHaveLength(2), { timeout: 4000 });
    fireEvent.click(thumbs()[1] as HTMLElement);
    fireEvent.click(screen.getByRole('button', { name: 'Make image 2 the main image' }));
    expect(within(screen.getByRole('list', { name: /Product photos/ })).getByText('Main').closest('li')).toBe(screen.getAllByRole('listitem').find((li) => li.hasAttribute('data-main')));
  });

  it('never leaves a verdict to colour alone: each one is written and its dot is hidden from screen readers', async () => {
    await renderScreen(<Harness />);
    upload('held-front.jpg');
    const verdict = await screen.findByTestId('image-verdict', undefined, { timeout: 3000 });
    expect(verdict.textContent?.trim()).not.toBe('');
    expect(verdict.querySelector('[aria-hidden="true"]')).not.toBeNull();
  });
});

describe('Improve your image (S391)', () => {
  it('lists the live listings whose main photo needs work, failing ones first, in plain words with the days left', async () => {
    await renderScreen(<ImageTasksBanner />);
    const tasks = within(await screen.findByTestId('image-tasks'));
    expect(tasks.getByText('Improve your image')).toBeInTheDocument();
    expect(tasks.getByText('2 listings need a better main photo')).toBeInTheDocument();
    const rows = tasks.getAllByRole('listitem').filter((li) => li.hasAttribute('data-state'));
    expect(rows.map((r) => r.getAttribute('data-state'))).toEqual(['FAIL', 'IMPROVE']);
    expect(rows[0]).toHaveTextContent('The main photo of this listing does not meet our photo rules yet.');
    expect(rows[0]).toHaveTextContent('The main photo needs a plain white background');
    expect(rows[0]).toHaveTextContent('48 days left to improve it before this listing may be limited in search and on the buy button.');
    expect(rows[1]).toHaveTextContent('The main photo of this listing works, and it could sell better.');
    expect(rows[1]).toHaveTextContent('The product could fill more of the photo. 85% or more looks best.');
    const link = within(rows[0] as HTMLElement).getByRole('link', { name: 'Replace the main photo' });
    expect(link.getAttribute('href')).toMatch(/^\/listings\/[^/]+\/edit$/);
    expect(document.body.textContent).not.toMatch(/AT[0-9A-Z]{8}/);
  });

  it('shows at the top of Listings, and the listing says it on its photos', async () => {
    await renderScreen(<ListingsView />);
    expect(await screen.findByTestId('image-tasks')).toBeInTheDocument();
    const first = (await screen.findByTestId('image-tasks')).querySelector('a')?.getAttribute('href') ?? '';
    const id = /^\/listings\/([^/]+)\/edit$/.exec(first)?.[1] ?? '';
    expect(id).not.toBe('');
  });

  it('shows on the photos of a listing that has a task, with the reasons, and nowhere else', async () => {
    await renderScreen(<ProductForm productId="prod-1" />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Edit product' })).toBeInTheDocument();
    const notice = await screen.findByText('The main photo of this listing does not meet our photo rules yet.', undefined, { timeout: 3000 });
    expect(notice.closest('[role]')?.textContent).toMatch(/Improve your image/);
    expect(notice.closest('[role]')?.textContent).toMatch(/plain white background/);
  });
});
