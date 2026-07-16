'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';

type Board = number[][];
const SIZE = 4;

const COLORS: Record<number, { bg: string; fg: string }> = {
  0: { bg: '#cdc1b4', fg: '#cdc1b4' },
  2: { bg: '#eee4da', fg: '#776e65' },
  4: { bg: '#ede0c8', fg: '#776e65' },
  8: { bg: '#f2b179', fg: '#fff' },
  16: { bg: '#f59563', fg: '#fff' },
  32: { bg: '#f67c5f', fg: '#fff' },
  64: { bg: '#f65e3b', fg: '#fff' },
  128: { bg: '#edcf72', fg: '#fff' },
  256: { bg: '#edcc61', fg: '#fff' },
  512: { bg: '#edc850', fg: '#fff' },
  1024: { bg: '#edc53f', fg: '#fff' },
  2048: { bg: '#edc22e', fg: '#fff' },
};
const hiColor = { bg: '#3c3a32', fg: '#fff' };

function empty(): Board { return Array.from({ length: SIZE }, () => Array(SIZE).fill(0)); }
function clone(b: Board): Board { return b.map((r) => [...r]); }
function eqRow(a: number[], b: number[]) { return a.every((x, i) => x === b[i]); }
function transpose(b: Board): Board { return b[0].map((_, c) => b.map((r) => r[c])); }

// 한 줄을 왼쪽으로 밀고 병합
function slide(row: number[]): { row: number[]; gained: number } {
  const arr = row.filter((x) => x);
  let gained = 0;
  for (let i = 0; i < arr.length - 1; i++) {
    if (arr[i] === arr[i + 1]) { arr[i] *= 2; gained += arr[i]; arr.splice(i + 1, 1); }
  }
  while (arr.length < SIZE) arr.push(0);
  return { row: arr, gained };
}

function move(b: Board, dir: 'left' | 'right' | 'up' | 'down'): { board: Board; gained: number; moved: boolean } {
  let work = clone(b);
  if (dir === 'up' || dir === 'down') work = transpose(work);
  const reverse = dir === 'right' || dir === 'down';
  let gained = 0, moved = false;
  const out = work.map((row) => {
    const src = reverse ? [...row].reverse() : row;
    const { row: nr, gained: g } = slide(src);
    gained += g;
    const final = reverse ? nr.reverse() : nr;
    if (!eqRow(final, row)) moved = true;
    return final;
  });
  let res = out;
  if (dir === 'up' || dir === 'down') res = transpose(out);
  return { board: res, gained, moved };
}

function spawn(b: Board): Board {
  const empties: [number, number][] = [];
  for (let r = 0; r < SIZE; r++) for (let c = 0; c < SIZE; c++) if (!b[r][c]) empties.push([r, c]);
  if (!empties.length) return b;
  const [r, c] = empties[Math.floor(Math.random() * empties.length)];
  const nb = clone(b);
  nb[r][c] = Math.random() < 0.9 ? 2 : 4;
  return nb;
}

function canMove(b: Board): boolean {
  for (let r = 0; r < SIZE; r++) for (let c = 0; c < SIZE; c++) {
    if (!b[r][c]) return true;
    if (c < SIZE - 1 && b[r][c] === b[r][c + 1]) return true;
    if (r < SIZE - 1 && b[r][c] === b[r + 1][c]) return true;
  }
  return false;
}

