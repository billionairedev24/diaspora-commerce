'use client';

import {
  accountQueries,
  isApiError,
  type PublicReview,
  REVIEW_REASON_CODES,
  REVIEW_SORTS,
  reviewQueries,
  type ReviewRating,
  type ReviewReasonCode,
  type ReviewSort} from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, Dialog,SealCheck, Select, Skeleton, Stars, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './Reviews.module.css';
import { ReviewSheet } from './ReviewSheet';

const PAGE_SIZE = 5;

function Breakdown({ rating, stars, onPick }: { rating: ReviewRating; stars: number; onPick: (n: number) => void }) {
  const t = useT();
  const fmt = useFormatters();
  return (
    <ul className={styles.bars} aria-label={t('rev.breakdown')}>
      {[5, 4, 3, 2, 1].map((n) => {
        const count = rating.breakdown[n - 1] ?? 0;
        const pct = rating.count ? Math.round((count / rating.count) * 100) : 0;
        return (
          <li key={n}>
            <button type="button" className={styles.barRow} aria-pressed={stars === n} aria-label={t('rev.filterStars', { n, count })} onClick={() => onPick(stars === n ? 0 : n)}>
              <span>{t('rev.stars', { n })}</span>
              <span className={styles.track} aria-hidden>
                <span className={styles.fill} style={{ width: `${pct}%` }} />
              </span>
              <span className={styles.count}>{fmt.number(count)}</span>
            </button>
          </li>
        );
      })}
    </ul>
  );
}

/** Report a review: a reason, and details for "Other". Customers and sellers can report; the review stays up until staff decide. */
function ReportDialog({ review, open, onOpenChange }: { review: PublicReview; open: boolean; onOpenChange: (open: boolean) => void }) {
  const t = useT();
  const toast = useToast();
  const [reason, setReason] = useState<ReviewReasonCode>('ABUSIVE');
  const [details, setDetails] = useState('');
  const [error, setError] = useState<string | null>(null);
  const report = useMutation({
    mutationFn: () => browserApi().reportProductReview(review.id, { reasonCode: reason, details: details.trim() || undefined }),
    onSuccess: (r) => {
      toast.show(r.created ? t('rev.reported') : t('rev.alreadyReported'));
      onOpenChange(false);
    },
    onError: (e) => setError(isApiError(e) && e.status === 403 ? t('rev.reportOwn') : isApiError(e) && e.status === 401 ? t('rev.reportSignIn') : t('auth.genericError'))
  });
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={t('rev.reportTitle')} description={t('rev.reportDescription')} closeLabel={t('rev.close')}>
      <form
        className={styles.form}
        onSubmit={(e) => {
          e.preventDefault();
          if (reason === 'OTHER' && !details.trim()) {
            setError(t('rev.reportDetails'));
            return;
          }
          setError(null);
          report.mutate();
        }}
      >
        <Select label={t('rev.reportReason')} value={reason} onChange={(e) => setReason(e.currentTarget.value as ReviewReasonCode)} options={REVIEW_REASON_CODES.map((c) => ({ value: c, label: t(`rev.reason.${c}`) }))} />
        <TextField label={t('rev.reportMore')} value={details} maxLength={500} onChange={(e) => setDetails(e.currentTarget.value)} />
        {error && <p className={styles.error} role="alert">{error}</p>}
        <div className={styles.formActions}>
          <Button type="submit" loading={report.isPending}>{t('rev.reportSend')}</Button>
          <Button type="button" variant="neutral" onClick={() => onOpenChange(false)}>{t('common.cancel')}</Button>
        </div>
      </form>
    </Dialog>
  );
}

function ReviewItem({ review, canReport }: { review: PublicReview; canReport: boolean }) {
  const t = useT();
  const fmt = useFormatters();
  const [reporting, setReporting] = useState(false);
  return (
    <li className={styles.review} data-testid="review">
      <div className={styles.who}>
        <strong>{review.reviewerName}</strong>
        <span className={styles.verified}><SealCheck weight="fill" aria-hidden /> {t('rev.verified')}</span>
      </div>
      <div className={styles.who}>
        <Stars rating={review.rating} ratingLabel={() => t('rev.ratedAria', { n: review.rating })} locale={fmt.tag} />
        {review.title && <h3 className={styles.title}>{review.title}</h3>}
      </div>
      <p className={styles.muted}>{t('rev.reviewedOn', { date: new Date(review.createdAt).toLocaleDateString(fmt.tag, { month: 'long', day: 'numeric', year: 'numeric' }) })}</p>
      {review.text && <p className={styles.text}>{review.text}</p>}
      {review.photos.length > 0 && (
        <ul className={styles.photos} aria-label={t('rev.photos')}>
          {review.photos.map((p) => (
            <li key={p.url}>
              <a href={p.url} target="_blank" rel="noopener noreferrer">
                <img src={p.url} alt={t('rev.photoAlt', { name: review.reviewerName })} loading="lazy" className={styles.photo} />
              </a>
            </li>
          ))}
        </ul>
      )}
      {review.reply && (
        <div className={styles.reply}>
          <strong>{t('rev.sellerReply')}</strong>
          <span>{review.reply.text}</span>
        </div>
      )}
      {canReport && (
        <div className={styles.actions}>
          <Button variant="link" onClick={() => setReporting(true)}>{t('rev.report')}</Button>
        </div>
      )}
      {reporting && <ReportDialog review={review} open={reporting} onOpenChange={setReporting} />}
    </li>
  );
}

