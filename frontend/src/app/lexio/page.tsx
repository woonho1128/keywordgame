'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

type Phase = 'NOT_STARTED' | 'LOBBY' | 'PLAYING' | 'ROUND_END' | 'ENDED';
type PlayerView = { seat: number; nick: string; bot: boolean; tileCount: number; score: number; out: boolean; passed: boolean };
type LxState = {
  status: Phase; serverNow: number; theme: 'BLACK' | 'WHITE'; scoreMode: 'SINGLE' | 'ACCUMULATE';
  isHost: boolean; joined: boolean; seat: number; nick: string | null;
  players: PlayerView[]; myTiles: number[];
  currentTurnSeat: number; myTurn: boolean;
  tableHand: number[]; tableSeat: number; tableHandLabel: string | null;
  turnEndsAt: number; mustIncludeTile: number; lastAction: string | null;
  roundWinnerSeat: number; gameWinnerSeat: number; playerCount: number; version: number;
};
type RoomSummary = { code: string; status: string; playerCount: number; host: string };

const CLIENT_ID_KEY = 'lexio_client_id';
const NICK_KEY = 'lexio_nick';
const ROOM_KEY = 'lexio_room';

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

// 무늬(약→강): 구름 < 별 < 달 < 해
const SUIT = [
  { name: '구름', sym: '☁', black: '#60a5fa', white: '#2563eb' },
  { name: '별', sym: '✦', black: '#4ade80', white: '#16a34a' },
  { name: '달', sym: '☾', black: '#fbbf24', white: '#b45309' },
  { name: '해', sym: '☀', black: '#f87171', white: '#dc2626' },
];
const suitOf = (id: number) => Math.floor(id / 15);
const numOf = (id: number) => (id % 15) + 1;
const nRank = (n: number) => (n >= 3 ? n - 3 : n === 1 ? 13 : 14); // 세기: 3<…<15<1<2
const sortKeyOf = (id: number, mode: 'STRENGTH' | 'NUMBER' | 'SUIT') => {
  const n = numOf(id), s = suitOf(id);
  if (mode === 'NUMBER') return n * 4 + s;   // 자연 숫자 1→15
  if (mode === 'SUIT') return s * 100 + n;   // 무늬끼리 묶어 숫자순
  return nRank(n) * 4 + s;                    // 세기순(기본)
};

function Tile({ id, theme, selected, small, onClick }: { id: number; theme: 'BLACK' | 'WHITE'; selected?: boolean; small?: boolean; onClick?: () => void }) {
  const s = SUIT[suitOf(id)];
  const color = theme === 'BLACK' ? s.black : s.white;
  const bg = theme === 'BLACK' ? '#1f2937' : '#ffffff';
  const size = small ? 'w-8 h-11 text-sm' : 'w-11 h-15';
  return (
    <button
      onClick={onClick}
      disabled={!onClick}
      style={{ background: bg, color, borderColor: color, transform: selected ? 'translateY(-10px)' : undefined }}
      className={`relative rounded-md border-2 flex flex-col items-center justify-center font-extrabold shadow-sm transition ${small ? 'w-9 h-12' : 'w-12 h-16'} ${onClick ? 'active:scale-95 cursor-pointer' : 'cursor-default'} ${selected ? 'ring-2 ring-offset-1 ring-yellow-400' : ''}`}
    >
      <span className={small ? 'text-base leading-none' : 'text-xl leading-none'}>{numOf(id)}</span>
      <span className="text-[10px] leading-none mt-0.5">{s.sym}</span>
    </button>
  );
}

const tid = (suit: number, num: number) => suit * 15 + (num - 1);
const JOKBO_EX: { name: string; desc: string; tiles: number[] }[] = [
  { name: '싱글', desc: '타일 1장', tiles: [tid(3, 8)] },
  { name: '원페어', desc: '같은 숫자 2장', tiles: [tid(0, 7), tid(1, 7)] },
  { name: '트리플', desc: '같은 숫자 3장', tiles: [tid(0, 9), tid(1, 9), tid(2, 9)] },
  { name: '스트레이트', desc: '연속 숫자 5장', tiles: [tid(0, 3), tid(1, 4), tid(2, 5), tid(3, 6), tid(0, 7)] },
  { name: '플러시', desc: '같은 무늬 5장', tiles: [tid(3, 3), tid(3, 5), tid(3, 7), tid(3, 9), tid(3, 12)] },
  { name: '풀하우스', desc: '트리플 + 페어', tiles: [tid(0, 8), tid(1, 8), tid(2, 8), tid(0, 10), tid(1, 10)] },
  { name: '포카드', desc: '같은 숫자 4장 + 1장', tiles: [tid(0, 10), tid(1, 10), tid(2, 10), tid(3, 10), tid(0, 12)] },
  { name: '스트레이트 플러시', desc: '연속 + 같은 무늬 5장', tiles: [tid(3, 4), tid(3, 5), tid(3, 6), tid(3, 7), tid(3, 8)] },
];

