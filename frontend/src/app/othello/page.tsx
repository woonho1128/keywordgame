'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

type Phase = 'NULL_ROOM' | 'LOBBY' | 'PLAYING' | 'ENDED';
type PlayerView = { seat: number; nick: string; ai: boolean; color: number };
type OthState = {
  status: Phase; serverNow: number; isHost: boolean; joined: boolean; seat: number; nick: string | null;
  myColor: number; hostColor: number; players: PlayerView[]; board: number[];
  currentColor: number; myTurn: boolean; validMoves: number[];
  blackCount: number; whiteCount: number; lastMove: number; turnEndsAt: number;
  lastAction: string | null; winner: number; playerCount: number; version: number;
  history: number[];
};
type RoomSummary = { code: string; status: string; playerCount: number; host: string };

const CLIENT_ID_KEY = 'othello_client_id';
const NICK_KEY = 'othello_nick';
const ROOM_KEY = 'othello_room';

function getClientId(): string {
  if (typeof window === 'undefined') return '';
  try {
    let id = localStorage.getItem(CLIENT_ID_KEY) || '';
    if (!id) {
      id = typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `c_${Date.now()}_${Math.random().toString(36).slice(2)}`;
      localStorage.setItem(CLIENT_ID_KEY, id);
    }
    return id;
  } catch { return `c_${Math.random().toString(36).slice(2)}`; }
}

const colName = (c: number) => (c === 1 ? '흑' : c === 2 ? '백' : '');

// 칸 인덱스 → 오델로 표기(a1~h8, 열=a~h, 행=1~8)
const cellToCoord = (i: number) => `${String.fromCharCode(97 + (i % 8))}${Math.floor(i / 8) + 1}`;

// 기보 텍스트 생성. 표준 표기(수순 연속 문자열) + 사람이 읽기 쉬운 번호 목록.
function buildRecord(s: OthState): string {
  const black = s.players.find((p) => p.color === 1);
  const white = s.players.find((p) => p.color === 2);
  const moves = s.history.map(cellToCoord);
  const transcript = moves.join('');
  const numbered = moves.map((m, i) => `${i + 1}.${m}`).join(' ');
  const lines = [
    '[오델로 기보 · gg]',
    `흑(선): ${black?.nick ?? '-'}`,
    `백: ${white?.nick ?? '-'}`,
    s.status === 'ENDED'
      ? `결과: 흑 ${s.blackCount} · 백 ${s.whiteCount} — ${s.winner === 3 ? '무승부' : (s.winner === 1 ? '흑' : '백') + ' 승'}`
      : `진행 중 (흑 ${s.blackCount} · 백 ${s.whiteCount})`,
    '',
    `수순: ${transcript}`,
    '',
    numbered,
  ];
  return lines.join('\n');
}

function Disc({ color }: { color: number }) {
  if (color === 0) return null;
  const bg = color === 1 ? 'radial-gradient(circle at 35% 30%, #4b5563, #111827)' : 'radial-gradient(circle at 35% 30%, #ffffff, #cbd5e1)';
  const border = color === 1 ? '#000' : '#94a3b8';
  return <span className="block w-full h-full rounded-full shadow" style={{ background: bg, border: `1px solid ${border}` }} />;
}

