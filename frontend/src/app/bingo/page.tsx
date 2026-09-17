'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

type PlayerView = { seat: number; nick: string; ready: boolean; lines: number };
type RoomSummary = { code: string; status: string; playerCount: number; host: string };

type BState = {
  status: 'NOT_STARTED' | 'LOBBY' | 'PLAYING' | 'ENDED';
  serverNow: number; isHost: boolean; joined: boolean; seat: number; nick: string | null;
  size: number; range: number; target: number; mode: 'AUTO' | 'TURN';
  players: PlayerView[]; myBoard: number[]; drawn: number[]; lastDrawn: number;
  nextDrawAt: number; currentTurnSeat: number; myTurn: boolean; turnEndsAt: number;
  myLines: number; winnerSeat: number; winnerNick: string | null;
  playerCount: number; version: number;
};

const CLIENT_ID_KEY = 'bingo_client_id';
const NICK_KEY = 'bingo_nick';
const ROOM_KEY = 'bingo_room';

function getClientId(): string {
  if (typeof window === 'undefined') return '';
  try {
    let id = localStorage.getItem(CLIENT_ID_KEY) || '';
    if (!id) {
      id = typeof crypto !== 'undefined' && 'randomUUID' in crypto
        ? crypto.randomUUID() : `c_${Date.now()}_${Math.random().toString(36).slice(2)}`;
      localStorage.setItem(CLIENT_ID_KEY, id);
    }
    return id;
  } catch { return `c_${Math.random().toString(36).slice(2)}`; }
}

