'use client';

import { createContext, type ReactNode, useCallback, useContext, useMemo, useState } from 'react';

import { LolaSheet } from './LolaSheet';

interface LolaLauncherValue {
  /** Opens the Lola drawer, optionally sending a first message (e.g. "Track my order"). */
  open: (prompt?: string) => void;
}

const Ctx = createContext<LolaLauncherValue>({ open: () => undefined });

/** One Lola drawer for the whole storefront; anything can open it (header, account menu, orders page). */
export function LolaProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const [prompt, setPrompt] = useState<string | null>(null);
  const launch = useCallback((p?: string) => {
    setPrompt(p ?? null);
    setOpen(true);
  }, []);
  const value = useMemo(() => ({ open: launch }), [launch]);
  return (
    <Ctx.Provider value={value}>
      {children}
      <LolaSheet open={open} onOpenChange={setOpen} prompt={prompt} onPromptSent={() => setPrompt(null)} />
    </Ctx.Provider>
  );
}

export const useLolaLauncher = (): LolaLauncherValue => useContext(Ctx);
