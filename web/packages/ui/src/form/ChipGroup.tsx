import styles from './ChipGroup.module.css';

export interface ChipGroupProps<V extends string> {
  /** Accessible name of the group ("Period", "View"). */
  label: string;
  options: { value: V; label: string }[];
  value: V;
  onChange: (value: V) => void;
}

/**
 * One-of-many pill toggles for page-level choices (a report period, a calendar view, a billing cadence). Tables never
 * use chips to filter: they have exactly one search over every column (@afrotransact/data-table).
 */
export function ChipGroup<V extends string>({ label, options, value, onChange }: ChipGroupProps<V>) {
  return (
    <div role="group" aria-label={label} className={styles.group}>
      {options.map((o) => (
        <button key={o.value} type="button" aria-pressed={value === o.value} className={styles.chip} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}
