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

/** The image files in a ZIP (folders inside are flattened; hidden and macOS resource files skipped). */
export function filesFromZip(zip: File): Promise<File[]> {
  return zip.arrayBuffer().then((buf) => new Promise<File[]>((resolve, reject) => {
    unzip(new Uint8Array(buf), { filter: (f) => !hidden(f.name) && typeOf(f.name) !== null }, (err, entries) => {
      if (err) {
        reject(err);
        return;
      }
      resolve(Object.entries(entries).map(([path, bytes]) => new File([bytes as BlobPart], baseName(path), { type: typeOf(path) ?? 'application/octet-stream' })));
    });
  }));
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
