import { type RowActionItem, RowActions } from '@afrotransact/ui';
import { type ColumnDef } from '@tanstack/react-table';

export interface RowActionsColumnOptions<T> {
  /** Column name for screen readers and the Columns menu ("Actions"; translated). */
  header: string;
  /** The row's name: the button reads "Actions for <name>". */
  name: (row: T) => string;
  /** Translated accessible name of the button for a row ("Actions pour Kaftan"); defaults to English. */
  label?: (row: T) => string;
  /** The menu for a row. Return an empty list (or only falsy entries) when the row has none: no button is drawn. */
  items: (row: T) => RowActionItem[];
  /** Menu width in px. */
  width?: number;
}

/**
 * The trailing "⋯" column of a table: ONE fixed-width column with the row's actions in a menu (see RowActions in
 * @afrotransact/ui). Never hideable, never sortable, never exported; on phones it sits in the corner of the card.
 */
export function rowActionsColumn<T>({ header, name, label, items, width }: RowActionsColumnOptions<T>): ColumnDef<T> {
  return {
    id: 'actions',
    header: () => <span className="at-visually-hidden">{header}</span>,
    size: 56,
    enableSorting: false,
    enableHiding: false,
    enableResizing: false,
    meta: { export: false, label: header, actions: true },
    cell: ({ row }) => <RowActions name={name(row.original)} label={label?.(row.original)} items={items(row.original)} width={width} />
  };
}
