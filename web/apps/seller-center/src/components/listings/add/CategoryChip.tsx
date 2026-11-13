'use client';

import { type CategoryNode } from '@afrotransact/api-client';
import { TextField, TextLinkButton } from '@afrotransact/ui';
import { useId, useState } from 'react';

import { useT } from '@/i18n/t';

import { type CategoryLeaf, leavesOf, searchLeaves } from '../form-model';

interface Props {
  tree: CategoryNode[] | undefined;
  /** The category in use: the seller's own choice, else the one catalog suggests. Null when there is none yet. */
  value: { path: string; suggested: boolean } | null;
  onPick: (leaf: CategoryLeaf | null) => void;
}

/**
 * The category of "What are you selling?" as a chip: suggested from the name and the results, never a required first step. "Change" opens a
 * small search over the categories; "Remove" goes back to the suggestion. The words are the category path, never an id.
 */
export function CategoryChip({ tree, value, onPick }: Props) {
  const t = useT();
  const id = useId();
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState('');
  const found = tree && text.trim() ? searchLeaves(leavesOf(tree), text, 6) : [];
  return (
    <div className="flex flex-col gap-2" data-testid="category-chip">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
        <span className="font-semibold">{t('sc.match.cat.label')}</span>
        {value ? (
          <span className="rounded-sm bg-surface-muted px-2 py-1" data-testid="category-chip-value">{value.suggested ? t('sc.match.cat.suggested', { path: value.path }) : value.path}</span>
        ) : (
          <span className="text-muted">{t('sc.match.cat.empty')}</span>
        )}
        <TextLinkButton aria-expanded={editing} onClick={() => setEditing((e) => !e)}>{t('sc.match.cat.change')}</TextLinkButton>
        {value && !value.suggested ? <TextLinkButton onClick={() => { onPick(null); setEditing(false); }}>{t('sc.match.cat.remove')}</TextLinkButton> : null}
      </div>
      {editing ? (
        <div className="flex flex-col gap-2">
          <TextField id={`${id}-search`} label={t('sc.match.cat.search')} autoComplete="off" value={text} onChange={(e) => setText(e.target.value)} />
          {text.trim() && !found.length ? <p className="m-0 text-sm text-muted">{t('sc.match.cat.noResults')}</p> : null}
          <ul className="m-0 flex list-none flex-col gap-1 p-0">
            {found.map((leaf) => (
              <li key={leaf.id}>
                <TextLinkButton onClick={() => { onPick(leaf); setEditing(false); setText(''); }}>{leaf.path}</TextLinkButton>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
