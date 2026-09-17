'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

type PlayerView = { seat: number; nick: string; ai: boolean; color: number };
type OmokState = {
  status: 'NULL_ROOM' | 'LOBBY' | 'PLAYING' | 'ENDED'; serverNow: number; isHost: boolean; joined: boolean;
  seat: number; nick: string | null; myColor: number; hostColor: number; rule: 'FREE' | 'RENJU';
  players: PlayerView[]; board: number[]; currentColor: number; myTurn: boolean; lastMove: number;
  winner: number; winLine: number[]; forbidden: number[]; lastAction: string | null; playerCount: number; version: number;
};
type RoomSummary = { code: string; status: string; playerCount: number; host: string };

const N = 15, CELL = 36, PAD = 26, BOARD = PAD * 2 + CELL * (N - 1);
const STARS = [[3, 3], [3, 11], [11, 3], [11, 11], [7, 7]];
const CID_KEY = 'omok_client_id', NICK_KEY = 'omok_nick', ROOM_KEY = 'omok_room';
const colName = (c: number) => (c === 1 ? '흑' : c === 2 ? '백' : '');

function getClientId(): string {
  if (typeof window === 'undefined') return '';
  try {
    let id = localStorage.getItem(CID_KEY) || '';
    if (!id) { id = crypto?.randomUUID?.() ?? `c_${Date.now()}_${Math.random().toString(36).slice(2)}`; localStorage.setItem(CID_KEY, id); }
    return id;
  } catch { return `c_${Math.random().toString(36).slice(2)}`; }
}

