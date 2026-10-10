/** Shared story parameters: RTL and mobile variants every component story offers. */
export const rtl = { parameters: { dir: 'rtl' } } as const;
export const mobile = { globals: { viewport: { value: 'mobile', isRotated: false } } } as const;