export default function BingoPage() {
  const [clientId, setClientId] = useState('');
  const [st, setSt] = useState<BState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [rooms, setRooms] = useState<RoomSummary[]>([]);
  const roomRef = useRef<string | null>(null);

  const [nick, setNick] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [size, setSize] = useState(5);
  const [target, setTarget] = useState(3);
  const [mode, setMode] = useState<'AUTO' | 'TURN'>('AUTO');
  const [showAdmin, setShowAdmin] = useState(false);
  const [adminInput, setAdminInput] = useState('');

  const [board, setBoard] = useState<number[]>([]);   // 셋업용(0=빈칸)
  const [editing, setEditing] = useState(false);
  const [, setNowTick] = useState(0);
  const cidRef = useRef('');
  const clockOffset = useRef(0);

  const changeRoom = useCallback((code: string | null) => {
    roomRef.current = code; setRoomCode(code);
    try { if (code) localStorage.setItem(ROOM_KEY, code); else localStorage.removeItem(ROOM_KEY); } catch {}
  }, []);

  const poll = useCallback(async () => {
    const code = roomRef.current; const c = cidRef.current;
    if (!code) { try { setRooms(await api<RoomSummary[]>('/api/v1/bingo/rooms')); } catch {} return; }
    try {
      const res = await api<BState>(`/api/v1/bingo/me?roomCode=${code}&clientId=${encodeURIComponent(c)}`);
      if (res.status === 'NOT_STARTED') { changeRoom(null); setSt(null); }
      else { clockOffset.current = res.serverNow - Date.now(); setSt(res); }
    } catch { /* ignore */ }
  }, [changeRoom]);

  useEffect(() => {
    const id = getClientId(); setClientId(id); cidRef.current = id;
    try {
      setNick(localStorage.getItem(NICK_KEY) || '');
      const saved = localStorage.getItem(ROOM_KEY);
      if (saved) { roomRef.current = saved; setRoomCode(saved); }
    } catch {}
    poll();
    const t = setInterval(poll, 1200);
    const tk = setInterval(() => setNowTick((n) => n + 1), 500);
    return () => { clearInterval(t); clearInterval(tk); };
  }, [poll]);

  // 방 들어오면 셋업 보드 초기화(크기에 맞춰)
  useEffect(() => {
    if (st?.status === 'LOBBY' && st.myBoard.length === 0 && board.length !== st.size * st.size) {
      setBoard(new Array(st.size * st.size).fill(0));
    }
  }, [st?.status, st?.size, st?.myBoard, board.length]);

  const cid = () => encodeURIComponent(clientId);
  const rp = () => `roomCode=${roomCode}&clientId=${cid()}`;
  const saveNick = (n: string) => { try { localStorage.setItem(NICK_KEY, n); } catch {} };

  const post = useCallback(async (path: string, body?: unknown) => {
    setBusy(true); setError(null);
    try { return await api<BState>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined }); }
    catch (e) { setError(e instanceof Error ? e.message : '오류가 발생했습니다'); return null; }
    finally { setBusy(false); }
  }, []);

  const handleCreate = async () => {
    const n = nick.trim(); if (!n) return setError('닉네임을 입력하세요');
    saveNick(n); setBusy(true); setError(null);
    try {
      const res = await api<{ roomCode: string; state: BState }>(
        `/api/v1/bingo/new?clientId=${cid()}`, { method: 'POST', body: JSON.stringify({ nick: n, size, target, mode }) });
      changeRoom(res.roomCode); setSt(res.state); setShowCreate(false); setBoard(new Array(size * size).fill(0));
    } catch (e) { setError(e instanceof Error ? e.message : '방 생성 실패'); } finally { setBusy(false); }
  };
  const handleJoin = async () => {
    const n = nick.trim(); if (!n) return setError('닉네임을 입력하세요');
    saveNick(n); const r = await post(`/api/v1/bingo/join?${rp()}`, { nick: n }); if (r) setSt(r);
  };
  const handleStart = async () => { const r = await post(`/api/v1/bingo/start?${rp()}`); if (r) setSt(r); };
  const handleCall = async (n: number) => { const r = await post(`/api/v1/bingo/call?${rp()}&number=${n}`); if (r) setSt(r); };
  const submitBoard = async (nums: number[]) => { const r = await post(`/api/v1/bingo/set-board?${rp()}`, { numbers: nums }); if (r) { setSt(r); setEditing(false); } };
  const handleEditBoard = () => { if (st) { setBoard(st.myBoard.length ? [...st.myBoard] : new Array(st.size * st.size).fill(0)); setEditing(true); } };

  const handleAdminReset = async () => {
    const code = adminInput.trim(); if (!code) return;
    const res = await post(`/api/v1/bingo/reset?code=${encodeURIComponent(code)}`);
    if (res) { setShowAdmin(false); setAdminInput(''); changeRoom(null); setSt(null); }
  };
  const handleCloseRoom = async (rc: string) => {
    const code = adminInput.trim();
    if (!code) { setError('관리자 코드를 먼저 입력하세요'); return; }
    if (!confirm(`${rc} 방을 삭제할까요?`)) return;
    try {
      await api<boolean>(`/api/v1/bingo/close-room?code=${encodeURIComponent(code)}&roomCode=${rc}`, { method: 'POST' });
      setRooms((cur) => cur.filter((r) => r.code !== rc));
    } catch (e) { setError(e instanceof Error ? e.message : '방 삭제 실패'); }
  };

  // ----- 셋업 보드 조작 -----
  const usedSet = new Set(board.filter((x) => x > 0));
  const placeNumber = (n: number) => {
    if (usedSet.has(n)) { setBoard((b) => b.map((x) => (x === n ? 0 : x))); return; } // 이미 있으면 제거
    const idx = board.indexOf(0);
    if (idx < 0) return;
    setBoard((b) => b.map((x, i) => (i === idx ? n : x)));
  };
  const clearCell = (i: number) => setBoard((b) => b.map((x, j) => (j === i ? 0 : x)));
  const randomFill = () => {
    if (!st) return;
    const pool: number[] = [];
    for (let i = 1; i <= st.range; i++) pool.push(i);
    for (let i = pool.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [pool[i], pool[j]] = [pool[j], pool[i]]; }
    setBoard(pool.slice(0, st.size * st.size));
  };
  const boardFull = board.length > 0 && board.every((x) => x > 0);

  const adminFooter = (
    <div className="w-full mt-6 pt-4 border-t border-gray-100 flex flex-col items-center gap-2">
      {showAdmin ? (
        <>
          <div className="flex items-center gap-2">
            <input type="password" value={adminInput} onChange={(e) => setAdminInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleAdminReset()} placeholder="관리자 코드"
              className="border border-gray-300 rounded-lg px-3 py-2 text-sm w-32 focus:outline-none focus:border-red-400" />
            <button onClick={handleAdminReset} className="bg-gray-700 text-white text-sm px-3 py-2 rounded-lg">전체 초기화</button>
          </div>
          <p className="text-[11px] text-gray-400">코드 입력 후 방 목록의 🗑 로 개별 방을 삭제할 수 있어요</p>
        </>
      ) : <button onClick={() => setShowAdmin(true)} className="text-xs text-gray-300 hover:text-gray-500">🔒 관리자</button>}
    </div>
  );

  // ----- 방 목록 -----
  if (!roomCode) {
    return (
      <main className="min-h-screen flex flex-col items-center p-6 max-w-lg mx-auto w-full">
        <h1 className="text-2xl font-bold mb-6 self-start">🔢 빙고</h1>
        {showCreate ? (
          <div className="w-full space-y-4">
            <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="내 닉네임"
              className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit" />
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">판 크기</p>
              <div className="grid grid-cols-3 gap-2">
                {[[3, '3×3', '1~15'], [4, '4×4', '1~25'], [5, '5×5', '1~50']].map(([s, label, r]) => (
                  <button key={s as number} onClick={() => setSize(s as number)} className={`py-3 rounded-lg border-2 text-sm font-bold ${size === s ? 'border-hit bg-hit/5 text-hit' : 'border-gray-200 text-gray-500'}`}>
                    {label}<br /><span className="text-[11px] font-normal">{r}</span>
                  </button>
                ))}
              </div>
            </div>
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">승리 줄 수 (빙고)</p>
              <div className="grid grid-cols-5 gap-1">
                {[1, 2, 3, 4, 5].map((t) => (
                  <button key={t} onClick={() => setTarget(t)} className={`py-2 rounded-lg border text-sm ${target === t ? 'border-hit bg-hit/5 text-hit font-bold' : 'border-gray-200 text-gray-500'}`}>{t}줄</button>
                ))}
              </div>
            </div>
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">진행 방식</p>
              <div className="grid grid-cols-2 gap-2">
                {([['AUTO', '🤖 자동 진행', '봇이 숫자를 자동으로 뽑아요'], ['TURN', '🔄 번갈아 지목', '참가자가 돌아가며 숫자를 불러요']] as const).map(([m, label, desc]) => (
                  <button key={m} onClick={() => setMode(m)} className={`py-2 px-2 rounded-lg border-2 text-sm text-center ${mode === m ? 'border-hit bg-hit/5 text-hit font-bold' : 'border-gray-200 text-gray-500'}`}>
                    {label}<br /><span className="text-[10px] font-normal">{desc}</span>
                  </button>
                ))}
              </div>
            </div>
            <div className="flex gap-2">
              <button onClick={() => setShowCreate(false)} className="flex-1 border border-gray-300 py-3 rounded-lg">취소</button>
              <button onClick={handleCreate} disabled={busy} className="flex-[2] bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">방 만들기</button>
            </div>
          </div>
        ) : (
          <div className="w-full space-y-4">
            <button onClick={() => { setShowCreate(true); setError(null); }} className="w-full bg-hit text-white font-bold py-3 rounded-lg hover:opacity-90">+ 새 방 만들기</button>
            <p className="text-sm font-bold text-gray-600">방 목록</p>
            {rooms.length === 0 && <p className="text-gray-400 text-sm text-center py-6">아직 만들어진 방이 없어요.</p>}
            {rooms.map((r) => {
              const badge = r.status === 'WAITING' ? '모집중' : r.status === 'PLAYING' ? '진행중' : '종료';
              const cls = r.status === 'WAITING' ? 'bg-green-100 text-green-700' : r.status === 'PLAYING' ? 'bg-yellow-100 text-yellow-700' : 'bg-gray-100 text-gray-400';
              return (
                <div key={r.code} className="flex items-center justify-between border border-gray-200 rounded-lg px-4 py-3">
                  <div><span className="font-bold tracking-wider">{r.code}</span><span className="text-xs text-gray-400 ml-2">{r.host} · {r.playerCount}명</span></div>
                  <div className="flex items-center gap-2">
                    <span className={`text-xs px-2 py-1 rounded-full ${cls}`}>{badge}</span>
                    {r.status === 'ENDED'
                      ? <span className="text-sm text-gray-300">종료</span>
                      : <button onClick={() => { changeRoom(r.code); setSt(null); }} className="text-sm font-bold text-hit">{r.status === 'WAITING' ? '참가' : '이어하기'}</button>}
                    {showAdmin && <button onClick={() => handleCloseRoom(r.code)} title="방 삭제" className="text-sm text-red-500 hover:text-red-600">🗑</button>}
                  </div>
                </div>
              );
            })}
          </div>
        )}
        {error && <p className="text-red-500 text-sm mt-4 text-center">{error}</p>}
        {adminFooter}
      <RoomChat game="bingo" roomCode="lobby" clientId={clientId} nick={nick} />
      </main>
    );
  }

  if (!st) return <main className="min-h-screen flex items-center justify-center"><p className="text-gray-400">불러오는 중...</p></main>;

  const drawnSet = new Set(st.drawn);
  const remain = st.status === 'PLAYING' && st.mode === 'AUTO' && st.nextDrawAt > 0 ? Math.max(0, Math.ceil((st.nextDrawAt - (Date.now() + clockOffset.current)) / 1000)) : null;
  const turnRemain = st.status === 'PLAYING' && st.mode === 'TURN' && st.turnEndsAt > 0 ? Math.max(0, Math.ceil((st.turnEndsAt - (Date.now() + clockOffset.current)) / 1000)) : null;
  const turnNick = st.currentTurnSeat > 0 ? (st.players.find((p) => p.seat === st.currentTurnSeat)?.nick ?? '') : '';
  const cellClass = st.size === 3 ? 'text-2xl' : st.size === 4 ? 'text-xl' : 'text-base';

  return (
    <main className="min-h-screen flex flex-col items-center p-4 max-w-lg mx-auto w-full">
      <div className="w-full flex items-center justify-between gap-2 flex-wrap mb-3">
        <div className="flex items-center gap-2 min-w-0">
          <h1 className="text-lg font-bold shrink-0">🔢 빙고</h1>
          <span className="text-xs bg-gray-100 rounded px-2 py-1 tracking-wider font-bold shrink-0">{roomCode}</span>
          <button onClick={() => { api(`/api/v1/bingo/leave?roomCode=${roomCode}&clientId=${cid()}`, { method: 'POST' }).catch(() => {}); changeRoom(null); setSt(null); }} className="text-xs text-gray-400 underline shrink-0">나가기</button>
        </div>
        <span className="text-xs text-gray-400">{st.size}×{st.size} · {st.target}줄 · {st.mode === 'TURN' ? '번갈아 지목' : '자동'}</span>
      </div>

      {/* 참가자 */}
      <div className="w-full flex flex-wrap gap-2 mb-3 text-xs">
        {st.players.map((p) => (
          <span key={p.seat} className={`rounded-full px-3 py-1 ${p.seat === st.seat ? 'bg-hit text-white font-bold' : 'bg-gray-100 text-gray-600'}`}>
            {p.nick}{p.seat === st.seat && '(나)'} {st.status === 'LOBBY' ? (p.ready ? '✅' : '…') : `· ${p.lines}줄`}
          </span>
        ))}
      </div>

      {/* 대기방: 판 셋업 */}
      {st.status === 'LOBBY' && (
        <div className="w-full space-y-3">
          {!st.joined ? (
            <div className="flex gap-2">
              <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임"
                className="flex-1 border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit" />
              <button onClick={handleJoin} disabled={busy} className="bg-hit text-white font-bold px-5 rounded-lg disabled:opacity-50">참가</button>
            </div>
          ) : st.myBoard.length > 0 && !editing ? (
            <div className="space-y-3">
              <p className="text-center text-sm text-green-600 font-bold">✅ 판 완성! 다른 사람을 기다리는 중...</p>
              <BoardGrid nums={st.myBoard} size={st.size} cellClass={cellClass} />
              <button onClick={handleEditBoard} className="w-full border border-gray-300 py-2 rounded-lg text-sm text-gray-600">판 다시 짜기</button>
              {st.isHost && (
                <button onClick={handleStart} disabled={busy || st.playerCount < 2 || st.players.some((p) => !p.ready)}
                  className="w-full bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">
                  {st.playerCount < 2 ? '최소 2명 필요' : st.players.some((p) => !p.ready) ? '모두 판을 완성해야 시작' : '게임 시작'}
                </button>
              )}
            </div>
          ) : (
            <div className="space-y-3">
              <p className="text-center text-sm font-bold text-gray-700">판을 채우세요 (1~{st.range}에서 {st.size * st.size}칸)</p>
              <div className="grid gap-1 mx-auto" style={{ gridTemplateColumns: `repeat(${st.size}, minmax(0,1fr))`, maxWidth: st.size * 64 }}>
                {board.map((v, i) => (
                  <button key={i} onClick={() => v > 0 && clearCell(i)}
                    className={`aspect-square rounded-lg border-2 font-bold ${cellClass} ${v > 0 ? 'border-hit bg-hit/10 text-hit' : 'border-dashed border-gray-300 text-gray-300'}`}>
                    {v > 0 ? v : ''}
                  </button>
                ))}
              </div>
              <div className="flex gap-2">
                <button onClick={randomFill} className="flex-1 border border-hit text-hit font-bold py-2 rounded-lg">🎲 랜덤 채우기</button>
                <button onClick={() => setBoard(new Array(st.size * st.size).fill(0))} className="flex-1 border border-gray-300 py-2 rounded-lg text-gray-600">초기화</button>
              </div>
              <div>
                <p className="text-xs text-gray-400 mb-1">직접 고르기 (탭하면 순서대로 채워짐)</p>
                <div className="flex flex-wrap gap-1">
                  {Array.from({ length: st.range }, (_, k) => k + 1).map((n) => (
                    <button key={n} onClick={() => placeNumber(n)}
                      className={`w-8 h-8 rounded text-xs font-bold ${usedSet.has(n) ? 'bg-hit text-white' : 'bg-gray-100 text-gray-600'}`}>{n}</button>
                  ))}
                </div>
              </div>
              <button onClick={() => submitBoard(board)} disabled={busy || !boardFull} className="w-full bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">
                {boardFull ? '판 완성!' : `${board.filter((x) => x > 0).length}/${st.size * st.size} 채움`}
              </button>
            </div>
          )}
        </div>
      )}

      {/* 진행/종료 */}
      {(st.status === 'PLAYING' || st.status === 'ENDED') && (
        <div className="w-full space-y-3">
          {st.status === 'PLAYING' && (
            <div className="text-center">
              {st.mode === 'TURN' ? (
                <>
                  <p className={`text-sm font-bold ${st.myTurn ? 'text-hit' : 'text-gray-500'}`}>
                    {st.myTurn ? '🎯 내 차례! 내 판에서 숫자를 눌러 지목' : `⏳ ${turnNick}님 차례`}
                    {turnRemain != null && <span className="ml-1 text-gray-400 font-normal">({turnRemain}s)</span>}
                  </p>
                  <p className="text-xs text-gray-400 mt-1">방금 지목된 숫자</p>
                  <p className="text-4xl font-extrabold text-hit">{st.lastDrawn > 0 ? st.lastDrawn : '—'}</p>
                </>
              ) : (
                <>
                  <p className="text-xs text-gray-400">방금 뽑힌 숫자{remain != null && ` · 다음 ${remain}s`}</p>
                  <p className="text-5xl font-extrabold text-hit">{st.lastDrawn > 0 ? st.lastDrawn : '—'}</p>
                </>
              )}
              <p className="text-sm font-bold text-gray-600 mt-1">내 빙고: {st.myLines}줄 / 목표 {st.target}줄</p>
            </div>
          )}
          {st.status === 'ENDED' && (
            <p className="text-center text-2xl font-extrabold text-hit">🎉 {st.winnerNick} 승리!</p>
          )}

          <BoardGrid nums={st.myBoard} size={st.size} cellClass={cellClass} marked={drawnSet}
            onCell={st.status === 'PLAYING' && st.mode === 'TURN' && st.myTurn && !busy ? handleCall : undefined} />

          <div className="rounded-xl border border-gray-200 p-2">
            <p className="text-xs text-gray-400 mb-1">뽑힌 숫자 ({st.drawn.length}/{st.range})</p>
            <div className="flex flex-wrap gap-1">
              {st.drawn.map((n, i) => (
                <span key={i} className={`w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold ${i === st.drawn.length - 1 ? 'bg-hit text-white' : 'bg-gray-100 text-gray-500'}`}>{n}</span>
              ))}
            </div>
          </div>

          {st.status === 'ENDED' && (
            <button onClick={() => { changeRoom(null); setSt(null); setShowCreate(true); }} className="w-full bg-hit text-white font-bold py-3 rounded-lg">🔄 새 방 만들기</button>
          )}
          {error && <p className="text-red-500 text-sm text-center">{error}</p>}
        </div>
      )}

      {adminFooter}
      <RoomChat game="bingo" roomCode={roomCode} clientId={clientId} nick={nick} />
    </main>
  );
}

function BoardGrid({ nums, size, cellClass, marked, onCell }: { nums: number[]; size: number; cellClass: string; marked?: Set<number>; onCell?: (n: number) => void }) {
  return (
    <div className="grid gap-1 mx-auto" style={{ gridTemplateColumns: `repeat(${size}, minmax(0,1fr))`, maxWidth: size * 64 }}>
      {nums.map((v, i) => {
        const hit = marked?.has(v);
        if (onCell && !hit) {
          return (
            <button key={i} onClick={() => onCell(v)}
              className={`aspect-square rounded-lg border-2 flex items-center justify-center font-bold transition active:scale-95 ${cellClass} border-hit text-hit hover:bg-hit/10`}>
              {v}
            </button>
          );
        }
        return (
          <div key={i} className={`aspect-square rounded-lg border-2 flex items-center justify-center font-bold ${cellClass} ${hit ? 'border-hit bg-hit text-white' : 'border-gray-200 text-gray-700'}`}>
            {v}
          </div>
        );
      })}
    </div>
  );
}
