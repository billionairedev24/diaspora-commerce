'use client';

import { type CategoryNode, CategoryRequestInputSchema, type ProductTypeSchema, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { Button, Checkbox, cx, Select, TextField, useToast } from '@afrotransact/ui';
import { HourglassMedium, UploadSimple } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { type ChangeEvent, useState } from 'react';

import { PageHeader } from '@/components/ui/PageHeader';
import { problemMessage } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { money } from '@/lib/format';
import { useSeller } from '@/lib/session';

import {
  type BuilderImage,
  type BuilderState,
  comboKey,
  combos,
  emptyBuilder,
  IMAGE_TYPES,
  MAX_IMAGE_BYTES,
  MAX_IMAGES,
  MIN_IMAGE_PX,
  type StepKey,
  STEPS,
  toDraft,
  validateStep
} from './builder-model';

const dims = (file: File) =>
  new Promise<{ w: number; h: number }>((resolve) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => resolve({ w: img.naturalWidth, h: img.naturalHeight });
    img.onerror = () => resolve({ w: 0, h: 0 });
    img.src = url;
  });

function CategoryStep({ s, set, tree, errors }: { s: BuilderState; set: (p: Partial<BuilderState>) => void; tree: CategoryNode[]; errors: Record<string, string> }) {
  const api = sellerApi();
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const [path, setPath] = useState<string[]>(['', '', '']);
  const [reqOpen, setReqOpen] = useState(false);
  const [req, setReq] = useState({ parentPath: '', name: '', exampleProduct: '', detailsNeeded: '' });
  const [reqErr, setReqErr] = useState<Record<string, string>>({});
  const level1 = tree;
  const level2 = level1.find((n) => n.id === path[0])?.children ?? [];
  const level3 = level2.find((n) => n.id === path[1])?.children ?? [];
  const choose = (i: number, id: string) => {
    const next = [...path.slice(0, i), id, ...['', '', ''].slice(i + 1)].slice(0, 3);
    setPath(next);
    const leaf = i === 2 ? level3.find((n) => n.id === id) : null;
    set({ categoryId: leaf?.id ?? null, categoryPath: leaf?.path ?? '', productType: leaf?.productType ?? null, request: null, attrs: {}, theme: '', selected: {}, rows: {} });
  };
  const request = useMutation({
    mutationFn: () => api.requestCategory(req),
    onSuccess: (r) => {
      void qc.invalidateQueries({ queryKey: sellerKeys.categoryRequests(orgId) });
      setReqOpen(false);
      // Category request switches the form to the requested category; the product waits for approval.
      set({ request: { id: r.id, path: r.path, reference: r.reference }, categoryId: null, categoryPath: r.path, productType: 'custom', attrs: {}, theme: '', selected: {}, rows: {} });
    },
    onError: (e) => setReqErr({ name: problemMessage(e) })
  });
  const opts = (nodes: CategoryNode[]) => nodes.map((n) => ({ value: n.id, label: n.name }));
  return (
    <div className="flex flex-col gap-3">
      <h2 className="m-0 text-xl font-semibold">Choose a category</h2>
      <div className="grid gap-2 sm:grid-cols-3">
        <Select label="Department" placeholder="Choose…" options={opts(level1)} value={path[0]} onChange={(e) => choose(0, e.target.value)} />
        <Select label="Category" placeholder="Choose…" options={opts(level2)} value={path[1]} disabled={!path[0]} onChange={(e) => choose(1, e.target.value)} />
        <Select label="Product type" placeholder="Choose…" options={opts(level3)} value={path[2]} disabled={!path[1]} onChange={(e) => choose(2, e.target.value)} error={errors.category} />
      </div>
      {s.categoryPath ? <span className="text-sm text-muted">{s.categoryPath}</span> : null}
      <button type="button" className="link-button self-start border-0 bg-transparent p-0 text-sm text-link hover:underline" aria-expanded={reqOpen} onClick={() => setReqOpen(!reqOpen)}>
        Can’t find your category? Request a new one
      </button>
      {reqOpen ? (
        <form
          className="grid gap-x-3 rounded-sm bg-surface-muted p-3 sm:grid-cols-2"
          onSubmit={(e) => {
            e.preventDefault();
            const p = CategoryRequestInputSchema.safeParse(req);
            if (!p.success) return setReqErr(Object.fromEntries(p.error.issues.map((i) => [String(i.path[0]), i.message])));
            setReqErr({});
            request.mutate();
          }}
        >
          <div className="sm:col-span-2"><TextField label="Where should it go?" placeholder="e.g. Fashion › Women › Bridal" value={req.parentPath} error={reqErr.parentPath} onChange={(e) => setReq({ ...req, parentPath: e.target.value })} /></div>
          <TextField label="New category name" placeholder="e.g. Bridal aso-ebi sets" value={req.name} error={reqErr.name} onChange={(e) => setReq({ ...req, name: e.target.value })} />
          <TextField label="Example product" placeholder="e.g. 6-piece aso-ebi set" value={req.exampleProduct} error={reqErr.exampleProduct} onChange={(e) => setReq({ ...req, exampleProduct: e.target.value })} />
          <div className="sm:col-span-2"><TextField label="Details shoppers need" placeholder="e.g. Pieces, colour, fabric, sizes" value={req.detailsNeeded} error={reqErr.detailsNeeded} onChange={(e) => setReq({ ...req, detailsNeeded: e.target.value })} /></div>
          <div className="flex flex-wrap items-center gap-2 sm:col-span-2">
            <Button type="submit" size="sm" loading={request.isPending}>Send request</Button>
            <span className="text-xs text-muted">Our catalog team reviews requests within 2 business days. You can keep building this product meanwhile.</span>
          </div>
        </form>
      ) : null}
      {s.request ? (
        <p className="m-0 flex items-center gap-2 text-xs">
          <HourglassMedium size={16} aria-hidden /> Requested <strong>{s.request.path}</strong> · Pending catalog review ({s.request.reference}) · you can keep building this product
        </p>
      ) : null}
    </div>
  );
}

