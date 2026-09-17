/**
 * 결과 전체를 담은 이미지를 캔버스에 직접 그린다.
 *
 * <p>DOM을 캡처(html2canvas 류)하지 않는 이유: 의존성이 늘고, 화면 레이아웃(버튼·링크·접힌 영역)까지
 * 같이 찍힌다. 대신 결과 데이터를 받아 이미지 전용 레이아웃으로 새로 그린다.
 * 한글은 캔버스가 시스템 폰트로 그려주므로 폰트 파일을 따로 실을 필요가 없다.
 *
 * <p>세로 길이는 내용에 맞춰 늘어난다. 먼저 1px 캔버스에 그려보며 높이만 재고(그리기는 잘린다),
 * 그만큼의 캔버스를 만들어 다시 그린다. 큰 임시 캔버스를 잡지 않으려는 방법이다.
 */

import { CompatReading, SajuReading } from './saju';

type Ctx = CanvasRenderingContext2D;

const W = 1080;
/** 내용이 짧아도 이 높이는 유지 (4:5) */
const MIN_H = 1350;
/** 안전장치 — 이보다 길어지면 잘린다 */
const MAX_H = 12000;
/** 본문 끝에서 카드 아래까지 (구분선 + 출처 + 여백) */
const FOOTER_SPACE = 176;
const PAD = 72;
const CONTENT = W - PAD * 2;
/** 박스 안쪽 여백 */
const INNER = 28;

const BODY_SIZE = 30;
const BODY_LH = 46;

const FONT = `-apple-system, BlinkMacSystemFont, "Malgun Gothic", "Apple SD Gothic Neo", "Noto Sans KR", sans-serif`;

const INK = '#171717';
const BODY_INK = '#374151';
const MUTED = '#6b7280';
const FAINT = '#d1d5db';
const HIT = '#6aaa64';
const MOVE = '#c9b458';

/** 오행별 색 — 화면(elementStyle)과 같은 계열 */
const ELEMENT_COLOR: Record<string, { fill: string; bg: string }> = {
  목: { fill: '#16a34a', bg: '#dcfce7' },
  화: { fill: '#dc2626', bg: '#fee2e2' },
  토: { fill: '#d97706', bg: '#fef3c7' },
  금: { fill: '#475569', bg: '#f1f5f9' },
  수: { fill: '#2563eb', bg: '#dbeafe' },
};

const ELEMENT_ORDER = ['목', '화', '토', '금', '수'];

function color(element: string) {
  return ELEMENT_COLOR[element] ?? { fill: MUTED, bg: '#f3f4f6' };
}

function font(size: number, weight: 'normal' | 'bold' = 'normal') {
  return `${weight} ${size}px ${FONT}`;
}

function roundRect(ctx: Ctx, x: number, y: number, w: number, h: number, r: number) {
  const radius = Math.max(0, Math.min(r, w / 2, h / 2));
  ctx.beginPath();
  ctx.moveTo(x + radius, y);
  ctx.arcTo(x + w, y, x + w, y + h, radius);
  ctx.arcTo(x + w, y + h, x, y + h, radius);
  ctx.arcTo(x, y + h, x, y, radius);
  ctx.arcTo(x, y, x + w, y, radius);
  ctx.closePath();
}

/** 글자 단위 줄바꿈 (한글은 단어 경계가 넓어 글자 단위가 깔끔하다) */
function wrap(ctx: Ctx, text: string, maxWidth: number, maxLines = 200): string[] {
  const lines: string[] = [];
  let line = '';
  for (const char of (text ?? '').replace(/\s+/g, ' ').trim()) {
    const next = line + char;
    if (ctx.measureText(next).width > maxWidth && line) {
      lines.push(line);
      line = char === ' ' ? '' : char;
      if (lines.length === maxLines) return ellipsize(ctx, lines, maxWidth);
    } else {
      line = next;
    }
  }
  if (line) lines.push(line);
  return lines;
}

function ellipsize(ctx: Ctx, lines: string[], maxWidth: number): string[] {
  let last = lines[lines.length - 1];
  while (last && ctx.measureText(last + '…').width > maxWidth) last = last.slice(0, -1);
  lines[lines.length - 1] = last + '…';
  return lines;
}

function drawLines(ctx: Ctx, lines: string[], x: number, y: number, lineHeight: number): number {
  lines.forEach((line, index) => ctx.fillText(line, x, y + index * lineHeight));
  return y + lines.length * lineHeight;
}

