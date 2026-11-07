/* Phone field of the hosted identity pages (progressive enhancement of `.phone[data-phone]`).
   Same library and metadata as the web apps (`libphonenumber-js/mobile`, see web/packages/ui/src/form/PhoneInput.tsx).
   Bundled into java/services/identity/src/main/resources/static/assets/phone.js by tools/identity/build-phone-field.sh. */
import { AsYouType, validatePhoneNumberLength } from 'libphonenumber-js/mobile';

const ALLOWED = /^[\d+\s().-]*$/;
const MAX_DIGITS = 17;

function maxDigits(region) {
  let n = 1;
  while (n < MAX_DIGITS && validatePhoneNumberLength('9'.repeat(n + 1), region) !== 'TOO_LONG') n += 1;
  return n;
}

function format(digits, region) {
  return digits ? new AsYouType(region).input(digits) : '';
}

function setup(box) {
  const select = box.querySelector('select');
  const input = box.querySelector('input');
  if (!select || !input) return;
  const placeholder = input.getAttribute('placeholder');
  const defaultRegion = select.dataset.defaultRegion;
  const limits = {};
  const limit = (region) => (limits[region] ??= maxDigits(region));

  // Closed picker: flag + calling code only ("🇺🇸 +1"); the open list and screen readers keep the country name.
  const options = Array.from(select.options);
  options.forEach((o) => {
    o.dataset.full = o.textContent;
    o.dataset.short = o.dataset.short || o.textContent;
  });
  const expand = () => options.forEach((o) => { o.textContent = o.dataset.full; });
  const collapse = () => {
    expand();
    const selected = select.selectedOptions[0];
    if (selected) selected.textContent = selected.dataset.short;
  };
  select.addEventListener('mousedown', expand);
  select.addEventListener('focus', expand);
  select.addEventListener('blur', collapse);
  select.addEventListener('keydown', expand);

  const digitsOf = (text, region) => {
    let digits = text.replace(/\D/g, '');
    while (digits.length > limit(region)) digits = digits.slice(0, -1);
    return digits;
  };

  // "+234 803 ...": an international number selects its own country when the list has it.
  const international = (text) => {
    const t = text.trim();
    if (!t.startsWith('+')) return null;
    const parsed = new AsYouType();
    parsed.input(t);
    const country = parsed.getCountry();
    if (!country || !options.some((o) => o.value === country)) return null;
    return { country, digits: String(parsed.getNumber()?.nationalNumber ?? '') };
  };

  const apply = (digits) => {
    const region = select.value;
    input.value = format(digits, region);
    input.maxLength = format('9'.repeat(limit(region)), region).length + 2;
    if (region === defaultRegion && placeholder) input.setAttribute('placeholder', placeholder);
    else input.removeAttribute('placeholder');
  };

  const caretAfter = (digitCount) => {
    let seen = 0;
    let pos = 0;
    while (pos < input.value.length && seen < digitCount) {
      if (/\d/.test(input.value[pos])) seen += 1;
      pos += 1;
    }
    return pos;
  };

  let last = input.value;

  const reformat = (raw, caret, deleting) => {
    let digits = digitsOf(raw, select.value);
    const intl = international(raw);
    if (!intl && raw.trim().startsWith('+')) {
      // International prefix typed but the country is not known yet: keep "+" and the digits, formatted, until it is.
      const partial = new AsYouType();
      input.maxLength = 24;
      input.value = partial.input(raw.trim().replace(/[^\d+]/g, '').slice(0, 16));
      last = input.value;
      return;
    }
    if (intl) {
      select.value = intl.country;
      digits = digitsOf(intl.digits, intl.country);
      if (document.activeElement !== select) collapse();
    } else if (deleting && digits === last.replace(/\D/g, '')) {
      // Deleting a formatting character removes the digit before it instead of being re-added.
      digits = digits.slice(0, -1);
    }
    const before = Math.min(raw.slice(0, caret).replace(/\D/g, '').length, digits.length);
    apply(digits);
    const pos = caret >= raw.length || intl ? input.value.length : caretAfter(before);
    input.setSelectionRange(pos, pos);
    last = input.value;
  };

  input.addEventListener('beforeinput', (e) => {
    if (e.data && e.inputType === 'insertText' && !ALLOWED.test(e.data)) e.preventDefault();
  });
  input.addEventListener('paste', (e) => {
    e.preventDefault();
    const text = (e.clipboardData?.getData('text') ?? '').replace(/[^\d+\s().-]/g, '');
    const start = input.selectionStart ?? input.value.length;
    const end = input.selectionEnd ?? start;
    const merged = input.value.slice(0, start) + text + input.value.slice(end);
    reformat(merged, start + text.length, false);
  });
  input.addEventListener('input', () => {
    reformat(input.value, input.selectionStart ?? input.value.length, input.value.length < last.length);
  });
  select.addEventListener('change', () => {
    apply(digitsOf(input.value, select.value));
    last = input.value;
    collapse();
  });
  apply(digitsOf(input.value, select.value));
  last = input.value;
  collapse();
}

document.querySelectorAll('.phone[data-phone]').forEach(setup);
