'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';

type Phase = 'NOT_STARTED' | 'LOBBY' | 'CLUE' | 'GUESS' | 'ENDED';
type Cell = { index: number; word: string; revealed: boolean; color: string | null };
type PlayerView = { seat: number; nick: string; team: string | null; spymaster: boolean };
type RoomSummary = { code: string; status: string; playerCount: number; host: string };

type CNState = {
  status: Phase;
  serverNow: number;
  isHost: boolean;
  joined: boolean;
  seat: number;
  nick: string | null;
  myTeam: string | null;
  amSpymaster: boolean;
  players: PlayerView[];
  board: Cell[];
  currentTeam: string | null;
  startTeam: string | null;
  clueWord: string | null;
  clueNumber: number;
  guessesLeft: number;
  redRemaining: number;
  blueRemaining: number;
  amActiveSpymaster: boolean;
  amActiveOperative: boolean;
  winner: string | null;
  winReason: string | null;
  playerCount: number;
};

const CLIENT_ID_KEY = 'codenames_client_id';
const NICK_KEY = 'codenames_nick';
const ROOM_KEY = 'codenames_room';

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

const teamKo = (t: string | null) => (t === 'RED' ? '레드' : t === 'BLUE' ? '블루' : '');
const teamTextColor = (t: string | null) => (t === 'RED' ? 'text-red-500' : t === 'BLUE' ? 'text-blue-500' : 'text-gray-500');

