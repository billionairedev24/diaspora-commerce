# Lola voice input (dictation) and capability hints

Owner decisions: Lola's hints show what she can do, with no festival names; voice is free dictation only, no paid transcription, and
AfroTransact never receives audio.

## What shoppers see

- **Hints.** The opening chips and the input placeholder are capability hints: "Track my order", "Delivery to my ZIP code", then one that
  rotates daily (Help me find a product, Jollof for 20 which scales a recipe to ingredients, Book knotless braids, Compare prices). The
  placeholder reads "Ask Lola to find a product, track an order or plan a meal…". Same set server-side (`LolaSuggestions.OPENING`) and in
  the mocks (`LOLA_MOCK_SUGGESTIONS`). Tests refuse festival names (deny-list: diwali, divali, deepavali, eid, ramadan, holi, hanukkah).
  Seasonal examples in docs and content are Christmas, Easter, Thanksgiving, Detty December and back to school.
- **Microphone button** in the Lola composer (web storefront and the customer app). Tap to start, the live transcript fills the input box
  (typed text before it is kept), tap again, press Esc, or pause for 4 seconds to stop. The text stays in the box to edit; **it is never
  sent automatically**. Focus returns to the input when dictation stops.
- **States.** Idle; listening (stop icon, `aria-pressed="true"`, "Listening…" in a polite live region, a pulse that respects
  `prefers-reduced-motion`); unsupported browser or no recogniser (button `aria-disabled` with a one-line reason, typing still works);
  permission denied (disabled, says how to allow it); error, no speech and offline (one line, the mic stays usable). Nothing fails silently.
- **Esc** stops dictation first; a second Esc closes the panel. 44px target. The input is `dir="auto"` and the layout uses logical
  properties, so it works in Arabic (right-to-left).
- **Languages.** Recognition language follows the app language: en-US, fr-FR, es-ES, ar-SA, yo-NG; if the engine rejects it (often yo-NG) it
  retries once in en-US. The new strings in fr, es, ar and yo are machine drafts, flagged by each catalog's `$meta.needsReview`.

## Privacy (accurate wording)

First use shows one line: "Your browser or device converts speech to text; with some browsers audio is sent to the browser vendor's
speech service. AfroTransact keeps no audio." (key `lola.mic.notice`; dismissed with "Got it", remembered in `localStorage`).

- No audio is ever sent to AfroTransact. There is **no audio or transcription endpoint** in ai-gateway, the BFF or the API client, and no new
  MCP tool. Only the text the shopper chooses to send goes into the normal Lola chat flow (PII masking and the AI spend cap apply as today).
- Chrome and Edge (desktop and Android) and Safari use the browser vendor's speech service, so audio can leave the device to that vendor;
  that is the browser's own feature and is governed by the vendor's terms. The phone apps use Apple Speech or the Android recogniser, which
  may also use the OS vendor's service.

## Support matrix

| Where | Dictation |
|---|---|
| Chrome, Edge (desktop, Android) | Yes (`SpeechRecognition` / `webkitSpeechRecognition`, vendor service, needs a network) |
| Safari 14.1+ (macOS, iOS) | Yes (`webkitSpeechRecognition`; Siri and Dictation must be enabled in the OS) |
| Firefox (desktop, Android) | No: the mic is disabled with "Voice input isn't available in this browser. You can still type." |
| Insecure (http) pages | Not offered by browsers; the mic shows the denied or unavailable line (every deployed host is https) |
| iOS and Android customer app | Yes, through `expo-speech-recognition` 57.1.1 (development build, not Expo Go) |

Languages the vendor service does not offer (Yoruba is the likely one) fall back to US English recognition.

## Implementation

- `web/packages/ui/src/voice/`: `speech.ts` (feature detection, locale map), `useDictation.ts` (hook: state machine, silence timer,
  language fallback), `MicButton.tsx`. `ChatPanel` gained `onEscapeKeyDown`. Wired in `web/apps/storefront/src/components/shell/LolaSheet.tsx`.
- Mobile: `mobile/apps/customer/src/domain/dictation.ts` (pure rules, unit-tested), `src/features/lola/useDictation.ts` (the engine behind a
  hook), the mic in `app/(app)/(tabs)/lola.tsx`, permission strings through the `expo-speech-recognition` config plugin in `app.config.ts`
  (microphone, and speech recognition on iOS).
- Seller and staff Lola sheets are unchanged for now.

## Tests

Vitest: `web/packages/ui/test/dictation.test.tsx` (idle, listening, interim to final, unsupported, denied, error, network, language retry,
RTL), `web/apps/storefront/test/lola-voice.test.tsx` (all five catalogs deny-list and key parity, chip rotation, the panel), `web/packages/api-client/test/lola-hints.test.ts`,
`mobile/apps/customer/test/dictation.test.ts`. JUnit: `LolaHintsTest` (suggestions, prompt and tool descriptions). Playwright:
`e2e/lola-voice.spec.ts` with a stubbed `SpeechRecognition` at 1280 and 390.

## Later phases (out of scope)

- **Spoken replies** (text-to-speech) and a **hands-free voice mode** (continuous listen, speak, barge-in) are a later phase. If a
  server-side speech route is ever wanted it needs a new owner decision on provider, cost and the spend cap.

## Open item for counsel

The published privacy notice (`docs/legal/privacy.en.md`, generated into a Flyway migration) has not been changed. Suggested sentence for its
"AI features" section at the next legal release: "Voice input in Lola is dictation: your browser or phone turns speech into text, and with
some browsers the audio is sent to the browser vendor's speech service. We do not receive, store or log audio; only the text you choose to
send reaches Lola." (Q11 in `docs/legal/open-questions-for-counsel.md`.)