function center(ctx: Ctx, text: string, x: number, width: number, y: number) {
  ctx.fillText(text, x + (width - ctx.measureText(text).width) / 2, y);
}

function createCanvas(height: number) {
  const canvas = document.createElement('canvas');
  canvas.width = W;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('캔버스를 만들 수 없습니다');
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, W, height);
  ctx.textBaseline = 'top';
  return { canvas, ctx };
}

/**
 * 본문을 두 번 그린다 — 처음엔 높이를 재려고 1px 캔버스에(그리기는 잘린다),
 * 그다음 실제 높이의 캔버스에.
 */
function renderCard(drawBody: (ctx: Ctx) => number): HTMLCanvasElement {
  const probe = createCanvas(1);
  const bodyBottom = drawBody(probe.ctx);

  const height = Math.min(MAX_H, Math.max(MIN_H, Math.round(bodyBottom + FOOTER_SPACE)));
  const card = createCanvas(height);
  drawBody(card.ctx);
  drawFooter(card.ctx, height);
  return card.canvas;
}

function drawFooter(ctx: Ctx, height: number) {
  const lineY = height - PAD - 56;
  ctx.strokeStyle = '#f3f4f6';
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(PAD, lineY);
  ctx.lineTo(W - PAD, lineY);
  ctx.stroke();

  ctx.font = font(26);
  ctx.fillStyle = '#9ca3af';
  center(ctx, 'gg.wonono1128.com · AI 사주', PAD, CONTENT, lineY + 22);
}

// ---------------------------------------------------------------------
// 공통 블록
// ---------------------------------------------------------------------

/** 카드 상단: 종류 · 총평 · 부제 */
function drawHeader(ctx: Ctx, y: number, typeLine: string, headline: string, sub: string): number {
  ctx.font = font(32);
  ctx.fillStyle = MUTED;
  ctx.fillText(typeLine, PAD, y);
  y += 56;

  ctx.font = font(62, 'bold');
  ctx.fillStyle = INK;
  y = drawLines(ctx, wrap(ctx, headline, CONTENT, 3), PAD, y, 82) + 16;

  ctx.font = font(30, 'bold');
  ctx.fillStyle = BODY_INK;
  ctx.fillText(sub, PAD, y);
  return y + 60;
}

function drawScore(ctx: Ctx, y: number, label: string, score: number, accent: string): number {
  ctx.font = font(30);
  ctx.fillStyle = MUTED;
  ctx.fillText(label, PAD, y + 12);

  ctx.font = font(52, 'bold');
  ctx.fillStyle = INK;
  const text = `${score}점`;
  ctx.fillText(text, W - PAD - ctx.measureText(text).width, y);

  const barY = y + 68;
  ctx.fillStyle = '#f3f4f6';
  roundRect(ctx, PAD, barY, CONTENT, 18, 9);
  ctx.fill();

  ctx.fillStyle = accent;
  roundRect(ctx, PAD, barY, (CONTENT * Math.min(100, Math.max(0, score))) / 100, 18, 9);
  ctx.fill();

  return barY + 18;
}

/** 섹션 제목 (좌측 색 바 + 굵은 글씨) */
function drawHeading(ctx: Ctx, y: number, text: string, accent = INK): number {
  ctx.fillStyle = accent;
  roundRect(ctx, PAD, y + 4, 8, 32, 4);
  ctx.fill();

  ctx.font = font(34, 'bold');
  ctx.fillStyle = INK;
  ctx.fillText(text, PAD + 26, y);
  return y + 52;
}

function drawParagraph(ctx: Ctx, y: number, text: string, x = PAD, width = CONTENT): number {
  ctx.font = font(BODY_SIZE);
  ctx.fillStyle = BODY_INK;
  return drawLines(ctx, wrap(ctx, text, width), x, y, BODY_LH);
}

/** 옅은 배경을 깐 문단 (요약용) */
function drawParagraphBox(ctx: Ctx, y: number, text: string, bg = '#f9fafb'): number {
  ctx.font = font(BODY_SIZE);
  const lines = wrap(ctx, text, CONTENT - INNER * 2);
  const height = lines.length * BODY_LH + INNER * 2;

  ctx.fillStyle = bg;
  roundRect(ctx, PAD, y, CONTENT, height, 20);
  ctx.fill();

  ctx.fillStyle = BODY_INK;
  drawLines(ctx, lines, PAD + INNER, y + INNER, BODY_LH);
  return y + height;
}

