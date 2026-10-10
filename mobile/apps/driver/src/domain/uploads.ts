/* Photos and signatures are captured offline as local files; before an event is sent, each one is uploaded to
   media-service and the event gets the asset id (pure helpers; unit-tested). */
import { type PhotoCapture, type QueuedEvent } from './types';

export interface PendingUpload {
  localUri: string;
  /** Applies the asset id to a copy of the event. */
  apply: (e: QueuedEvent, assetId: string) => QueuedEvent;
}

const photoDone = (p: PhotoCapture | undefined): boolean => !p || !!p.assetId || !p.localUri;

export function pendingUploads(e: QueuedEvent): PendingUpload[] {
  const out: PendingUpload[] = [];
  if (e.type === 'inspection.submitted') {
    for (const [side, p] of Object.entries(e.inspection.photos)) {
      if (!photoDone(p) && p.localUri) out.push({ localUri: p.localUri, apply: (ev, id) => (ev.type === 'inspection.submitted' ? { ...ev, inspection: { ...ev.inspection, photos: { ...ev.inspection.photos, [side]: { ...p, assetId: id } } } } : ev) });
    }
    const sig = e.inspection.signatureLocalUri;
    if (sig && !e.inspection.signatureAssetId) out.push({ localUri: sig, apply: (ev, id) => (ev.type === 'inspection.submitted' ? { ...ev, inspection: { ...ev.inspection, signatureAssetId: id } } : ev) });
  }
  if (e.type === 'delivery.completed') {
    const p = e.delivery.photo;
    if (!photoDone(p) && p.localUri) out.push({ localUri: p.localUri, apply: (ev, id) => (ev.type === 'delivery.completed' ? { ...ev, delivery: { ...ev.delivery, photo: { ...ev.delivery.photo, assetId: id } } } : ev) });
    const sig = e.delivery.signatureLocalUri;
    if (sig && !e.delivery.signatureAssetId) out.push({ localUri: sig, apply: (ev, id) => (ev.type === 'delivery.completed' ? { ...ev, delivery: { ...ev.delivery, signatureAssetId: id } } : ev) });
  }
  if (e.type === 'delivery.problem' && e.problem.photo && !photoDone(e.problem.photo) && e.problem.photo.localUri) {
    const p = e.problem.photo;
    out.push({ localUri: p.localUri ?? '', apply: (ev, id) => (ev.type === 'delivery.problem' && ev.problem.photo ? { ...ev, problem: { ...ev.problem, photo: { ...p, assetId: id } } } : ev) });
  }
  if (e.type === 'pickup.problem' && e.pickupProblem.photo && !photoDone(e.pickupProblem.photo) && e.pickupProblem.photo.localUri) {
    const p = e.pickupProblem.photo;
    out.push({ localUri: p.localUri ?? '', apply: (ev, id) => (ev.type === 'pickup.problem' && ev.pickupProblem.photo ? { ...ev, pickupProblem: { ...ev.pickupProblem, photo: { ...p, assetId: id } } } : ev) });
  }
  return out;
}
