/* "Rate this item" (S324): offered on the delivered items of an order. Opens a sheet with stars (required), a title, the
   review text and the "Verified purchase" note; the shopper can edit within 30 days or delete their review. The backend
   decides who may review (a delivered order line), so a refusal is explained rather than hidden. */
import { isApiError } from '@afrotransact/api-client';
import { StarIcon as Star } from 'phosphor-react-native';
import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { useMyReview, useReviewActions } from '@/api/queries';
import { draftErrors, draftOf, MAX_STARS, problemKind, REVIEW_TEXT_MAX, REVIEW_TITLE_MAX, type ReviewDraft, savedKind, toRequest } from '@/domain/reviews';
import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { AppText, Banner, Button, Field, Loading } from '@/ui/primitives';
import { Sheet } from '@/ui/Sheet';
import { useToast } from '@/ui/Toast';

function Stars({ value, onChange, label }: { value: number; onChange: (n: number) => void; label: string }) {
  const tr = useT();
  return (
    <View style={styles.stars} accessibilityRole="radiogroup" accessibilityLabel={label}>
      {Array.from({ length: MAX_STARS }, (_, i) => i + 1).map((n) => (
        <Pressable
          key={n}
          accessibilityRole="radio"
          accessibilityState={{ selected: n === value }}
          accessibilityLabel={tr('cu.reviews.stars', { n })}
          hitSlop={4}
          onPress={() => onChange(n)}
          style={styles.star}
        >
          <Star size={32} weight={n <= value ? 'fill' : 'regular'} color={n <= value ? c.rating : t.ink['300']} />
        </Pressable>
      ))}
    </View>
  );
}

function ReviewForm({ atin, onDone }: { atin: string; onDone: () => void }) {
  const tr = useT();
  const toast = useToast();
  const mine = useMyReview(atin, true);
  const { write, edit, remove } = useReviewActions(atin);
  const existing = mine.data?.review ?? null;
  const [draft, setDraft] = useState<ReviewDraft | null>(null);
  const [error, setError] = useState<string | null>(null);
  const current = draft ?? draftOf(existing);
  const errors = draftErrors(current);
  const [submitted, setSubmitted] = useState(false);

  if (mine.isPending) return <Loading />;
  if (mine.isError || !mine.data) return <Banner tone="danger">{tr('cu.reviews.loadError')}</Banner>;
  if (!mine.data.eligible && !existing) return <Banner tone="info">{tr('cu.reviews.notVerified')}</Banner>;

  const failed = (e: unknown) => setError(tr(`cu.reviews.problem.${problemKind(isApiError(e) ? e.status : undefined, isApiError(e) ? e.code : undefined)}`));
  const submit = () => {
    setSubmitted(true);
    setError(null);
    if (Object.keys(errors).length > 0) return;
    const body = toRequest(current);
    const m = existing ? edit : write;
    m.mutate(body, {
      onSuccess: (saved) => {
        toast(tr(`cu.reviews.saved.${savedKind(saved.status, existing !== null)}`));
        onDone();
      },
      onError: failed
    });
  };
  const readOnly = existing !== null && !existing.editable;
  return (
    <ScrollView keyboardShouldPersistTaps="handled" style={styles.scroll} contentContainerStyle={{ gap: sp['3'] }}>
      {existing?.status === 'PENDING' ? <Banner tone="warning">{tr('cu.reviews.pending')}</Banner> : null}
      {readOnly ? <Banner tone="warning">{tr('cu.reviews.problem.windowClosed')}</Banner> : null}
      <AppText variant="small" color={c.textMuted}>{tr('cu.reviews.yourRating')}</AppText>
      <Stars value={current.rating} onChange={(rating) => setDraft({ ...current, rating })} label={tr('cu.reviews.yourRating')} />
      {submitted && errors.rating ? <AppText variant="caption" color={c.danger} accessibilityRole="alert">{tr('cu.reviews.starsRequired')}</AppText> : null}
      <Field label={tr('cu.reviews.titleLabel')} value={current.title} maxLength={REVIEW_TITLE_MAX} onChangeText={(title) => setDraft({ ...current, title })} editable={!readOnly} />
      <Field
        label={tr('cu.reviews.textLabel')}
        value={current.text}
        maxLength={REVIEW_TEXT_MAX}
        multiline
        numberOfLines={5}
        textAlignVertical="top"
        style={styles.text}
        hint={tr('cu.reviews.textHint')}
        onChangeText={(text) => setDraft({ ...current, text })}
        editable={!readOnly}
      />
      <AppText variant="caption" color={c.textMuted}>{tr('cu.reviews.publicNote')}</AppText>
      {error ? <Banner tone="danger">{error}</Banner> : null}
      <Button label={existing ? tr('cu.reviews.save') : tr('cu.reviews.submit')} onPress={submit} loading={write.isPending || edit.isPending} disabled={readOnly} />
      {existing ? (
        <Button
          kind="outline"
          label={tr('cu.reviews.delete')}
          loading={remove.isPending}
          onPress={() => remove.mutate(undefined, { onSuccess: () => { toast(tr('cu.reviews.deleted')); onDone(); }, onError: failed })}
        />
      ) : null}
    </ScrollView>
  );
}

export function RateItem({ atin, title }: { atin: string; title: string }) {
  const tr = useT();
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button size="md" kind="outline" label={tr('cu.reviews.rate')} accessibilityLabel={`${tr('cu.reviews.rate')}: ${title}`} onPress={() => setOpen(true)} />
      <Sheet visible={open} onClose={() => setOpen(false)}>
        <AppText variant="title">{tr('cu.reviews.rate')}</AppText>
        <AppText variant="small" color={c.textMuted}>{title}</AppText>
        {open ? <ReviewForm atin={atin} onDone={() => setOpen(false)} /> : null}
      </Sheet>
    </>
  );
}

const styles = StyleSheet.create({
  scroll: { maxHeight: 520 },
  stars: { flexDirection: 'row', gap: sp['1'] },
  star: { minWidth: 44, minHeight: 44, alignItems: 'center', justifyContent: 'center' },
  text: { minHeight: 110 }
});