function OmokBoard({ board, lastMove, winLine, forbidden, myTurn, myColor, onPlace }: {
  board: number[]; lastMove: number; winLine: number[]; forbidden: number[]; myTurn: boolean; myColor: number; onPlace: (cell: number) => void;
}) {
  const ref = useRef<HTMLCanvasElement>(null);
  const [hover, setHover] = useState(-1);
  const forbSet = new Set(forbidden);
  const winSet = new Set(winLine);

  useEffect(() => {
    const cv = ref.current; if (!cv) return;
    const dpr = Math.min(window.devicePixelRatio || 1, 2.5);
    cv.width = BOARD * dpr; cv.height = BOARD * dpr;
    const ctx = cv.getContext('2d'); if (!ctx) return;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const px = (i: number) => PAD + i * CELL;

    // 나뭇결 배경
    const g = ctx.createLinearGradient(0, 0, BOARD, BOARD);
    g.addColorStop(0, '#e8c079'); g.addColorStop(1, '#d8a94f');
    ctx.fillStyle = g; ctx.fillRect(0, 0, BOARD, BOARD);
    ctx.strokeStyle = 'rgba(120,80,30,0.25)'; ctx.lineWidth = 1;
    for (let y = 0; y < BOARD; y += 7) { ctx.beginPath(); ctx.moveTo(0, y + Math.sin(y) * 0.5); ctx.lineTo(BOARD, y); ctx.stroke(); }

    // 격자
    ctx.strokeStyle = '#5b3d1a'; ctx.lineWidth = 1;
    for (let i = 0; i < N; i++) {
      ctx.beginPath(); ctx.moveTo(px(0), px(i)); ctx.lineTo(px(N - 1), px(i)); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(px(i), px(0)); ctx.lineTo(px(i), px(N - 1)); ctx.stroke();
    }
    ctx.lineWidth = 2; ctx.strokeRect(px(0), px(0), CELL * (N - 1), CELL * (N - 1));
    // 화점
    ctx.fillStyle = '#4a2f12';
    for (const [r, c] of STARS) { ctx.beginPath(); ctx.arc(px(c), px(r), 3.5, 0, Math.PI * 2); ctx.fill(); }

    // 금수 표시
    if (myTurn && myColor === 1) {
      ctx.strokeStyle = 'rgba(220,38,38,0.8)'; ctx.lineWidth = 2.5;
      for (const f of forbidden) {
        const r = Math.floor(f / N), c = f % N, x = px(c), y = px(r), s = 6;
        ctx.beginPath(); ctx.moveTo(x - s, y - s); ctx.lineTo(x + s, y + s); ctx.moveTo(x + s, y - s); ctx.lineTo(x - s, y + s); ctx.stroke();
      }
    }

    // 돌
    const drawStone = (r: number, c: number, color: number, ghost = false) => {
      const x = px(c), y = px(r), rad = CELL * 0.44;
      ctx.save();
      if (!ghost) { ctx.shadowColor = 'rgba(0,0,0,0.35)'; ctx.shadowBlur = 4; ctx.shadowOffsetY = 2; }
      const grd = ctx.createRadialGradient(x - rad * 0.35, y - rad * 0.4, rad * 0.15, x, y, rad);
      if (color === 1) { grd.addColorStop(0, '#6b6b6b'); grd.addColorStop(1, '#0a0a0a'); }
      else { grd.addColorStop(0, '#ffffff'); grd.addColorStop(1, '#c8ccd2'); }
      ctx.globalAlpha = ghost ? 0.4 : 1;
      ctx.fillStyle = grd; ctx.beginPath(); ctx.arc(x, y, rad, 0, Math.PI * 2); ctx.fill();
      ctx.restore();
      if (color === 2 && !ghost) { ctx.strokeStyle = 'rgba(150,155,165,0.6)'; ctx.lineWidth = 1; ctx.beginPath(); ctx.arc(x, y, rad, 0, Math.PI * 2); ctx.stroke(); }
    };
    for (let i = 0; i < board.length; i++) if (board[i] !== 0) drawStone(Math.floor(i / N), i % N, board[i]);

    // 호버 미리보기
    if (myTurn && hover >= 0 && board[hover] === 0 && !forbSet.has(hover)) drawStone(Math.floor(hover / N), hover % N, myColor, true);

    // 마지막 착수
    if (lastMove >= 0 && board[lastMove] !== 0) {
      const r = Math.floor(lastMove / N), c = lastMove % N;
      ctx.strokeStyle = board[lastMove] === 1 ? '#fde047' : '#ef4444'; ctx.lineWidth = 2.5;
      ctx.beginPath(); ctx.arc(px(c), px(r), CELL * 0.2, 0, Math.PI * 2); ctx.stroke();
    }

    // 승리 라인
    if (winLine.length >= 2) {
      const a = winLine[0], b = winLine[winLine.length - 1];
      ctx.strokeStyle = 'rgba(250,204,21,0.85)'; ctx.lineWidth = 7; ctx.lineCap = 'round';
      ctx.shadowColor = 'rgba(250,204,21,0.8)'; ctx.shadowBlur = 12;
      ctx.beginPath(); ctx.moveTo(px(a % N), px(Math.floor(a / N))); ctx.lineTo(px(b % N), px(Math.floor(b / N))); ctx.stroke();
      ctx.shadowBlur = 0;
    }
  }, [board, lastMove, winLine, forbidden, hover, myTurn, myColor, forbSet, winSet]);

  const cellAt = (e: React.MouseEvent | React.TouchEvent) => {
    const cv = ref.current; if (!cv) return -1;
    const rect = cv.getBoundingClientRect();
    const p = 'touches' in e ? e.changedTouches[0] : (e as React.MouseEvent);
    const lx = (p.clientX - rect.left) / rect.width * BOARD, ly = (p.clientY - rect.top) / rect.height * BOARD;
    const c = Math.round((lx - PAD) / CELL), r = Math.round((ly - PAD) / CELL);
    if (r < 0 || r >= N || c < 0 || c >= N) return -1;
    return r * N + c;
  };

  return (
    <canvas ref={ref} className="w-full max-w-[440px] rounded-lg shadow-lg touch-none" style={{ aspectRatio: '1 / 1' }}
      onMouseMove={(e) => setHover(cellAt(e))} onMouseLeave={() => setHover(-1)}
      onClick={(e) => { const cell = cellAt(e); if (cell >= 0 && myTurn && board[cell] === 0 && !forbSet.has(cell)) onPlace(cell); }} />
  );
}

