/** Every string of the session timer, translated by the app (placeholders: {time} m:ss or spoken, {clock} a wall-clock time). */
export interface SessionTimeoutLabels {
  /** Dialog title. */
  warnTitle: string;
  /** "You'll be signed out in {time} because you've been inactive." */
  idleBody: string;
  /** "For your security you need to sign in again at {clock}." */
  absoluteBody: string;
  /** Why staying signed in does not help (session length reached). */
  absoluteNote: string;
  /** Caption of the big countdown. */
  timeLeft: string;
  /** Spoken, polite then assertive: "You'll be signed out in {time}." */
  announceIdle: string;
  /** Spoken for the absolute limit: "You'll need to sign in again in {time}." */
  announceAbsolute: string;
  stay: string;
  signOutNow: string;
  /** "Got it" on the absolute-limit warning. */
  dismiss: string;
  /** The dialog's close button (Esc does the same): closing keeps the person signed in. */
  close: string;
  /** "Stay signed in" did not reach the server. */
  failed: string;
  endedTitle: string;
  endedIdle: string;
  endedAbsolute: string;
  /** Signed out in another tab or window. */
  endedSignedOut: string;
  signInAgain: string;
  /** Shown while the sign-out is being finished. */
  signingOut: string;
}