/** 목록 박스 (강점 / 주의점) */
function drawListBox(
  ctx: Ctx,
  y: number,
  title: string,
  items: string[],
  accent: string,
  bg: string,
  marker: string
): number {
  ctx.font = font(BODY_SIZE);
  const markerWidth = 34;
  const wrapped = items.map((item) => wrap(ctx, item, CONTENT - INNER * 2 - markerWidth));
  const linesTotal = wrapped.reduce((sum, lines) => sum + lines.length, 0);
  const height = INNER * 2 + 46 + linesTotal * BODY_LH + (items.length - 1) * 14;

  ctx.fillStyle = bg;
  roundRect(ctx, PAD, y, CONTENT, height, 20);
  ctx.fill();
  ctx.strokeStyle = accent;
  ctx.lineWidth = 3;
  roundRect(ctx, PAD, y, CONTENT, height, 20);
  ctx.stroke();

  ctx.font = font(32, 'bold');
  ctx.fillStyle = accent;
  ctx.fillText(title, PAD + INNER, y + INNER);

  let cursor = y + INNER + 46;
  wrapped.forEach((lines, index) => {
    ctx.font = font(BODY_SIZE, 'bold');
    ctx.fillStyle = accent;
    ctx.fillText(marker, PAD + INNER, cursor);

    ctx.font = font(BODY_SIZE);
    ctx.fillStyle = BODY_INK;
    cursor = drawLines(ctx, lines, PAD + INNER + markerWidth, cursor, BODY_LH);
    if (index < wrapped.length - 1) cursor += 14;
  });

  return y + height;
}

/** 테두리만 있는 강조 박스 (조언) */
function drawCallout(ctx: Ctx, y: number, title: string, body: string, accent: string): number {
  ctx.font = font(BODY_SIZE);
  const lines = wrap(ctx, body, CONTENT - INNER * 2);
  const height = INNER * 2 + 46 + lines.length * BODY_LH;

  ctx.strokeStyle = accent;
  ctx.lineWidth = 4;
  roundRect(ctx, PAD, y, CONTENT, height, 20);
  ctx.stroke();

  ctx.font = font(32, 'bold');
  ctx.fillStyle = accent;
  ctx.fillText(title, PAD + INNER, y + INNER);

  ctx.font = font(BODY_SIZE);
  ctx.fillStyle = BODY_INK;
  drawLines(ctx, lines, PAD + INNER, y + INNER + 46, BODY_LH);
  return y + height;
}

function drawKeywords(ctx: Ctx, y: number, keywords: string[]): number {
  ctx.font = font(28);
  let x = PAD;
  let rowTop = y;
  for (const keyword of keywords) {
    const text = `#${keyword}`;
    const width = ctx.measureText(text).width + 40;
    if (x + width > W - PAD) {
      x = PAD;
      rowTop += 64;
    }
    ctx.strokeStyle = FAINT;
    ctx.lineWidth = 2;
    roundRect(ctx, x, rowTop, width, 52, 26);
    ctx.stroke();

    ctx.fillStyle = MUTED;
    ctx.fillText(text, x + 20, rowTop + 13);
    x += width + 12;
  }
  return rowTop + 52;
}

/** 요점 카드 — 한눈에 읽히는 답 (label / 큰 value / 근거) */
function drawHighlights(
  ctx: Ctx,
  y: number,
  items: { label: string; value: string; detail: string | null }[]
): number {
  const gap = 16;
  const boxWidth = (CONTENT - gap * (items.length - 1)) / items.length;
  const inner = 20;

  // 가장 높은 박스에 맞춰 높이를 통일한다
  ctx.font = font(22);
  const details = items.map((item) =>
    item.detail ? wrap(ctx, item.detail, boxWidth - inner * 2, 4) : []
  );
  const detailLines = Math.max(0, ...details.map((lines) => lines.length));
  const height = inner * 2 + 30 + 44 + detailLines * 30;

  items.forEach((item, index) => {
    const x = PAD + index * (boxWidth + gap);

    ctx.strokeStyle = FAINT;
    ctx.lineWidth = 3;
    roundRect(ctx, x, y, boxWidth, height, 18);
    ctx.stroke();

    ctx.font = font(22);
    ctx.fillStyle = MUTED;
    ctx.fillText(wrap(ctx, item.label, boxWidth - inner * 2, 1)[0] ?? '', x + inner, y + inner);

    ctx.font = font(36, 'bold');
    ctx.fillStyle = INK;
    ctx.fillText(wrap(ctx, item.value, boxWidth - inner * 2, 1)[0] ?? '', x + inner, y + inner + 32);

    ctx.font = font(22);
    ctx.fillStyle = MUTED;
    drawLines(ctx, details[index], x + inner, y + inner + 78, 30);
  });

  return y + height;
}

