import { unzip } from 'fflate';

/* Image library files (S310): Amazon / Walmart style names tie a photo to a SKU — SKU.MAIN.jpg is the main image,
   SKU.PT01.jpg … SKU.PT08.jpg the additional ones, SKU.SWCH.jpg the swatch. A file, a folder or a ZIP of them can be
   dropped; media parses the name again when the upload completes. */

export const LIBRARY_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const;
export type LibraryType = (typeof LIBRARY_TYPES)[number];
export const MAX_LIBRARY_BYTES = 8 * 1024 * 1024;
export const MAX_FILES_PER_DROP = 2000;

const NAME = /^(.+?)[._\- ](MAIN|PT0[1-8]|SWCH)\.(?:jpe?g|png|webp)$/i;
const EXT_TYPE: Record<string, LibraryType> = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp' };

export interface ParsedName {
  sku: string;
  slot: string;
}

/** The SKU and slot a file name stands for, or null when it does not follow the convention. */
export function parseLibraryName(fileName: string): ParsedName | null {
  const m = NAME.exec(baseName(fileName));
  return m?.[1] && m[2] ? { sku: m[1].toUpperCase(), slot: m[2].toUpperCase() } : null;
}

export function baseName(path: string): string {
  return path.split(/[\\/]/).pop() ?? path;
}

export function typeOf(fileName: string): LibraryType | null {
  const ext = baseName(fileName).split('.').pop()?.toLowerCase() ?? '';
  return EXT_TYPE[ext] ?? null;
}

const isZip = (f: File) => f.type === 'application/zip' || f.type === 'application/x-zip-compressed' || f.name.toLowerCase().endsWith('.zip');
const hidden = (path: string) => path.startsWith('__MACOSX') || baseName(path).startsWith('.');

/* Zip-bomb guard: the central directory (read first, nothing inflated yet) states every entry's sizes, so an archive that
   would expand to too much is refused before any byte is inflated. What was actually produced is checked again. */
export const MAX_ZIP_ENTRIES = 10_000;
export const MAX_ZIP_TOTAL_BYTES = 500 * 1024 * 1024;
export const MAX_ZIP_RATIO = 100;
/** Ratios on less than this are not suspicious (a few KB of data can legitimately shrink a lot). */
const RATIO_FLOOR_BYTES = 1024 * 1024;

export type LibraryZipErrorCode = 'unreadable' | 'too-many' | 'entry-too-large' | 'too-large' | 'ratio';

/** Why a ZIP was refused, with the numbers the message shows. */
export class LibraryZipError extends Error {
  readonly code: LibraryZipErrorCode;
  readonly detail: Record<string, string | number>;

  constructor(code: LibraryZipErrorCode, detail: Record<string, string | number> = {}) {
    super(`zip refused: ${code}`);
    this.name = 'LibraryZipError';
    this.code = code;
    this.detail = detail;
  }
}

export interface ZipEntry {
  name: string;
  compressed: number;
  uncompressed: number;
}

const EOCD = 0x06054b50;
const CENTRAL = 0x02014b50;
const ZIP64_MARK = 0xffffffff;
const formatMb = (bytes: number) => Math.round(bytes / (1024 * 1024));

/** The entries the central directory lists (names and stated sizes), without inflating anything. */
export function readZipDirectory(bytes: Uint8Array): ZipEntry[] {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let eocd = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 22 - 0xffff); i--) {
    if (view.getUint32(i, true) === EOCD) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new LibraryZipError('unreadable');
  const count = view.getUint16(eocd + 10, true);
  let at = view.getUint32(eocd + 16, true);
  // ZIP64 archives are for 4 GB and up: far beyond the cap.
  if (count === 0xffff || at === ZIP64_MARK) throw new LibraryZipError('too-large', { limit: formatMb(MAX_ZIP_TOTAL_BYTES) });
  if (count > MAX_ZIP_ENTRIES) throw new LibraryZipError('too-many', { count, limit: MAX_ZIP_ENTRIES });
  const decoder = new TextDecoder();
  const entries: ZipEntry[] = [];
  for (let n = 0; n < count; n++) {
    if (at + 46 > bytes.length || view.getUint32(at, true) !== CENTRAL) throw new LibraryZipError('unreadable');
    const compressed = view.getUint32(at + 20, true);
    const uncompressed = view.getUint32(at + 24, true);
    const nameLength = view.getUint16(at + 28, true);
    const extraLength = view.getUint16(at + 30, true);
    const commentLength = view.getUint16(at + 32, true);
    if (compressed === ZIP64_MARK || uncompressed === ZIP64_MARK) throw new LibraryZipError('too-large', { limit: formatMb(MAX_ZIP_TOTAL_BYTES) });
    entries.push({ name: decoder.decode(bytes.subarray(at + 46, at + 46 + nameLength)), compressed, uncompressed });
    at += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

/** Throws a LibraryZipError when the images the archive would inflate are too many, too big, or compress suspiciously well. */
export function checkZipEntries(entries: readonly ZipEntry[], archiveBytes: number): void {
  const images = entries.filter((e) => !hidden(e.name) && typeOf(e.name) !== null);
  if (images.length > MAX_FILES_PER_DROP) throw new LibraryZipError('too-many', { count: images.length, limit: MAX_FILES_PER_DROP });
  let total = 0;
  for (const e of images) {
    if (e.uncompressed > MAX_LIBRARY_BYTES) throw new LibraryZipError('entry-too-large', { name: baseName(e.name), limit: formatMb(MAX_LIBRARY_BYTES) });
    if (e.uncompressed > RATIO_FLOOR_BYTES && e.uncompressed > e.compressed * MAX_ZIP_RATIO) throw new LibraryZipError('ratio', { name: baseName(e.name), ratio: MAX_ZIP_RATIO });
    total += e.uncompressed;
    if (total > MAX_ZIP_TOTAL_BYTES) throw new LibraryZipError('too-large', { limit: formatMb(MAX_ZIP_TOTAL_BYTES) });
  }
  if (total > RATIO_FLOOR_BYTES && total > archiveBytes * MAX_ZIP_RATIO) throw new LibraryZipError('ratio', { name: '', ratio: MAX_ZIP_RATIO });
}

/** Blob.arrayBuffer() where it exists (every current browser), FileReader elsewhere (jsdom). */
function readBuffer(file: Blob): Promise<ArrayBuffer> {
  if (typeof file.arrayBuffer === 'function') return file.arrayBuffer();
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as ArrayBuffer);
    reader.onerror = () => reject(new LibraryZipError('unreadable'));
    reader.readAsArrayBuffer(file);
  });
}

