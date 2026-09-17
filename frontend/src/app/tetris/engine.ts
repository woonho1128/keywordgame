// 테트리스 순수 엔진 — 프레임워크 무관. 렌더/입력/타이밍은 page.tsx가 담당.
import { PieceType, PIECES, STATES, BOX, kicksFor } from './srs';

export const COLS = 10;
export const VIS_ROWS = 20;        // 화면에 보이는 줄
export const HIDDEN = 2;           // 상단 버퍼(스폰용)
export const ROWS = VIS_ROWS + HIDDEN; // 22
export const SPRINT_GOAL = 40;

export type Mode = 'marathon' | 'sprint';
// 'G' = 가비지(방해) 블록. 배틀 모드에서 상대 공격으로 바닥에 쌓인다.
export type Cell = PieceType | 'G' | null;
export type Piece = { type: PieceType; rot: number; row: number; col: number };
export type TSpin = 'none' | 'mini' | 'full';
export type ClearKind =
  | 'none' | 'single' | 'double' | 'triple' | 'tetris' | 'tspin' | 'tspin-mini';

export type LockResult = {
  linesCleared: number;
  kind: ClearKind;        // 연출용 라벨
  points: number;         // 이번 락으로 얻은 점수
  tspin: TSpin;
  perfectClear: boolean;
  b2b: boolean;           // 이번 클리어가 백투백 보너스를 받았는가
  combo: number;          // 현재 콤보(0=콤보 없음)
  gameOver: boolean;
  goalReached: boolean;   // 스프린트 40줄 달성
  rowsCleared: number[];  // 지워진 줄 인덱스(연출용)
};

const LINE_PTS = [0, 100, 300, 500, 800];
const TSPIN_PTS = [400, 800, 1200, 1600];
const TSPIN_MINI_PTS = [100, 200, 400, 400];
const PC_PTS = [0, 800, 1200, 1800, 2000]; // 퍼펙트 클리어 보너스

export class TetrisEngine {
  board: Cell[][];
  cur: Piece | null = null;
  hold: PieceType | null = null;
  canHold = true;
  queue: PieceType[] = [];
  bag: PieceType[] = [];
  score = 0;
  lines = 0;
  level = 1;
  combo = -1;
  b2b = false;
  over = false;
  goalReached = false;
  readonly mode: Mode;
  private rng: () => number;
  private lastRotate = false;   // 직전 성공 동작이 회전이었나(T-스핀 판정용)
  private lastKick = 0;         // 직전 회전에 성공한 킥 인덱스(0~4)

  constructor(mode: Mode, rng: () => number = Math.random) {
    this.mode = mode;
    this.rng = rng;
    this.board = Array.from({ length: ROWS }, () => Array<Cell>(COLS).fill(null));
    this.fillQueue();
    this.spawn();
  }

  // ── 7-bag ──────────────────────────────────────────────
  private refillBag() {
    const b = PIECES.slice();
    for (let i = b.length - 1; i > 0; i--) {
      const j = Math.floor(this.rng() * (i + 1));
      [b[i], b[j]] = [b[j], b[i]];
    }
    this.bag.push(...b);
  }
  private fillQueue() {
    while (this.queue.length < 6) {
      if (this.bag.length === 0) this.refillBag();
      this.queue.push(this.bag.shift()!);
    }
  }

  // ── 스폰 ───────────────────────────────────────────────
  private spawnCol(t: PieceType) { return t === 'O' ? 4 : 3; }
  spawn(type?: PieceType) {
    const t = type ?? this.queue.shift()!;
    this.fillQueue();
    const p: Piece = { type: t, rot: 0, row: 0, col: this.spawnCol(t) };
    this.cur = p;
    this.canHold = true;
    this.lastRotate = false;
    if (this.collides(p)) this.over = true; // 블록아웃
  }

  // ── 좌표/충돌 ──────────────────────────────────────────
  cellsOf(p: Piece): number[][] {
    return STATES[p.type][p.rot].map(([r, c]) => [p.row + r, p.col + c]);
  }
  private collides(p: Piece): boolean {
    for (const [r, c] of this.cellsOf(p)) {
      if (c < 0 || c >= COLS || r >= ROWS) return true;
      if (r >= 0 && this.board[r][c]) return true;
    }
    return false;
  }

