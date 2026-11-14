'use client';

import { type DeliveryProof } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Dialog, ImagePlaceholder, TextLinkButton } from '@afrotransact/ui';
import { useState } from 'react';

import { useT } from '@/i18n/t';

import styles from './Orders.module.css';

/** Proof of delivery, only for an order AfroTransact delivered: how it was handed over, to whom, when, and the photos taken at the door. */
export function DeliveryProofCard({ proof }: { proof: DeliveryProof }) {
  const t = useT();
  const fmt = useFormatters();
  const how =
    proof.method === 'PHOTO'
      ? proof.recipientName
        ? t('ord.proofReceivedBy', { name: proof.recipientName })
        : t('ord.proofDeliveredByUs')
      : proof.method === 'CUSTOMER'
      ? t('ord.proofCustomer')
      : proof.method === 'SOMEONE_ELSE'
        ? t('ord.proofSomeoneElse', { name: [proof.recipientName, proof.relationship ? `(${proof.relationship.toLowerCase()})` : ''].filter(Boolean).join(' ') || t('ord.proofSomeone') })
        : t('ord.proofSafePlace', { place: (proof.safePlace ?? t('ord.proofSafeDefault')).toLowerCase() });
  const photos = proof.photoUrls.length > 0 ? proof.photoUrls : proof.photoUrl ? [proof.photoUrl] : [];
  const when = new Date(proof.at).toLocaleString(fmt.tag, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
  return (
    <div className={styles.proof} data-testid="delivery-proof">
      <p className={styles.proofHow}>
        {how} <span className={styles.muted}>· {when}</span>
      </p>
      {(photos.length > 0 || proof.signatureUrl) && (
        <div className={styles.proofPictures}>
          {photos.map((url, i) => (
            <figure className={styles.proofFigure} key={url}>
              <ImagePlaceholder src={url} alt={photos.length > 1 ? t('ord.proofPhotoAltN', { n: i + 1 }) : t('ord.proofPhotoAlt')} ratio="4 / 3" />
              <figcaption>{photos.length > 1 ? t('ord.proofPhotoN', { n: i + 1 }) : t('ord.proofPhoto')}</figcaption>
            </figure>
          ))}
          {proof.signatureUrl && (
            <figure className={styles.proofFigure}>
              <ImagePlaceholder src={proof.signatureUrl} alt={t('ord.proofSignatureAlt')} ratio="4 / 3" />
              <figcaption>{t('ord.proofSignature')}</figcaption>
            </figure>
          )}
        </div>
      )}
    </div>
  );
}

/** "View delivery proof": a plain link that opens the proof (who received it, the photo and signature) in a dialog. */
export function DeliveryProofLink({ proof }: { proof: DeliveryProof }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  return (
    <>
      <TextLinkButton onClick={() => setOpen(true)} data-testid="order-view-proof">
        {t('ord.viewProof')}
      </TextLinkButton>
      <Dialog open={open} onOpenChange={setOpen} title={t('ord.proofTitle')} closeLabel={t('ord.closePopover')} width={520}>
        <DeliveryProofCard proof={proof} />
      </Dialog>
    </>
  );
}
