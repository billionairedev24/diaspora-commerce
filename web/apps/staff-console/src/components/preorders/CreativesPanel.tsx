'use client';

import { staffPreorderKeys } from '@afrotransact/api-client';
import { CREATIVE_SLOTS, type CreativeSlotSpec, type PreorderWindow } from '@afrotransact/api-client/staff-preorders';
import { useToast } from '@afrotransact/ui';
import { Image as ImageIcon, Trash, UploadSimple } from '@phosphor-icons/react/ssr';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type ChangeEvent, useId } from 'react';

import { problemMessage } from '@/components/ui/States';

import { Pill, usePreordersApi } from './primitives';

/** Pixel size of an image file (0 × 0 when the browser can't decode it; media-service measures it again). */
async function imageSize(file: File): Promise<{ width: number; height: number }> {
  try {
    if (typeof createImageBitmap === 'function' && file.type !== 'image/svg+xml') {
      const bmp = await createImageBitmap(file);
      const out = { width: bmp.width, height: bmp.height };
      bmp.close();
      return out;
    }
  } catch {
    /* fall through */
  }
  if (typeof Image === 'undefined' || typeof URL.createObjectURL !== 'function') return { width: 0, height: 0 };
  const url = URL.createObjectURL(file);
  try {
    return await new Promise((resolve) => {
      const img = new Image();
      const timer = setTimeout(() => resolve({ width: 0, height: 0 }), 3000);
      img.onload = () => {
        clearTimeout(timer);
        resolve({ width: img.naturalWidth, height: img.naturalHeight });
      };
      img.onerror = () => {
        clearTimeout(timer);
        resolve({ width: 0, height: 0 });
      };
      img.src = url;
    });
  } finally {
    URL.revokeObjectURL(url);
  }
}

function SlotCard({ w, slot, canEdit }: { w: PreorderWindow; slot: CreativeSlotSpec; canEdit: boolean }) {
  const api = usePreordersApi();
  const qc = useQueryClient();
  const toast = useToast();
  const inputId = useId();
  const c = w.creatives.find((x) => x.slot === slot.slot);
  const done = (next: PreorderWindow) => {
    qc.setQueryData(staffPreorderKeys.window(w.id), next);
    void qc.invalidateQueries({ queryKey: staffPreorderKeys.summary(w.id) });
  };
  const upload = useMutation({
    mutationFn: async (file: File) => api.uploadCreative(w.id, slot.slot, file, await imageSize(file)),
    onSuccess: (next) => {
      done(next);
      toast.show(`${slot.name} uploaded`);
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const remove = useMutation({ mutationFn: () => api.removeCreative(w.id, slot.slot), onSuccess: done, onError: (e) => toast.show(problemMessage(e), 'error') });
  const onFile = (e: ChangeEvent<HTMLInputElement>) => {
    const f = e.currentTarget.files?.[0];
    e.currentTarget.value = '';
    if (f) upload.mutate(f);
  };
  const status = !c ? (slot.required ? 'Required' : 'Missing') : c.ratioOff ? `Ratio off · ${c.width}×${c.height}` : 'Uploaded';
  const pill = !c ? (slot.required ? 'bg-red-50 text-red-700' : 'bg-ink-100 text-ink-700') : c.ratioOff ? 'bg-gold-100 text-gold-900' : 'bg-green-50 text-green-800';
  return (
    <div className="flex flex-col gap-2 rounded-md border border-border bg-ink-0 p-3" data-testid={`creative-${slot.slot}`}>
      <div
        className={`relative grid max-h-[260px] place-items-center overflow-hidden rounded-sm border border-dashed bg-ink-50 ${c ? 'border-transparent' : 'border-ink-300'}`}
        style={{ aspectRatio: `${slot.width} / ${slot.height}` }}
      >
        {c ? (
          <div role="img" aria-label={slot.name} className="absolute inset-0 bg-cover bg-center bg-no-repeat" style={{ backgroundImage: `url("${c.url}")` }} />
        ) : (
          <span className="flex flex-col items-center gap-1 text-xs text-muted">
            <ImageIcon size={26} aria-hidden />
            {slot.spec}
          </span>
        )}
      </div>
      <span className="flex items-center justify-between gap-2">
        <strong className="text-sm">{slot.name}</strong>
        <Pill className={`px-2 py-px text-2xs ${pill}`}>{status}</Pill>
      </span>
      <span className="text-xs text-muted">
        {slot.where} · {slot.spec}
      </span>
      <div className="flex gap-2">
        {canEdit ? (
          <>
            <label
              htmlFor={inputId}
              aria-busy={upload.isPending || undefined}
              className="flex h-8 flex-1 cursor-pointer items-center justify-center gap-1 rounded-sm border border-border-strong text-xs font-bold focus-within:outline-2 focus-within:outline-focus"
            >
              <UploadSimple size={14} aria-hidden />
              {upload.isPending ? 'Uploading…' : c ? 'Replace' : 'Upload'}
              <span className="at-visually-hidden">{slot.name}</span>
              <input id={inputId} type="file" accept="image/*" onChange={onFile} className="at-visually-hidden" />
            </label>
            {c ? (
              <button type="button" onClick={() => remove.mutate()} aria-label={`Remove ${slot.name}`} className="h-8 w-8 cursor-pointer rounded-sm border border-border-strong bg-ink-0">
                <Trash size={14} aria-hidden />
              </button>
            ) : null}
          </>
        ) : (
          <span className="text-xs text-subtle">View only for your role</span>
        )}
      </div>
    </div>
  );
}

/** The five advertorial slots with spec, where it shows, ratio check and upload / replace / remove. */
export function CreativesPanel({ w, canEdit }: { w: PreorderWindow; canEdit: boolean }) {
  return (
    <div className="flex flex-col gap-3">
      <span className="text-sm text-muted">
        Upload a creative for each slot. As soon as the pre-order is live, it appears in that slot on the storefront, in emails and on social. Only pre-order managers can upload.
      </span>
      <div className="grid items-start gap-4" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(230px, 1fr))' }}>
        {CREATIVE_SLOTS.map((s) => (
          <SlotCard key={s.slot} w={w} slot={s} canEdit={canEdit} />
        ))}
      </div>
    </div>
  );
}
