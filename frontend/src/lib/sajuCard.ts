/**
 * 공유용 결과 카드를 캔버스에 직접 그린다.
 *
 * <p>DOM을 통째로 캡처(html2canvas 류)하지 않는 이유:
 * 결과 페이지는 6개 섹션이라 세로로 아주 길다. 그대로 찍으면 글씨가 읽히지 않는 긴 이미지가 되고,
 * 라이브러리 의존성도 늘어난다. 대신 공유에 필요한 것만 추려 1080x1350 카드로 그린다.
 * 한글은 캔버스가 시스템 폰트로 그려주므로 폰트 파일을 따로 실을 필요가 없다.
 */

import { CompatReading, SajuReading } from './saju';

const W = 1080;
/** 최소 높이 — 내용이 짧아도 4:5 비율은 유지한다 */
const MIN_H = 1350;
/** 요약이 아무리 길어도 이보다 길어지면 말줄임 */
const MAX_H = 2400;
/** 본문 끝에서 카드 아래까지 — 구분선(48px 아래) + 출처 + 여백 */
const FOOTER_SPACE = 176;
const PAD = 72;
const CONTENT = W - PAD * 2;

const FONT = `-apple-system, BlinkMacSystemFont, "Malgun Gothic", "Apple SD Gothic Neo", "Noto Sans KR", sans-serif`;

const INK = '#171717';
const MUTED = '#6b7280';
const FAINT = '#d1d5db';
const HIT = '#6aaa64';
const MOVE = '#c9b458';

/** 오행별 색 — 화면(elementStyle)과 같은 계열로 맞춘다 */
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

function roundRect(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  r: number
) {
  const radius = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + radius, y);
  ctx.arcTo(x + w, y, x + w, y + h, radius);
  ctx.arcTo(x + w, y + h, x, y + h, radius);
  ctx.arcTo(x, y + h, x, y, radius);
  ctx.arcTo(x, y, x + w, y, radius);
  ctx.closePath();
}

/** 글자 단위로 줄바꿈 (한글은 단어 경계가 넓어서 글자 단위가 더 깔끔하다) */
function wrap(ctx: CanvasRenderingContext2D, text: string, maxWidth: number, maxLines: number) {
  const lines: string[] = [];
  let line = '';
  for (const char of text.replace(/\s+/g, ' ').trim()) {
    const next = line + char;
    if (ctx.measureText(next).width > maxWidth && line) {
      lines.push(line);
      line = char === ' ' ? '' : char;
      if (lines.length === maxLines) return trimTail(ctx, lines, maxWidth);
    } else {
      line = next;
    }
  }
  if (line) lines.push(line);
  return lines.slice(0, maxLines);
}

/** 잘린 마지막 줄 끝에 말줄임표 */
function trimTail(ctx: CanvasRenderingContext2D, lines: string[], maxWidth: number) {
  const last = lines[lines.length - 1];
  let trimmed = last;
  while (trimmed && ctx.measureText(trimmed + '…').width > maxWidth) {
    trimmed = trimmed.slice(0, -1);
  }
  lines[lines.length - 1] = trimmed + '…';
  return lines;
}