/** 해석 섹션들 (제목 + 본문) */
function drawSections(
  ctx: Ctx,
  y: number,
  sections: { title: string; body: string }[],
  accent: string
): number {
  for (const section of sections) {
    y = drawHeading(ctx, y, section.title, accent);
    y = drawParagraph(ctx, y, section.body) + 40;
  }
  return y - 40;
}

// ---------------------------------------------------------------------
// 사주 카드
// ---------------------------------------------------------------------

/** 사주팔자 4기둥 (시주를 모르면 3칸) */
function drawPillars(ctx: Ctx, y: number, reading: SajuReading): number {
  const pillars = reading.chart.pillars;
  const gap = 16;
  const boxWidth = (CONTENT - gap * (pillars.length - 1)) / pillars.length;
  const cellHeight = 96;

  pillars.forEach((pillar, index) => {
    const x = PAD + index * (boxWidth + gap);

    ctx.font = font(24);
    ctx.fillStyle = MUTED;
    center(ctx, pillar.position, x, boxWidth, y);

    const top = y + 36;
    [
      { hanja: pillar.stemHanja, korean: pillar.stem, element: pillar.stemElement, tenGod: pillar.stemTenGod },
      { hanja: pillar.branchHanja, korean: pillar.branch, element: pillar.branchElement, tenGod: pillar.branchTenGod },
    ].forEach((cell, row) => {
      const cellY = top + row * cellHeight;
      const tone = color(cell.element);

      ctx.fillStyle = tone.bg;
      roundRect(ctx, x, cellY, boxWidth, cellHeight, 16);
      ctx.fill();

      ctx.font = font(54, 'bold');
      ctx.fillStyle = tone.fill;
      center(ctx, cell.hanja, x, boxWidth, cellY + 12);

      ctx.font = font(22);
      center(ctx, `${cell.korean}·${cell.element}`, x, boxWidth, cellY + 66);
    });

    // 십성은 기둥 아래에 작게
    ctx.font = font(22);
    ctx.fillStyle = MUTED;
    center(ctx, pillar.stemTenGod, x, boxWidth, top + cellHeight * 2 + 10);
    center(ctx, pillar.branchTenGod, x, boxWidth, top + cellHeight * 2 + 38);
  });

  return y + 36 + cellHeight * 2 + 70;
}

function drawElements(ctx: Ctx, y: number, counts: Record<string, number>): number {
  const max = Math.max(1, ...Object.values(counts));
  const gap = 14;
  const boxWidth = (CONTENT - gap * 4) / 5;

  ELEMENT_ORDER.forEach((element, index) => {
    const x = PAD + index * (boxWidth + gap);
    const count = counts[element] ?? 0;
    const tone = color(element);

    ctx.fillStyle = '#f3f4f6';
    roundRect(ctx, x, y, boxWidth, 14, 7);
    ctx.fill();

    if (count > 0) {
      ctx.fillStyle = tone.fill;
      roundRect(ctx, x, y, (boxWidth * count) / max, 14, 7);
      ctx.fill();
    }

    ctx.font = font(26, 'bold');
    ctx.fillStyle = count > 0 ? tone.fill : FAINT;
    center(ctx, `${element} ${count}`, x, boxWidth, y + 24);
  });

  return y + 56;
}

/** 십성 분포 5칸 */
function drawTenGods(ctx: Ctx, y: number, groups: Record<string, number>): number {
  const entries = Object.entries(groups);
  const gap = 14;
  const boxWidth = (CONTENT - gap * (entries.length - 1)) / entries.length;

  entries.forEach(([group, count], index) => {
    const x = PAD + index * (boxWidth + gap);
    ctx.strokeStyle = count === 0 ? '#f3f4f6' : FAINT;
    ctx.lineWidth = 2;
    roundRect(ctx, x, y, boxWidth, 78, 14);
    ctx.stroke();

    ctx.font = font(24);
    ctx.fillStyle = count === 0 ? FAINT : MUTED;
    center(ctx, group, x, boxWidth, y + 12);

    ctx.font = font(30, 'bold');
    ctx.fillStyle = count === 0 ? FAINT : INK;
    center(ctx, String(count), x, boxWidth, y + 42);
  });

  return y + 78;
}

