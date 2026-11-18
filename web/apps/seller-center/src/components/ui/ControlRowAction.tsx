import { fieldStyles } from '@afrotransact/ui';
import type { ReactNode } from 'react';

/**
 * A button that sits in a row of labelled fields and lines up with the controls, not the labels or the
 * message line under them. It mirrors the Field structure (label row, control, message line) with an
 * invisible label, so the button shares the controls' top edge and height whatever the label metrics are,
 * and a validation message under a neighbour never pushes it off the control line. Below `sm` the row
 * stacks and the spacer is dropped, so the button is a full-width control under the fields.
 */
export function ControlRowAction({ children }: { children: ReactNode }) {
  return (
    <div data-control-row-action="" className={fieldStyles.field}>
      <span aria-hidden className={`${fieldStyles.labelRow} max-sm:hidden`}>
        <span className={`${fieldStyles.label} invisible`}>&nbsp;</span>
      </span>
      {children}
    </div>
  );
}
