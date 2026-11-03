'use client';

import {
  isApiError,
  REVIEW_REASON_CODES,
  type ReviewReasonCode,
  SELLER_REPLY_MAX,
  sellerKeys,
  sellerQueries,
  type SellerReview} from '@afrotransact/api-client';
import { Button, Dialog, Select, Stars, TextField, useToast } from '@afrotransact/ui';
import { SealCheck } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { EmptyState, ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { moduleCopy } from '@/lib/modules';
import { useSeller } from '@/lib/session';

const PAGE_SIZE = 20;

/** Reply once, in public. A reply cannot be edited or removed, so the dialog says so before it is sent. */
function ReplyDialog({ review, onClose }: { review: SellerReview; onClose: () => void }) {
  const t = useT();
  const toast = useToast();
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const reply = useMutation({
    mutationFn: () => sellerApi().replyToReview(review.id, text.trim()),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: sellerKeys.reviews(orgId) });
      toast.show(t('sc.reviews.replied'));
      onClose();
    },
    onError: (e) => setError(isApiError(e) && e.status === 409 ? t('sc.reviews.alreadyReplied') : problemMessage(e))
  });
  return (
    <Dialog open onOpenChange={(o) => (o ? undefined : onClose())} title={t('sc.reviews.replyTitle')} description={t('sc.reviews.replyHint')} width={520}>
      <form
        className="flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (!text.trim()) {
            setError(t('sc.reviews.replyRequired'));
            return;
          }
          setError(null);
          reply.mutate();
        }}
      >
        <p className="m-0 text-sm text-muted">
          <strong>{review.reviewerName}</strong> · {review.title ?? review.text?.slice(0, 80) ?? ''}
        </p>
        <div className="flex flex-col gap-1">
          <label htmlFor="seller-review-reply" className="text-sm font-semibold">{t('sc.reviews.replyLabel')}</label>
          <textarea
            id="seller-review-reply"
            className="min-h-32 w-full rounded-sm border border-border-strong bg-surface p-2"
            maxLength={SELLER_REPLY_MAX}
            value={text}
            onChange={(e) => setText(e.currentTarget.value)}
          />
          <span className="self-end text-xs text-muted">{t('sc.reviews.counter', { n: text.length, max: SELLER_REPLY_MAX })}</span>
        </div>
        {error ? <p role="alert" className="m-0 text-sm text-danger">{error}</p> : null}
        <div className="flex flex-wrap gap-2">
          <Button type="submit" loading={reply.isPending}>{t('sc.reviews.sendReply')}</Button>
          <Button type="button" variant="neutral" onClick={onClose}>{t('sc.reviews.cancel')}</Button>
        </div>
      </form>
    </Dialog>
  );
}

/** Report a review to Trust & Safety (abusive, personal data, spam, not about the product, conflict of interest). */
function ReportDialog({ review, onClose }: { review: SellerReview; onClose: () => void }) {
  const t = useT();
  const toast = useToast();
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const [reason, setReason] = useState<ReviewReasonCode>('ABUSIVE');
  const [details, setDetails] = useState('');
  const [error, setError] = useState<string | null>(null);
  const report = useMutation({
    mutationFn: () => sellerApi().reportReview(review.id, { reasonCode: reason, details: details.trim() || undefined }),
    onSuccess: async (r) => {
      await qc.invalidateQueries({ queryKey: sellerKeys.reviews(orgId) });
      toast.show(r.created ? t('sc.reviews.reported') : t('sc.reviews.alreadyReported'));
      onClose();
    },
    onError: (e) => setError(problemMessage(e))
  });
  return (
    <Dialog open onOpenChange={(o) => (o ? undefined : onClose())} title={t('sc.reviews.reportTitle')} description={t('sc.reviews.reportHint')} width={520}>
      <form
        className="flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (reason === 'OTHER' && !details.trim()) {
            setError(t('sc.reviews.reportDetailsRequired'));
            return;
          }
          setError(null);
          report.mutate();
        }}
      >
        <Select
          label={t('sc.reviews.reportReason')}
          value={reason}
          onChange={(e) => setReason(e.currentTarget.value as ReviewReasonCode)}
          options={REVIEW_REASON_CODES.map((c) => ({ value: c, label: t(`sc.reviews.reason.${c}` as MessageKey) }))}
        />
        <TextField label={t('sc.reviews.reportDetails')} value={details} maxLength={500} onChange={(e) => setDetails(e.currentTarget.value)} />
        {error ? <p role="alert" className="m-0 text-sm text-danger">{error}</p> : null}
        <div className="flex flex-wrap gap-2">
          <Button type="submit" loading={report.isPending}>{t('sc.reviews.sendReport')}</Button>
          <Button type="button" variant="neutral" onClick={onClose}>{t('sc.reviews.cancel')}</Button>
        </div>
      </form>
    </Dialog>
  );
}