/** 시기별 흐름 */
function drawTimeline(ctx: Ctx, y: number, periods: { period: string; body: string }[]): number {
  for (const period of periods) {
    ctx.fillStyle = HIT;
    ctx.beginPath();
    ctx.arc(PAD + 8, y + 16, 8, 0, Math.PI * 2);
    ctx.fill();

    ctx.font = font(30, 'bold');
    ctx.fillStyle = INK;
    ctx.fillText(period.period, PAD + 32, y);

    y = drawParagraph(ctx, y + 44, period.body, PAD + 32, CONTENT - 32) + 24;
  }
  return y - 24;
}

/** 행운 정보 2x2 */
function drawLucky(ctx: Ctx, y: number, items: { label: string; value: string }[]): number {
  const gap = 16;
  const boxWidth = (CONTENT - gap) / 2;
  const boxHeight = 96;

  items.forEach((item, index) => {
    const x = PAD + (index % 2) * (boxWidth + gap);
    const top = y + Math.floor(index / 2) * (boxHeight + gap);

    ctx.strokeStyle = FAINT;
    ctx.lineWidth = 2;
    roundRect(ctx, x, top, boxWidth, boxHeight, 16);
    ctx.stroke();

    ctx.font = font(24);
    ctx.fillStyle = MUTED;
    ctx.fillText(item.label, x + 24, top + 20);

    ctx.font = font(32, 'bold');
    ctx.fillStyle = INK;
    ctx.fillText(wrap(ctx, item.value, boxWidth - 48, 1)[0] ?? '', x + 24, top + 52);
  });

  const rows = Math.ceil(items.length / 2);
  return y + rows * boxHeight + (rows - 1) * gap;
}

/** 사주 결과 전체 이미지 */
export function drawSajuCard(reading: SajuReading): HTMLCanvasElement {
  return renderCard((ctx) => drawSajuBody(ctx, reading));
}

function drawSajuBody(ctx: Ctx, reading: SajuReading): number {
  const { chart, result } = reading;
  const meta = [reading.nickname, reading.birthDate, reading.birthTimeLabel, reading.gender]
    .filter(Boolean)
    .join(' · ');

  let y = drawHeader(
    ctx, PAD,
    `${reading.typeEmoji} ${reading.typeLabel}`,
    result.headline || reading.typeLabel,
    meta
  );

  if (result.score !== null && result.score !== undefined) {
    y = drawScore(ctx, y, `${reading.typeLabel} 점수`, result.score, HIT) + 56;
  }

  if (result.highlights?.length) {
    y = drawHighlights(ctx, y, result.highlights) + 48;
  }

  y = drawPillars(ctx, y, reading) + 8;

  ctx.font = font(26);
  ctx.fillStyle = MUTED;
  const chartMeta = `일간 ${chart.dayMaster}(${chart.dayMasterHanja}) · ${chart.zodiac}띠 · 세는나이 ${chart.koreanAge}세`
    + (chart.bodyStrength ? ` · 일간의 힘 ${chart.bodyStrength}` : '');
  ctx.fillText(chartMeta, PAD, y);
  y += 52;

  y = drawElements(ctx, y, chart.elementCounts) + 28;

  if (chart.tenGodGroups && Object.keys(chart.tenGodGroups).length > 0) {
    y = drawTenGods(ctx, y, chart.tenGodGroups) + 32;
  }

  if (result.keywords?.length) {
    y = drawKeywords(ctx, y, result.keywords) + 32;
  }

  y = drawParagraphBox(ctx, y, result.summary) + 56;

  if (result.sections?.length) {
    y = drawSections(ctx, y, result.sections, HIT) + 56;
  }

  if (result.strengths?.length) {
    y = drawListBox(ctx, y, '타고난 강점', result.strengths, HIT, '#f4faf3', '✔') + 24;
  }
  if (result.cautions?.length) {
    y = drawListBox(ctx, y, '조심할 점', result.cautions, MOVE, '#fdfaef', '!') + 24;
  }
  if (result.strengths?.length || result.cautions?.length) y += 32;

  if (result.timeline?.length) {
    y = drawHeading(ctx, y, '시기별 흐름');
    y = drawTimeline(ctx, y, result.timeline) + 56;
  }

  const lucky = [
    { label: '행운의 색', value: result.lucky?.color },
    { label: '행운의 숫자', value: result.lucky?.number },
    { label: '좋은 방향', value: result.lucky?.direction },
    { label: '지니면 좋은 것', value: result.lucky?.item },
  ].filter((item): item is { label: string; value: string } => !!item.value);
  if (lucky.length) {
    y = drawLucky(ctx, y, lucky) + 48;
  }

  if (result.advice) {
    y = drawCallout(ctx, y, '오늘부터 이렇게', result.advice, HIT);
  }

  return y;
}

