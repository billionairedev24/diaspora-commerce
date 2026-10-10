import { resolvedVariables } from '@afrotransact/tokens';

import { type Tabular } from './table';

const rgb = (hexColour: string): [number, number, number] => {
  const h = hexColour.replace('#', '');
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
};

/** Renders an SVG (the on-screen Recharts chart) to a PNG data URL. */
async function svgToPng(svg: SVGSVGElement): Promise<{ data: string; w: number; h: number } | null> {
  const box = svg.getBoundingClientRect();
  if (!box.width || !box.height) return null;
  const clone = svg.cloneNode(true) as SVGSVGElement;
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
  clone.setAttribute('width', String(box.width));
  clone.setAttribute('height', String(box.height));
  const img = new Image();
  img.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(new XMLSerializer().serializeToString(clone))}`;
  await new Promise<void>((resolve) => {
    img.onload = () => resolve();
    img.onerror = () => resolve();
  });
  const canvas = document.createElement('canvas');
  canvas.width = box.width * 2;
  canvas.height = box.height * 2;
  const ctx = canvas.getContext('2d');
  if (!ctx) return null;
  ctx.fillStyle = resolvedVariables['--at-color-canvas'];
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
  return { data: canvas.toDataURL('image/png'), w: box.width, h: box.height };
}

/** PDF with the chart (as drawn on screen) and its table; jsPDF loads only when someone exports. */
export async function toPdf(t: Tabular, chartSvg: SVGSVGElement | null): Promise<Blob> {
  const { jsPDF } = await import('jspdf');
  const doc = new jsPDF({ unit: 'pt', format: 'letter' });
  const W = doc.internal.pageSize.getWidth();
  const H = doc.internal.pageSize.getHeight();
  let y = 48;
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(16);
  doc.text(t.title, 40, y);
  doc.setFont('helvetica', 'normal');
  doc.setFontSize(9);
  doc.setTextColor(...rgb(resolvedVariables['--at-color-text-muted']));
  doc.text(`AfroTransact Seller Central · exported ${new Date().toLocaleString('en-US')}`, 40, y + 16);
  doc.setTextColor(...rgb(resolvedVariables['--at-color-text']));
  y += 32;
  const png = chartSvg ? await svgToPng(chartSvg) : null;
  if (png) {
    const w = W - 80;
    const h = (w * png.h) / png.w;
    doc.addImage(png.data, 'PNG', 40, y, w, h);
    y += h + 16;
  }
  const cw = (W - 80) / t.headers.length;
  const head = () => {
    doc.setFillColor(...rgb(resolvedVariables['--at-gold-500']));
    doc.rect(40, y - 11, W - 80, 16, 'F');
    doc.setFont('helvetica', 'bold');
    t.headers.forEach((k, i) => doc.text(String(k), 44 + i * cw, y));
    doc.setFont('helvetica', 'normal');
    y += 16;
  };
  doc.setFontSize(9);
  head();
  t.rows.forEach((r, ri) => {
    if (y > H - 40) {
      doc.addPage();
      y = 48;
      head();
    }
    if (ri % 2) {
      doc.setFillColor(...rgb(resolvedVariables['--at-ink-50']));
      doc.rect(40, y - 11, W - 80, 15, 'F');
    }
    r.forEach((v, i) => doc.text(typeof v === 'number' ? v.toLocaleString('en-US') : String(v), 44 + i * cw, y));
    y += 15;
  });
  return doc.output('blob');
}