export default function Game2048() {
  const [board, setBoard] = useState<Board>(empty);
  const [score, setScore] = useState(0);
  const [best, setBest] = useState(0);
  const [over, setOver] = useState(false);
  const [won, setWon] = useState(false);
  const [nick, setNick] = useState('');
  const [myRank, setMyRank] = useState<number | null>(null);
  const [rows, setRows] = useState<{ rank: number; nick: string; score: number }[]>([]);
  const submitted = useRef(false);
  const boardRef = useRef(board); boardRef.current = board;
  const overRef = useRef(over); overRef.current = over;

  const loadRanking = useCallback(() => { api<any[]>(`/api/v1/scores/g2048?limit=10`).then(setRows).catch(() => {}); }, []);

  useEffect(() => {
    try { setNick(localStorage.getItem('arcade_nick') || ''); setBest(Number(localStorage.getItem('best2048') || 0)); } catch {}
    loadRanking();
    reset();
  }, [loadRanking]);

  const reset = () => {
    let b = spawn(spawn(empty()));
    setBoard(b); setScore(0); setOver(false); setWon(false); setMyRank(null);
    submitted.current = false;
  };

  const submit = useCallback(async (sc: number) => {
    const n = (nick.trim() || '익명').slice(0, 16);
    try { localStorage.setItem('arcade_nick', n); } catch {}
    try {
      const r = await api<{ myRank: number }>(`/api/v1/scores/g2048`, { method: 'POST', body: JSON.stringify({ nick: n, score: sc }) });
      setMyRank(r.myRank);
    } catch {}
    loadRanking();
  }, [nick, loadRanking]);

  const doMove = useCallback((dir: 'left' | 'right' | 'up' | 'down') => {
    if (overRef.current) return;
    const { board: nb, gained, moved } = move(boardRef.current, dir);
    if (!moved) return;
    const withSpawn = spawn(nb);
    setBoard(withSpawn);
    setScore((s) => {
      const ns = s + gained;
      setBest((bst) => { const nbst = Math.max(bst, ns); try { localStorage.setItem('best2048', String(nbst)); } catch {} return nbst; });
      return ns;
    });
    if (!won && nb.some((r) => r.some((x) => x >= 2048))) setWon(true);
    if (!canMove(withSpawn)) {
      setOver(true);
      setScore((s) => { if (!submitted.current) { submitted.current = true; submit(s); } return s; });
    }
  }, [won, submit]);

  // 키보드
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const map: Record<string, 'left' | 'right' | 'up' | 'down'> = {
        ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down',
        a: 'left', d: 'right', w: 'up', s: 'down',
      };
      const dir = map[e.key];
      if (dir) { e.preventDefault(); doMove(dir); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [doMove]);

  // 스와이프
  const touch = useRef<{ x: number; y: number } | null>(null);
  const onTouchStart = (e: React.TouchEvent) => { const t = e.touches[0]; touch.current = { x: t.clientX, y: t.clientY }; };
  const onTouchEnd = (e: React.TouchEvent) => {
    if (!touch.current) return;
    const t = e.changedTouches[0];
    const dx = t.clientX - touch.current.x, dy = t.clientY - touch.current.y;
    touch.current = null;
    if (Math.max(Math.abs(dx), Math.abs(dy)) < 24) return;
    if (Math.abs(dx) > Math.abs(dy)) doMove(dx > 0 ? 'right' : 'left');
    else doMove(dy > 0 ? 'down' : 'up');
  };

  const tileColor = (v: number) => COLORS[v] || hiColor;

  return (
    <main className="min-h-screen flex flex-col items-center p-4 max-w-md mx-auto w-full">
      <div className="w-full flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <Link href="/" aria-label="홈" className="text-lg text-slate-500 hover:text-slate-800">🏠</Link>
          <h1 className="text-2xl font-extrabold text-[#776e65]">2048</h1>
        </div>
        <div className="flex gap-2">
          <div className="rounded-lg bg-[#bbada0] px-3 py-1 text-center text-white">
            <p className="text-[10px] font-bold">점수</p><p className="font-bold">{score}</p>
          </div>
          <div className="rounded-lg bg-[#bbada0] px-3 py-1 text-center text-white">
            <p className="text-[10px] font-bold">최고</p><p className="font-bold">{best}</p>
          </div>
        </div>
      </div>

      <div className="relative w-full select-none" onTouchStart={onTouchStart} onTouchEnd={onTouchEnd} style={{ touchAction: 'none' }}>
        <div className="rounded-xl bg-[#bbada0] p-2 grid grid-cols-4 gap-2" style={{ aspectRatio: '1/1' }}>
          {board.flat().map((v, i) => {
            const c = tileColor(v);
            return (
              <div key={i} className="rounded-md flex items-center justify-center font-extrabold transition-colors"
                style={{ background: c.bg, color: c.fg, fontSize: v >= 1024 ? '1.1rem' : v >= 128 ? '1.4rem' : '1.7rem' }}>
                {v > 0 ? v : ''}
              </div>
            );
          })}
        </div>
        {over && (
          <div className="absolute inset-0 flex flex-col items-center justify-center bg-[#eee4da]/85 rounded-xl gap-2">
            <p className="text-2xl font-extrabold text-[#776e65]">게임 오버</p>
            <p className="text-[#776e65] font-bold">{score}점 {myRank != null && `· ${myRank}위`}</p>
            <button onClick={reset} className="mt-1 px-5 py-2 rounded-lg bg-[#8f7a66] text-white font-bold">다시 하기</button>
          </div>
        )}
        {won && !over && (
          <div className="absolute top-2 left-2 right-2 rounded-lg bg-[#edc22e]/90 text-white text-center py-1 text-sm font-bold">🎉 2048 달성! 계속 가능</div>
        )}
      </div>

      <p className="text-xs text-slate-400 mt-3 text-center">방향키/WASD 또는 스와이프로 타일을 밀어 같은 숫자를 합치세요.</p>

      <div className="w-full mt-4 space-y-3">
        <div className="flex gap-2">
          <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임"
            className="flex-1 border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-[#8f7a66]" />
          <button onClick={reset} className="px-4 rounded-lg bg-[#8f7a66] text-white font-bold text-sm">새 게임</button>
        </div>
        <div className="rounded-xl border border-gray-200 p-3">
          <p className="text-sm font-bold text-gray-600 mb-2">🏆 랭킹 TOP 10</p>
          {rows.length === 0 ? <p className="text-gray-300 text-sm text-center py-2">아직 기록이 없어요</p> : (
            <div className="space-y-0.5">
              {rows.map((r) => (
                <div key={`${r.rank}-${r.nick}`} className={`flex justify-between text-sm px-1 ${nick.trim() && r.nick === nick.trim() ? 'bg-[#edc22e]/10 rounded' : ''}`}>
                  <span className="font-bold text-gray-700">{r.rank <= 3 ? ['🥇', '🥈', '🥉'][r.rank - 1] : `${r.rank}.`} {r.nick}</span>
                  <span className="text-[#8f7a66] font-bold">{r.score.toLocaleString()}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </main>
  );
}