function ImagesStep({ s, set, errors }: { s: BuilderState; set: (fn: (prev: BuilderState) => Partial<BuilderState>) => void; errors: Record<string, string> }) {
  const api = sellerApi();
  const [err, setErr] = useState('');
  const update = (id: string, patch: Partial<BuilderImage>) => set((p) => ({ images: p.images.map((i) => (i.id === id ? { ...i, ...patch } : i)) }));
  const add = async (files: FileList | null) => {
    if (!files) return;
    let message = '';
    for (const file of Array.from(files)) {
      if (s.images.length >= MAX_IMAGES) { message = `Up to ${MAX_IMAGES} images.`; break; }
      if (!(IMAGE_TYPES as readonly string[]).includes(file.type)) { message = `${file.name}: use JPG, PNG or WebP.`; continue; }
      if (file.size > MAX_IMAGE_BYTES) { message = `${file.name}: larger than 8 MB.`; continue; }
      const id = `${Date.now()}-${file.name}`;
      const { w, h } = await dims(file);
      const low = w < MIN_IMAGE_PX || h < MIN_IMAGE_PX;
      set((p) => ({ images: [...p.images, { id, previewUrl: URL.createObjectURL(file), url: null, width: w, height: h, bytes: file.size, contentType: file.type, status: 'uploading' }] }));
      try {
        const ticket = await api.requestUpload({ purpose: 'product-image', fileName: file.name, contentType: file.type as (typeof IMAGE_TYPES)[number], size: file.size });
        const put = await fetch(ticket.url, { method: ticket.method, headers: ticket.headers, body: file });
        if (!put.ok) throw new Error('upload failed');
        const asset = await api.completeUpload(ticket.assetId);
        update(id, { url: asset.url, status: low ? 'low' : 'ready' });
      } catch {
        update(id, { status: 'error' });
      }
    }
    setErr(message);
  };
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="m-0 text-xl font-semibold">Add images</h2>
        <span className="text-xs text-muted">{s.images.length}/{MAX_IMAGES} · JPG, PNG or WebP · ≤ 8 MB · ≥ {MIN_IMAGE_PX} px</span>
      </div>
      <label className="flex min-h-30 cursor-pointer flex-col items-center justify-center gap-2 rounded-md border-2 border-dashed border-border-strong bg-surface-sunken p-4 text-center text-sm">
        <UploadSimple size={30} aria-hidden />
        <span><strong>Drop images here</strong> or click to browse</span>
        <span className="text-xs text-muted">Main image on a white background. Uploaded with a signed link, virus-scanned and resized.</span>
        <input type="file" accept={IMAGE_TYPES.join(',')} multiple className="at-visually-hidden" onChange={(e: ChangeEvent<HTMLInputElement>) => void add(e.target.files)} />
      </label>
      <span role="alert" className="min-h-4 text-xs text-danger">{err || errors.images}</span>
      <ul className="m-0 grid list-none grid-cols-[repeat(auto-fill,minmax(110px,1fr))] gap-2 p-0">
        {s.images.map((im, i) => (
          <li key={im.id} className="flex flex-col gap-1">
            <img src={im.previewUrl} alt={`Upload ${i + 1}${i === 0 ? " (main)" : ""}`} className={cx('aspect-square w-full rounded-sm border-2 object-cover', i === 0 ? 'border-gold-500' : 'border-border')} />
            <span className={cx('text-2xs', im.status === 'ready' ? 'text-muted' : 'text-danger')}>
              {i === 0 ? 'Main · ' : ''}{im.status === 'uploading' ? 'Uploading…' : im.status === 'error' ? 'Upload failed' : im.status === 'low' ? `${im.width}×${im.height} — too small for the main image` : `${im.width}×${im.height}`}
            </span>
            <span className="flex gap-2 text-2xs">
              {i > 0 ? <button type="button" className="border-0 bg-transparent p-0 text-link" onClick={() => set((p) => ({ images: [im, ...p.images.filter((x) => x.id !== im.id)] }))}>Main</button> : null}
              <button type="button" className="border-0 bg-transparent p-0 text-danger" onClick={() => set((p) => ({ images: p.images.filter((x) => x.id !== im.id) }))}>Remove</button>
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function DetailsStep({ s, set, type, errors }: { s: BuilderState; set: (p: Partial<BuilderState>) => void; type: ProductTypeSchema | undefined; errors: Record<string, string> }) {
  return (
    <div className="flex flex-col gap-3">
      <h2 className="m-0 text-xl font-semibold">Product details · {type?.label ?? ''}</h2>
      <TextField label="Product title" placeholder="Brand + product + key detail (e.g. Ankara Wrap Dress, Puff Sleeve)" value={s.title} error={errors.title} onChange={(e) => set({ title: e.target.value })} />
      <div className="grid gap-x-3 sm:grid-cols-2">
        {type?.attributes.map((a) =>
          a.options.length ? (
            <Select key={a.key} label={a.label} placeholder="Choose…" options={a.options.map((o) => ({ value: o, label: o }))} value={s.attrs[a.key] ?? ''} error={errors[a.key]} onChange={(e) => set({ attrs: { ...s.attrs, [a.key]: e.target.value } })} />
          ) : (
            <div key={a.key} className={a.multiline ? 'sm:col-span-2' : undefined}>
              <TextField label={a.label} value={s.attrs[a.key] ?? ''} error={errors[a.key]} onChange={(e) => set({ attrs: { ...s.attrs, [a.key]: e.target.value } })} />
            </div>
          )
        )}
      </div>
    </div>
  );
}

function VariationsStep({ s, set, type, errors }: { s: BuilderState; set: (p: Partial<BuilderState>) => void; type: ProductTypeSchema | undefined; errors: Record<string, string> }) {
  const theme = type?.themes.find((t) => t.name === s.theme);
  const list = combos(type, s);
  const row = (k: string) => s.rows[k] ?? { sku: '', gtin: '', price: '', qty: '' };
  const setRow = (k: string, patch: Partial<ReturnType<typeof row>>) => set({ rows: { ...s.rows, [k]: { ...row(k), ...patch } } });
  const idLabel = type?.identifierRule === 'isbn' ? 'ISBN-13' : 'Barcode (GTIN)';
  return (
    <div className="flex flex-col gap-3">
      <h2 className="m-0 text-xl font-semibold">Variations, price &amp; stock</h2>
      <div role="group" aria-label="How does it vary?" className="flex flex-wrap gap-2">
        {type?.themes.map((t) => (
          <button key={t.name} type="button" aria-pressed={s.theme === t.name} onClick={() => set({ theme: t.name, selected: {}, rows: {} })}
            className={cx('h-8 rounded-pill border px-4 text-sm font-semibold', s.theme === t.name ? 'border-gold-500 bg-gold-50' : 'border-border-strong bg-surface')}>
            {t.name}
          </button>
        ))}
      </div>
      {errors.theme ? <span role="alert" className="text-xs text-danger">{errors.theme}</span> : null}
      {theme?.dimensions.map((d) => (
        <div key={d.name} role="group" aria-label={d.name} className="flex flex-col gap-1">
          <span className="text-sm font-semibold">{d.name}</span>
          <div className="flex flex-wrap gap-1">
            {d.options.map((o) => {
              const on = (s.selected[d.name] ?? []).includes(o);
              return (
                <button key={o} type="button" aria-pressed={on}
                  onClick={() => set({ selected: { ...s.selected, [d.name]: on ? (s.selected[d.name] ?? []).filter((x) => x !== o) : [...(s.selected[d.name] ?? []), o] } })}
                  className={cx('h-[30px] rounded-pill border px-3 text-xs font-semibold', on ? 'border-gold-500 bg-gold-50' : 'border-border-strong bg-surface')}>
                  {o}
                </button>
              );
            })}
          </div>
        </div>
      ))}
      {list.length ? (
        <div className="overflow-hidden rounded-md border border-border">
          <table className="at-table text-sm" aria-label="Variations">
            <colgroup><col style={{ width: '22%' }} /><col style={{ width: '24%' }} /><col style={{ width: '26%' }} /><col style={{ width: '14%' }} /><col style={{ width: '14%' }} /></colgroup>
            <thead className="bg-surface-muted text-left">
              <tr>{['Variant', 'SKU', idLabel, 'Price', 'Qty'].map((h) => <th key={h} scope="col" className="p-2 text-xs">{h}</th>)}</tr>
            </thead>
            <tbody>
              {list.map((c) => {
                const k = comboKey(c);
                const r = row(k);
                const input = (field: 'sku' | 'gtin' | 'price' | 'qty', label: string, extra: { inputMode?: 'numeric' | 'decimal'; disabled?: boolean } = {}) => (
                  <>
                    <input aria-label={`${label} for ${k}`} value={r[field]} onChange={(e) => setRow(k, { [field]: e.target.value })} {...extra}
                      className={cx('h-8 w-full rounded-sm border bg-surface px-1.5 text-xs', errors[`${field}:${k}`] ? 'border-danger' : 'border-border-strong', field !== 'price' && field !== 'qty' && 'font-mono')} />
                    {errors[`${field}:${k}`] ? <span className="block text-2xs text-danger">{errors[`${field}:${k}`]}</span> : null}
                  </>
                );
                return (
                  <tr key={k} className="border-t border-border">
                    <td className="p-2 font-semibold">{k}</td>
                    <td className="p-2">{input('sku', 'SKU')}</td>
                    <td className="p-2">{s.exempt ? <span className="text-xs text-muted">Exempt</span> : input('gtin', idLabel, { inputMode: 'numeric' })}</td>
                    <td className="p-2">{input('price', 'Price', { inputMode: 'decimal' })}</td>
                    <td className="p-2">{input('qty', 'Quantity', { inputMode: 'numeric' })}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : null}
      <Checkbox label="No manufacturer barcode (handmade, private label, tailored) — request GTIN exemption" checked={s.exempt} onChange={(e) => set({ exempt: e.target.checked })} />
    </div>
  );
}

function Preview({ s, type }: { s: BuilderState; type: ProductTypeSchema | undefined }) {
  const prices = combos(type, s).map((c) => Number((s.rows[comboKey(c)]?.price ?? '').replace(/[$,]/g, ''))).filter((n) => n > 0);
  const main = s.images[0];
  return (
    <aside aria-label="Shopper preview" className="sticky top-20 flex flex-col gap-2 self-start rounded-md border border-border p-4">
      <strong>Shopper preview</strong>
      {main ? <img src={main.previewUrl} alt="" className="aspect-square w-full rounded-sm object-cover" /> : <div className="grid aspect-square place-items-center rounded-sm bg-surface-muted text-xs text-subtle">No image yet</div>}
      <span className="text-md leading-tight font-medium">{s.title || 'Your product title'}</span>
      <span className="text-xs text-muted">{s.categoryPath || 'Category'}</span>
      <strong className="text-xl">{prices.length ? money(Math.min(...prices) * 100) : '$—'}</strong>
      {combos(type, s).length > 1 ? <span className="text-xs">{combos(type, s).length} options</span> : null}
      <span className="self-start rounded-xs bg-gold-100 px-2 py-0.5 text-2xs font-bold">{s.request ? 'Waits for category approval' : 'In review after you submit'}</span>
    </aside>
  );
}

/** 4-step product builder: category (or request one) → images → details → variations, with a live shopper preview. */
export function ProductBuilder() {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const router = useRouter();
  const { orgId } = useSeller();
  const [s, setS] = useState<BuilderState>(emptyBuilder);
  const [step, setStep] = useState(0);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const tree = useQuery(sellerQueries.categories(api));
  const typeQ = useQuery(sellerQueries.productType(api, s.productType ?? ''));
  const type = typeQ.data;
  const set = (p: Partial<BuilderState>) => setS((prev) => ({ ...prev, ...p }));
  const setFn = (fn: (prev: BuilderState) => Partial<BuilderState>) => setS((prev) => ({ ...prev, ...fn(prev) }));
  const current = STEPS[step]?.key ?? 'category';
  const submit = useMutation({
    mutationFn: () => api.submitProduct(toDraft(s, type)),
    onSuccess: (row) => {
      void qc.invalidateQueries({ queryKey: sellerKeys.listings(orgId) });
      toast.show(row.status === 'PENDING_CATEGORY_APPROVAL' ? 'Saved — it goes to review once the category is approved' : 'Submitted for review');
      router.push('/listings');
    },
    onError: (e) => setErrors({ submit: problemMessage(e) })
  });
  const check = (k: StepKey) => {
    const e = validateStep(k, s, type);
    setErrors(e);
    return Object.keys(e).length === 0;
  };
  const go = (to: number) => {
    // Each step validates before moving forward.
    for (let i = 0; i < to; i++) {
      const k = STEPS[i]?.key;
      if (k && !check(k)) return setStep(i);
    }
    setErrors({});
    setStep(to);
  };
  const last = step === STEPS.length - 1;
  return (
    <>
    <PageHeader title="Add a product" description="Choose a category, add images and details, then set variations, price and stock." />
    <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_300px]">
      <div className="flex min-w-0 flex-col gap-4">
        <ol aria-label="Steps" className="m-0 grid list-none grid-cols-4 gap-2 p-0">
          {STEPS.map((st, i) => (
            <li key={st.key}>
              <button type="button" onClick={() => go(i)} aria-current={i === step ? 'step' : undefined} className="flex w-full flex-col gap-1.5 border-0 bg-transparent p-0 text-left">
                <span className={cx('h-1 rounded-pill', i <= step ? 'bg-gold-500' : 'bg-surface-muted')} />
                <span className={cx('text-xs font-bold', i === step ? 'text-text' : 'text-muted')}>{i + 1}. {st.label}</span>
              </button>
            </li>
          ))}
        </ol>
        <div className="rounded-md border border-border p-5">
          {current === 'category' ? (tree.data ? <CategoryStep s={s} set={set} tree={tree.data} errors={errors} /> : <p className="m-0 text-sm text-muted">Loading categories…</p>) : null}
          {current === 'images' ? <ImagesStep s={s} set={setFn} errors={errors} /> : null}
          {current === 'details' ? <DetailsStep s={s} set={set} type={type} errors={errors} /> : null}
          {current === 'variations' ? <VariationsStep s={s} set={set} type={type} errors={errors} /> : null}
        </div>
        {errors.submit ? <span role="alert" className="text-sm text-danger">{errors.submit}</span> : null}
        <div className="flex items-center gap-2">
          {step > 0 ? <Button variant="neutral" onClick={() => { setErrors({}); setStep(step - 1); }}>Back</Button> : null}
          <span className="flex-1" />
          {last ? (
            <Button loading={submit.isPending} onClick={() => { if (STEPS.every((st) => check(st.key))) submit.mutate(); }}>
              {s.request ? 'Save product (publishes after category approval)' : 'Submit for review'}
            </Button>
          ) : (
            <Button onClick={() => go(step + 1)}>Continue</Button>
          )}
        </div>
      </div>
      <Preview s={s} type={type} />
    </div>
    </>
  );
}
