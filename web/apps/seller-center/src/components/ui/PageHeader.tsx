import { type ReactNode } from 'react';

/** `tour` names the header for guided tours (`data-tour="<tour>"`, and `<tour>-actions` on the buttons). */
export function PageHeader({ title, description, actions, tour }: { title: string; description?: ReactNode; actions?: ReactNode; tour?: string }) {
  return (
    <div className="flex flex-wrap items-end gap-x-6 gap-y-3" data-tour={tour}>
      <div className="min-w-0 flex-1 basis-72">
        <h1 className="m-0 text-3xl font-bold">{title}</h1>
        {description ? <p className="mt-1 mb-0 max-w-[80ch] text-muted">{description}</p> : null}
      </div>
      {actions ? <div className="flex flex-wrap items-center gap-2" data-tour={tour ? `${tour}-actions` : undefined}>{actions}</div> : null}
    </div>
  );
}