function drawLines(
  ctx: CanvasRenderingContext2D,
  lines: string[],
  x: number,
  y: number,
  lineHeight: number
) {
  lines.forEach((line, index) => ctx.fillText(line, x, y + index * lineHeight));
  return y + lines.length * lineHeight;
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
 * 내용 높이에 맞춰 카드를 만든다.
 *
 * <p>고정 높이로 그리면 요약이 길 때 잘린다. 그래서 넉넉한 임시 캔버스에 본문을 먼저 그리고,
 * 실제로 쓴 높이만큼만 잘라낸 캔버스에 옮긴 뒤 맨 아래에 푸터를 붙인다.
 *
 * @param drawBody 본문을 그리고 마지막 y를 돌려준다
 */
function renderCard(drawBody: (ctx: CanvasRenderingContext2D) => number): HTMLCanvasElement {
  const scratch = createCanvas(MAX_H);
  const bodyBottom = drawBody(scratch.ctx);

  const height = Math.min(MAX_H, Math.max(MIN_H, Math.round(bodyBottom + FOOTER_SPACE)));
  const card = createCanvas(height);
  card.ctx.drawImage(scratch.canvas, 0, 0);   // 남는 아래쪽은 잘린다
  drawFooter(card.ctx, height);
  return card.canvas;
}

/** 점수 막대 */
function drawScore(ctx: CanvasRenderingContext2D, y: number, label: string, score: number, accent: string) {
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

/** 키워드 칩 (한 줄에 들어가는 만큼만) */
function drawKeywords(ctx: CanvasRenderingContext2D, y: number, keywords: string[]) {
  ctx.font = font(28);
  let x = PAD;
  for (const keyword of keywords) {
    const text = `#${keyword}`;
    const width = ctx.measureText(text).width + 40;
    if (x + width > W - PAD) break;

    ctx.strokeStyle = FAINT;
    ctx.lineWidth = 2;
    roundRect(ctx, x, y, width, 52, 26);
    ctx.stroke();

    ctx.fillStyle = MUTED;
    ctx.fillText(text, x + 20, y + 13);
    x += width + 12;
  }
  return y + 52;
}

function drawFooter(ctx: CanvasRenderingContext2D, height: number) {
  // 카드 맨 아래 구분선 + 출처. FAINT(#d1d5db)는 흰 배경에서 거의 안 보여서 한 단계 진하게 쓴다
  const lineY = height - PAD - 56;
  ctx.strokeStyle = '#f3f4f6';
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(PAD, lineY);
  ctx.lineTo(W - PAD, lineY);
  ctx.stroke();

  ctx.font = font(26);
  ctx.fillStyle = '#9ca3af';
  const text = 'gg.wonono1128.com · AI 사주';
  ctx.fillText(text, (W - ctx.measureText(text).width) / 2, lineY + 22);
}

/** 사주팔자 4기둥 (시주를 모르면 3칸) */
function drawPillars(ctx: CanvasRenderingContext2D, y: number, reading: SajuReading) {
  const pillars = reading.chart.pillars;
  const gap = 16;
  const boxWidth = (CONTENT - gap * (pillars.length - 1)) / pillars.length;
  const cellHeight = 96;

  pillars.forEach((pillar, index) => {
    const x = PAD + index * (boxWidth + gap);

    ctx.font = font(24);
    ctx.fillStyle = MUTED;
    ctx.fillText(pillar.position, x + (boxWidth - ctx.measureText(pillar.position).width) / 2, y);

    const top = y + 36;
    [
      { hanja: pillar.stemHanja, korean: pillar.stem, element: pillar.stemElement },
      { hanja: pillar.branchHanja, korean: pillar.branch, element: pillar.branchElement },
    ].forEach((cell, row) => {
      const cellY = top + row * cellHeight;
      const tone = color(cell.element);

      ctx.fillStyle = tone.bg;
      roundRect(ctx, x, cellY, boxWidth, cellHeight, row === 0 ? 16 : 16);
      ctx.fill();

      ctx.font = font(54, 'bold');
      ctx.fillStyle = tone.fill;
      ctx.fillText(cell.hanja, x + (boxWidth - ctx.measureText(cell.hanja).width) / 2, cellY + 12);

      ctx.font = font(22);
      const sub = `${cell.korean}·${cell.element}`;
      ctx.fillText(sub, x + (boxWidth - ctx.measureText(sub).width) / 2, cellY + 66);
    });
  });

  return y + 36 + cellHeight * 2;
}

/** 오행 분포 미니 막대 */
function drawElements(ctx: CanvasRenderingContext2D, y: number, counts: Record<string, number>) {
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
    const label = `${element} ${count}`;
    ctx.fillText(label, x + (boxWidth - ctx.measureText(label).width) / 2, y + 24);
  });

  return y + 56;
}

/** 요약은 카드 높이가 늘어나므로 넉넉히 쓰되, 극단적으로 긴 글은 잘라낸다 */
const SUMMARY_MAX_LINES = 18;
const SUMMARY_LINE_HEIGHT = 46;

/** 사주 결과 카드 */
export function drawSajuCard(reading: SajuReading): HTMLCanvasElement {
  return renderCard((ctx) => drawSajuBody(ctx, reading));
}

