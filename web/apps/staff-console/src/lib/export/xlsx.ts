import { type ChartSpec } from '@afrotransact/api-client';
import { resolvedVariables } from '@afrotransact/tokens';
import { strToU8, zipSync } from 'fflate';

import { sliceColour, tabular, toneColour } from './table';

const esc = (v: string | number) => String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const col = (i: number) => String.fromCharCode(65 + i);
const hex = (c: string) => c.replace('#', '').toUpperCase();
const XML = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
const NS_MAIN = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main';
const NS_REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
const NS_C = 'http://schemas.openxmlformats.org/drawingml/2006/chart';
const NS_A = 'http://schemas.openxmlformats.org/drawingml/2006/main';
const rel = (id: string, type: string, target: string) => `<Relationship Id="${id}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/${type}" Target="${target}"/>`;
const rels = (x: string) => `${XML}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${x}</Relationships>`;

/**
 * Native .xlsx: the data on a sheet plus a real (editable) Excel chart bound to that range — line, bar, stacked bar or
 * doughnut, like the dashboard. Built in the browser with fflate (no server round trip needed for this data size).
 */
export function toXlsx(spec: ChartSpec): Uint8Array<ArrayBuffer> {
  const t = tabular(spec);
  const n = t.rows.length;
  const sheetName = 'Data';
  const cellXml = (c: number, r: number, v: string | number) =>
    typeof v === 'number'
      ? `<c r="${col(c)}${r}"${r === 1 ? ' s="1"' : ''}><v>${v}</v></c>`
      : `<c r="${col(c)}${r}" t="inlineStr"${r === 1 ? ' s="1"' : ''}><is><t>${esc(v)}</t></is></c>`;
  const sheet = `${XML}<worksheet xmlns="${NS_MAIN}" xmlns:r="${NS_REL}"><cols><col min="1" max="1" width="26" customWidth="1"/><col min="2" max="${t.headers.length}" width="16" customWidth="1"/></cols><sheetData>${[t.headers, ...t.rows]
    .map((row, ri) => `<row r="${ri + 1}">${row.map((v, ci) => cellXml(ci, ri + 1, v)).join('')}</row>`)
    .join('')}</sheetData><drawing r:id="rId1"/></worksheet>`;
  const ref = (c: number) => `'${sheetName}'!$${col(c)}$2:$${col(c)}$${n + 1}`;
  const fill = (c: string) => `<c:spPr><a:solidFill><a:srgbClr val="${hex(c)}"/></a:solidFill></c:spPr>`;
  const ser = (i: number) => {
    const s = spec.series[i];
    const colour = s ? toneColour(s.tone) : resolvedVariables['--at-ink-900'];
    const pts = spec.kind === 'donut' ? spec.rows.map((_, p) => `<c:dPt><c:idx val="${p}"/><c:bubble3D val="0"/>${fill(sliceColour(p))}</c:dPt>`).join('') : '';
    const look = spec.kind === 'donut' ? pts : spec.kind === 'trend' ? `<c:spPr><a:ln w="28575"><a:solidFill><a:srgbClr val="${hex(colour)}"/></a:solidFill></a:ln></c:spPr><c:marker><c:symbol val="none"/></c:marker>` : fill(colour);
    return `<c:ser><c:idx val="${i}"/><c:order val="${i}"/><c:tx><c:strRef><c:f>'${sheetName}'!$${col(i + 1)}$1</c:f></c:strRef></c:tx>${look}<c:cat><c:strRef><c:f>${ref(0)}</c:f></c:strRef></c:cat><c:val><c:numRef><c:f>${ref(i + 1)}</c:f></c:numRef></c:val>${spec.kind === 'trend' ? '<c:smooth val="0"/>' : ''}</c:ser>`;
  };
  const series = (spec.kind === 'donut' ? [0] : spec.series.map((_, i) => i)).map(ser).join('');
  const horizontal = spec.kind === 'hbar';
  const axIds = '<c:axId val="1001"/><c:axId val="1002"/>';
  const grid = resolvedVariables['--at-ink-200'];
  const axes = `<c:catAx><c:axId val="1001"/><c:scaling><c:orientation val="${horizontal ? 'maxMin' : 'minMax'}"/></c:scaling><c:delete val="0"/><c:axPos val="${horizontal ? 'l' : 'b'}"/><c:numFmt formatCode="General" sourceLinked="0"/><c:tickLblPos val="nextTo"/><c:crossAx val="1002"/><c:crosses val="autoZero"/><c:auto val="1"/><c:lblAlgn val="ctr"/><c:lblOffset val="100"/></c:catAx><c:valAx><c:axId val="1002"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="${horizontal ? 'b' : 'l'}"/><c:majorGridlines><c:spPr><a:ln><a:solidFill><a:srgbClr val="${hex(grid)}"/></a:solidFill></a:ln></c:spPr></c:majorGridlines><c:numFmt formatCode="${spec.valueFormat === 'money' ? '&quot;$&quot;#,##0' : '#,##0'}" sourceLinked="0"/><c:tickLblPos val="nextTo"/><c:crossAx val="1001"/><c:crosses val="autoZero"/></c:valAx>`;
  const plot =
    spec.kind === 'donut'
      ? `<c:doughnutChart><c:varyColors val="1"/>${series}<c:firstSliceAng val="0"/><c:holeSize val="55"/></c:doughnutChart>`
      : spec.kind === 'trend'
        ? `<c:lineChart><c:grouping val="standard"/><c:varyColors val="0"/>${series}<c:marker val="1"/>${axIds}</c:lineChart>${axes}`
        : `<c:barChart><c:barDir val="${horizontal ? 'bar' : 'col'}"/><c:grouping val="${spec.kind === 'stack' ? 'stacked' : 'clustered'}"/><c:varyColors val="0"/>${series}<c:gapWidth val="60"/>${spec.kind === 'stack' ? '<c:overlap val="100"/>' : ''}${axIds}</c:barChart>${axes}`;
  const chart = `${XML}<c:chartSpace xmlns:c="${NS_C}" xmlns:a="${NS_A}" xmlns:r="${NS_REL}"><c:roundedCorners val="0"/><c:chart><c:title><c:tx><c:rich><a:bodyPr/><a:p><a:pPr><a:defRPr sz="1400" b="1"/></a:pPr><a:r><a:rPr lang="en-US" sz="1400" b="1"/><a:t>${esc(spec.title)}</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title><c:autoTitleDeleted val="0"/><c:plotArea><c:layout/>${plot}</c:plotArea><c:legend><c:legendPos val="${spec.kind === 'donut' ? 'r' : 'b'}"/><c:overlay val="0"/></c:legend><c:plotVisOnly val="1"/></c:chart></c:chartSpace>`;
  const c0 = t.headers.length + 1;
  const drawing = `${XML}<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="${NS_A}" xmlns:r="${NS_REL}" xmlns:c="${NS_C}"><xdr:twoCellAnchor><xdr:from><xdr:col>${c0}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:to><xdr:col>${c0 + 10}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>22</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to><xdr:graphicFrame macro=""><xdr:nvGraphicFramePr><xdr:cNvPr id="2" name="Chart 1"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr><xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></xdr:xfrm><a:graphic><a:graphicData uri="${NS_C}"><c:chart r:id="rId1"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor></xdr:wsDr>`;
  const types = `${XML}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/><Override PartName="/xl/charts/chart1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/></Types>`;
  const headerFill = hex(resolvedVariables['--at-gold-500']);
  const styles = `${XML}<styleSheet xmlns="${NS_MAIN}"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF${headerFill}"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf/><xf fontId="1" fillId="2" applyFont="1" applyFill="1"/></cellXfs></styleSheet>`;
  const zip = zipSync({
    '[Content_Types].xml': strToU8(types),
    '_rels/.rels': strToU8(rels(rel('rId1', 'officeDocument', 'xl/workbook.xml'))),
    'xl/workbook.xml': strToU8(`${XML}<workbook xmlns="${NS_MAIN}" xmlns:r="${NS_REL}"><sheets><sheet name="${sheetName}" sheetId="1" r:id="rId1"/></sheets></workbook>`),
    'xl/_rels/workbook.xml.rels': strToU8(rels(rel('rId1', 'worksheet', 'worksheets/sheet1.xml') + rel('rId2', 'styles', 'styles.xml'))),
    'xl/styles.xml': strToU8(styles),
    'xl/worksheets/sheet1.xml': strToU8(sheet),
    'xl/worksheets/_rels/sheet1.xml.rels': strToU8(rels(rel('rId1', 'drawing', '../drawings/drawing1.xml'))),
    'xl/drawings/drawing1.xml': strToU8(drawing),
    'xl/drawings/_rels/drawing1.xml.rels': strToU8(rels(rel('rId1', 'chart', '../charts/chart1.xml'))),
    'xl/charts/chart1.xml': strToU8(chart)
  });
  return new Uint8Array(zip);
}