export default function OthelloPage() {
  const [clientId, setClientId] = useState('');
  const [st, setSt] = useState<OthState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [rooms, setRooms] = useState<RoomSummary[]>([]);
  const roomRef = useRef<string | null>(null);

  const [nick, setNick] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [hostColor, setHostColor] = useState<'BLACK' | 'WHITE'>('BLACK');
  const [turnSec, setTurnSec] = useState(60);
  const [remaining, setRemaining] = useState(0);
  const [showAdmin, setShowAdmin] = useState(false);
  const [adminInput, setAdminInput] = useState('');
  const [copied, setCopied] = useState(false);
  const [showRecord, setShowRecord] = useState(false);

  const cidRef = useRef('');
  const offsetRef = useRef(0);
  const endsAtRef = useRef(0);

  const cid = () => encodeURIComponent(clientId);
  const rp = () => `roomCode=${roomCode}&clientId=${cid()}`;

  const changeRoom = useCallback((code: string | null) => {
    roomRef.current = code; setRoomCode(code);
    try { if (code) localStorage.setItem(ROOM_KEY, code); else localStorage.removeItem(ROOM_KEY); } catch {}
  }, []);

  const poll = useCallback(async () => {
    const code = roomRef.current; const c = cidRef.current;
    if (!code) { try { setRooms(await api<RoomSummary[]>('/api/v1/othello/rooms')); } catch {} return; }
    try {
      const res = await api<OthState>(`/api/v1/othello/me?roomCode=${code}&clientId=${encodeURIComponent(c)}`);
      if (res.status === 'NULL_ROOM') { changeRoom(null); setSt(null); }
      else { offsetRef.current = res.serverNow - Date.now(); endsAtRef.current = res.turnEndsAt; setSt(res); }
    } catch {}
  }, [changeRoom]);

  useEffect(() => {
    const id = getClientId(); setClientId(id); cidRef.current = id;
    try { setNick(localStorage.getItem(NICK_KEY) || ''); const saved = localStorage.getItem(ROOM_KEY); if (saved) { roomRef.current = saved; setRoomCode(saved); } } catch {}
    poll();
    const t = setInterval(poll, 1000);
    const tk = setInterval(() => {
      if (endsAtRef.current > 0) setRemaining(Math.max(0, Math.ceil((endsAtRef.current - (Date.now() + offsetRef.current)) / 1000)));
      else setRemaining(0);
    }, 250);
    return () => { clearInterval(t); clearInterval(tk); };
  }, [poll]);

  const post = useCallback(async (path: string, body?: unknown) => {
    setBusy(true); setError(null);
    try { const res = await api<OthState>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined }); setSt(res); endsAtRef.current = res.turnEndsAt; offsetRef.current = res.serverNow - Date.now(); return res; }
    catch (e) { setError(e instanceof Error ? e.message : '오류가 발생했습니다'); return null; }
    finally { setBusy(false); }
  }, []);

  const saveNick = (n: string) => { try { localStorage.setItem(NICK_KEY, n); } catch {} };

  const handleCreate = async () => {
    const n = nick.trim(); if (!n) return setError('닉네임을 입력하세요');
    saveNick(n); setBusy(true); setError(null);
    try {
      const res = await api<{ roomCode: string; state: OthState }>(`/api/v1/othello/new?clientId=${cid()}`,
        { method: 'POST', body: JSON.stringify({ nick: n, hostColor, turnSec }) });
      changeRoom(res.roomCode); setSt(res.state); setShowCreate(false);
    } catch (e) { setError(e instanceof Error ? e.message : '방 생성 실패'); } finally { setBusy(false); }
  };
  const handleJoin = async () => { const n = nick.trim(); if (!n) return setError('닉네임을 입력하세요'); saveNick(n); await post(`/api/v1/othello/join?${rp()}`, { nick: n }); };
  const handleAddBot = (level: string) => post(`/api/v1/othello/add-bot?${rp()}&level=${level}`);
  const handleStart = () => post(`/api/v1/othello/start?${rp()}`);
  const handlePlace = (cell: number) => post(`/api/v1/othello/place?${rp()}&cell=${cell}`);
  const handleLeave = () => { api(`/api/v1/othello/leave?${rp()}`, { method: 'POST' }).catch(() => {}); changeRoom(null); setSt(null); };
  const copyRecord = async () => {
    if (!st) return;
    const text = buildRecord(st);
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true); setTimeout(() => setCopied(false), 1500);
    } catch { setShowRecord(true); } // 클립보드 불가 시 텍스트를 펼쳐 직접 복사하도록
  };
  const handleAdminReset = async () => { const code = adminInput.trim(); if (!code) return; const res = await post(`/api/v1/othello/reset?code=${encodeURIComponent(code)}`); if (res) { setShowAdmin(false); setAdminInput(''); changeRoom(null); setSt(null); } };
  const handleCloseRoom = async (rc: string) => { const code = adminInput.trim(); if (!code) { setError('관리자 코드를 먼저 입력하세요'); return; } if (!confirm(`${rc} 방을 삭제할까요?`)) return; try { await api<boolean>(`/api/v1/othello/close-room?code=${encodeURIComponent(code)}&roomCode=${rc}`, { method: 'POST' }); setRooms((cur) => cur.filter((r) => r.code !== rc)); } catch (e) { setError(e instanceof Error ? e.message : '방 삭제 실패'); } };

  // ---------- 방 목록 ----------
  if (!roomCode) {
    return (
      <main className="min-h-screen flex flex-col items-center p-6 max-w-lg mx-auto w-full">
        <h1 className="text-2xl font-bold mb-6 self-start">⚫⚪ 오델로</h1>
        {showCreate ? (
          <div className="w-full space-y-4">
            <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="내 닉네임" className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit" />
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">내 돌 색 (흑이 선공)</p>
              <div className="grid grid-cols-2 gap-2">
                {(['BLACK', 'WHITE'] as const).map((t) => (
                  <button key={t} onClick={() => setHostColor(t)} className={`py-3 rounded-lg border-2 text-sm font-bold ${hostColor === t ? 'border-hit bg-hit/5 text-hit' : 'border-gray-200 text-gray-500'}`}>{t === 'BLACK' ? '⚫ 흑 (선공)' : '⚪ 백 (후공)'}</button>
                ))}
              </div>
            </div>
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">차례 제한시간</p>
              <div className="grid grid-cols-4 gap-1">{[15, 30, 45, 60, 90, 180].map((s) => (<button key={s} onClick={() => setTurnSec(s)} className={`py-2 rounded-lg border text-sm ${turnSec === s ? 'border-hit bg-hit/5 text-hit font-bold' : 'border-gray-200 text-gray-500'}`}>{s}초</button>))}</div>
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
                    {r.status === 'ENDED' ? <span className="text-sm text-gray-300">종료</span> : <button onClick={() => { changeRoom(r.code); setSt(null); }} className="text-sm font-bold text-hit">{r.status === 'WAITING' ? '참가' : '관전'}</button>}
                    {showAdmin && <button onClick={() => handleCloseRoom(r.code)} title="방 삭제" className="text-sm text-red-500">🗑</button>}
                  </div>
                </div>
              );
            })}
          </div>
        )}
        {error && <p className="text-red-500 text-sm mt-4 text-center">{error}</p>}
        <div className="w-full mt-6 pt-4 border-t border-gray-100 flex justify-center">
          {showAdmin ? (
            <div className="flex items-center gap-2">
              <input type="password" value={adminInput} onChange={(e) => setAdminInput(e.target.value)} placeholder="관리자 코드" className="border border-gray-300 rounded-lg px-3 py-2 text-sm w-32" />
              <button onClick={handleAdminReset} className="bg-gray-700 text-white text-sm px-3 py-2 rounded-lg">전체 초기화</button>
            </div>
          ) : <button onClick={() => setShowAdmin(true)} className="text-xs text-gray-300 hover:text-gray-500">🔒 관리자</button>}
        </div>
      <RoomChat game="othello" roomCode="lobby" clientId={clientId} nick={nick} />
      </main>
    );
  }

  if (!st) return <main className="min-h-screen flex items-center justify-center"><p className="text-gray-400">불러오는 중...</p></main>;
  const phase = st.status;
  const canPlace = phase === 'PLAYING' && st.myTurn;
  const validSet = new Set(st.validMoves);

  return (
    <main className="min-h-screen flex flex-col items-center p-4 max-w-lg mx-auto w-full">
      <div className="w-full flex items-center justify-between gap-2 mb-3">
        <div className="flex items-center gap-2 min-w-0">
          <h1 className="text-lg font-bold shrink-0">⚫⚪ 오델로</h1>
          <span className="text-xs bg-gray-100 rounded px-2 py-1 tracking-wider font-bold shrink-0">{roomCode}</span>
          <button onClick={handleLeave} className="text-xs text-gray-400 underline shrink-0">나가기</button>
        </div>
      </div>

      {/* 참가자 / 점수 */}
      <div className="w-full flex items-center justify-center gap-3 my-2 text-sm">
        {st.players.map((p) => {
          const cur = p.color === st.currentColor && phase === 'PLAYING';
          return (
            <span key={p.seat} className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 ${cur ? 'bg-hit text-white font-bold' : 'bg-gray-100 text-gray-600'}`}>
              <span className="inline-block w-4 h-4"><Disc color={p.color} /></span>
              {p.nick}{p.seat === st.seat && '(나)'}{p.ai && ' 🤖'} · {p.color === 1 ? st.blackCount : st.whiteCount}
            </span>
          );
        })}
      </div>

      {/* 대기방 */}
      {phase === 'LOBBY' && (
        <div className="w-full space-y-3 mt-2">
          <p className="text-center text-xs text-gray-400">내 돌: {colName(st.myColor)} · 8×8 · 2인 대전</p>
          {!st.joined ? (
            <div className="flex gap-2">
              <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임" className="flex-1 border border-gray-300 rounded-lg px-3 py-2" />
              <button onClick={handleJoin} disabled={busy} className="bg-hit text-white font-bold px-5 rounded-lg disabled:opacity-50">참가</button>
            </div>
          ) : st.isHost ? (
            <div className="space-y-2">
              {st.players.length < 2 && (
                <div>
                  <p className="text-xs text-gray-400 mb-1 text-center">🤖 AI 봇과 대전 (난이도)</p>
                  <div className="grid grid-cols-2 gap-2">
                    {([['EASY', '초급', 'bg-emerald-400'], ['NORMAL', '중급', 'bg-amber-400'], ['HARD', '고급', 'bg-rose-400'], ['MASTER', '🔥 초고수', 'bg-purple-600'], ['GRAND', '👑 그랜드마스터', 'bg-gradient-to-br from-slate-800 to-black'], ['MYTHIC', '🌌 신화', 'bg-gradient-to-br from-fuchsia-700 to-indigo-900']] as const).map(([lv, label, cls]) => (
                      <button key={lv} onClick={() => handleAddBot(lv)} disabled={busy} className={`${cls} text-white text-sm font-bold py-2 rounded-lg hover:opacity-90 disabled:opacity-40`}>{label}</button>
                    ))}
                  </div>
                  <p className="text-[11px] text-gray-400 mt-1 text-center">초급~고급=기본 · 초고수=수읽기 · 그랜드마스터=TT·안정석 · 🌌신화=준-엔진급(수 3초 숙고, 종반 22칸 완전탐색)</p>
                </div>
              )}
              <button onClick={handleStart} disabled={busy || st.players.length < 2} className="w-full bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">{st.players.length < 2 ? '상대(봇 또는 유저)를 기다리는 중' : '게임 시작'}</button>
            </div>
          ) : <p className="text-center text-gray-500 text-sm">방장이 시작하기를 기다리는 중...</p>}
        </div>
      )}

      {/* 진행 / 종료 */}
      {(phase === 'PLAYING' || phase === 'ENDED') && (
        <div className="w-full space-y-3 mt-1">
          {phase === 'PLAYING' && (
            <p className="text-center text-sm font-bold">
              {st.myTurn ? <span className="text-hit">🎯 내 차례 ({colName(st.myColor)}) {remaining > 0 && <span className="text-gray-400 font-normal">({remaining}s)</span>}</span> : <span className="text-gray-500">⏳ {colName(st.currentColor)} 차례 {remaining > 0 && `(${remaining}s)`}</span>}
            </p>
          )}
          {phase === 'ENDED' && (
            <p className="text-center text-2xl font-extrabold text-hit">
              {st.winner === 3 ? '🤝 무승부!' : `🎉 ${colName(st.winner)} 승리!`}
              <span className="block text-sm font-normal text-gray-500 mt-1">흑 {st.blackCount} : 백 {st.whiteCount}</span>
            </p>
          )}

          {/* 보드 */}
          <div className="w-full aspect-square max-w-[420px] mx-auto rounded-lg p-1.5" style={{ background: '#15803d' }}>
            <div className="grid grid-cols-8 gap-[3px] w-full h-full">
              {st.board.map((cell, i) => {
                const isValid = canPlace && validSet.has(i);
                const isLast = i === st.lastMove;
                return (
                  <button
                    key={i}
                    onClick={isValid ? () => handlePlace(i) : undefined}
                    disabled={!isValid || busy}
                    className={`relative rounded-sm flex items-center justify-center ${isValid ? 'cursor-pointer' : 'cursor-default'}`}
                    style={{ background: '#166534', boxShadow: isLast ? 'inset 0 0 0 2px #fde047' : undefined }}
                  >
                    <span className="block w-[76%] h-[76%]"><Disc color={cell} /></span>
                    {isValid && <span className="absolute w-[28%] h-[28%] rounded-full bg-white/40" />}
                  </button>
                );
              })}
            </div>
          </div>

          {st.lastAction && <p className="text-center text-[11px] text-gray-400">{st.lastAction}</p>}

          {/* 기보 복사 */}
          {st.history.length > 0 && (
            <div className="space-y-2">
              <div className="flex items-center justify-center gap-2">
                <button onClick={copyRecord} className="text-sm px-3 py-1.5 rounded-lg border border-gray-300 text-gray-600 hover:border-hit hover:text-hit">
                  {copied ? '✓ 복사됨' : '📋 기보 복사'}
                </button>
                <button onClick={() => setShowRecord((v) => !v)} className="text-sm px-3 py-1.5 rounded-lg border border-gray-300 text-gray-600 hover:border-hit hover:text-hit">
                  {showRecord ? '기보 숨기기' : '기보 보기'}
                </button>
              </div>
              {showRecord && (
                <textarea readOnly value={buildRecord(st)} onFocus={(e) => e.currentTarget.select()}
                  className="w-full h-40 text-xs font-mono border border-gray-200 rounded-lg p-2 bg-gray-50 text-gray-600 resize-none" />
              )}
            </div>
          )}

          {phase === 'ENDED' && st.isHost && <button onClick={() => { handleLeave(); setShowCreate(true); }} className="w-full bg-hit text-white font-bold py-3 rounded-lg">🔄 새 방 만들기</button>}
          {error && <p className="text-red-500 text-sm text-center">{error}</p>}
        </div>
      )}
      <RoomChat game="othello" roomCode={roomCode} clientId={clientId} nick={nick} />
    </main>
  );
}
