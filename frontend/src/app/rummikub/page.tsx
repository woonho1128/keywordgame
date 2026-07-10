'use client';

import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { api } from '@/lib/api';

type TileView = { id: number; color: string | null; number: number; joker: boolean };
type PlayerView = { seat: number; nick: string; rackCount: number; melded: boolean };
type RoomSummary = { code: string; status: string; playerCount: number; host: string };

type RkState = {
  status: 'NOT_STARTED' | 'LOBBY' | 'PLAYING' | 'ENDED';
  serverNow: number;
  isHost: boolean;
  joined: boolean;
  seat: number;
  nick: string | null;
  players: PlayerView[];
  myRack: TileView[];
  table: TileView[][];
  drawCount: number;
  currentSeat: number;
  isMyTurn: boolean;
  myMelded: boolean;
  winnerSeat: number;
  winnerNick: string | null;
  lastAction: string | null;
  playerCount: number;
  turnDeadlineMs: number;
};

const CLIENT_ID_KEY = 'rummikub_client_id';
const NICK_KEY = 'rummikub_nick';
const ROOM_KEY = 'rummikub_room';

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

const COLOR_CLS: Record<string, string> = {
  RED: 'text-red-600', BLUE: 'text-blue-600', BLACK: 'text-gray-800', ORANGE: 'text-amber-500',
};
const COLOR_ORDER: Record<string, number> = { RED: 0, ORANGE: 1, BLUE: 2, BLACK: 3 };

