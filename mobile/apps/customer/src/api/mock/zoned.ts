/** Wall-clock minutes on a date in the provider's time zone → instant (mock calendars are kept in provider time). */
export function atZoned(dateIso: string, minutes: number, timeZone: string): Date {
  const guess = new Date(`${dateIso}T00:00:00Z`);
  guess.setUTCMinutes(minutes);
  const parts = new Intl.DateTimeFormat('en-US', { timeZone, hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).formatToParts(guess);
  const get = (type: string) => Number(parts.find((x) => x.type === type)?.value ?? 0);
  const asZone = Date.UTC(get('year'), get('month') - 1, get('day'), get('hour') % 24, get('minute'));
  return new Date(guess.getTime() - (asZone - guess.getTime()));
}