/**
 * The ratings section of the product page (S324): average, count and star breakdown (each row filters the list), the
 * reviews sorted and paged, and the shopper's own review or "Write a review" when they received the item.
 */
export function ProductReviews({ atin, title }: { atin: string; title: string }) {
  const t = useT();
  const fmt = useFormatters();
  const api = browserApi();
  const [sort, setSort] = useState<ReviewSort>('recent');
  const [stars, setStars] = useState(0);
  const [page, setPage] = useState(0);
  const [writing, setWriting] = useState(false);
  const list = useQuery({ ...reviewQueries.list(api, atin, { sort, stars: stars || undefined, page, size: PAGE_SIZE }), placeholderData: (prev) => prev });
  const { data: me } = useQuery(accountQueries.me(api));
  const signedIn = me?.authenticated === true;
  const mine = useQuery({ ...reviewQueries.mine(api, atin), enabled: signedIn, retry: false });
  const data = list.data;
  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;
  const own = mine.data?.review ?? null;
  const pick = (n: number) => {
    setStars(n);
    setPage(0);
  };
  return (
    <section className={styles.section} aria-labelledby="reviews-title" id="reviews" data-testid="product-reviews">
      <div className={styles.summary}>
        <h2 className={styles.h2} id="reviews-title">{t('rev.title')}</h2>
        {list.isPending && <Skeleton height={160} />}
        {data && data.rating.count === 0 && <p className={styles.muted}>{t('rev.none')}</p>}
        {data && data.rating.count > 0 && (
          <>
            <div className={styles.average}>
              <strong>{fmt.number(data.rating.average, { minimumFractionDigits: 1, maximumFractionDigits: 1 })}</strong>
              <Stars rating={data.rating.average} size={18} ratingLabel={(r) => t('card.rating', { rating: r })} locale={fmt.tag} />
            </div>
            <p className={styles.muted}>{t('rev.count', { count: data.rating.count })}</p>
            <Breakdown rating={data.rating} stars={stars} onPick={pick} />
          </>
        )}
        {signedIn && mine.data?.eligible && (
          <Button variant="neutral" onClick={() => setWriting(true)}>{t('rev.write')}</Button>
        )}
        {signedIn && !own && mine.data && !mine.data.eligible && <p className={styles.muted}>{t('rev.onlyBuyers')}</p>}
        {!signedIn && me && <p className={styles.muted}>{t('rev.onlyBuyers')}</p>}
      </div>
      <div>
        {own && (
          <div className={styles.mine} data-testid="own-review">
            <strong>{t('rev.yours')}</strong>
            {own.status === 'PENDING' && <span className={styles.pending}>{t('rev.pending')}</span>}
            {(own.status === 'REJECTED' || own.status === 'REMOVED') && <p className={styles.error}>{t('rev.moderatedNote', { reason: own.moderation?.reason ?? '' })}</p>}
            <Stars rating={own.rating} ratingLabel={() => t('rev.ratedAria', { n: own.rating })} locale={fmt.tag} />
            {own.title && <p className={styles.title}>{own.title}</p>}
            {own.text && <p className={styles.text}>{own.text}</p>}
            <div className={styles.actions}>
              <Button variant="link" onClick={() => setWriting(true)}>{own.editable ? t('rev.edit') : t('rev.view')}</Button>
            </div>
          </div>
        )}
        <div className={styles.controls}>
          <p className={styles.muted} role="status">{stars ? t('rev.showingStars', { n: stars }) : data ? t('rev.showingAll', { count: data.total }) : ''}</p>
          <label className={styles.sort}>
            {t('rev.sortBy')}
            <select className={styles.select} value={sort} onChange={(e) => { setSort(e.currentTarget.value as ReviewSort); setPage(0); }}>
              {REVIEW_SORTS.map((s) => <option key={s} value={s}>{t(`rev.sort.${s}`)}</option>)}
            </select>
          </label>
        </div>
        {list.isError && <p className={styles.error} role="alert">{t('acct.loadError')}</p>}
        {data && data.items.length > 0 && (
          <ul className={styles.list}>
            {data.items.map((r) => <ReviewItem key={r.id} review={r} canReport={signedIn && r.id !== own?.id} />)}
          </ul>
        )}
        {data && data.total > data.size && (
          <nav className={styles.pager} aria-label={t('rev.pages')}>
            <Button variant="neutral" size="sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>{t('rev.previous')}</Button>
            <span className={styles.muted}>{t('rev.pageOf', { page: page + 1, pages })}</span>
            <Button variant="neutral" size="sm" disabled={page + 1 >= pages} onClick={() => setPage((p) => p + 1)}>{t('rev.next')}</Button>
          </nav>
        )}
      </div>
      {writing && <ReviewSheet atin={atin} title={title} open={writing} onOpenChange={setWriting} />}
    </section>
  );
}
