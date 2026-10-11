import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Barcode } from '../src/barcode/Barcode';
import { caseLabelData, code128Values, ean13, encode, FNC1, gs1CheckDigit, gs1Values, gtin14, gtinValid, modules, parseGs1, sscc } from '../src/barcode/encode';

describe('barcode encoders', () => {
  it('computes GS1 check digits (GTIN-13, GTIN-12 and SSCC)', () => {
    expect(gs1CheckDigit('400638133393')).toBe(1);
    expect(gtinValid('4006381333931')).toBe(true);
    expect(gtinValid('4006381333932')).toBe(false);
    expect(gtinValid('036000291452')).toBe(true);
    expect(gtin14('036000291452')).toBe('00036000291452');
    expect(sscc('10614141123456789')).toBe('106141411234567897'); // GS1 General Specifications example
  });

  it('encodes Code 128 in set B with the mod-103 check (the classic “PJJ123C” example)', () => {
    // Start B (104), P=48, J=42, J=42, 1=17, 2=18, 3=19, C=35; check = (104 + 48 + 84 + 126 + 68 + 90 + 114 + 245) % 103 = 55
    expect(code128Values(['PJJ123C'])).toEqual([104, 48, 42, 42, 17, 18, 19, 35, 55]);
  });

  it('packs digit runs into code set C', () => {
    const v = code128Values(['12345678']);
    expect(v[0]).toBe(105);
    expect(v.slice(1, 5)).toEqual([12, 34, 56, 78]);
    const mixed = code128Values(['LPN12345678']);
    expect(mixed[0]).toBe(104);
    expect(mixed).toContain(99); // switch to C for the 8 digits
  });

  it('builds GS1-128 with an FNC1 start and a separator after variable-length AIs only', () => {
    const els = parseGs1('(01)00860012000613(10)B-77(17)271001');
    const v = gs1Values(els);
    expect(v[0]).toBe(105);
    expect(v[1]).toBe(FNC1);
    // (01) + 14 digits = 16 digits = 8 pairs in set C, then lot in set B, then FNC1, then (17)… back in C.
    expect(v.slice(2, 10)).toEqual([1, 0, 86, 0, 12, 0, 6, 13]);
    expect(v.filter((x) => x === FNC1)).toHaveLength(2);
    expect(() => parseGs1('(01)123')).toThrow(/14 characters/);
    expect(() => parseGs1('0100860012000613')).toThrow();
  });

  it('puts the lot last on a case label so it needs no trailing separator', () => {
    expect(caseLabelData({ gtin: '086001200069', lotNo: 'B-77', expDate: '2027-10-01', mfgDate: '2026-09-01' })).toBe(
      '(01)00086001200069(17)271001(11)260901(10)B-77'
    );
  });

  it('encodes EAN-13 with 95 modules between the quiet zones', () => {
    const e = ean13('400638133393');
    expect(e.digits).toBe('4006381333931');
    expect(modules(e.bars) - 18).toBe(95);
    expect(() => ean13('4006381333932')).toThrow();
  });

  it('gives the human-readable line for each symbology', () => {
    expect(encode('gs1-128', '(00)106141411234567897').text).toBe('(00) 106141411234567897');
    expect(encode('code128', 'HOU-A-01-02').text).toBe('HOU-A-01-02');
  });
});

describe('<Barcode>', () => {
  it('renders an accessible SVG with one rect per bar', () => {
    const { container } = render(<Barcode value="(01)00086001200061(17)271001(10)B-77" symbology="gs1-128" />);
    const svg = screen.getByRole('img', { name: /GS1-128 barcode \(01\) 00086001200061 \(17\) 271001 \(10\) B-77/ });
    expect(svg.getAttribute('fill')).toBe('currentColor');
    expect(container.querySelectorAll('rect').length).toBeGreaterThan(30);
  });

  it('refuses data it cannot encode instead of drawing a wrong symbol', () => {
    render(<Barcode value="12345" symbology="ean13" />);
    expect(screen.getByRole('alert')).toHaveTextContent(/Cannot encode/);
  });
});
