// SRS(Super Rotation System) — 블록 정의 · 회전 상태 · 월킥 테이블
// 좌표계: [row, col], row는 아래로 증가. 킥 오프셋은 표준 SRS(y-up) 값을 그대로 쓰고
// 엔진에서 newRow = row - ky, newCol = col + kx 로 변환한다.

export type PieceType = 'I' | 'O' | 'T' | 'S' | 'Z' | 'J' | 'L';
export const PIECES: PieceType[] = ['I', 'O', 'T', 'S', 'Z', 'J', 'L'];

// 각 블록의 박스 크기와 스폰(상태 0) 모양
const SHAPES: Record<PieceType, { box: number; rows: string[] }> = {
  I: { box: 4, rows: ['....', 'IIII', '....', '....'] },
  O: { box: 2, rows: ['OO', 'OO'] },
  T: { box: 3, rows: ['.T.', 'TTT', '...'] },
  S: { box: 3, rows: ['.SS', 'SS.', '...'] },
  Z: { box: 3, rows: ['ZZ.', '.ZZ', '...'] },
  J: { box: 3, rows: ['J..', 'JJJ', '...'] },
  L: { box: 3, rows: ['..L', 'LLL', '...'] },
};

export const BOX: Record<PieceType, number> = {
  I: 4, O: 2, T: 3, S: 3, Z: 3, J: 3, L: 3,
};

// 박스를 시계방향 90° 회전
function rotateCW(cells: number[][], box: number): number[][] {
  return cells.map(([r, c]) => [c, box - 1 - r]);
}

// STATES[type][rot] = 채워진 셀들의 [row, col] 목록 (박스 로컬 좌표)
export const STATES: Record<PieceType, number[][][]> = {} as any;
for (const t of PIECES) {
  const { box, rows } = SHAPES[t];
  const base: number[][] = [];
  rows.forEach((line, r) => {
    for (let c = 0; c < line.length; c++) if (line[c] !== '.') base.push([r, c]);
  });
  const states = [base];
  for (let i = 1; i < 4; i++) states.push(rotateCW(states[i - 1], box));
  STATES[t] = states;
}

// 월킥 테이블 — 키는 `${from}${to}` (상태 0,1,2,3 = spawn,R,2,L). 값은 [kx, ky] (y-up)
type KickTable = Record<string, number[][]>;

export const JLSTZ_KICKS: KickTable = {
  '01': [[0, 0], [-1, 0], [-1, 1], [0, -2], [-1, -2]],
  '10': [[0, 0], [1, 0], [1, -1], [0, 2], [1, 2]],
  '12': [[0, 0], [1, 0], [1, -1], [0, 2], [1, 2]],
  '21': [[0, 0], [-1, 0], [-1, 1], [0, -2], [-1, -2]],
  '23': [[0, 0], [1, 0], [1, 1], [0, -2], [1, -2]],
  '32': [[0, 0], [-1, 0], [-1, -1], [0, 2], [-1, 2]],
  '30': [[0, 0], [-1, 0], [-1, -1], [0, 2], [-1, 2]],
  '03': [[0, 0], [1, 0], [1, 1], [0, -2], [1, -2]],
};

export const I_KICKS: KickTable = {
  '01': [[0, 0], [-2, 0], [1, 0], [-2, -1], [1, 2]],
  '10': [[0, 0], [2, 0], [-1, 0], [2, 1], [-1, -2]],
  '12': [[0, 0], [-1, 0], [2, 0], [-1, 2], [2, -1]],
  '21': [[0, 0], [1, 0], [-2, 0], [1, -2], [-2, 1]],
  '23': [[0, 0], [2, 0], [-1, 0], [2, 1], [-1, -2]],
  '32': [[0, 0], [-2, 0], [1, 0], [-2, -1], [1, 2]],
  '30': [[0, 0], [1, 0], [-2, 0], [1, -2], [-2, 1]],
  '03': [[0, 0], [-1, 0], [2, 0], [-1, 2], [2, -1]],
};

// O는 회전해도 모양이 같아 킥이 필요 없다
export function kicksFor(type: PieceType, from: number, to: number): number[][] {
  if (type === 'O') return [[0, 0]];
  const table = type === 'I' ? I_KICKS : JLSTZ_KICKS;
  return table[`${from}${to}`] ?? [[0, 0]];
}