function drawSajuBody(ctx: CanvasRenderingContext2D, reading: SajuReading): number {
  const { chart, result } = reading;
  let y = PAD;

  ctx.font = font(32);
  ctx.fillStyle = MUTED;
  ctx.fillText(`${reading.typeEmoji} ${reading.typeLabel}`, PAD, y);
  y += 56;

  ctx.font = font(62, 'bold');
  ctx.fillStyle = INK;
  y = drawLines(ctx, wrap(ctx, result.headline || reading.typeLabel, CONTENT, 2), PAD, y, 82) + 16;

  ctx.font = font(28);
  ctx.fillStyle = MUTED;
  const meta = [reading.nickname, reading.birthDate, reading.birthTimeLabel, reading.gender]
    .filter(Boolean)
    .join(' · ');
  ctx.fillText(meta, PAD, y);
  y += 72;

  if (result.score !== null && result.score !== undefined) {
    y = drawScore(ctx, y, `${reading.typeLabel} 점수`, result.score, HIT) + 56;
  }

  y = drawPillars(ctx, y, reading) + 20;

  ctx.font = font(26);
  ctx.fillStyle = MUTED;
  const chartMeta = `일간 ${chart.dayMaster}(${chart.dayMasterHanja}) · ${chart.zodiac}띠 · 세는나이 ${chart.koreanAge}세`;
  ctx.fillText(chartMeta, PAD, y);
  y += 52;

  y = drawElements(ctx, y, chart.elementCounts) + 32;

  if (result.keywords?.length) {
    y = drawKeywords(ctx, y, result.keywords) + 32;
  }

  ctx.font = font(30);
  ctx.fillStyle = '#374151';
  return drawLines(
    ctx,
    wrap(ctx, result.summary, CONTENT, SUMMARY_MAX_LINES),
    PAD, y, SUMMARY_LINE_HEIGHT
  );
}

/** 궁합 결과 카드 */
export function drawCompatCard(reading: CompatReading): HTMLCanvasElement {
  return renderCard((ctx) => drawCompatBody(ctx, reading));
}

function drawCompatBody(ctx: CanvasRenderingContext2D, reading: CompatReading): number {
  const { analysis, result } = reading;
  let y = PAD;

  ctx.font = font(32);
  ctx.fillStyle = MUTED;
  ctx.fillText(`${reading.typeEmoji} ${reading.typeLabel}`, PAD, y);
  y += 56;

  ctx.font = font(62, 'bold');
  ctx.fillStyle = INK;
  y = drawLines(ctx, wrap(ctx, result.headline || reading.typeLabel, CONTENT, 2), PAD, y, 82) + 16;

  ctx.font = font(34, 'bold');
  ctx.fillStyle = '#374151';
  const names = `${analysis.aName}  💞  ${analysis.bName}`;
  ctx.fillText(names, PAD, y);
  y += 80;

  y = drawScore(ctx, y, '궁합 점수', analysis.score, MOVE) + 56;

  // 계산된 관계 근거 — 이 카드의 핵심
  ctx.font = font(28, 'bold');
  ctx.fillStyle = INK;
  ctx.fillText('두 사주의 관계', PAD, y);
  y += 50;

  const signals = analysis.signals.filter((s) => s.relation !== '십성').slice(0, 4);
  if (signals.length === 0) {
    ctx.font = font(28);
    ctx.fillStyle = MUTED;
    ctx.fillText('눈에 띄는 합도 충도 없는 조합', PAD, y);
    y += 46;
  } else {
    for (const signal of signals) {
      const tone = signal.positive ? HIT : MOVE;

      ctx.fillStyle = tone;
      roundRect(ctx, PAD, y + 4, 92, 40, 12);
      ctx.fill();

      ctx.font = font(24, 'bold');
      ctx.fillStyle = '#ffffff';
      ctx.fillText(signal.position, PAD + (92 - ctx.measureText(signal.position).width) / 2, y + 12);

      ctx.font = font(28);
      ctx.fillStyle = '#374151';
      const detail = wrap(ctx, signal.detail, CONTENT - 112, 1);
      ctx.fillText(detail[0] ?? '', PAD + 112, y + 10);
      y += 56;
    }
  }
  y += 24;

  ctx.font = font(26);
  ctx.fillStyle = MUTED;
  // aSeesB = A의 일간이 보는 B의 십성. 이름과 짝을 헷갈리지 말 것
  const seen = `${analysis.aName} → ${analysis.aSeesB} · ${analysis.bName} → ${analysis.bSeesA}`;
  ctx.fillText(wrap(ctx, seen, CONTENT, 1)[0] ?? '', PAD, y);
  y += 56;

  if (result.keywords?.length) {
    y = drawKeywords(ctx, y, result.keywords) + 32;
  }

  ctx.font = font(30);
  ctx.fillStyle = '#374151';
  return drawLines(
    ctx,
    wrap(ctx, result.summary, CONTENT, SUMMARY_MAX_LINES),
    PAD, y, SUMMARY_LINE_HEIGHT
  );
}
