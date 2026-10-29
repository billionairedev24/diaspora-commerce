/** A gift card code is 16 letters and digits, shown in four groups of four. */
export const GIFT_CODE_LENGTH = 16;

/** Whatever was typed or pasted (spaces, dashes, lower case, a pasted "Code: …" line) as the grouped code "ABCD-EFGH-JKLM-2345". */
export function formatGiftCode(raw: string): string {
  const clean = raw.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, GIFT_CODE_LENGTH);
  return clean.replace(/(.{4})(?=.)/g, '$1-');
}

/** Whether the text holds a full 16-character code. */
export function giftCodeComplete(value: string): boolean {
  return value.replace(/[^A-Za-z0-9]/g, '').length === GIFT_CODE_LENGTH;
}
