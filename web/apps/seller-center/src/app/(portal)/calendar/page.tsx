import { type Metadata } from 'next';

import { CalendarView } from '@/components/provider/CalendarView';

export const metadata: Metadata = { title: 'Calendar' };

export default function CalendarPage() {
  return <CalendarView />;
}
