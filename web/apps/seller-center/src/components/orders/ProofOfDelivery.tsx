'use client';

import { type DeliveryProof } from '@afrotransact/api-client';
import { ImagePlaceholder } from '@afrotransact/ui';

import { useT } from '@/i18n/t';
import { dateTime } from '@/lib/format';

/** How our driver handed a parcel over: to the buyer, to someone else, or left in a safe place, with the photo and signature. */
export function ProofOfDelivery({ proof }: { proof: DeliveryProof }) {
  const t = useT();
  const how =
    proof.method === 'CUSTOMER'
      ? t('sc.proof.customer')
      : proof.method === 'SOMEONE_ELSE'
        ? t('sc.proof.someoneElse', { name: [proof.recipientName, proof.relationship ? `(${proof.relationship.toLowerCase()})` : ''].filter(Boolean).join(' ') || t('sc.proof.someone') })
        : t('sc.proof.safePlace', { place: (proof.safePlace ?? t('sc.proof.door')).toLowerCase() });
  return (
    <div className="flex flex-col gap-2 rounded-md bg-surface-muted p-3 text-sm" data-testid="proof-of-delivery">
      <strong>{t('sc.proof.title')}</strong>
      <span>
        {how} <span className="text-xs text-muted">· {dateTime(proof.at)}</span>
      </span>
      {proof.photoUrl || proof.signatureUrl ? (
        <div className="grid grid-cols-[repeat(auto-fit,minmax(120px,180px))] gap-3">
          {proof.photoUrl ? (
            <figure className="m-0 flex flex-col gap-1 text-xs text-muted">
              <ImagePlaceholder src={proof.photoUrl} alt={t('sc.proof.photoAlt')} ratio="4 / 3" />
              <figcaption>{t('sc.proof.photo')}</figcaption>
            </figure>
          ) : null}
          {proof.signatureUrl ? (
            <figure className="m-0 flex flex-col gap-1 text-xs text-muted">
              <ImagePlaceholder src={proof.signatureUrl} alt={t('sc.proof.signatureAlt')} ratio="4 / 3" />
              <figcaption>{t('sc.proof.signature')}</figcaption>
            </figure>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
