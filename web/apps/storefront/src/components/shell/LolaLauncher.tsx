'use client';

import { usePathname } from 'next/navigation';
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useState } from 'react';

import { LolaSheet } from './LolaSheet';

interface LolaLauncherValue {
  /** Opens the chat panel, optionally sending a first message (e.g. "Track my order"). */
  open: (prompt?: string) => void;
  close: () => void;
  isOpen: boolean;
}

const Ctx = createContext<LolaLauncherValue>({ open: () => undefined, close: () => undefined, isOpen: false });

/** The checkout is a focused flow (like Amazon's): no chat there, and a chat left open closes when the shopper goes to it. */
export const lolaHiddenOn = (pathname: string) => pathname === '/checkout' || pathname.startsWith('/checkout/');

/** One Lola chat panel for the whole storefront, opened only by the floating launcher (LolaFab). */
export function LolaProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const [prompt, setPrompt] = useState<string | null>(null);
  const pathname = usePathname();
  const launch = useCallback((p?: string) => {
    setPrompt(p ?? null);
    setOpen(true);
  }, []);
  const close = useCallback(() => setOpen(false), []);
  const hidden = lolaHiddenOn(pathname);
  useEffect(() => {
    if (hidden) setOpen(false);
  }, [hidden]);
  const value = useMemo(() => ({ open: launch, close, isOpen: open }), [launch, close, open]);
  return (
    <Ctx.Provider value={value}>
      {children}
      <LolaSheet open={open && !hidden} onOpenChange={setOpen} prompt={prompt} onPromptSent={() => setPrompt(null)} />
    </Ctx.Provider>
  );
}

export const useLolaLauncher = (): LolaLauncherValue => useContext(Ctx);