export default function CodenamesPage() {
  const [clientId, setClientId] = useState('');
  const [st, setSt] = useState<CNState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [rooms, setRooms] = useState<RoomSummary[]>([]);
  const roomRef = useRef<string | null>(null);

  const [nick, setNick] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [clueWord, setClueWord] = useState('');
  const [clueNumber, setClueNumber] = useState(2);

  const [showAdmin, setShowAdmin] = useState(false);
  const [adminInput, setAdminInput] = useState('');

  const cidRef = useRef('');
  const inflight = useRef(false);

  const changeRoom = useCallback((code: string | null) => {
    roomRef.current = code;
    setRoomCode(code);
    try {
      if (code) localStorage.setItem(ROOM_KEY, code);
      else localStorage.removeItem(ROOM_KEY);
    } catch {}
  }, []);

  const poll = useCallback(async () => {
    const c = cidRef.current;
    const code = roomRef.current;
    if (!code) {
      try { setRooms(await api<RoomSummary[]>('/api/v1/codenames/rooms')); } catch {}
      return;
    }
    if (inflight.current) return;
    inflight.current = true;
    try {
      const res = await api<CNState>(`/api/v1/codenames/me?roomCode=${code}&clientId=${encodeURIComponent(c)}`);
      if (res.status === 'NOT_STARTED') { changeRoom(null); setSt(null); }
      else setSt(res);
    } catch { /* ignore */ } finally { inflight.current = false; }
  }, [changeRoom]);

  useEffect(() => {
    const id = getClientId();
    setClientId(id);
    cidRef.current = id;
    try {
      setNick(localStorage.getItem(NICK_KEY) || '');
      const saved = localStorage.getItem(ROOM_KEY);
      if (saved) { roomRef.current = saved; setRoomCode(saved); }
    } catch {}
    poll();
    const t = setInterval(poll, 1000);
    return () => clearInterval(t);
  }, [poll]);

  const post = useCallback(async (path: string, body?: unknown) => {
    setBusy(true);
    setError(null);
    try {
      const res = await api<CNState>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined });
      setSt(res);
      return res;
    } catch (e) {
      setError(e instanceof Error ? e.message : '오류가 발생했습니다');
      return null;
    } finally { setBusy(false); }
  }, []);

  const cid = () => encodeURIComponent(clientId);
  const rp = () => `roomCode=${roomCode}&clientId=${cid()}`;
  const saveNick = (n: string) => { try { localStorage.setItem(NICK_KEY, n); } catch {} };

  const handleCreate = async () => {
    const n = nick.trim();
    if (!n) return setError('닉네임을 입력하세요');
    saveNick(n);
    setBusy(true); setError(null);
    try {
      const res = await api<{ roomCode: string; state: CNState }>(
        `/api/v1/codenames/new?clientId=${cid()}`,
        { method: 'POST', body: JSON.stringify({ nick: n }) });
      changeRoom(res.roomCode);
      setSt(res.state);
      setShowCreate(false);
    } catch (e) { setError(e instanceof Error ? e.message : '방 생성 실패'); } finally { setBusy(false); }
  };

  const handleJoin = () => {
    const n = nick.trim();
    if (!n) return setError('닉네임을 입력하세요');
    saveNick(n);
    post(`/api/v1/codenames/join?${rp()}`, { nick: n });
  };
  const handleTeam = (team: string) => post(`/api/v1/codenames/team?${rp()}`, { team });
  const handleSpymaster = () => post(`/api/v1/codenames/spymaster?${rp()}`);
  const handleRandom = () => post(`/api/v1/codenames/random-assign?${rp()}`);
  const handleStart = () => post(`/api/v1/codenames/start?${rp()}`);
  const handleClue = () => {
    const w = clueWord.trim();
    if (!w) return setError('힌트 단어를 입력하세요');
    post(`/api/v1/codenames/clue?${rp()}`, { word: w, number: clueNumber }).then((r) => r && setClueWord(''));
  };
  const handleGuess = (index: number) => post(`/api/v1/codenames/guess?${rp()}`, { index });
  const handlePass = () => post(`/api/v1/codenames/pass?${rp()}`);
  const handleAdminReset = async () => {
    const code = adminInput.trim();
    if (!code) return;
    const res = await post(`/api/v1/codenames/reset?code=${encodeURIComponent(code)}`);
    if (res) { setShowAdmin(false); setAdminInput(''); changeRoom(null); setSt(null); }
  };

  const adminFooter = (
    <div className="w-full mt-6 pt-4 border-t border-gray-100 flex justify-center">
      {showAdmin ? (
        <div className="flex items-center gap-2">
          <input type="password" value={adminInput} onChange={(e) => setAdminInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleAdminReset()} placeholder="관리자 코드"
            className="border border-gray-300 rounded-lg px-3 py-2 text-sm w-32 focus:outline-none focus:border-red-400" />
          <button onClick={handleAdminReset} className="bg-gray-700 text-white text-sm px-3 py-2 rounded-lg">전체 초기화</button>
        </div>
      ) : (
        <button onClick={() => setShowAdmin(true)} className="text-xs text-gray-300 hover:text-gray-500">🔒 관리자</button>
      )}
    </div>
  );

  // ----- 방 목록 / 생성 -----
  if (!roomCode) {
    return (
      <main className="min-h-screen flex flex-col items-center p-6 max-w-lg mx-auto w-full">
        <h1 className="text-2xl font-bold mb-6 self-start">🔡 코드네임</h1>
        {showCreate ? (
          <div className="w-full space-y-4">
            <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="내 닉네임"
              className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit" />
            <div className="flex gap-2">
              <button onClick={() => setShowCreate(false)} className="flex-1 border border-gray-300 py-3 rounded-lg">취소</button>
              <button onClick={handleCreate} disabled={busy} className="flex-[2] bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-50">방 만들기</button>
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
                    {r.status === 'WAITING'
                      ? <button onClick={() => { changeRoom(r.code); setSt(null); }} className="text-sm font-bold text-hit">참가</button>
                      : <span className="text-sm text-gray-300">{badge}</span>}
                  </div>
                </div>
              );
            })}
          </div>
        )}
        {error && <p className="text-red-500 text-sm mt-4 text-center">{error}</p>}
        {adminFooter}
      </main>
    );
  }

  if (!st) return <main className="min-h-screen flex items-center justify-center"><p className="text-gray-400">불러오는 중...</p></main>;
  const phase = st.status;

  return (
    <main className="min-h-screen flex flex-col items-center p-4 max-w-lg mx-auto w-full">
      <div className="w-full flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <h1 className="text-xl font-bold">🔡 코드네임</h1>
          <span className="text-xs bg-gray-100 rounded px-2 py-1 tracking-wider font-bold">{roomCode}</span>
          <button onClick={() => { changeRoom(null); setSt(null); }} className="text-xs text-gray-400 underline">나가기</button>
        </div>
        {(phase === 'CLUE' || phase === 'GUESS') && (
          <div className="text-right text-sm font-bold">
            <span className="text-red-500">{st.redRemaining}</span>
            <span className="text-gray-300 mx-1">:</span>
            <span className="text-blue-500">{st.blueRemaining}</span>
          </div>
        )}
      </div>

      <div className="flex-1 w-full">
        {phase === 'LOBBY' && renderLobby()}
        {(phase === 'CLUE' || phase === 'GUESS' || phase === 'ENDED') && renderGame()}
        {error && <p className="text-red-500 text-sm mt-3 text-center">{error}</p>}
      </div>

      {adminFooter}
    </main>
  );

  function renderLobby() {
    const red = st!.players.filter((p) => p.team === 'RED');
    const blue = st!.players.filter((p) => p.team === 'BLUE');
    const none = st!.players.filter((p) => !p.team);
    const chip = (p: PlayerView) => (
      <span key={p.seat} className={`rounded-full px-3 py-1 text-sm ${p.seat === st!.seat ? 'ring-2 ring-hit font-bold' : ''} bg-white`}>
        {p.spymaster && '👑 '}{p.nick}{p.seat === st!.seat && ' (나)'}
      </span>
    );
    return (
      <div className="space-y-4 mt-2">
        <div className="grid grid-cols-2 gap-2">
          <div className="rounded-xl border-2 border-red-200 p-3 bg-red-50 min-h-[90px]">
            <p className="text-sm font-bold text-red-500 mb-2">레드 팀</p>
            <div className="flex flex-wrap gap-1">{red.map(chip)}</div>
          </div>
          <div className="rounded-xl border-2 border-blue-200 p-3 bg-blue-50 min-h-[90px]">
            <p className="text-sm font-bold text-blue-500 mb-2">블루 팀</p>
            <div className="flex flex-wrap gap-1">{blue.map(chip)}</div>
          </div>
        </div>
        {none.length > 0 && (
          <div><p className="text-xs text-gray-400 mb-1">미배정</p><div className="flex flex-wrap gap-1">{none.map(chip)}</div></div>
        )}

        {!st!.joined ? (
          <div className="flex gap-2">
            <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임"
              className="flex-1 border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit" />
            <button onClick={handleJoin} disabled={busy} className="bg-hit text-white font-bold px-5 rounded-lg disabled:opacity-50">참가</button>
          </div>
        ) : (
          <div className="space-y-3">
            <div className="flex gap-2">
              <button onClick={() => handleTeam('RED')} className={`flex-1 py-2 rounded-lg font-bold border-2 ${st!.myTeam === 'RED' ? 'border-red-400 bg-red-100 text-red-600' : 'border-gray-200 text-gray-500'}`}>레드</button>
              <button onClick={() => handleTeam('BLUE')} className={`flex-1 py-2 rounded-lg font-bold border-2 ${st!.myTeam === 'BLUE' ? 'border-blue-400 bg-blue-100 text-blue-600' : 'border-gray-200 text-gray-500'}`}>블루</button>
            </div>
            <button onClick={handleSpymaster} disabled={!st!.myTeam || busy}
              className={`w-full py-2 rounded-lg font-bold border-2 ${st!.amSpymaster ? 'border-amber-400 bg-amber-100 text-amber-700' : 'border-gray-200 text-gray-500'} disabled:opacity-40`}>
              👑 스파이마스터 하기
            </button>
          </div>
        )}

        {st!.isHost && (
          <div className="flex gap-2 pt-2">
            <button onClick={handleRandom} disabled={busy} className="flex-1 border border-gray-300 py-3 rounded-lg font-medium">🎲 랜덤 배정</button>
            <button onClick={handleStart} disabled={busy} className="flex-1 bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-50">게임 시작</button>
          </div>
        )}
        <p className="text-center text-xs text-gray-400">각 팀 최소 2명 + 스파이마스터 1명. 같은 주소를 친구들에게 공유하세요.</p>
      </div>
    );
  }

  function cellCls(c: Cell, clickable: boolean) {
    if (c.revealed) {
      return c.color === 'RED' ? 'bg-red-400 text-white border-red-400'
        : c.color === 'BLUE' ? 'bg-blue-400 text-white border-blue-400'
        : c.color === 'ASSASSIN' ? 'bg-gray-900 text-white border-gray-900'
        : 'bg-amber-100 text-amber-600 border-amber-100';
    }
    if (c.color) { // 스파이마스터 시야: 미공개 칸의 비밀 색을 옅게
      return c.color === 'RED' ? 'bg-red-50 border-red-300 text-gray-800'
        : c.color === 'BLUE' ? 'bg-blue-50 border-blue-300 text-gray-800'
        : c.color === 'ASSASSIN' ? 'bg-gray-200 border-gray-800 text-gray-900 font-bold'
        : 'bg-stone-50 border-stone-300 text-gray-600';
    }
    return `bg-white border-gray-300 text-gray-800${clickable ? ' hover:bg-gray-50 active:scale-95' : ''}`;
  }

  function renderGame() {
    const canGuess = st!.amActiveOperative;
    const ended = phase === 'ENDED';
    return (
      <div className="space-y-3 mt-1">
        {/* 상태 배너 */}
        {!ended && (
          <div className="text-center text-sm">
            <span className={`font-bold ${teamTextColor(st!.currentTeam)}`}>{teamKo(st!.currentTeam)} 팀</span>
            <span className="text-gray-500"> 차례 · </span>
            {phase === 'CLUE'
              ? <span className="text-gray-500">스파이마스터가 힌트를 주는 중</span>
              : <span className="text-gray-700 font-medium">힌트: {st!.clueWord} <b>{st!.clueNumber}</b> (남은 추측 {st!.guessesLeft})</span>}
          </div>
        )}

        {/* 5x5 보드 */}
        <div className="grid grid-cols-5 gap-1 w-full">
          {st!.board.map((c) => (
            <button key={c.index} disabled={!canGuess || c.revealed} onClick={() => handleGuess(c.index)}
              className={`aspect-[4/3] rounded border text-[11px] leading-tight font-medium flex items-center justify-center text-center px-0.5 break-keep transition ${cellCls(c, canGuess && !c.revealed)}`}>
              {c.word}
            </button>
          ))}
        </div>

        {/* 스파이마스터 힌트 입력 */}
        {st!.amActiveSpymaster && (
          <div className="flex gap-2 items-center bg-gray-50 rounded-lg p-3">
            <input value={clueWord} onChange={(e) => setClueWord(e.target.value)} maxLength={20} placeholder="한 단어 힌트"
              className="flex-1 border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit" />
            <select value={clueNumber} onChange={(e) => setClueNumber(Number(e.target.value))} className="border border-gray-300 rounded-lg px-2 py-2">
              {[1, 2, 3, 4, 5, 6, 7, 8, 9].map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
            <button onClick={handleClue} disabled={busy} className="bg-hit text-white font-bold px-4 py-2 rounded-lg disabled:opacity-50">힌트</button>
          </div>
        )}

        {/* 요원 패스 */}
        {st!.amActiveOperative && (
          <button onClick={handlePass} disabled={busy} className="w-full border border-gray-300 py-2 rounded-lg text-sm text-gray-600">패스 (턴 넘기기)</button>
        )}

        {/* 관전(비활성) 안내 */}
        {!ended && !st!.amActiveSpymaster && !st!.amActiveOperative && (
          <p className="text-center text-gray-400 text-sm">
            {st!.amSpymaster ? '상대 팀 차례입니다.' : phase === 'CLUE' ? '스파이마스터의 힌트를 기다리세요.' : '지금은 상대 팀 차례입니다.'}
          </p>
        )}

        {/* 종료 */}
        {ended && (
          <div className="text-center space-y-3 mt-2">
            <p className={`text-2xl font-extrabold ${teamTextColor(st!.winner)}`}>{teamKo(st!.winner)} 팀 승리! 🎉</p>
            {st!.winReason && <p className="text-sm text-gray-500">{st!.winReason}</p>}
            <button onClick={() => { setShowCreate(true); changeRoom(null); setSt(null); }}
              className="w-full bg-hit text-white font-bold py-3 rounded-lg hover:opacity-90">🔄 새 방 만들기</button>
          </div>
        )}
      </div>
    );
  }
}