/** The image files in a ZIP (folders inside are flattened; hidden and macOS resource files skipped). A bomb is refused first. */
export function filesFromZip(zip: File): Promise<File[]> {
  return readBuffer(zip).then((buf) => {
    const bytes = new Uint8Array(buf);
    checkZipEntries(readZipDirectory(bytes), bytes.length);
    return new Promise<File[]>((resolve, reject) => {
      unzip(bytes, { filter: (f) => !hidden(f.name) && typeOf(f.name) !== null }, (err, entries) => {
        if (err) {
          reject(new LibraryZipError('unreadable'));
          return;
        }
        // The stated sizes were trusted to allocate; what was really produced is checked too.
        let total = 0;
        for (const [path, data] of Object.entries(entries)) {
          total += data.length;
          if (data.length > MAX_LIBRARY_BYTES) {
            reject(new LibraryZipError('entry-too-large', { name: baseName(path), limit: formatMb(MAX_LIBRARY_BYTES) }));
            return;
          }
        }
        if (total > MAX_ZIP_TOTAL_BYTES) {
          reject(new LibraryZipError('too-large', { limit: formatMb(MAX_ZIP_TOTAL_BYTES) }));
          return;
        }
        resolve(Object.entries(entries).map(([path, data]) => new File([data as BlobPart], baseName(path), { type: typeOf(path) ?? 'application/octet-stream' })));
      });
    });
  });
}

/** Expands dropped or chosen files: ZIPs are opened, everything else is passed through. */
export async function expandFiles(files: readonly File[]): Promise<File[]> {
  const out: File[] = [];
  for (const f of files) {
    if (isZip(f)) out.push(...(await filesFromZip(f)));
    else if (!hidden(f.name)) out.push(f);
  }
  return out;
}

interface EntryLike {
  isFile: boolean;
  isDirectory: boolean;
  name: string;
  file?: (ok: (f: File) => void, fail: (e: unknown) => void) => void;
  createReader?: () => { readEntries: (ok: (entries: EntryLike[]) => void, fail: (e: unknown) => void) => void };
}

async function walk(entry: EntryLike, out: File[]): Promise<void> {
  if (entry.isFile && entry.file) {
    const read = entry.file.bind(entry);
    out.push(await new Promise<File>((ok, fail) => read(ok, fail)));
    return;
  }
  if (entry.isDirectory && entry.createReader) {
    const reader = entry.createReader();
    // readEntries returns at most ~100 entries per call: read until it returns none.
    for (;;) {
      const batch = await new Promise<EntryLike[]>((ok, fail) => reader.readEntries(ok, fail));
      if (!batch.length) break;
      for (const e of batch) await walk(e, out);
    }
  }
}

/** The files of a drop, including the contents of dropped folders. */
export async function droppedFiles(dt: DataTransfer): Promise<File[]> {
  const entries: EntryLike[] = [];
  for (const item of Array.from(dt.items)) {
    const getter = (item as DataTransferItem & { webkitGetAsEntry?: () => EntryLike | null }).webkitGetAsEntry;
    const entry = getter ? getter.call(item) : null;
    if (entry) entries.push(entry);
  }
  if (!entries.length) return Array.from(dt.files);
  const out: File[] = [];
  for (const e of entries) await walk(e, out);
  return out;
}