  // ── 이동/회전 ──────────────────────────────────────────
  move(dx: number): boolean {
    if (!this.cur || this.over) return false;
    const p = { ...this.cur, col: this.cur.col + dx };
    if (this.collides(p)) return false;
    this.cur = p; this.lastRotate = false; return true;
  }
  moveDown(): boolean {
    if (!this.cur || this.over) return false;
    const p = { ...this.cur, row: this.cur.row + 1 };
    if (this.collides(p)) return false;
    this.cur = p; this.lastRotate = false; return true;
  }
  rotate(dir: 1 | -1): boolean {
    if (!this.cur || this.over) return false;
    const from = this.cur.rot;
    const to = (from + (dir === 1 ? 1 : 3)) % 4;
    const kicks = kicksFor(this.cur.type, from, to);
    for (let i = 0; i < kicks.length; i++) {
      const [kx, ky] = kicks[i];
      const p: Piece = { ...this.cur, rot: to, col: this.cur.col + kx, row: this.cur.row - ky };
      if (!this.collides(p)) {
        this.cur = p; this.lastRotate = true; this.lastKick = i; return true;
      }
    }
    return false;
  }
  isResting(): boolean {
    if (!this.cur) return false;
    return this.collides({ ...this.cur, row: this.cur.row + 1 });
  }

  // ── 고스트 ─────────────────────────────────────────────
  ghost(): Piece | null {
    if (!this.cur) return null;
    let p = { ...this.cur };
    while (!this.collides({ ...p, row: p.row + 1 })) p = { ...p, row: p.row + 1 };
    return p;
  }

  // ── 홀드 ───────────────────────────────────────────────
  holdPiece(): boolean {
    if (!this.cur || !this.canHold || this.over) return false;
    const cur = this.cur.type;
    if (this.hold == null) { this.hold = cur; this.spawn(); }
    else { const h = this.hold; this.hold = cur; this.spawn(h); }
    this.canHold = false;
    return true;
  }

  // ── 드롭 ───────────────────────────────────────────────
  softDrop(): boolean {
    if (this.moveDown()) { this.score += 1; return true; }
    return false;
  }
  hardDrop(): LockResult {
    let dist = 0;
    while (this.moveDown()) dist++;
    this.score += dist * 2;
    return this.lock();
  }

  // ── T-스핀 판정 (3-코너 규칙) ──────────────────────────
  private detectTSpin(): TSpin {
    if (!this.cur || this.cur.type !== 'T' || !this.lastRotate) return 'none';
    const { row, col, rot } = this.cur;
    const filled = (r: number, c: number) =>
      c < 0 || c >= COLS || r >= ROWS || (r >= 0 && !!this.board[r][c]);
    const TL = filled(row, col), TR = filled(row, col + 2);
    const BL = filled(row + 2, col), BR = filled(row + 2, col + 2);
    // 회전상태별 '앞쪽'(T가 가리키는 방향) 두 코너
    const fronts = [[TL, TR], [TR, BR], [BL, BR], [TL, BL]][rot];
    const frontFilled = fronts.filter(Boolean).length;
    const total = [TL, TR, BL, BR].filter(Boolean).length;
    if (total < 3) return 'none';
    if (frontFilled === 2) return 'full';
    // 앞 1개뿐이면 미니 — 단, 마지막(대각) 킥으로 성사됐으면 풀로 승격
    return this.lastKick === 4 ? 'full' : 'mini';
  }

