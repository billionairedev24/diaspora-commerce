/* How a driver's session ends, in one place (DriverProvider.signOut, the sign-out policy, revocation all use it).
   The contract that matters on a delivery phone: ending the session NEVER touches the outbox. Events are flushed first
   (best effort, with the token that is still valid), then the push token, the tokens and the sign-out clock are
   forgotten, and the engine is detached from the driver. The SQLite outbox, its sequence counter and the cached route
   stay on the phone, so unsynced events are accepted the next time the same driver signs in (events captured before a
   revocation are still accepted by the server). Nothing here logs a token. */

export interface EndSessionParts {
  engine: {
    /** Sends the pending events; may reject (offline, server down): ignored here. */
    flush(): Promise<unknown>;
    setUser(userId: string | null): void;
  };
  unregisterPush(): Promise<void>;
  revokeTokens(): Promise<void>;
  removeTokens(): Promise<void>;
  /** Forgets the sign-out clock (domain/sessionGuard.ts end()). */
  forgetClock(): Promise<void>;
}

/** Forgets the credentials and the clock; keeps the outbox. */
export async function forgetSession(p: EndSessionParts): Promise<void> {
  await p.unregisterPush().catch(() => undefined);
  await p.revokeTokens();
  await p.removeTokens();
  await p.forgetClock();
  p.engine.setUser(null); // the outbox stays on the phone
}

/** A last best-effort sync, then forgetSession. */
export async function endSession(p: EndSessionParts): Promise<void> {
  await p.engine.flush().catch(() => undefined);
  await forgetSession(p);
}
