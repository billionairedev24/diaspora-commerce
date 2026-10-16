'use client';

import { type DeliveryProof } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { ImagePlaceholder } from '@afrotransact/ui';

import pageStyles from '@/components/account/Pages.module.css';
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
    <section className={styles.proof} aria-label={t('ord.proofTitle')}>
      <h3 className={styles.proofTitle}>{t('ord.proofTitle')}</h3>
      <p className={styles.proofHow}>
        {how} <span className={pageStyles.muted}>· {when}</span>
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
    </section>
  );
}