export default function RummikubPage() {
  const [clientId, setClientId] = useState('');
  const [st, setSt] = useState<RkState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [rooms, setRooms] = useState<RoomSummary[]>([]);
  const roomRef = useRef<string | null>(null);

  const [nick, setNick] = useState('');
  const [turnSec, setTurnSec] = useState(60);
  const [showCreate, setShowCreate] = useState(false);
  const [showRules, setShowRules] = useState(false);

  const [showAdmin, setShowAdmin] = useState(false);
  const [adminInput, setAdminInput] = useState('');
  const [wide, setWide] = useState(false); // 가로(넓게) 보기
  const [, setNowTick] = useState(0);      // 타이머 리렌더용
  const clockOffset = useRef(0);           // serverNow - Date.now()

  // 워크스페이스(내 턴 편집)
  const [wt, setWt] = useState<number[][]>([]);   // 테이블 세트(타일 id)
  const [wr, setWr] = useState<number[]>([]);      // 아직 안 놓은 내 타일
  const [sel, setSel] = useState<Set<number>>(new Set());
  const [rackSetLens, setRackSetLens] = useState<number[]>([]); // 정렬 시 앞에 모은 "낼 수 있는 세트"들의 길이
  const turnRef = useRef('');
  const rackOrderRef = useRef<number[]>([]); // 내가 정렬해둔 랙 순서 기억(새 타일은 우측 append)

  const cidRef = useRef('');
  const inflight = useRef(false);

  const changeRoom = useCallback((code: string | null) => {
    roomRef.current = code;
    setRoomCode(code);
    try { if (code) localStorage.setItem(ROOM_KEY, code); else localStorage.removeItem(ROOM_KEY); } catch {}
  }, []);

  const poll = useCallback(async () => {
    const c = cidRef.current;
    const code = roomRef.current;
    if (!code) {
      try { setRooms(await api<RoomSummary[]>('/api/v1/rummikub/rooms')); } catch {}
      return;
    }
    if (inflight.current) return;
    inflight.current = true;
    try {
      const res = await api<RkState>(`/api/v1/rummikub/me?roomCode=${code}&clientId=${encodeURIComponent(c)}`);
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
      setWide(localStorage.getItem('rummikub_wide') === '1');
      const saved = localStorage.getItem(ROOM_KEY);
      if (saved) { roomRef.current = saved; setRoomCode(saved); }
    } catch {}
    poll();
    const t = setInterval(poll, 1000);
    return () => clearInterval(t);
  }, [poll]);

  // 타이머 카운트다운용 리렌더(500ms) + 서버 시계 오차 보정
  useEffect(() => {
    const t = setInterval(() => setNowTick((n) => n + 1), 500);
    return () => clearInterval(t);
  }, []);
  useEffect(() => { if (st) clockOffset.current = st.serverNow - Date.now(); }, [st?.serverNow]);

  // 내 랙을 "기억해둔 순서" 기준으로 정렬: 기존 타일은 내 순서 유지, 새로 들어온 타일은 맨 우측에 붙인다.
  const orderRackView = (serverIds: number[]): number[] => {
    const set = new Set(serverIds);
    const kept = rackOrderRef.current.filter((id) => set.has(id));
    const keptSet = new Set(kept);
    const extras = serverIds.filter((id) => !keptSet.has(id));
    return [...kept, ...extras];
  };
  const orderRack = (serverIds: number[]): number[] => {
    const result = orderRackView(serverIds);
    rackOrderRef.current = result;
    return result;
  };

  // 커밋된 서버 상태(차례·테이블·내 랙)가 바뀌면 워크스페이스를 초기화.
  // 편집 중엔 서버 상태가 안 바뀌므로 내 편집은 유지되고,
  // 봇이 내 폴링 중 자동으로 두어 차례가 나에게 되돌아와도(테이블 변경) 다시 동기화된다.
  useEffect(() => {
    if (!st) return;
    const sig = st.status === 'PLAYING'
      ? `${st.currentSeat}|${st.table.map((s) => s.map((t) => t.id).join(',')).join(';')}|${st.myRack.map((t) => t.id).join(',')}`
      : `x${st.status}`;
    if (sig !== turnRef.current) {
      turnRef.current = sig;
      setWt(st.table.map((s) => s.map((t) => t.id)));
      setWr(orderRack(st.myRack.map((t) => t.id)));
      setSel(new Set());
      setRackSetLens([]);
    }
  }, [st]);

  const post = useCallback(async (path: string, body?: unknown) => {
    setBusy(true); setError(null);
    try {
      const res = await api<RkState>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined });
      setSt(res);
      return res;
    } catch (e) {
      setError(e instanceof Error ? e.message : '오류가 발생했습니다');
      return null;
    } finally { setBusy(false); }
  }, []);

  const cid = () => encodeURIComponent(clientId);
  const rp = () => `roomCode=${roomCode}&clientId=${cid()}`;
  const toggleWide = () => setWide((v) => { const n = !v; try { localStorage.setItem('rummikub_wide', n ? '1' : '0'); } catch {} return n; });
  const saveNick = (n: string) => { try { localStorage.setItem(NICK_KEY, n); } catch {} };

  const handleCreate = async () => {
    const n = nick.trim();
    if (!n) return setError('닉네임을 입력하세요');
    saveNick(n);
    setBusy(true); setError(null);
    try {
      const res = await api<{ roomCode: string; state: RkState }>(
        `/api/v1/rummikub/new?clientId=${cid()}`, { method: 'POST', body: JSON.stringify({ nick: n, turnSec }) });
      changeRoom(res.roomCode); setSt(res.state); setShowCreate(false);
    } catch (e) { setError(e instanceof Error ? e.message : '방 생성 실패'); } finally { setBusy(false); }
  };
  const handleJoin = () => {
    const n = nick.trim();
    if (!n) return setError('닉네임을 입력하세요');
    saveNick(n);
    post(`/api/v1/rummikub/join?${rp()}`, { nick: n });
  };
  const handleStart = () => post(`/api/v1/rummikub/start?${rp()}`);
  const handleAddAi = (level: string) => post(`/api/v1/rummikub/add-ai?${rp()}&level=${level}`);
  const handleRemoveAi = () => post(`/api/v1/rummikub/remove-ai?${rp()}`);
  const handleDraw = () => post(`/api/v1/rummikub/draw?${rp()}`);
  const handleSubmit = () => {
    // 재배치로 집어든 테이블 타일은 모두 다시 놓여야 함
    const placed = new Set(wt.flat());
    if ([...origTable].some((id) => !placed.has(id))) {
      setError('테이블에서 집어든 타일을 모두 다시 배치한 뒤 제출하세요');
      return;
    }
    post(`/api/v1/rummikub/play?${rp()}`, { table: wt.filter((s) => s.length > 0) });
  };
  const handleAdminReset = async () => {
    const code = adminInput.trim();
    if (!code) return;
    const res = await post(`/api/v1/rummikub/reset?code=${encodeURIComponent(code)}`);
    if (res) { setShowAdmin(false); setAdminInput(''); changeRoom(null); setSt(null); }
  };
  const handleCloseRoom = async (rc: string) => {
    const code = adminInput.trim();
    if (!code) { setError('관리자 코드를 먼저 입력하세요'); return; }
    if (!confirm(`${rc} 방을 삭제할까요?`)) return;
    try {
      await api<boolean>(`/api/v1/rummikub/close-room?code=${encodeURIComponent(code)}&roomCode=${rc}`, { method: 'POST' });
      setRooms((cur) => cur.filter((r) => r.code !== rc));
    } catch (e) { setError(e instanceof Error ? e.message : '방 삭제에 실패했습니다'); }
  };

  // 타일 조회 맵
  const tileMap = new Map<number, TileView>();
  if (st) { st.myRack.forEach((t) => tileMap.set(t.id, t)); st.table.flat().forEach((t) => tileMap.set(t.id, t)); }
  const origTable = new Set<number>(st ? st.table.flat().map((t) => t.id) : []);

  const toggleSel = (id: number) => setSel((cur) => {
    const n = new Set(cur); if (n.has(id)) n.delete(id); else n.add(id); return n;
  });
  const selArr = () => Array.from(sel);
  const addToSet = (i: number) => {
    if (sel.size === 0) return;
    const arr = selArr();
    setWt((cur) => cur.map((s, idx) => (idx === i ? [...s, ...arr] : s)));
    setWr((cur) => cur.filter((id) => !sel.has(id)));
    setSel(new Set()); setRackSetLens([]);
  };
  const newSet = () => {
    if (sel.size === 0) return;
    const arr = selArr();
    setWt((cur) => [...cur, arr]);
    setWr((cur) => cur.filter((id) => !sel.has(id)));
    setSel(new Set()); setRackSetLens([]);
  };
  const removePlaced = (setIdx: number, id: number) => {
    // 첫 등록(30점) 전에는 테이블 원래 타일을 건드릴 수 없다. 등록 후엔 재배치 허용.
    if (origTable.has(id) && !st?.myMelded) return;
    setWt((cur) => cur.map((s, i) => (i === setIdx ? s.filter((x) => x !== id) : s)).filter((s) => s.length > 0));
    setWr((cur) => [...cur, id]); setRackSetLens([]);
  };
  const resetWork = () => {
    if (!st) return;
    setWt(st.table.map((s) => s.map((t) => t.id)));
    setWr(orderRack(st.myRack.map((t) => t.id)));
    setSel(new Set()); setRackSetLens([]);
  };

  // rem에서 가장 점수 높은 유효 세트(그룹/런, 조커 제외) 하나. 없으면 null.
  const bestRackSet = (rem: number[]): number[] | null => {
    let best: number[] | null = null, bestVal = -1;
    const byNum = new Map<number, Map<string, number>>();
    const byColor = new Map<string, Map<number, number>>();
    for (const id of rem) {
      const t = tileMap.get(id); if (!t || t.joker) continue;
      if (!byNum.has(t.number)) byNum.set(t.number, new Map());
      const mn = byNum.get(t.number)!; if (!mn.has(t.color ?? '')) mn.set(t.color ?? '', id);
      if (!byColor.has(t.color ?? '')) byColor.set(t.color ?? '', new Map());
      const mc = byColor.get(t.color ?? '')!; if (!mc.has(t.number)) mc.set(t.number, id);
    }
    for (const [num, m] of byNum) if (m.size >= 3) { const g = [...m.values()]; const v = num * g.length; if (v > bestVal) { bestVal = v; best = g; } }
    for (const [, m] of byColor) {
      const nums = [...m.keys()].sort((a, b) => a - b);
      let i = 0;
      while (i < nums.length) {
        let j = i; while (j + 1 < nums.length && nums[j + 1] === nums[j] + 1) j++;
        if (j - i + 1 >= 3) { const run: number[] = []; let v = 0; for (let k = i; k <= j; k++) { run.push(m.get(nums[k])!); v += nums[k]; } if (v > bestVal) { bestVal = v; best = run; } }
        i = j + 1;
      }
    }
    return best;
  };
  // 랙에서 겹치지 않는 완성 세트들을 그리디로 추출.
  const findRackSets = (ids: number[]): number[][] => {
    let rem = ids.slice();
    const out: number[][] = [];
    for (;;) {
      const best = bestRackSet(rem);
      if (!best) break;
      out.push(best);
      const bs = new Set(best);
      rem = rem.filter((id) => !bs.has(id));
    }
    return out;
  };
  const cmpTile = (mode: 'number' | 'color') => (a: number, b: number) => {
    const ta = tileMap.get(a), tb = tileMap.get(b);
    if (!ta || !tb) return 0;
    if (ta.joker !== tb.joker) return ta.joker ? 1 : -1;
    if (ta.joker) return 0;
    const ca = COLOR_ORDER[ta.color ?? ''] ?? 9, cb = COLOR_ORDER[tb.color ?? ''] ?? 9;
    return mode === 'number' ? (ta.number - tb.number || ca - cb) : (ca - cb || ta.number - tb.number);
  };
  // 내 패 정렬: 낼 수 있는 세트를 앞으로 빼서 모으고(rackSetLens), 나머지는 선택한 기준으로 정렬.
  const sortWr = (mode: 'number' | 'color') => {
    const sets = findRackSets(wr).map((s) => [...s].sort(cmpTile(mode)));
    const used = new Set(sets.flat());
    const leftover = wr.filter((id) => !used.has(id)).sort(cmpTile(mode));
    const ordered = [...sets.flat(), ...leftover];
    rackOrderRef.current = ordered;
    setRackSetLens(sets.map((s) => s.length));
    setWr(ordered);
  };

  // 세트를 보기 좋게 정렬(런=숫자 오름차순·조커는 빈칸/뒤, 그룹=색 순서). 클릭은 id 기반이라 표시 순서만 바뀜.
  const sortSet = (ids: number[]): number[] => {
    const reals = ids.filter((id) => { const t = tileMap.get(id); return t && !t.joker; });
    const jokers = ids.filter((id) => { const t = tileMap.get(id); return t && t.joker; });
    if (reals.length === 0) return ids;
    const num0 = tileMap.get(reals[0])!.number;
    const isGroup = ids.length <= 4 && reals.every((id) => tileMap.get(id)!.number === num0);
    if (isGroup) {
      reals.sort((a, b) => (COLOR_ORDER[tileMap.get(a)!.color ?? ''] ?? 9) - (COLOR_ORDER[tileMap.get(b)!.color ?? ''] ?? 9));
      return [...reals, ...jokers];
    }
    reals.sort((a, b) => tileMap.get(a)!.number - tileMap.get(b)!.number);
    const out: number[] = [];
    let ji = 0, prev = -1;
    for (const id of reals) {
      const n = tileMap.get(id)!.number;
      if (prev >= 0) for (let g = prev + 1; g < n && ji < jokers.length; g++) out.push(jokers[ji++]);
      out.push(id);
      prev = n;
    }
    while (ji < jokers.length) out.push(jokers[ji++]);
    return out;
  };

  function Tile({ t, onClick, selected, small, fromTable, playable }: { t: TileView; onClick?: () => void; selected?: boolean; small?: boolean; fromTable?: boolean; playable?: boolean }) {
    const color = t.joker ? 'text-fuchsia-500' : COLOR_CLS[t.color ?? ''] ?? 'text-gray-700';
    const ring = selected ? '-translate-y-1.5 ring-2 ring-hit shadow-lg z-10'
      : fromTable ? 'ring-2 ring-rose-400 shadow-[0_2px_0_rgba(0,0,0,0.18)]'
      : playable ? 'ring-2 ring-emerald-400 shadow-[0_2px_0_rgba(0,0,0,0.18)]'
      : 'shadow-[0_2px_0_rgba(0,0,0,0.18)]';
    return (
      <button onClick={onClick} disabled={!onClick}
        className={`relative ${small ? 'w-8 h-11' : 'w-9 h-12'} rounded-lg bg-[#fffdf4] border border-black/10 flex flex-col items-center justify-center font-extrabold shrink-0 transition
          ${ring} ${color} ${onClick ? 'active:translate-y-0 cursor-pointer' : ''}`}>
        <span className={small ? 'text-base leading-none' : 'text-lg leading-none'}>{t.joker ? '🃏' : t.number}</span>
        {!t.joker && <span className="w-1.5 h-1.5 rounded-full mt-1" style={{ backgroundColor: 'currentColor' }} />}
      </button>
    );
  }

  // 내 랙 렌더링: 정렬 시 앞으로 모은 세트(rackSetLens)는 초록 테두리 + 세트 사이 간격, 이후 나머지 타일.
  const rackTileEls = (): ReactNode[] => {
    const setCount = rackSetLens.reduce((a, b) => a + b, 0);
    const setEnds = new Set<number>();  // 각 세트가 끝나는 누적 인덱스
    { let acc = 0; for (const l of rackSetLens) { acc += l; setEnds.add(acc); } }
    const els: ReactNode[] = [];
    wr.forEach((id, idx) => {
      const t = tileMap.get(id);
      if (!t) return;
      if (idx === setCount && setCount > 0 && setCount < wr.length)
        els.push(<span key={`div`} className="w-px self-stretch bg-amber-400/60 mx-1" />);          // 세트↔나머지 구분선
      else if (idx > 0 && idx < setCount && setEnds.has(idx))
        els.push(<span key={`gap${idx}`} className="w-2 shrink-0" />);                                // 세트끼리 간격
      els.push(<Tile key={id} t={t} selected={sel.has(id)} fromTable={origTable.has(id)} playable={idx < setCount} onClick={() => toggleSel(id)} />);
    });
    return els;
  };

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
      ) : (
        <button onClick={() => setShowAdmin(true)} className="text-xs text-gray-300 hover:text-gray-500">🔒 관리자</button>
      )}
    </div>
  );

  const rulesHelp = (
    <div className="w-full rounded-xl border border-gray-200 p-4 text-sm space-y-2 text-left mt-3">
      <div className="flex items-center justify-between"><p className="font-bold">게임 방법</p>
        <button onClick={() => setShowRules(false)} className="text-xs text-gray-400">닫기 ✕</button></div>
      <p className="text-gray-600">🎯 <b>내 타일(랙)을 먼저 다 내려놓으면 승리.</b></p>
      <p className="text-gray-600">세트 2종: <b>그룹</b>(같은 숫자·다른 색 3~4개) / <b>런</b>(같은 색·연속 숫자 3개+). 🃏조커는 아무 타일 대체.</p>
      <p className="text-gray-600">내 차례에: 랙 타일을 선택 → <b>새 세트</b>로 놓거나 기존 세트에 <b>추가</b> → <b>제출</b>. 못 놓으면 <b>가져오기</b>로 1장 뽑고 넘김.</p>
      <p className="text-gray-600">⚠️ <b>첫 등록</b>은 내 타일로만 만든 세트 합이 <b>30점 이상</b>이어야 합니다.</p>
      <p className="text-gray-600">🤖 <b>혼자여도 OK!</b> 대기방에서 <b>초급·중급·고급</b> AI 봇을 넣어 바로 플레이하세요.</p>
    </div>
  );

  // ----- 방 목록 -----
  if (!roomCode) {
    return (
      <main className="min-h-screen flex flex-col items-center p-6 max-w-lg mx-auto w-full">
        <h1 className="text-2xl font-bold mb-6 self-start">🁢 루미큐브</h1>
        {showCreate ? (
          <div className="w-full space-y-4">
            <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="내 닉네임"
              className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit" />
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">차례 제한시간 <span className="font-normal text-gray-400 text-xs">(시간 초과 시 자동 가져오기)</span></p>
              <div className="grid grid-cols-4 gap-1">
                {[30, 45, 60, 90, 120, 180].map((s) => (
                  <button key={s} onClick={() => setTurnSec(s)}
                    className={`py-2 rounded-lg border text-sm ${turnSec === s ? 'border-hit bg-hit/5 text-hit font-bold' : 'border-gray-200 text-gray-500'}`}>{s}초</button>
                ))}
              </div>
            </div>
            <div className="flex gap-2">
              <button onClick={() => setShowCreate(false)} className="flex-1 border border-gray-300 py-3 rounded-lg">취소</button>
              <button onClick={handleCreate} disabled={busy} className="flex-[2] bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-50">방 만들기</button>
            </div>
          </div>
        ) : (
          <div className="w-full space-y-4">
            <button onClick={() => { setShowCreate(true); setError(null); }} className="w-full bg-hit text-white font-bold py-3 rounded-lg hover:opacity-90">+ 새 방 만들기</button>
            <div className="text-center"><button onClick={() => setShowRules((v) => !v)} className="text-sm text-gray-400 underline">게임 방법 보기</button></div>
            {showRules && rulesHelp}
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
      </main>
    );
  }

  if (!st) return <main className="min-h-screen flex items-center justify-center"><p className="text-gray-400">불러오는 중...</p></main>;

  return (
    <main className={`min-h-screen flex flex-col items-center p-4 ${wide ? 'max-w-5xl' : 'max-w-lg'} mx-auto w-full`}>
      <div className="w-full flex items-center justify-between gap-2 flex-wrap mb-3">
        <div className="flex items-center gap-2 min-w-0">
          <h1 className="text-lg sm:text-xl font-bold shrink-0">🁢 루미큐브</h1>
          <span className="text-xs bg-gray-100 rounded px-2 py-1 tracking-wider font-bold shrink-0">{roomCode}</span>
          <button onClick={() => { changeRoom(null); setSt(null); }} className="text-xs text-gray-400 underline shrink-0">나가기</button>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <button onClick={toggleWide} title="가로/세로 보기 전환"
            className="text-xs border border-gray-300 rounded-md px-2 py-1 text-gray-500 hover:bg-gray-50">{wide ? '📱 세로' : '🖥 가로'}</button>
          {st.status === 'PLAYING' && <span className="text-xs text-gray-400">더미 {st.drawCount}</span>}
        </div>
      </div>

      {st.status === 'LOBBY' && (
        <div className="w-full space-y-4 mt-2">
          <div className="rounded-xl border border-gray-200 p-4">
            <p className="text-sm font-bold mb-2">참가자 ({st.playerCount}/4)</p>
            <div className="flex flex-wrap gap-2">
              {st.players.map((p) => (
                <span key={p.seat} className={`bg-gray-100 rounded-full px-3 py-1 text-sm ${p.seat === st.seat ? 'ring-2 ring-hit font-bold' : ''}`}>{p.nick}{p.seat === st.seat && ' (나)'}</span>
              ))}
            </div>
          </div>
          <div className="text-center"><button onClick={() => setShowRules((v) => !v)} className="text-sm text-gray-400 underline">게임 방법 보기</button></div>
          {showRules && rulesHelp}
          {!st.joined ? (
            <div className="flex gap-2">
              <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임"
                className="flex-1 border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit" />
              <button onClick={handleJoin} disabled={busy} className="bg-hit text-white font-bold px-5 rounded-lg disabled:opacity-50">참가</button>
            </div>
          ) : st.isHost ? (
            <div className="space-y-3">
              <div className="rounded-xl border border-amber-200 bg-amber-50/60 p-3">
                <div className="flex items-center justify-between mb-2">
                  <p className="text-sm font-bold text-amber-800">🤖 AI 봇 추가</p>
                  <button onClick={handleRemoveAi} disabled={busy || !st.players.some((p) => p.nick.startsWith('🤖'))}
                    className="text-xs text-gray-500 underline disabled:opacity-30">AI 제거</button>
                </div>
                <div className="grid grid-cols-3 gap-2">
                  {[['EASY', '초급', 'bg-emerald-400'], ['NORMAL', '중급', 'bg-amber-400'], ['HARD', '고급', 'bg-rose-400']].map(([lv, label, cls]) => (
                    <button key={lv} onClick={() => handleAddAi(lv)} disabled={busy || st.playerCount >= 4}
                      className={`${cls} text-white font-bold py-2 rounded-lg text-sm shadow-[0_2px_0_rgba(0,0,0,0.15)] active:translate-y-0.5 disabled:opacity-40`}>
                      {label}
                    </button>
                  ))}
                </div>
                <p className="text-[11px] text-gray-400 mt-2 text-center">혼자서도 바로 플레이 · 최대 4명까지</p>
              </div>
              <button onClick={handleStart} disabled={busy || st.playerCount < 2} className="w-full bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">
                {st.playerCount >= 2 ? '게임 시작' : '최소 2명 필요 (AI 추가 가능)'}
              </button>
            </div>
          ) : <p className="text-center text-gray-500 text-sm">방장이 시작하기를 기다리는 중...</p>}
        </div>
      )}

      {(st.status === 'PLAYING' || st.status === 'ENDED') && (
        <div className="w-full space-y-3">
          {/* 플레이어 상태 */}
          <div className="flex flex-wrap gap-2 text-xs">
            {st.players.map((p) => (
              <span key={p.seat} className={`rounded-full px-3 py-1 ${p.seat === st.currentSeat && st.status === 'PLAYING' ? 'bg-hit text-white font-bold' : 'bg-gray-100 text-gray-600'}`}>
                {p.nick}{p.seat === st.seat && '(나)'} · {p.rackCount}장{p.melded ? '' : ' ·미등록'}
              </span>
            ))}
          </div>
          {st.lastAction && <p className="text-xs text-gray-400 text-center">{st.lastAction}</p>}
          {st.status === 'PLAYING' && st.turnDeadlineMs > 0 && (() => {
            const remain = Math.max(0, Math.ceil((st.turnDeadlineMs - (Date.now() + clockOffset.current)) / 1000));
            const urgent = remain <= 10;
            return (
              <div className="flex items-center justify-center gap-2">
                <span className={`text-sm font-bold ${urgent ? 'text-red-500 animate-pulse' : st.isMyTurn ? 'text-hit' : 'text-gray-500'}`}>
                  ⏱ {remain}초
                </span>
                <span className="text-xs text-gray-400">{st.isMyTurn ? '· 내 차례 (60초 초과 시 자동 가져오기)' : ''}</span>
              </div>
            );
          })()}

          {/* 테이블 (펠트) */}
          <div className="rounded-2xl p-3 min-h-[90px] bg-gradient-to-b from-emerald-600 to-emerald-800 shadow-inner ring-1 ring-emerald-900/40 border-[3px] border-emerald-900/30">
            <p className="text-xs text-emerald-100/80 mb-2 font-bold tracking-wide">🃏 테이블</p>
            <div className="flex flex-wrap gap-2 items-start">
              {(st.isMyTurn ? wt : st.table.map((s) => s.map((t) => t.id))).map((set, i) => (
                <div key={i} className="flex items-center gap-1 flex-wrap max-w-full shrink-0 bg-emerald-900/25 rounded-xl p-1.5">
                  {sortSet(set).map((id) => {
                    const t = tileMap.get(id);
                    if (!t) return null;
                    const removable = st.isMyTurn && (!origTable.has(id) || st.myMelded);
                    return <Tile key={id} t={t} small onClick={removable ? () => removePlaced(i, id) : undefined} />;
                  })}
                  {st.isMyTurn && (
                    <button onClick={() => addToSet(i)} disabled={sel.size === 0}
                      className="text-xs text-white/90 border border-white/50 rounded-lg px-2 py-1 disabled:opacity-30 active:translate-y-0.5">＋추가</button>
                  )}
                </div>
              ))}
              {(st.isMyTurn ? wt : st.table).length === 0 && <p className="w-full text-emerald-100/50 text-sm text-center py-3">아직 내려놓은 세트가 없어요</p>}
              {st.isMyTurn && (
                <button onClick={newSet} disabled={sel.size === 0}
                  className="self-center text-xs text-emerald-50 border border-dashed border-emerald-200/60 rounded-lg px-3 py-2 disabled:opacity-30 active:translate-y-0.5 shrink-0">＋ 새 세트</button>
              )}
            </div>
          </div>

          {/* 종료 */}
          {st.status === 'ENDED' && (
            <div className="text-center space-y-3 py-2">
              <p className="text-2xl font-extrabold text-hit">🎉 {st.winnerNick} 승리!</p>
              <button onClick={() => { setShowCreate(true); changeRoom(null); setSt(null); }} className="w-full bg-hit text-white font-bold py-3 rounded-lg">🔄 새 방 만들기</button>
            </div>
          )}

          {/* 내 랙 (나무 받침) */}
          {st.status === 'PLAYING' && (
            <div className="rounded-2xl p-3 bg-gradient-to-b from-amber-100 to-amber-200/70 border-[3px] border-amber-300/70 shadow-[inset_0_2px_6px_rgba(180,120,40,0.25)]">
              <div className="flex items-center justify-between mb-2">
                <p className="text-xs text-amber-700/80 font-bold">🪵 내 타일 ({st.isMyTurn ? wr.length : st.myRack.length})</p>
                <div className="flex items-center gap-1.5">
                  {st.isMyTurn && (
                    <>
                      <button onClick={() => sortWr('number')} className="text-[11px] text-amber-800 bg-amber-200/70 border border-amber-300 rounded-md px-2 py-0.5 font-bold active:translate-y-0.5">숫자순</button>
                      <button onClick={() => sortWr('color')} className="text-[11px] text-amber-800 bg-amber-200/70 border border-amber-300 rounded-md px-2 py-0.5 font-bold active:translate-y-0.5">색깔순</button>
                      <span className="text-xs text-white bg-hit rounded-full px-2 py-0.5 font-bold animate-pulse">내 차례!</span>
                    </>
                  )}
                </div>
              </div>
              <div className="flex flex-wrap items-center gap-1">
                {st.isMyTurn
                  ? rackTileEls()
                  : orderRackView(st.myRack.map((t) => t.id)).map((id) => { const t = tileMap.get(id); return t ? <Tile key={id} t={t} /> : null; })}
              </div>
              {st.isMyTurn && rackSetLens.length > 0 && (
                <p className="text-[11px] text-emerald-600 mt-2">🟢 초록 테두리 = 지금 낼 수 있는 세트 ({rackSetLens.length}개) — 선택 후 새 세트로 내려놓으세요</p>
              )}
              {st.isMyTurn && wr.some((id) => origTable.has(id)) && (
                <p className="text-[11px] text-rose-500 mt-2">🔴 테두리 타일은 테이블에서 집어온 것 — 다시 배치해야 제출할 수 있어요</p>
              )}
              {st.isMyTurn && st.myMelded && (
                <p className="text-[11px] text-amber-600/70 mt-1">💡 테이블 타일을 눌러 집어와 다른 세트로 재배치할 수 있어요</p>
              )}
            </div>
          )}

          {/* 액션 */}
          {st.status === 'PLAYING' && st.isMyTurn && (
            <div className="flex gap-2">
              <button onClick={resetWork} className="border border-gray-300 py-3 px-4 rounded-lg text-sm text-gray-600">되돌리기</button>
              <button onClick={handleDraw} disabled={busy} className="flex-1 border border-gray-400 py-3 rounded-lg font-medium disabled:opacity-50">가져오기</button>
              <button onClick={handleSubmit} disabled={busy} className="flex-1 bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-50">제출</button>
            </div>
          )}
          {st.status === 'PLAYING' && !st.isMyTurn && (
            <p className="text-center text-gray-500 text-sm py-2">{st.players[st.currentSeat - 1]?.nick}님의 차례입니다.</p>
          )}
          {error && <p className="text-red-500 text-sm text-center">{error}</p>}
        </div>
      )}

      {adminFooter}
    </main>
  );
}
