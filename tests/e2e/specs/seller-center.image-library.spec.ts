import { crc32 } from 'node:zlib';

import { expect, test } from '@playwright/test';

import { SELLER_ORG, setActiveOrg } from '../support/seller';

/* Seller Center image library (S310) against the mock BFF: a small ZIP of SKU-named photos is dropped, opened in the browser and uploaded one by one;
   a ZIP that would expand past the per-file cap is refused with a clear message before anything is inflated. The mock library is process-wide state,
   so each run uses its own SKU. */

const RUN = Date.now().toString(36).toUpperCase();
const SKU = `ZIP${RUN}`;

interface Entry {
  name: string;
  data: Buffer;
  /** What the central directory states as the uncompressed size, when it lies about the data. */
  statedSize?: number;
}

/** A ZIP with stored (uncompressed) entries: local headers, data, central directory, end record. */
function zip(entries: Entry[]): Buffer {
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let offset = 0;
  for (const e of entries) {
    const name = Buffer.from(e.name, 'utf8');
    const crc = crc32(e.data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(e.data.length, 18);
    local.writeUInt32LE(e.data.length, 22);
    local.writeUInt16LE(name.length, 26);
    locals.push(local, name, e.data);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(e.data.length, 20);
    central.writeUInt32LE(e.statedSize ?? e.data.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, name);
    offset += local.length + name.length + e.data.length;
  }
  const directory = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, end]);
}

const PHOTO = Buffer.from('/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=', 'base64');

test.describe('seller center · image library', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
  });

  test('a small ZIP of SKU-named photos is opened and every photo uploaded', async ({ page }) => {
    await page.goto('/listings/images');
    await page.getByTestId('library-files').setInputFiles({
      name: 'photos.zip',
      mimeType: 'application/zip',
      buffer: zip([
        { name: `${SKU}.MAIN.jpg`, data: PHOTO },
        { name: `folder/${SKU}.PT01.jpg`, data: PHOTO },
        { name: `__MACOSX/._${SKU}.MAIN.jpg`, data: PHOTO },
        { name: 'readme.txt', data: Buffer.from('not a photo') }
      ])
    });
    await expect(page.getByRole('row', { name: new RegExp(`${SKU}.MAIN.jpg ${SKU} · Main`) })).toBeVisible();
    await expect(page.getByRole('row', { name: new RegExp(`${SKU}.PT01.jpg ${SKU} · Image 2`) })).toBeVisible();
    await expect(page.getByText('readme.txt')).toHaveCount(0);
    await expect(page.getByText('._' + SKU)).toHaveCount(0);
  });

  test('a ZIP that would expand past the per-file limit is refused before it is opened', async ({ page }) => {
    await page.goto('/listings/images');
    await page.getByTestId('library-files').setInputFiles({
      name: 'bomb.zip',
      mimeType: 'application/zip',
      buffer: zip([{ name: `${SKU}-BOMB.MAIN.jpg`, data: PHOTO, statedSize: 9 * 1024 * 1024 }])
    });
    await expect(page.getByText(`${SKU}-BOMB.MAIN.jpg is larger than 8 MB once opened. Resize it and try again.`)).toBeVisible();
    await expect(page.getByText(/Uploaded \d+ of \d+\./)).toHaveCount(0);
  });

  test('a file that is not a ZIP gets the plain message', async ({ page }) => {
    await page.goto('/listings/images');
    await page.getByTestId('library-files').setInputFiles({ name: 'broken.zip', mimeType: 'application/zip', buffer: Buffer.alloc(64) });
    await expect(page.getByText('That ZIP could not be opened. Check that it is a normal ZIP file.')).toBeVisible();
  });
});