export default function LexioPage() {
  const [clientId, setClientId] = useState('');
  const [st, setSt] = useState<LxState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [rooms, setRooms] = useState<RoomSummary[]>([]);
  const roomRef = useRef<string | null>(null);

  const [nick, setNick] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [theme, setTheme] = useState<'BLACK' | 'WHITE'>('BLACK');
  const [scoreMode, setScoreMode] = useState<'SINGLE' | 'ACCUMULATE'>('SINGLE');
  const [turnSec, setTurnSec] = useState(40);
  const [sel, setSel] = useState<number[]>([]);
  const [sortMode, setSortMode] = useState<'STRENGTH' | 'NUMBER' | 'SUIT'>('STRENGTH');
  const [showJokbo, setShowJokbo] = useState(false);
  const [remaining, setRemaining] = useState(0);
  const [showAdmin, setShowAdmin] = useState(false);
  const [adminInput, setAdminInput] = useState('');

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
    if (!code) { try { setRooms(await api<RoomSummary[]>('/api/v1/lexio/rooms')); } catch {} return; }
    try {
      const res = await api<LxState>(`/api/v1/lexio/me?roomCode=${code}&clientId=${encodeURIComponent(c)}`);
      if (res.status === 'NOT_STARTED') { changeRoom(null); setSt(null); }
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
    try { const res = await api<LxState>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined }); setSt(res); endsAtRef.current = res.turnEndsAt; offsetRef.current = res.serverNow - Date.now(); return res; }
    catch (e) { setError(e instanceof Error ? e.message : '오류가 발생했습니다'); return null; }
    finally { setBusy(false); }
  }, []);

  const saveNick = (n: string) => { try { localStorage.setItem(NICK_KEY, n); } catch {} };

  const handleCreate = async () => {
    const n = nick.trim(); if (!n) return setError('닉네임을 입력하세요');
    saveNick(n); setBusy(true); setError(null);
    try {
      const res = await api<{ roomCode: string; state: LxState }>(`/api/v1/lexio/new?clientId=${cid()}`,
        { method: 'POST', body: JSON.stringify({ nick: n, theme, scoreMode, turnSec }) });
      changeRoom(res.roomCode); setSt(res.state); setShowCreate(false);
    } catch (e) { setError(e instanceof Error ? e.message : '방 생성 실패'); } finally { setBusy(false); }
  };
  const handleJoin = async () => { const n = nick.trim(); if (!n) return setError('닉네임을 입력하세요'); saveNick(n); await post(`/api/v1/lexio/join?${rp()}`, { nick: n }); };
  const handleAddBot = (level: string) => post(`/api/v1/lexio/add-bot?${rp()}&level=${level}`);
  const handleStart = () => post(`/api/v1/lexio/start?${rp()}`);
  const handlePlay = async () => { if (sel.length === 0) return; const r = await post(`/api/v1/lexio/play?${rp()}`, { tiles: sel }); if (r) setSel([]); };
  const handlePass = () => post(`/api/v1/lexio/pass?${rp()}`);
  const handleNextRound = () => post(`/api/v1/lexio/next-round?${rp()}`);
  const handleLeave = () => { api(`/api/v1/lexio/leave?${rp()}`, { method: 'POST' }).catch(() => {}); changeRoom(null); setSt(null); };
  const handleAdminReset = async () => { const code = adminInput.trim(); if (!code) return; const res = await post(`/api/v1/lexio/reset?code=${encodeURIComponent(code)}`); if (res) { setShowAdmin(false); setAdminInput(''); changeRoom(null); setSt(null); } };
  const handleCloseRoom = async (rc: string) => { const code = adminInput.trim(); if (!code) { setError('관리자 코드를 먼저 입력하세요'); return; } if (!confirm(`${rc} 방을 삭제할까요?`)) return; try { await api<boolean>(`/api/v1/lexio/close-room?code=${encodeURIComponent(code)}&roomCode=${rc}`, { method: 'POST' }); setRooms((cur) => cur.filter((r) => r.code !== rc)); } catch (e) { setError(e instanceof Error ? e.message : '방 삭제 실패'); } };

  const toggleTile = (id: number) => setSel((cur) => cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]);
  const nickOf = (seat: number) => st?.players.find((p) => p.seat === seat)?.nick ?? `${seat}번`;

  const renderJokbo = (thm: 'BLACK' | 'WHITE') => showJokbo && (
    <div className="w-full mt-3 rounded-xl border border-gray-200 p-4 text-sm">
      <div className="flex items-center justify-between mb-2"><p className="font-bold">📖 족보 (약 → 강)</p><button onClick={() => setShowJokbo(false)} className="text-xs text-gray-400">닫기 ✕</button></div>
      <p className="text-[11px] text-gray-400 mb-3">무늬: 구름 &lt; 별 &lt; 달 &lt; 해 · 숫자: 3&lt;4&lt;…&lt;15&lt;1&lt;<b>2(최강)</b></p>
      <ol className="space-y-2.5">
        {JOKBO_EX.map((h, i) => (
          <li key={h.name} className="border-t border-gray-50 pt-2 first:border-0 first:pt-0">
            <div className="flex items-center gap-1.5 mb-1"><span className="text-gray-400 text-xs">{i + 1}.</span><span className="font-bold">{h.name}</span><span className="text-gray-500 text-xs">— {h.desc}</span></div>
            <div className="flex gap-0.5">{h.tiles.map((id) => <Tile key={id} id={id} theme={thm} small />)}</div>
          </li>
        ))}
      </ol>
    </div>
  );

  // ---------- 방 목록 ----------
  if (!roomCode) {
    return (
      <main className="min-h-screen flex flex-col items-center p-6 max-w-lg mx-auto w-full">
        <h1 className="text-2xl font-bold mb-6 self-start">🀫 렉시오</h1>
        {showCreate ? (
          <div className="w-full space-y-4">
            <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="내 닉네임" className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit" />
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">타일 테마</p>
              <div className="grid grid-cols-2 gap-2">
                {(['BLACK', 'WHITE'] as const).map((t) => (
                  <button key={t} onClick={() => setTheme(t)} className={`py-3 rounded-lg border-2 text-sm font-bold ${theme === t ? 'border-hit bg-hit/5 text-hit' : 'border-gray-200 text-gray-500'}`}>{t === 'BLACK' ? '🀫 블랙 타일' : '🀆 화이트 타일'}</button>
                ))}
              </div>
            </div>
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">점수 방식</p>
              <div className="grid grid-cols-2 gap-2">
                {(['SINGLE', 'ACCUMULATE'] as const).map((m) => (
                  <button key={m} onClick={() => setScoreMode(m)} className={`py-2 px-2 rounded-lg border-2 text-sm text-center ${scoreMode === m ? 'border-hit bg-hit/5 text-hit font-bold' : 'border-gray-200 text-gray-500'}`}>{m === 'SINGLE' ? '단판 승부' : '여러 판 누적'}<br /><span className="text-[10px] font-normal">{m === 'SINGLE' ? '먼저 다 내면 승' : '남은 타일 벌점 누적'}</span></button>
                ))}
              </div>
            </div>
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">차례 제한시간</p>
              <div className="grid grid-cols-4 gap-1">{[20, 30, 40, 60, 90, 180].map((s) => (<button key={s} onClick={() => setTurnSec(s)} className={`py-2 rounded-lg border text-sm ${turnSec === s ? 'border-hit bg-hit/5 text-hit font-bold' : 'border-gray-200 text-gray-500'}`}>{s}초</button>))}</div>
            </div>
            <div className="flex gap-2">
              <button onClick={() => setShowCreate(false)} className="flex-1 border border-gray-300 py-3 rounded-lg">취소</button>
              <button onClick={handleCreate} disabled={busy} className="flex-[2] bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">방 만들기</button>
            </div>
          </div>
        ) : (
          <div className="w-full space-y-4">
            <button onClick={() => { setShowCreate(true); setError(null); }} className="w-full bg-hit text-white font-bold py-3 rounded-lg hover:opacity-90">+ 새 방 만들기</button>
            <button onClick={() => setShowJokbo((v) => !v)} className="text-sm text-gray-400 underline">족보 보기</button>
            {renderJokbo(theme)}
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
                    {r.status === 'ENDED' ? <span className="text-sm text-gray-300">종료</span> : <button onClick={() => { changeRoom(r.code); setSt(null); }} className="text-sm font-bold text-hit">{r.status === 'WAITING' ? '참가' : '이어하기'}</button>}
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
      <RoomChat game="lexio" roomCode="lobby" clientId={clientId} nick={nick} />
      </main>
    );
  }

  if (!st) return <main className="min-h-screen flex items-center justify-center"><p className="text-gray-400">불러오는 중...</p></main>;
  const phase = st.status;
  const th = st.theme;

  return (
    <main className="min-h-screen flex flex-col items-center p-4 max-w-lg mx-auto w-full">
      <div className="w-full flex items-center justify-between gap-2 mb-3">
        <div className="flex items-center gap-2 min-w-0">
          <h1 className="text-lg font-bold shrink-0">🀫 렉시오</h1>
          <span className="text-xs bg-gray-100 rounded px-2 py-1 tracking-wider font-bold shrink-0">{roomCode}</span>
          <button onClick={handleLeave} className="text-xs text-gray-400 underline shrink-0">나가기</button>
        </div>
        <button onClick={() => setShowJokbo((v) => !v)} className="text-xs font-bold text-hit shrink-0">📖 족보</button>
      </div>
      {renderJokbo(th)}

      {/* 참가자 / 점수 */}
      <div className="w-full flex flex-wrap gap-2 my-3 text-xs">
        {st.players.map((p) => {
          const cur = p.seat === st.currentTurnSeat && phase === 'PLAYING';
          return (
            <span key={p.seat} className={`rounded-full px-3 py-1 ${cur ? 'bg-hit text-white font-bold' : p.out ? 'bg-green-100 text-green-700' : p.passed ? 'bg-gray-100 text-gray-400' : 'bg-gray-100 text-gray-600'}`}>
              {cur && '▶ '}{p.nick}{p.seat === st.seat && '(나)'} · {p.out ? '완료' : `${p.tileCount}장`}{st.scoreMode === 'ACCUMULATE' && ` · ${p.score}점`}{p.passed && ' 패스'}
            </span>
          );
        })}
      </div>

      {/* 대기방 */}
      {phase === 'LOBBY' && (
        <div className="w-full space-y-3">
          <p className="text-center text-xs text-gray-400">{st.theme === 'BLACK' ? '🀫 블랙' : '🀆 화이트'} · {st.scoreMode === 'SINGLE' ? '단판' : '누적'} · 2~5인</p>
          <p className="text-center text-[11px] text-gray-400">딜: 3인 1~9/12장 · 4인 1~13/13장 · 5인 1~15/12장 (정규 규칙) · 2인은 3인 세팅 변형</p>
          {!st.joined ? (
            <div className="flex gap-2">
              <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임" className="flex-1 border border-gray-300 rounded-lg px-3 py-2" />
              <button onClick={handleJoin} disabled={busy} className="bg-hit text-white font-bold px-5 rounded-lg disabled:opacity-50">참가</button>
            </div>
          ) : st.isHost ? (
            <div className="space-y-2">
              <div>
                <p className="text-xs text-gray-400 mb-1 text-center">🤖 AI 봇 추가 (난이도)</p>
                <div className="grid grid-cols-3 gap-2">
                  {([['EASY', '초급', 'bg-emerald-400'], ['NORMAL', '중급', 'bg-amber-400'], ['HARD', '고급', 'bg-rose-400']] as const).map(([lv, label, cls]) => (
                    <button key={lv} onClick={() => handleAddBot(lv)} disabled={busy || st.players.length >= 5} className={`${cls} text-white text-sm font-bold py-2 rounded-lg hover:opacity-90 disabled:opacity-40`}>{label}</button>
                  ))}
                </div>
                <p className="text-[11px] text-gray-400 mt-1 text-center">초급=싱글 위주 · 중급=조합으로 털기 · 고급=강한 타일 아껴 컨트롤</p>
              </div>
              <button onClick={handleStart} disabled={busy || st.players.length < 2} className="w-full bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">{st.players.length < 2 ? '최소 2명 필요' : '게임 시작'}</button>
            </div>
          ) : <p className="text-center text-gray-500 text-sm">방장이 시작하기를 기다리는 중...</p>}
        </div>
      )}

      {/* 진행 */}
      {(phase === 'PLAYING' || phase === 'ROUND_END' || phase === 'ENDED') && (
        <div className="w-full space-y-3">
          {/* 테이블 */}
          <div className="rounded-xl border border-gray-200 p-4 min-h-[6rem] flex flex-col items-center justify-center gap-2 bg-gray-50">
            {st.tableHand.length > 0 ? (
              <>
                <p className="text-xs text-gray-400">{nickOf(st.tableSeat)} · {st.tableHandLabel}</p>
                <div className="flex gap-1 flex-wrap justify-center">{st.tableHand.map((id) => <Tile key={id} id={id} theme={th} />)}</div>
              </>
            ) : <p className="text-sm text-gray-400">{phase === 'PLAYING' ? '새 선 — 자유롭게 낼 수 있어요' : '—'}</p>}
          </div>

          {phase === 'PLAYING' && (
            <p className="text-center text-sm font-bold">
              {st.myTurn ? <span className="text-hit">🎯 내 차례! {remaining > 0 && <span className="text-gray-400 font-normal">({remaining}s)</span>}</span> : <span className="text-gray-500">⏳ {nickOf(st.currentTurnSeat)}님 차례 {remaining > 0 && `(${remaining}s)`}</span>}
            </p>
          )}
          {phase === 'PLAYING' && st.myTurn && st.mustIncludeTile >= 0 && (
            <p className="text-center text-[11px] text-amber-600">첫 턴이에요 — 가장 낮은 타일(<b>{numOf(st.mustIncludeTile)}{SUIT[suitOf(st.mustIncludeTile)].sym}</b>)을 반드시 포함해서 내세요</p>
          )}
          {st.lastAction && phase === 'PLAYING' && <p className="text-center text-[11px] text-gray-400">{st.lastAction}</p>}

          {/* 라운드/게임 결과 */}
          {phase === 'ENDED' && <p className="text-center text-2xl font-extrabold text-hit">🎉 {nickOf(st.gameWinnerSeat)} 승리!</p>}
          {phase === 'ROUND_END' && (
            <div className="text-center space-y-2">
              <p className="text-lg font-bold text-hit">🎉 이번 판 {nickOf(st.roundWinnerSeat)} 승리!</p>
              <div className="rounded-lg border border-gray-200 p-3 text-sm">
                <p className="font-bold mb-1">누적 점수 (낮을수록 좋음)</p>
                {st.players.slice().sort((a, b) => a.score - b.score).map((p) => <div key={p.seat} className="flex justify-between"><span>{p.nick}{p.seat === st.seat && '(나)'}</span><b>{p.score}점</b></div>)}
              </div>
              {st.isHost ? <button onClick={handleNextRound} disabled={busy} className="w-full bg-hit text-white font-bold py-3 rounded-lg">다음 판 시작</button> : <p className="text-gray-500 text-sm">방장이 다음 판을 시작하기를 기다리는 중...</p>}
            </div>
          )}

          {/* 내 손패 */}
          {st.joined && st.myTiles.length > 0 && (
            <div>
              <div className="flex items-center justify-between mb-1">
                <p className="text-xs text-gray-400">내 손패 ({st.myTiles.length}장){sel.length > 0 && ` · ${sel.length}장 선택`}</p>
                <div className="flex items-center gap-1">
                  <span className="text-[11px] text-gray-400">정렬</span>
                  {(['STRENGTH', 'NUMBER', 'SUIT'] as const).map((m) => (
                    <button key={m} onClick={() => setSortMode(m)} className={`text-[11px] px-2 py-0.5 rounded border ${sortMode === m ? 'border-hit text-hit font-bold' : 'border-gray-200 text-gray-400'}`}>
                      {m === 'STRENGTH' ? '세기' : m === 'NUMBER' ? '숫자' : '무늬'}순
                    </button>
                  ))}
                </div>
              </div>
              <div className="flex flex-wrap gap-1 justify-center">
                {[...st.myTiles].sort((a, b) => sortKeyOf(a, sortMode) - sortKeyOf(b, sortMode)).map((id) => (
                  <Tile key={id} id={id} theme={th} selected={sel.includes(id)} onClick={phase === 'PLAYING' && st.myTurn ? () => toggleTile(id) : undefined} />
                ))}
              </div>
            </div>
          )}

          {phase === 'PLAYING' && st.myTurn && (
            <div className="flex gap-2">
              <button onClick={handlePass} disabled={busy || st.tableHand.length === 0} className="flex-1 border border-gray-300 py-3 rounded-lg font-bold text-gray-600 disabled:opacity-40">패스</button>
              <button onClick={handlePlay} disabled={busy || sel.length === 0} className="flex-[2] bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">내기 {sel.length > 0 && `(${sel.length}장)`}</button>
            </div>
          )}
          {phase === 'ENDED' && <button onClick={() => { handleLeave(); setShowCreate(true); }} className="w-full bg-hit text-white font-bold py-3 rounded-lg">🔄 새 방 만들기</button>}
          {error && <p className="text-red-500 text-sm text-center">{error}</p>}
        </div>
      )}
      <RoomChat game="lexio" roomCode={roomCode} clientId={clientId} nick={nick} />
    </main>
  );
}