function ReviewCard({ review, canEdit }: { review: SellerReview; canEdit: boolean }) {
  const t = useT();
  const [dialog, setDialog] = useState<'reply' | 'report' | null>(null);
  return (
    <li className="flex flex-col gap-2 rounded-md border border-border bg-surface p-4" data-testid="seller-review">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <Stars rating={review.rating} ratingLabel={() => t('sc.reviews.rated', { n: review.rating })} />
        <strong>{review.productTitle}</strong>
        <span className="text-xs text-muted">{review.atin}</span>
      </div>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
        <span>{review.reviewerName}</span>
        <span className="inline-flex items-center gap-1 text-xs font-bold text-success"><SealCheck weight="fill" aria-hidden /> {t('sc.reviews.verified')}</span>
        <span className="text-muted">{shortDate(review.createdAt)}</span>
        {review.reportCount > 0 ? <span className="text-xs text-muted">{t('sc.reviews.reportedCount', { count: review.reportCount })}</span> : null}
      </div>
      {review.title ? <h3 className="m-0 text-md">{review.title}</h3> : null}
      {review.text ? <p className="m-0 whitespace-pre-line break-words">{review.text}</p> : null}
      {review.photos.length > 0 ? (
        <ul className="m-0 flex list-none flex-wrap gap-2 p-0">
          {review.photos.map((p) => (
            <li key={p.url}>
              <a href={p.url} target="_blank" rel="noopener noreferrer">
                <img src={p.url} alt={t('sc.reviews.photoAlt', { name: review.reviewerName })} loading="lazy" className="size-[72px] rounded-sm border border-border object-cover" />
              </a>
            </li>
          ))}
        </ul>
      ) : null}
      {review.reply ? (
        <div className="flex flex-col gap-1 rounded-sm border-s-[3px] border-border-strong bg-surface-muted px-3 py-2 text-sm">
          <strong>{t('sc.reviews.yourReply')}</strong>
          <span className="whitespace-pre-line break-words">{review.reply.text}</span>
          <span className="text-xs text-muted">{shortDate(review.reply.at)}</span>
        </div>
      ) : null}
      {canEdit ? (
        <div className="flex flex-wrap gap-2">
          {review.reply ? null : <Button size="sm" variant="neutral" onClick={() => setDialog('reply')}>{t('sc.reviews.reply')}</Button>}
          <Button size="sm" variant="link" onClick={() => setDialog('report')}>{t('sc.reviews.report')}</Button>
        </div>
      ) : null}
      {dialog === 'reply' ? <ReplyDialog review={review} onClose={() => setDialog(null)} /> : null}
      {dialog === 'report' ? <ReportDialog review={review} onClose={() => setDialog(null)} /> : null}
    </li>
  );
}

/**
 * Reviews of the organisation's products (S324): published, verified-purchase reviews with the reviewer's first name
 * and last initial, one public reply each, and a report to Trust & Safety. There is deliberately no way to edit, hide
 * or delete a review: only Trust & Safety can.
 */
export function ReviewsView() {
  const t = useT();
  const { orgId, can, session } = useSeller();
  const [unanswered, setUnanswered] = useState(false);
  const [page, setPage] = useState(0);
  const q = useQuery({ ...sellerQueries.reviews(sellerApi(), orgId, unanswered, page), placeholderData: (prev) => prev });
  const copy = moduleCopy('reviews', { provider: false, orgName: session.activeOrg?.name ?? '' }, t);
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const pages = q.data ? Math.max(1, Math.ceil(q.data.total / PAGE_SIZE)) : 1;
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={copy.title}
        description={copy.description}
        actions={
          <Select
            label={t('sc.reviews.filter')}
            value={unanswered ? 'unanswered' : 'all'}
            onChange={(e) => {
              setUnanswered(e.currentTarget.value === 'unanswered');
              setPage(0);
            }}
            options={[{ value: 'all', label: t('sc.reviews.filterAll') }, { value: 'unanswered', label: t('sc.reviews.filterUnanswered') }]}
          />
        }
      />
      <Notice tone="info" title={t('sc.reviews.note')} />
      {q.isPending ? <Spinner label={t('sc.reviews.loading')} /> : null}
      {q.data && q.data.items.length === 0 ? <EmptyState title={t(unanswered ? 'sc.reviews.emptyUnanswered' : 'sc.reviews.empty')} /> : null}
      {q.data && q.data.items.length > 0 ? (
        <ul className="m-0 flex list-none flex-col gap-3 p-0">
          {q.data.items.map((r) => <ReviewCard key={r.id} review={r} canEdit={can('reviews', 'EDIT')} />)}
        </ul>
      ) : null}
      {q.data && q.data.total > PAGE_SIZE ? (
        <nav className="flex items-center justify-between gap-3" aria-label={t('sc.reviews.pages')}>
          <Button variant="neutral" size="sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>{t('sc.reviews.previous')}</Button>
          <span className="text-sm text-muted">{t('sc.reviews.pageOf', { page: page + 1, pages })}</span>
          <Button variant="neutral" size="sm" disabled={page + 1 >= pages} onClick={() => setPage((p) => p + 1)}>{t('sc.reviews.next')}</Button>
        </nav>
      ) : null}
    </div>
  );
}
