import { zipSync } from 'fflate';
import { describe, expect, it } from 'vitest';

import { checkZipEntries, expandFiles, filesFromZip, LibraryZipError, MAX_FILES_PER_DROP, MAX_LIBRARY_BYTES, MAX_ZIP_TOTAL_BYTES, readZipDirectory } from '@/components/listings/images/library-files';

const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4]);
const zipFile = (entries: Record<string, Uint8Array>, name = 'photos.zip') => new File([zipSync(entries) as BlobPart], name, { type: 'application/zip' });

/** Rewrites the stated sizes in every central directory entry: a crafted archive whose header lies about its contents. */
function withStatedSizes(zip: Uint8Array, compressed: number, uncompressed: number): Uint8Array {
  const out = new Uint8Array(zip);
  const view = new DataView(out.buffer);
  for (let i = 0; i + 46 <= out.length; i++) {
    if (view.getUint32(i, true) === 0x02014b50) {
      view.setUint32(i + 20, compressed, true);
      view.setUint32(i + 24, uncompressed, true);
    }
  }
  return out;
}

const rejection = async (file: File): Promise<LibraryZipError> => {
  try {
    await filesFromZip(file);
  } catch (e) {
    expect(e).toBeInstanceOf(LibraryZipError);
    return e as LibraryZipError;
  }
  throw new Error('the ZIP was accepted');
};

describe('Image library › ZIP files (S310 zip-bomb guard)', () => {
  it('opens a normal ZIP: images only, folders flattened, hidden and macOS files skipped', async () => {
    const files = await expandFiles([
      zipFile({ 'SKU1.MAIN.jpg': JPEG, 'set/SKU1.PT01.jpg': JPEG, '__MACOSX/._SKU1.MAIN.jpg': JPEG, 'notes.txt': new Uint8Array([65]), '.hidden.jpg': JPEG })
    ]);
    expect(files.map((f) => f.name).sort()).toEqual(['SKU1.MAIN.jpg', 'SKU1.PT01.jpg']);
    expect(files.every((f) => f.type === 'image/jpeg')).toBe(true);
  });

  it('reads the central directory without inflating anything', () => {
    const entries = readZipDirectory(zipSync({ 'a/SKU1.MAIN.jpg': JPEG, 'b.png': JPEG }));
    expect(entries.map((e) => e.name).sort()).toEqual(['a/SKU1.MAIN.jpg', 'b.png']);
    expect(entries.every((e) => e.uncompressed === JPEG.length)).toBe(true);
  });

  it('refuses an entry over the per-file cap before inflating it', async () => {
    const bomb = zipFile({ 'SKU1.MAIN.jpg': new Uint8Array(MAX_LIBRARY_BYTES + 1) });
    const e = await rejection(bomb);
    expect(e.code).toBe('entry-too-large');
    expect(e.detail).toMatchObject({ name: 'SKU1.MAIN.jpg', limit: 8 });
  });

  it('refuses an image that compresses more than 100 to 1', async () => {
    const e = await rejection(zipFile({ 'SKU1.MAIN.jpg': new Uint8Array(5 * 1024 * 1024) }));
    expect(e.code).toBe('ratio');
  });

  it('refuses more images than the drop cap', async () => {
    const many: Record<string, Uint8Array> = {};
    for (let i = 0; i <= MAX_FILES_PER_DROP; i++) many[`SKU${i}.MAIN.jpg`] = JPEG;
    const e = await rejection(zipFile(many));
    expect(e.code).toBe('too-many');
    expect(e.detail).toMatchObject({ count: MAX_FILES_PER_DROP + 1, limit: MAX_FILES_PER_DROP });
  });

  it('refuses an archive whose images add up to more than 500 MB, from the headers alone', async () => {
    const entries: Record<string, Uint8Array> = {};
    for (let i = 0; i < 80; i++) entries[`SKU${i}.MAIN.jpg`] = JPEG;
    const crafted = withStatedSizes(zipSync(entries), 7 * 1024 * 1024, 7 * 1024 * 1024);
    expect(80 * 7 * 1024 * 1024).toBeGreaterThan(MAX_ZIP_TOTAL_BYTES);
    const e = await rejection(new File([crafted as BlobPart], 'big.zip', { type: 'application/zip' }));
    expect(e.code).toBe('too-large');
    expect(e.detail).toMatchObject({ limit: 500 });
  });

  it('refuses an archive that expands over 100 to 1 overall', () => {
    const entries = Array.from({ length: 40 }, (_, i) => ({ name: `SKU${i}.MAIN.jpg`, compressed: 4096, uncompressed: 1_000_000 }));
    expect(() => checkZipEntries(entries, 40 * 4096)).toThrowError(LibraryZipError);
    expect(() => checkZipEntries(entries.map((e) => ({ ...e, compressed: 900_000 })), 40 * 900_000)).not.toThrow();
  });

  it('checks what was produced when the headers understate it', async () => {
    const lying = withStatedSizes(zipSync({ 'SKU1.MAIN.jpg': new Uint8Array(MAX_LIBRARY_BYTES + 1) }), 20, 100);
    await expect(filesFromZip(new File([lying as BlobPart], 'liar.zip', { type: 'application/zip' }))).rejects.toBeInstanceOf(LibraryZipError);
  });

  it('refuses something that is not a ZIP', async () => {
    const e = await rejection(new File([new Uint8Array(64)], 'photos.zip', { type: 'application/zip' }));
    expect(e.code).toBe('unreadable');
  });

  it('lets ordinary files through untouched', async () => {
    const jpg = new File([JPEG as BlobPart], 'SKU1.MAIN.jpg', { type: 'image/jpeg' });
    expect(await expandFiles([jpg])).toEqual([jpg]);
  });
});
