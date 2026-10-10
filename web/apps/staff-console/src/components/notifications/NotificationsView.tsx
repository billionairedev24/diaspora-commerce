'use client';

import { Tabs } from '@afrotransact/ui';
import { useSearchParams } from 'next/navigation';

import { ModulePage } from '@/components/module/ModulePage';
import { useT } from '@/i18n/t';

import { TemplateDetail } from './TemplateDetail';
import { TemplateList } from './TemplateList';

/** Notifications → Templates (S268): the list of template keys, or one key's preview, history and editor (?key=). */
export function NotificationsView() {
  const t = useT();
  const key = useSearchParams().get('key');
  return (
    <ModulePage module="notifications">
      <Tabs
        label={t('st.ntf.tabs')}
        value="templates"
        items={[{ value: 'templates', label: t('st.ntf.templatesTab'), content: key ? <TemplateDetail key={key} templateKey={key} /> : <TemplateList /> }]}
      />
    </ModulePage>
  );
}