export default function OmokPage() {
  const [clientId, setClientId] = useState('');
  const [st, setSt] = useState<OmokState | null>(null);
  const [rooms, setRooms] = useState<RoomSummary[]>([]);
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const roomRef = useRef<string | null>(null);
  const cidRef = useRef('');

  const [nick, setNick] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [hostColor, setHostColor] = useState<'BLACK' | 'WHITE'>('BLACK');
  const [rule, setRule] = useState<'FREE' | 'RENJU'>('FREE');

  const cid = () => encodeURIComponent(clientId);
  const rp = () => `roomCode=${roomCode}&clientId=${cid()}`;
  const changeRoom = useCallback((code: string | null) => { roomRef.current = code; setRoomCode(code); try { if (code) localStorage.setItem(ROOM_KEY, code); else localStorage.removeItem(ROOM_KEY); } catch {} }, []);

  const poll = useCallback(async () => {
    const code = roomRef.current, c = cidRef.current;
    if (!code) { try { setRooms(await api<RoomSummary[]>('/api/v1/omok/rooms')); } catch {} return; }
    try { const res = await api<OmokState>(`/api/v1/omok/me?roomCode=${code}&clientId=${encodeURIComponent(c)}`); if (res.status === 'NULL_ROOM') { changeRoom(null); setSt(null); } else setSt(res); } catch {}
  }, [changeRoom]);

  useEffect(() => {
    const id = getClientId(); setClientId(id); cidRef.current = id;
    try { setNick(localStorage.getItem(NICK_KEY) || ''); const s = localStorage.getItem(ROOM_KEY); if (s) { roomRef.current = s; setRoomCode(s); } } catch {}
    poll(); const t = setInterval(poll, 1000); return () => clearInterval(t);
  }, [poll]);

  const post = useCallback(async (path: string, body?: unknown) => {
    setBusy(true); setError(null);
    try { const res = await api<OmokState>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined }); setSt(res); return res; }
    catch (e) { setError(e instanceof Error ? e.message : '오류'); return null; } finally { setBusy(false); }
  }, []);
  const saveNick = (n: string) => { try { localStorage.setItem(NICK_KEY, n); } catch {} };

  const handleCreate = async () => {
    const n = nick.trim(); if (!n) return setError('닉네임을 입력하세요'); saveNick(n); setBusy(true); setError(null);
    try { const res = await api<{ roomCode: string; state: OmokState }>(`/api/v1/omok/new?clientId=${cid()}`, { method: 'POST', body: JSON.stringify({ nick: n, hostColor, rule }) }); changeRoom(res.roomCode); setSt(res.state); setShowCreate(false); }
    catch (e) { setError(e instanceof Error ? e.message : '방 생성 실패'); } finally { setBusy(false); }
  };
  const handleJoin = async () => { const n = nick.trim(); if (!n) return setError('닉네임을 입력하세요'); saveNick(n); await post(`/api/v1/omok/join?${rp()}`, { nick: n }); };
  const handleAddBot = (level: string) => post(`/api/v1/omok/add-bot?${rp()}&level=${level}`);
  const handleStart = () => post(`/api/v1/omok/start?${rp()}`);
  const handlePlace = (cell: number) => post(`/api/v1/omok/place?${rp()}&cell=${cell}`);
  const handleLeave = () => { api(`/api/v1/omok/leave?${rp()}`, { method: 'POST' }).catch(() => {}); changeRoom(null); setSt(null); };

  // ---------- 방 목록 ----------
  if (!roomCode) {
    return (
      <main className="min-h-screen flex flex-col items-center p-6 max-w-lg mx-auto w-full">
        <h1 className="text-2xl font-bold mb-6 self-start">⚫ 오목</h1>
        {showCreate ? (
          <div className="w-full space-y-4">
            <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="내 닉네임" className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit" />
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">내 돌 색 (흑이 선공)</p>
              <div className="grid grid-cols-2 gap-2">{(['BLACK', 'WHITE'] as const).map((t) => <button key={t} onClick={() => setHostColor(t)} className={`py-3 rounded-lg border-2 text-sm font-bold ${hostColor === t ? 'border-hit bg-hit/5 text-hit' : 'border-gray-200 text-gray-500'}`}>{t === 'BLACK' ? '⚫ 흑 (선공)' : '⚪ 백 (후공)'}</button>)}</div>
            </div>
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">룰</p>
              <div className="grid grid-cols-2 gap-2">
                <button onClick={() => setRule('FREE')} className={`py-3 rounded-lg border-2 text-sm font-bold ${rule === 'FREE' ? 'border-hit bg-hit/5 text-hit' : 'border-gray-200 text-gray-500'}`}>자유룰<span className="block text-[10px] font-normal">금수 없음·단순</span></button>
                <button onClick={() => setRule('RENJU')} className={`py-3 rounded-lg border-2 text-sm font-bold ${rule === 'RENJU' ? 'border-hit bg-hit/5 text-hit' : 'border-gray-200 text-gray-500'}`}>금수룰<span className="block text-[10px] font-normal">흑 3-3·4-4·장목 금지</span></button>
              </div>
            </div>
            <div className="flex gap-2"><button onClick={() => setShowCreate(false)} className="flex-1 border py-3 rounded-lg">취소</button><button onClick={handleCreate} disabled={busy} className="flex-[2] bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">방 만들기</button></div>
          </div>
        ) : (
          <div className="w-full space-y-3">
            <button onClick={() => { setShowCreate(true); setError(null); }} className="w-full bg-hit text-white font-bold py-3 rounded-lg">+ 새 방 만들기</button>
            <p className="text-sm font-bold text-gray-600">방 목록</p>
            {rooms.length === 0 && <p className="text-gray-400 text-sm text-center py-6">아직 만들어진 방이 없어요.</p>}
            {rooms.map((r) => {
              const badge = r.status === 'WAITING' ? '모집중' : r.status === 'PLAYING' ? '진행중' : '종료';
              const cls = r.status === 'WAITING' ? 'bg-green-100 text-green-700' : r.status === 'PLAYING' ? 'bg-yellow-100 text-yellow-700' : 'bg-gray-100 text-gray-400';
              return (
                <div key={r.code} className="flex items-center justify-between border border-gray-200 rounded-lg px-4 py-3">
                  <div><span className="font-bold tracking-wider">{r.code}</span><span className="text-xs text-gray-400 ml-2">{r.host} · {r.playerCount}명</span></div>
                  <div className="flex items-center gap-2"><span className={`text-xs px-2 py-1 rounded-full ${cls}`}>{badge}</span>{r.status === 'ENDED' ? <span className="text-sm text-gray-300">종료</span> : <button onClick={() => { changeRoom(r.code); setSt(null); }} className="text-sm font-bold text-hit">{r.status === 'WAITING' ? '참가' : '관전'}</button>}</div>
                </div>
              );
            })}
          </div>
        )}
        {error && <p className="text-red-500 text-sm mt-4 text-center">{error}</p>}
        <RoomChat game="omok" roomCode="lobby" clientId={clientId} nick={nick} />
      </main>
    );
  }

  if (!st) return <main className="min-h-screen flex items-center justify-center"><p className="text-gray-400">불러오는 중...</p></main>;
  const phase = st.status;

  return (
    <main className="min-h-screen flex flex-col items-center p-4 max-w-lg mx-auto w-full">
      <div className="w-full flex items-center justify-between gap-2 mb-3">
        <div className="flex items-center gap-2 min-w-0">
          <h1 className="text-lg font-bold shrink-0">⚫ 오목</h1>
          <span className="text-xs bg-gray-100 rounded px-2 py-1 tracking-wider font-bold shrink-0">{roomCode}</span>
          <span className="text-[11px] text-gray-400 shrink-0">{st.rule === 'FREE' ? '자유룰' : '금수룰'}</span>
          <button onClick={handleLeave} className="text-xs text-gray-400 underline shrink-0">나가기</button>
        </div>
      </div>

      {/* 참가자 */}
      <div className="w-full flex items-center justify-center gap-3 mb-3 text-sm">
        {st.players.map((p) => {
          const cur = p.color === st.currentColor && phase === 'PLAYING';
          return <span key={p.seat} className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 ${cur ? 'bg-hit text-white font-bold' : 'bg-gray-100 text-gray-600'}`}>
            <span className={`inline-block w-3.5 h-3.5 rounded-full ${p.color === 1 ? 'bg-gray-900' : 'bg-white border border-gray-400'}`} />{p.nick}{p.seat === st.seat && '(나)'}{p.ai && ' 🤖'}</span>;
        })}
      </div>

      {phase === 'LOBBY' && (
        <div className="w-full space-y-3 mt-2">
          <p className="text-center text-xs text-gray-400">내 돌: {colName(st.myColor)} · {st.rule === 'FREE' ? '자유룰' : '금수룰'} · 15×15</p>
          {!st.joined ? (
            <div className="flex gap-2"><input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임" className="flex-1 border border-gray-300 rounded-lg px-3 py-2" /><button onClick={handleJoin} disabled={busy} className="bg-hit text-white font-bold px-5 rounded-lg disabled:opacity-50">참가</button></div>
          ) : st.isHost ? (
            <div className="space-y-2">
              {st.players.length < 2 && (
                <div>
                  <p className="text-xs text-gray-400 mb-1 text-center">🤖 AI 봇과 대전 (난이도)</p>
                  <div className="grid grid-cols-3 gap-2">{([['EASY', '초급', 'bg-emerald-400'], ['NORMAL', '중급', 'bg-amber-400'], ['HARD', '고급', 'bg-rose-400']] as const).map(([lv, label, c]) => <button key={lv} onClick={() => handleAddBot(lv)} disabled={busy} className={`${c} text-white text-sm font-bold py-2 rounded-lg hover:opacity-90 disabled:opacity-40`}>{label}</button>)}</div>
                </div>
              )}
              <button onClick={handleStart} disabled={busy || st.players.length < 2} className="w-full bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">{st.players.length < 2 ? '상대(봇 또는 유저)를 기다리는 중' : '게임 시작'}</button>
            </div>
          ) : <p className="text-center text-gray-500 text-sm">방장이 시작하기를 기다리는 중...</p>}
        </div>
      )}

      {(phase === 'PLAYING' || phase === 'ENDED') && (
        <div className="w-full flex flex-col items-center space-y-3">
          {phase === 'PLAYING' && <p className="text-center text-sm font-bold">{st.myTurn ? <span className="text-hit">🎯 내 차례 ({colName(st.myColor)})</span> : <span className="text-gray-500">⏳ {colName(st.currentColor)} 차례</span>}</p>}
          {phase === 'ENDED' && <p className="text-center text-2xl font-extrabold text-hit">{st.winner === 3 ? '🤝 무승부!' : `🎉 ${colName(st.winner)} 승리!`}</p>}
          <OmokBoard board={st.board} lastMove={st.lastMove} winLine={st.winLine} forbidden={st.forbidden} myTurn={phase === 'PLAYING' && st.myTurn} myColor={st.myColor} onPlace={handlePlace} />
          {st.lastAction && <p className="text-center text-[11px] text-gray-400">{st.lastAction}</p>}
          {st.myTurn && st.myColor === 1 && st.rule === 'RENJU' && st.forbidden.length > 0 && <p className="text-center text-[11px] text-red-400">✕ 표시는 금수 자리예요</p>}
          {phase === 'ENDED' && st.isHost && <button onClick={() => { handleLeave(); setShowCreate(true); }} className="w-full bg-hit text-white font-bold py-3 rounded-lg">🔄 새 방 만들기</button>}
        </div>
      )}

      {error && <p className="text-red-500 text-sm mt-2 text-center">{error}</p>}
      <RoomChat game="omok" roomCode={roomCode} clientId={clientId} nick={nick} />
    </main>
  );
}