  // ── 고정 + 라인 클리어 + 점수 ──────────────────────────
  lock(): LockResult {
    if (!this.cur || this.over) {
      return this.emptyResult();
    }
    const tspin = this.detectTSpin();
    for (const [r, c] of this.cellsOf(this.cur)) if (r >= 0) this.board[r][c] = this.cur.type;

    // 꽉 찬 줄 찾기
    const rowsCleared: number[] = [];
    for (let r = 0; r < ROWS; r++) if (this.board[r].every((x) => x)) rowsCleared.push(r);
    const cleared = rowsCleared.length;
    for (const r of rowsCleared) this.board.splice(r, 1);
    for (let i = 0; i < cleared; i++) this.board.unshift(Array<Cell>(COLS).fill(null));

    const L = this.level;
    const difficult = cleared === 4 || (tspin !== 'none' && cleared > 0);
    const b2bBonus = difficult && this.b2b && cleared > 0;

    let pts = 0;
    if (tspin === 'full') pts = TSPIN_PTS[cleared] ?? 0;
    else if (tspin === 'mini') pts = TSPIN_MINI_PTS[cleared] ?? 0;
    else pts = LINE_PTS[cleared] ?? 0;
    pts *= L;
    if (b2bBonus) pts = Math.floor(pts * 1.5);

    // 콤보
    if (cleared > 0) this.combo++; else this.combo = -1;
    const comboBonus = this.combo > 0 ? 50 * this.combo * L : 0;

    // 퍼펙트 클리어(보드 완전 비움)
    const perfectClear = cleared > 0 && this.board.every((rw) => rw.every((x) => !x));
    const pcBonus = perfectClear ? (PC_PTS[cleared] ?? 0) * L : 0;

    const points = pts + comboBonus + pcBonus;
    this.score += points;

    // 백투백 상태 갱신(줄을 지웠을 때만)
    if (cleared > 0) this.b2b = difficult;

    // 줄/레벨
    this.lines += cleared;
    this.level = Math.min(20, Math.floor(this.lines / 10) + 1);
    this.goalReached = this.mode === 'sprint' && this.lines >= SPRINT_GOAL;

    const kind: ClearKind =
      tspin === 'full' ? 'tspin'
        : tspin === 'mini' ? 'tspin-mini'
          : (['none', 'single', 'double', 'triple', 'tetris'][cleared] as ClearKind);

    this.spawn();
    if (this.goalReached) this.over = true;

    return {
      linesCleared: cleared, kind, points, tspin, perfectClear,
      b2b: b2bBonus, combo: this.combo, gameOver: this.over,
      goalReached: this.goalReached, rowsCleared,
    };
  }

  private emptyResult(): LockResult {
    return {
      linesCleared: 0, kind: 'none', points: 0, tspin: 'none', perfectClear: false,
      b2b: false, combo: this.combo, gameOver: this.over, goalReached: this.goalReached,
      rowsCleared: [],
    };
  }

  // ── 렌더용 조회 ────────────────────────────────────────
  nextQueue(n = 5): PieceType[] { return this.queue.slice(0, n); }

  // ── 배틀: 가비지 · 열 높이 ─────────────────────────────
  /** 바닥에 구멍 뚫린 가비지 줄을 rows만큼 밀어 올린다. 스택이 넘치면 top-out. */
  addGarbage(rows: number, holeCol: number) {
    if (rows <= 0) return;
    const hole = ((holeCol % COLS) + COLS) % COLS;
    for (let i = 0; i < rows; i++) {
      const top = this.board.shift()!;              // 맨 윗줄 제거(밀어 올림)
      if (top.some((x) => x)) this.over = true;     // 윗줄에 블록이 있었으면 넘침 → top-out
      const g = Array<Cell>(COLS).fill('G');
      g[hole] = null;
      this.board.push(g);                           // 바닥에 가비지 추가
    }
    if (this.cur && this.collides(this.cur)) this.over = true; // 현재 조각이 끼면 top-out
  }

  /** 각 열의 높이(바닥부터 쌓인 칸 수). 상대 미니보드/봇 위험도 표시에 사용. */
  columnHeights(): number[] {
    const h = new Array<number>(COLS).fill(0);
    for (let c = 0; c < COLS; c++) {
      for (let r = 0; r < ROWS; r++) {
        if (this.board[r][c]) { h[c] = ROWS - r; break; }
      }
    }
    return h;
  }
}

// 라인 클리어 결과 → 상대에게 보낼 가비지 줄 수(정통 가이드라인 근사).
export function attackLines(res: LockResult): number {
  if (res.linesCleared === 0) return 0;
  let atk: number;
  if (res.tspin === 'full') atk = [0, 2, 4, 6][res.linesCleared] ?? 0;
  else if (res.tspin === 'mini') atk = [0, 0, 1][res.linesCleared] ?? 0;
  else atk = [0, 0, 1, 2, 4][res.linesCleared] ?? 0; // 싱글0 더블1 트리플2 테트리스4
  if (res.b2b) atk += 1;                              // 백투백 보너스
  const c = res.combo;                               // 콤보 누적
  if (c >= 1) atk += c <= 2 ? 1 : c <= 4 ? 2 : c <= 6 ? 3 : 4;
  if (res.perfectClear) atk += 10;
  return atk;
}

// 레벨별 중력(칸당 ms). 가이드라인 곡선.
export function gravityMs(level: number): number {
  const l = Math.max(1, Math.min(20, level));
  const t = Math.pow(0.8 - (l - 1) * 0.007, l - 1) * 1000;
  return Math.max(12, t);
}
