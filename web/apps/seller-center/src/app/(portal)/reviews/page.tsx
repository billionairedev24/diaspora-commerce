import { type Metadata } from 'next';

import { ReviewsView } from '@/components/reviews/ReviewsView';

export const metadata: Metadata = { title: 'Reviews' };

export default function ReviewsPage() {
  return <ReviewsView />;
}
