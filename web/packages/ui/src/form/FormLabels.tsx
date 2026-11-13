'use client';

import { createContext, type ReactNode, useContext } from 'react';

export interface FormLabels {
  /** Read out after a required field's label, where the asterisk is only a picture ("required"). */
  required: string;
  /** Accessible name of an info button that opens a hint ("More information"). */
  info: string;
  /** Accessible name of the remove button on a chip ("Remove {name}"). */
  remove: (name: string) => string;
}

const DEFAULT: FormLabels = { required: 'required', info: 'More information', remove: (name) => `Remove ${name}` };
const Ctx = createContext<FormLabels>(DEFAULT);

/** Gives the form controls below it their words in the app's language (the ui package has no catalogue of its own). */
export function FormLabelsProvider({ labels, children }: { labels: Partial<FormLabels>; children: ReactNode }) {
  return <Ctx.Provider value={{ ...DEFAULT, ...labels }}>{children}</Ctx.Provider>;
}

export const useFormLabels = (): FormLabels => useContext(Ctx);