// ---------------------------------------------------------------------
// 궁합 카드
// ---------------------------------------------------------------------

/** 자리별 합·충 근거 */
function drawSignals(
  ctx: Ctx,
  y: number,
  signals: { position: string; relation: string; detail: string; positive: boolean }[]
): number {
  if (signals.length === 0) {
    return drawParagraph(ctx, y, '눈에 띄는 합도 충도 없는 조합입니다. 서로 간섭이 적은 편이에요.');
  }

  const chipWidth = 92;
  for (const signal of signals) {
    ctx.font = font(BODY_SIZE);
    const lines = wrap(ctx, signal.detail, CONTENT - chipWidth - 20);
    const tone = signal.positive ? HIT : MOVE;

    ctx.fillStyle = tone;
    roundRect(ctx, PAD, y + 4, chipWidth, 40, 12);
    ctx.fill();

    ctx.font = font(24, 'bold');
    ctx.fillStyle = '#ffffff';
    center(ctx, signal.position, PAD, chipWidth, y + 12);

    ctx.font = font(BODY_SIZE);
    ctx.fillStyle = BODY_INK;
    y = drawLines(ctx, lines, PAD + chipWidth + 20, y + 6, BODY_LH) + 14;
  }
  return y - 14;
}

/** 궁합 결과 전체 이미지 */
export function drawCompatCard(reading: CompatReading): HTMLCanvasElement {
  return renderCard((ctx) => drawCompatBody(ctx, reading));
}

function drawCompatBody(ctx: Ctx, reading: CompatReading): number {
  const { analysis, result } = reading;

  let y = drawHeader(
    ctx, PAD,
    `${reading.typeEmoji} ${reading.typeLabel}`,
    result.headline || reading.typeLabel,
    `${analysis.aName}  💞  ${analysis.bName}`
  );

  y = drawScore(ctx, y, '궁합 점수', analysis.score, MOVE) + 56;

  y = drawHeading(ctx, y, '두 사주의 관계', MOVE);
  y = drawSignals(ctx, y, analysis.signals.filter((s) => s.relation !== '십성')) + 20;

  ctx.font = font(26);
  ctx.fillStyle = MUTED;
  const seen = `${analysis.aName} → ${analysis.aSeesB} · ${analysis.bName} → ${analysis.bSeesA}`
    + (analysis.dayStemHap ? ` · 일간 천간합(${analysis.dayStemHap})` : '');
  y = drawLines(ctx, wrap(ctx, seen, CONTENT), PAD, y, 36) + 36;

  if (result.keywords?.length) {
    y = drawKeywords(ctx, y, result.keywords) + 32;
  }

  y = drawParagraphBox(ctx, y, result.summary) + 56;

  if (result.sections?.length) {
    y = drawSections(ctx, y, result.sections, MOVE) + 56;
  }

  if (result.strengths?.length) {
    y = drawListBox(ctx, y, '잘 맞는 부분', result.strengths, HIT, '#f4faf3', '✔') + 24;
  }
  if (result.cautions?.length) {
    y = drawListBox(ctx, y, '부딪칠 수 있는 부분', result.cautions, MOVE, '#fdfaef', '!') + 24;
  }
  if (result.strengths?.length || result.cautions?.length) y += 32;

  if (result.aToB) {
    y = drawHeading(ctx, y, `${analysis.aName} → ${analysis.bName}`, MOVE);
    y = drawParagraph(ctx, y, result.aToB) + 40;
  }
  if (result.bToA) {
    y = drawHeading(ctx, y, `${analysis.bName} → ${analysis.aName}`, MOVE);
    y = drawParagraph(ctx, y, result.bToA) + 40;
  }

  if (result.advice) {
    y = drawCallout(ctx, y, '오래 잘 지내려면', result.advice, MOVE);
  }

  return y;
}
