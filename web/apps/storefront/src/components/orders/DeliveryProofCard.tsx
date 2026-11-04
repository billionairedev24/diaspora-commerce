'use client';

import { type DeliveryProof } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Dialog, ImagePlaceholder, TextLinkButton } from '@afrotransact/ui';
import { useState } from 'react';

import { useT } from '@/i18n/t';

import styles from './Orders.module.css';

/** Proof of delivery: how our driver handed the parcel over, to whom, and the pictures taken at the door. */
export function DeliveryProofCard({ proof }: { proof: DeliveryProof }) {
  const t = useT();
  const fmt = useFormatters();
  const how =
    proof.method === 'CUSTOMER'
      ? t('ord.proofCustomer')
      : proof.method === 'SOMEONE_ELSE'
        ? t('ord.proofSomeoneElse', { name: [proof.recipientName, proof.relationship ? `(${proof.relationship.toLowerCase()})` : ''].filter(Boolean).join(' ') || t('ord.proofSomeone') })
        : t('ord.proofSafePlace', { place: (proof.safePlace ?? t('ord.proofSafeDefault')).toLowerCase() });
  const when = new Date(proof.at).toLocaleString(fmt.tag, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
  return (
    <div className={styles.proof} data-testid="delivery-proof">
      <p className={styles.proofHow}>
        {how} <span className={styles.muted}>· {when}</span>
      </p>
      {(proof.photoUrl || proof.signatureUrl) && (
        <div className={styles.proofPictures}>
          {proof.photoUrl && (
            <figure className={styles.proofFigure}>
              <ImagePlaceholder src={proof.photoUrl} alt={t('ord.proofPhotoAlt')} ratio="4 / 3" />
              <figcaption>{t('ord.proofPhoto')}</figcaption>
            </figure>
          )}
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
