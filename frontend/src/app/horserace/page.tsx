'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

type Phase = 'NULL_ROOM' | 'LOBBY' | 'BETTING' | 'RACING' | 'RESULT' | 'ENDED';
type HorseView = {
  index: number; name: string; emoji: string; condition: number; style: string;
  formLine: number[]; streak: number; isNew: boolean; oddsWin: number; oddsPlace: number; lane: number;
};
type PlayerView = { seat: number; nick: string; chips: number; bot: boolean; account: boolean; host: boolean };
type BetView = { type: string; picks: number[]; amount: number };
type RaceView = { timeline: number[][]; finishOrder: number[]; raceStartAt: number; tickMs: number; finishDist: number; eventLog: string[] };
type HrState = {
  status: Phase; serverNow: number; raceType: string; oddsMode: string; round: number;
  isHost: boolean; joined: boolean; seat: number; nick: string | null; isAccount: boolean; chips: number; canBonus: boolean;
  betEndsAt: number; betSec: number; horses: HorseView[]; players: PlayerView[]; myBets: BetView[]; race: RaceView | null;
  exactaOdds: Record<string, number>; trioOdds: Record<string, number>;
  finishOrder: number[]; myLastNet: number; buyIn: number; horseCount: number; playerCount: number; totalPool: number; version: number;
};
type Account = { token: string; accountId: number; nickname: string; balance: number; peakBalance: number; totalRaces: number; wins: number };
type RoomSummary = { code: string; status: string; playerCount: number; host: string };

const CID_KEY = 'horserace_client_id', TOK_KEY = 'horserace_token', NICK_KEY = 'horserace_nick', ROOM_KEY = 'horserace_room';
const LANE_COLORS = ['#ef4444', '#3b82f6', '#22c55e', '#eab308', '#a855f7', '#ec4899', '#14b8a6', '#f97316', '#64748b', '#84cc16', '#06b6d4', '#f43f5e'];
const STYLE_LABEL: Record<string, string> = { FRONT: '🏃선행', CLOSER: '🐆추입', EVEN: '⚖️평준' };
const won = (n: number) => n.toLocaleString();

function getClientId(): string {
  if (typeof window === 'undefined') return '';
  try {
    let id = localStorage.getItem(CID_KEY) || '';
    if (!id) { id = (crypto?.randomUUID?.() ?? `c_${Date.now()}_${Math.random().toString(36).slice(2)}`); localStorage.setItem(CID_KEY, id); }
    return id;
  } catch { return `c_${Math.random().toString(36).slice(2)}`; }
}

function stars(n: number) { return '★'.repeat(n) + '☆'.repeat(5 - n); }

/** 서버 타임라인을 서버시계 기준으로 로컬 재생하는 캔버스. */
function RaceCanvas({ race, offsetRef }: { race: RaceView; offsetRef: React.MutableRefObject<number> }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    let raf = 0;
    const n = race.timeline[0]?.length ?? 0;
    const draw = () => {
      const cv = ref.current; if (!cv) { raf = requestAnimationFrame(draw); return; }
      const ctx = cv.getContext('2d'); if (!ctx) return;
      const W = cv.width, H = cv.height, padL = 44, padR = 30, laneH = H / n;
      const now = Date.now() + offsetRef.current;
      const T = race.timeline.length;
      let f = Math.max(0, (now - race.raceStartAt) / race.tickMs);
      const done = f >= T - 1;
      if (f > T - 1) f = T - 1;
      const f0 = Math.floor(f), f1 = Math.min(T - 1, f0 + 1), fr = f - f0;

      // 트랙
      ctx.fillStyle = '#15803d'; ctx.fillRect(0, 0, W, H);
      for (let i = 0; i < n; i++) {
        ctx.fillStyle = i % 2 === 0 ? 'rgba(255,255,255,0.04)' : 'rgba(0,0,0,0.06)';
        ctx.fillRect(0, i * laneH, W, laneH);
      }
      // 결승선(체크무늬)
      const fx = W - padR;
      for (let y = 0; y < H; y += 10) { ctx.fillStyle = (Math.floor(y / 10) % 2 === 0) ? '#fff' : '#111'; ctx.fillRect(fx, y, 8, 10); }
      // 게이트
      ctx.fillStyle = 'rgba(255,255,255,0.25)'; ctx.fillRect(padL - 4, 0, 3, H);

      // 현재 순위(거리) 계산
      const pos = new Array(n).fill(0).map((_, i) => {
        const a = race.timeline[f0][i], b = race.timeline[f1][i];
        return a + (b - a) * fr;
      });
      const order = pos.map((p, i) => [p, i]).sort((x, y) => y[0] - x[0]).map((z) => z[1]);
      const leader = order[0];

      for (let i = 0; i < n; i++) {
        const frac = Math.min(1, pos[i] / race.finishDist);
        const x = padL + frac * (fx - padL);
        const y = i * laneH + laneH / 2;
        // 레인 번호
        ctx.fillStyle = LANE_COLORS[i % LANE_COLORS.length];
        ctx.beginPath(); ctx.arc(20, y, 11, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = '#fff'; ctx.font = 'bold 12px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
        ctx.fillText(String(i + 1), 20, y);
        // 선두 글로우
        if (i === leader && !done) { ctx.beginPath(); ctx.arc(x, y, 16, 0, Math.PI * 2); ctx.fillStyle = 'rgba(253,224,71,0.35)'; ctx.fill(); }
        // 말
        ctx.font = '22px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
        ctx.save(); ctx.translate(x, y); ctx.scale(-1, 1); ctx.fillText('🏇', 0, 0); ctx.restore();
      }
      raf = requestAnimationFrame(draw);
    };
    draw();
    return () => cancelAnimationFrame(raf);
  }, [race, offsetRef]);
  const n = race.timeline[0]?.length ?? 9;
  return <canvas ref={ref} width={800} height={n * 46} className="w-full rounded-lg shadow-inner" style={{ imageRendering: 'auto' }} />;
}

export default function HorseRacePage() {
  const [clientId, setClientId] = useState('');
  const [account, setAccount] = useState<Account | null>(null);
  const [st, setSt] = useState<HrState | null>(null);
  const [rooms, setRooms] = useState<RoomSummary[]>([]);
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const roomRef = useRef<string | null>(null);
  const cidRef = useRef('');
  const offsetRef = useRef(0);
  const endsAtRef = useRef(0);

  const [nick, setNick] = useState('');
  const [showLogin, setShowLogin] = useState(false);
  const [pw, setPw] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [raceType, setRaceType] = useState<'BASIC' | 'SPECIAL'>('BASIC');
  const [oddsMode, setOddsMode] = useState<'FIXED' | 'PARIMUTUEL'>('FIXED');
  const [buyIn, setBuyIn] = useState(5000);
  const [betSec, setBetSec] = useState(25);
  const [remaining, setRemaining] = useState(0);
  const [showBoard, setShowBoard] = useState(false);
  const [board, setBoard] = useState<Account[]>([]);
  const [showGuide, setShowGuide] = useState(false);
  const [showAdmin, setShowAdmin] = useState(false);
  const [adminCode, setAdminCode] = useState('');
  const [adminList, setAdminList] = useState<{ nickname: string; balance: number; peak: number; races: number; wins: number }[]>([]);
  const [adminAmt, setAdminAmt] = useState(10000);
  const [adminErr, setAdminErr] = useState<string | null>(null);

  // 배팅 슬립
  const [betType, setBetType] = useState<'WIN' | 'PLACE' | 'EXACTA' | 'TRIO'>('WIN');
  const [picks, setPicks] = useState<number[]>([]);
  const [betAmt, setBetAmt] = useState(100);
  const needPicks = betType === 'EXACTA' ? 2 : betType === 'TRIO' ? 3 : 1;
  const changeBetType = (t: 'WIN' | 'PLACE' | 'EXACTA' | 'TRIO') => { setBetType(t); setPicks([]); };
  const clickHorse = (i: number) => {
    setPicks((cur) => {
      if (betType === 'WIN' || betType === 'PLACE') return cur[0] === i ? [] : [i];
      if (cur.includes(i)) return cur.filter((x) => x !== i);
      return cur.length < needPicks ? [...cur, i] : cur;
    });
  };

  const cid = () => encodeURIComponent(clientId);
  const rp = () => `roomCode=${roomCode}&clientId=${cid()}`;

  const changeRoom = useCallback((code: string | null) => {
    roomRef.current = code; setRoomCode(code);
    try { if (code) localStorage.setItem(ROOM_KEY, code); else localStorage.removeItem(ROOM_KEY); } catch {}
  }, []);

  const poll = useCallback(async () => {
    const code = roomRef.current, c = cidRef.current;
    if (!code) { try { setRooms(await api<RoomSummary[]>('/api/v1/horserace/rooms')); } catch {} return; }
    try {
      const res = await api<HrState>(`/api/v1/horserace/me?roomCode=${code}&clientId=${encodeURIComponent(c)}`);
      if (res.status === 'NULL_ROOM') { changeRoom(null); setSt(null); }
      else { offsetRef.current = res.serverNow - Date.now(); endsAtRef.current = res.betEndsAt; setSt(res); }
    } catch {}
  }, [changeRoom]);

  useEffect(() => {
    const id = getClientId(); setClientId(id); cidRef.current = id;
    try {
      setNick(localStorage.getItem(NICK_KEY) || '');
      const saved = localStorage.getItem(ROOM_KEY); if (saved) { roomRef.current = saved; setRoomCode(saved); }
      const tok = localStorage.getItem(TOK_KEY);
      if (tok) api<Account>(`/api/v1/horserace/account/me?token=${tok}`).then((a) => setAccount({ ...a, token: tok })).catch(() => { try { localStorage.removeItem(TOK_KEY); } catch {} });
    } catch {}
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
    try {
      const res = await api<HrState>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined });
      setSt(res); endsAtRef.current = res.betEndsAt; offsetRef.current = res.serverNow - Date.now();
      if (res.isAccount) setAccount((a) => a ? { ...a, balance: res.chips } : a);
      return res;
    } catch (e) { setError(e instanceof Error ? e.message : '오류가 발생했습니다'); return null; }
    finally { setBusy(false); }
  }, []);

  const saveNick = (n: string) => { try { localStorage.setItem(NICK_KEY, n); } catch {} };

  const doAuth = async () => {
    const n = nick.trim(); if (!n) return setError('닉네임을 입력하세요');
    if (pw.length < 4) return setError('암호는 4자 이상');
    setBusy(true); setError(null);
    try {
      const a = await api<Account & { created: boolean }>(`/api/v1/horserace/account/auth`, { method: 'POST', body: JSON.stringify({ nickname: n, password: pw }) });
      try { localStorage.setItem(TOK_KEY, a.token); } catch {}
      setAccount(a); saveNick(n); setShowLogin(false); setPw('');
    } catch (e) { setError(e instanceof Error ? e.message : '로그인 실패'); } finally { setBusy(false); }
  };
  const logout = () => { try { localStorage.removeItem(TOK_KEY); } catch {} setAccount(null); };

  const loadBoard = async () => { try { setBoard(await api<Account[]>(`/api/v1/horserace/leaderboard`)); setShowBoard(true); } catch {} };

  type AdminAcc = { nickname: string; balance: number; peak: number; races: number; wins: number };
  const loadAdmin = async () => {
    setAdminErr(null);
    try { setAdminList(await api<AdminAcc[]>(`/api/v1/horserace/admin/accounts?code=${encodeURIComponent(adminCode)}`)); }
    catch (e) { setAdminErr(e instanceof Error ? e.message : '조회 실패'); }
  };
  const adminGrant = async (nick: string, amt: number) => {
    setAdminErr(null);
    try { await api(`/api/v1/horserace/admin/grant?code=${encodeURIComponent(adminCode)}&nickname=${encodeURIComponent(nick)}&amount=${amt}`, { method: 'POST' }); loadAdmin(); }
    catch (e) { setAdminErr(e instanceof Error ? e.message : '지급 실패'); }
  };
  const adminDelete = async (nick: string) => {
    if (!confirm(`${nick} 계정을 삭제할까요? (되돌릴 수 없음)`)) return;
    setAdminErr(null);
    try { await api(`/api/v1/horserace/admin/delete?code=${encodeURIComponent(adminCode)}&nickname=${encodeURIComponent(nick)}`, { method: 'POST' }); loadAdmin(); }
    catch (e) { setAdminErr(e instanceof Error ? e.message : '삭제 실패'); }
  };

  const handleCreate = async () => {
    const n = (account?.nickname || nick).trim(); if (!n) return setError('닉네임을 입력하세요');
    saveNick(n); setBusy(true); setError(null);
    try {
      const res = await api<{ roomCode: string; state: HrState }>(`/api/v1/horserace/new?clientId=${cid()}`,
        { method: 'POST', body: JSON.stringify({ nick: n, token: account?.token ?? null, raceType, oddsMode, buyIn, betSec, horseCount: 9, autoEndRounds: 0 }) });
      changeRoom(res.roomCode); setSt(res.state); setShowCreate(false);
    } catch (e) { setError(e instanceof Error ? e.message : '방 생성 실패'); } finally { setBusy(false); }
  };
  const handleJoin = async (code: string) => {
    const n = (account?.nickname || nick).trim(); if (!n) return setError('닉네임을 입력하세요');
    saveNick(n); changeRoom(code);
    await post(`/api/v1/horserace/join?roomCode=${code}&clientId=${cid()}`, { nick: n, token: account?.token ?? null });
  };
  const handleAddBot = () => post(`/api/v1/horserace/add-bot?${rp()}`);
  const handleStart = () => post(`/api/v1/horserace/start?${rp()}`);
  const handleBet = async () => {
    if (picks.length !== needPicks) return setError(`말 ${needPicks}마리를 선택하세요`);
    const amount = Math.floor(betAmt / 10) * 10; // 10칩 단위로 맞춤
    if (amount < 10) return setError('최소 10칩부터 배팅할 수 있어요');
    const r = await post(`/api/v1/horserace/bet?${rp()}`, { type: betType, picks, amount });
    if (r) { setError(null); setPicks([]); }
  };
  const handleNext = () => post(`/api/v1/horserace/next-race?${rp()}`);
  const handleEnd = () => post(`/api/v1/horserace/end?${rp()}`);
  const handleLeave = () => { api(`/api/v1/horserace/leave?${rp()}`, { method: 'POST' }).catch(() => {}); changeRoom(null); setSt(null); };
  const handleBonus = async () => {
    if (!account) return;
    try { const bal = await api<number>(`/api/v1/horserace/account/bonus?token=${account.token}&roomCode=${roomCode ?? ''}`, { method: 'POST' }); setAccount({ ...account, balance: bal }); poll(); }
    catch (e) { setError(e instanceof Error ? e.message : '보너스 실패'); }
  };

  const guidePanel = showGuide && (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/50" onClick={() => setShowGuide(false)}>
      <div onClick={(e) => e.stopPropagation()}
        className="w-full sm:max-w-md bg-white rounded-t-2xl sm:rounded-2xl max-h-[90vh] sm:max-h-[85vh] overflow-y-auto shadow-xl">
        {/* 헤더(고정) */}
        <div className="sticky top-0 bg-white/95 backdrop-blur border-b border-gray-100 px-5 py-3.5 flex items-center justify-between">
          <h2 className="text-lg font-extrabold">🏇 경마 가이드</h2>
          <button onClick={() => setShowGuide(false)} className="w-9 h-9 rounded-full bg-gray-100 text-gray-500 font-bold text-lg flex items-center justify-center active:scale-95">✕</button>
        </div>

        <div className="px-5 py-4 space-y-6 text-[15px] leading-relaxed text-gray-700">
          {/* 한 줄 요약 */}
          <p className="bg-amber-50 text-amber-800 rounded-xl px-4 py-3 text-sm">말에 <b>가상 칩</b>을 걸고, 예상한 착순을 맞히면 <b>배당만큼</b> 칩을 따는 게임이에요. (놀이용 · 실제 환전 없음)</p>

          {/* 컨디션 */}
          <section>
            <h3 className="text-base font-bold mb-1.5 flex items-center gap-1.5">⭐ 컨디션 <span className="text-amber-500 text-lg tracking-tight">★★★★☆</span></h3>
            <p>말의 <b>오늘 실력</b>(★1~5). 높을수록 강하고 <b>배당이 낮아요</b>. 단 운이 크게 작용해서 <b className="text-hit">★5도 절반 이상 집니다</b> — 언더독에 걸어 대박을 노릴 수도 있어요.</p>
          </section>

          {/* 주행 스타일 */}
          <section>
            <h3 className="text-base font-bold mb-2">🏇 주행 스타일 (각질)</h3>
            <div className="space-y-2">
              {[['🏃', '선행', '초반에 빠르지만 후반에 지칠 수 있어요'], ['🐆', '추입', '초반엔 느리지만 막판에 치고 나와요'], ['⚖️', '평준', '처음부터 끝까지 고르게 달려요']].map(([ic, name, desc]) => (
                <div key={name} className="flex items-start gap-3 bg-gray-50 rounded-xl px-3.5 py-2.5">
                  <span className="text-2xl leading-none">{ic}</span>
                  <span><b className="text-[15px]">{name}</b><span className="block text-sm text-gray-500">{desc}</span></span>
                </div>
              ))}
            </div>
          </section>

          {/* 배팅 권종 */}
          <section>
            <h3 className="text-base font-bold mb-2">💰 배팅 권종</h3>
            <div className="space-y-2">
              {[['단승', '고른 말이 1등', '기본', 'border-blue-400'], ['연승', '고른 말이 3등 안', '안전·낮은 배당', 'border-green-400'], ['쌍승', '말 2개 → 1·2등을 순서대로', '고배당', 'border-orange-400'], ['삼복승', '말 3개 → 1·2·3등 (순서 무관)', '초고배당', 'border-rose-400']].map(([name, cond, tag, bc]) => (
                <div key={name} className={`border-l-4 ${bc} bg-gray-50 rounded-r-xl px-3.5 py-2.5`}>
                  <div className="flex items-center justify-between"><b className="text-[15px]">{name}</b><span className="text-xs text-gray-400">{tag}</span></div>
                  <span className="text-sm text-gray-500">{cond}</span>
                </div>
              ))}
            </div>
            <p className="text-sm text-gray-500 mt-2">적중 시 <b>배팅액 × 배당</b>. 예) 배당 5.0에 100칩 → <b className="text-hit">500칩</b></p>
          </section>

          {/* 배당 방식 */}
          <section>
            <h3 className="text-base font-bold mb-2">📊 배당 방식 (방 생성 시 선택)</h3>
            <div className="space-y-2 text-sm">
              <div className="bg-gray-50 rounded-xl px-3.5 py-2.5"><b>🎯 고정 배당</b><span className="block text-gray-500">말 실력으로 배당이 정해져 배팅해도 안 변해요. 언더독 대박이 매력.</span></div>
              <div className="bg-gray-50 rounded-xl px-3.5 py-2.5"><b>🌊 펀드풀</b><span className="block text-gray-500">모두의 판돈이 모여 <b>배당이 실시간 변동</b>해요. 인기 없는 말에 미리 걸면 배당이 높아 유리 — 눈치싸움!</span></div>
            </div>
          </section>

          {/* 경마 종류 */}
          <section>
            <h3 className="text-base font-bold mb-2">🏁 경마 종류</h3>
            <div className="space-y-2 text-sm">
              <div className="bg-gray-50 rounded-xl px-3.5 py-2.5"><b>🏇 기본경마</b><span className="block text-gray-500">정통 경마. 컨디션·각질이 정직하게 반영돼요.</span></div>
              <div className="bg-purple-50 rounded-xl px-3.5 py-2.5"><b>🎪 특수경마</b><span className="block text-gray-500">레이스 중 랜덤 이벤트로 대혼돈! 🔙뒷걸음질 🍌미끄덩 🚀부스터 😴낮잠 🌀워프 🐢역전 — 언더독도 잘 이겨요.</span></div>
            </div>
          </section>

          {/* 말 이력 */}
          <section>
            <h3 className="text-base font-bold mb-2">📋 말 이력 보는 법</h3>
            <div className="space-y-1.5 text-sm">
              <p><span className="inline-block bg-gray-100 rounded px-2 py-0.5 font-bold">🆕신입</span> 이 방에서 처음 출전한 말</p>
              <p><span className="inline-block bg-gray-100 rounded px-2 py-0.5 font-bold">1-2-1</span> 최근 착순 기록(왼쪽이 최근)</p>
              <p><span className="inline-block bg-orange-100 text-orange-600 rounded px-2 py-0.5 font-bold">🔥3</span> 연속 입상(3등 내). 셀수록 강해서 <b>배당에 반영</b>돼요</p>
            </div>
          </section>

          {/* 흐름 */}
          <section>
            <h3 className="text-base font-bold mb-2">🔄 게임 흐름</h3>
            <p className="text-sm">매 레이스 <b>9마리</b> 출전 → 배팅(제한시간) → 레이스 → 정산. 말은 매 판 새로 나오고, <b>직전 1~3등은 다음 판에 다시 출전</b>해서 연승을 쌓아요. 말 이름·이모지는 그냥 이름이에요(성능과 무관).</p>
          </section>

          {/* 계정 */}
          <section>
            <h3 className="text-base font-bold mb-2">🔒 계정 / 게스트</h3>
            <p className="text-sm"><b>로그인(닉+암호)</b> 하면 잔고가 저장돼 부자 랭킹에 도전할 수 있어요. <b>게스트</b>는 그 방에서만 쓰는 칩이에요. 파산하면 하루 3번까지 <b>재기 보너스</b>를 받을 수 있어요.</p>
          </section>
        </div>
        <div className="px-5 pb-5 pt-1">
          <button onClick={() => setShowGuide(false)} className="w-full bg-hit text-white font-bold py-3 rounded-xl active:scale-[0.99]">확인했어요</button>
        </div>
      </div>
    </div>
  );

  // ============ 방 목록 화면 ============
  if (!roomCode) {
    return (
      <main className="min-h-screen flex flex-col items-center p-6 max-w-lg mx-auto w-full">
        <div className="w-full flex items-center justify-between mb-1">
          <h1 className="text-2xl font-bold">🏇 경마</h1>
          <button onClick={() => setShowGuide((v) => !v)} className="text-sm font-bold text-hit">📖 규칙</button>
        </div>
        <p className="text-[11px] text-gray-400 self-start mb-4">놀이용 가상 칩 · 실제 환전 아님</p>
        {guidePanel}

        {/* 계정 바 */}
        <div className="w-full mb-4 rounded-xl border border-gray-200 p-3 flex items-center justify-between">
          {account ? (
            <>
              <div className="text-sm"><b>🏇 {account.nickname}</b><span className="ml-2 text-amber-600 font-bold">💰 {won(account.balance)}</span><span className="block text-[11px] text-gray-400">{account.totalRaces}전 {account.wins}승 · 최고 {won(account.peakBalance)}</span></div>
              <button onClick={logout} className="text-xs text-gray-400 underline">로그아웃</button>
            </>
          ) : (
            <>
              <div className="text-sm text-gray-500">게스트 모드 <span className="block text-[11px] text-gray-400">로그인하면 잔고가 저장돼요</span></div>
              <button onClick={() => { setShowLogin(true); setError(null); }} className="bg-hit text-white text-sm font-bold px-3 py-2 rounded-lg">로그인 / 가입</button>
            </>
          )}
        </div>

        {showLogin && (
          <div className="w-full mb-4 rounded-xl border-2 border-hit/40 p-4 space-y-2">
            <p className="text-sm font-bold">경마 계정 (닉+암호)</p>
            <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임" className="w-full border border-gray-300 rounded-lg px-3 py-2" />
            <input type="password" value={pw} onChange={(e) => setPw(e.target.value)} placeholder="암호(4자 이상)" className="w-full border border-gray-300 rounded-lg px-3 py-2" />
            <p className="text-[11px] text-gray-400">처음 쓰는 닉네임이면 가입, 기존 닉네임이면 로그인. 암호 분실 시 복구 불가.</p>
            <div className="flex gap-2">
              <button onClick={() => setShowLogin(false)} className="flex-1 border border-gray-300 py-2 rounded-lg text-sm">취소</button>
              <button onClick={doAuth} disabled={busy} className="flex-[2] bg-hit text-white font-bold py-2 rounded-lg text-sm disabled:opacity-40">시작</button>
            </div>
          </div>
        )}

        {showCreate ? (
          <div className="w-full space-y-4">
            {!account && <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="내 닉네임(게스트)" className="w-full border border-gray-300 rounded-lg px-3 py-2" />}
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">경마 종류</p>
              <div className="grid grid-cols-2 gap-2">
                <button onClick={() => setRaceType('BASIC')} className={`py-3 rounded-lg border-2 text-sm font-bold ${raceType === 'BASIC' ? 'border-hit bg-hit/5 text-hit' : 'border-gray-200 text-gray-500'}`}>🏇 기본경마<span className="block text-[10px] font-normal">정통 경마</span></button>
                <button onClick={() => setRaceType('SPECIAL')} className={`py-3 rounded-lg border-2 text-sm font-bold ${raceType === 'SPECIAL' ? 'border-hit bg-hit/5 text-hit' : 'border-gray-200 text-gray-500'}`}>🎪 특수경마<span className="block text-[10px] font-normal">이벤트로 대혼돈</span></button>
              </div>
            </div>
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">배당 방식</p>
              <div className="grid grid-cols-2 gap-2">
                {([['FIXED', '🎯 고정 배당', '말 실력으로 배당 고정'], ['PARIMUTUEL', '🌊 펀드풀', '판돈 몰릴수록 배당 변동']] as const).map(([m, label, desc]) => (
                  <button key={m} onClick={() => setOddsMode(m)} className={`py-2.5 rounded-lg border-2 text-sm font-bold ${oddsMode === m ? 'border-hit bg-hit/5 text-hit' : 'border-gray-200 text-gray-500'}`}>{label}<span className="block text-[10px] font-normal">{desc}</span></button>
                ))}
              </div>
            </div>
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">게스트 시작 칩</p>
              <div className="grid grid-cols-4 gap-1">{[3000, 5000, 10000, 30000].map((s) => (<button key={s} onClick={() => setBuyIn(s)} className={`py-2 rounded-lg border text-xs ${buyIn === s ? 'border-hit bg-hit/5 text-hit font-bold' : 'border-gray-200 text-gray-500'}`}>{won(s)}</button>))}</div>
              <p className="text-[11px] text-gray-400 mt-1">계정은 자기 잔고로 플레이(이 설정은 게스트만)</p>
            </div>
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">배팅 제한시간</p>
              <div className="grid grid-cols-4 gap-1">{[15, 20, 25, 40].map((s) => (<button key={s} onClick={() => setBetSec(s)} className={`py-2 rounded-lg border text-sm ${betSec === s ? 'border-hit bg-hit/5 text-hit font-bold' : 'border-gray-200 text-gray-500'}`}>{s}초</button>))}</div>
            </div>
            <div className="flex gap-2">
              <button onClick={() => setShowCreate(false)} className="flex-1 border border-gray-300 py-3 rounded-lg">취소</button>
              <button onClick={handleCreate} disabled={busy} className="flex-[2] bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">방 만들기</button>
            </div>
          </div>
        ) : (
          <div className="w-full space-y-3">
            <div className="flex gap-2">
              <button onClick={() => { setShowCreate(true); setError(null); }} className="flex-1 bg-hit text-white font-bold py-3 rounded-lg hover:opacity-90">+ 새 방 만들기</button>
              <button onClick={loadBoard} className="px-4 border border-gray-300 rounded-lg text-sm font-bold">🏆 부자 랭킹</button>
            </div>
            {showBoard && (
              <div className="rounded-xl border border-gray-200 p-3">
                <div className="flex justify-between items-center mb-2"><p className="font-bold text-sm">🏆 부자 랭킹</p><button onClick={() => setShowBoard(false)} className="text-xs text-gray-400">닫기 ✕</button></div>
                {board.length === 0 ? <p className="text-gray-400 text-xs text-center py-3">아직 없어요</p> : board.map((a, i) => (
                  <div key={a.nickname} className="flex justify-between text-sm py-0.5"><span>{i + 1}. {a.nickname}</span><b className="text-amber-600">{won(a.balance)}</b></div>
                ))}
              </div>
            )}
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
                    {r.status === 'ENDED' ? <span className="text-sm text-gray-300">종료</span> : <button onClick={() => handleJoin(r.code)} className="text-sm font-bold text-hit">{r.status === 'WAITING' ? '참가' : '입장'}</button>}
                  </div>
                </div>
              );
            })}
          </div>
        )}
        {error && <p className="text-red-500 text-sm mt-4 text-center">{error}</p>}

        {/* 관리자: 계정 관리 */}
        <div className="w-full mt-8 pt-4 border-t border-gray-100">
          {showAdmin ? (
            <div className="space-y-2">
              <div className="flex items-center justify-between"><p className="font-bold text-sm">🔒 계정 관리 (관리자)</p><button onClick={() => setShowAdmin(false)} className="text-xs text-gray-400">닫기 ✕</button></div>
              <div className="flex gap-2">
                <input type="password" value={adminCode} onChange={(e) => setAdminCode(e.target.value)} placeholder="관리자 코드" className="flex-1 border border-gray-300 rounded-lg px-3 py-2 text-sm" />
                <button onClick={loadAdmin} className="bg-gray-700 text-white text-sm px-4 rounded-lg font-bold">조회</button>
              </div>
              <div className="flex items-center gap-2 text-xs text-gray-500">
                <span>지급/차감액</span>
                <input type="number" value={adminAmt} onChange={(e) => setAdminAmt(Math.round(+e.target.value || 0))} className="w-28 border border-gray-300 rounded px-2 py-1" />
                <span className="text-gray-400">(음수면 차감)</span>
              </div>
              {adminErr && <p className="text-red-500 text-xs">{adminErr}</p>}
              {adminList.map((a) => (
                <div key={a.nickname} className="flex items-center justify-between border border-gray-200 rounded-lg px-3 py-2 text-sm gap-2">
                  <span className="min-w-0 truncate"><b>{a.nickname}</b> <span className="text-amber-600">💰{won(a.balance)}</span> <span className="text-gray-400 text-[11px]">{a.races}전 {a.wins}승</span></span>
                  <span className="flex gap-1 shrink-0">
                    <button onClick={() => adminGrant(a.nickname, adminAmt)} className="bg-green-500 text-white text-xs px-2 py-1 rounded font-bold">지급</button>
                    <button onClick={() => adminDelete(a.nickname)} className="bg-red-500 text-white text-xs px-2 py-1 rounded font-bold">삭제</button>
                  </span>
                </div>
              ))}
            </div>
          ) : (
            <div className="flex justify-center"><button onClick={() => setShowAdmin(true)} className="text-xs text-gray-300 hover:text-gray-500">🔒 관리자</button></div>
          )}
        </div>
      </main>
    );
  }

  if (!st) return <main className="min-h-screen flex items-center justify-center"><p className="text-gray-400">불러오는 중...</p></main>;
  const phase = st.status;
  const sortedPlayers = [...st.players].sort((a, b) => b.chips - a.chips);
  const betOdds = (() => {
    if (picks.length !== needPicks) return 0;
    if (betType === 'WIN') return st.horses[picks[0]]?.oddsWin ?? 0;
    if (betType === 'PLACE') return st.horses[picks[0]]?.oddsPlace ?? 0;
    if (betType === 'EXACTA') return st.exactaOdds[`${picks[0]}-${picks[1]}`] ?? 50;
    return st.trioOdds[[...picks].sort((a, b) => a - b).join('-')] ?? 50;
  })();
  const betAmtEff = Math.floor(betAmt / 10) * 10;
  const potential = Math.round(betAmtEff * betOdds);
  const BET_LABEL: Record<string, string> = { WIN: '단승', PLACE: '연승', EXACTA: '쌍승', TRIO: '삼복승' };
  const pickBadge = (i: number) => {
    const at = picks.indexOf(i);
    if (at < 0) return null;
    if (betType === 'EXACTA') return at === 0 ? '1착' : '2착';
    if (betType === 'TRIO') return '✓';
    return '✓';
  };

  return (
    <main className="min-h-screen flex flex-col items-center p-4 max-w-lg mx-auto w-full">
      <div className="w-full flex items-center justify-between gap-2 mb-2">
        <div className="flex items-center gap-2 min-w-0">
          <h1 className="text-lg font-bold shrink-0">🏇 경마</h1>
          <span className="text-xs bg-gray-100 rounded px-2 py-1 tracking-wider font-bold shrink-0">{roomCode}</span>
          <span className="text-[11px] text-gray-400 shrink-0">{st.round}R</span>
          <button onClick={handleLeave} className="text-xs text-gray-400 underline shrink-0">나가기</button>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <button onClick={() => setShowGuide((v) => !v)} className="text-xs font-bold text-hit">📖 가이드</button>
          <span className="text-sm font-bold text-amber-600">💰 {won(st.chips)}</span>
        </div>
      </div>

      {guidePanel}

      {/* 참가자 칩 */}
      <div className="w-full flex flex-wrap gap-1.5 mb-3 text-[11px]">
        {sortedPlayers.map((p) => (
          <span key={p.seat} className={`rounded-full px-2 py-0.5 ${p.seat === st.seat ? 'bg-hit text-white font-bold' : 'bg-gray-100 text-gray-600'}`}>
            {p.host && '👑'}{p.bot && '🤖'}{p.nick}{p.account && '🔒'} {won(p.chips)}
          </span>
        ))}
      </div>

      {/* 대기방 */}
      {phase === 'LOBBY' && (
        <div className="w-full space-y-3">
          <p className="text-center text-xs text-gray-400">{st.raceType === 'BASIC' ? '기본경마' : '특수경마'} · 9마리 · 배팅 {st.betSec}초</p>
          {st.isHost ? (
            <div className="space-y-2">
              <button onClick={handleAddBot} disabled={busy} className="w-full border-2 border-gray-200 py-2 rounded-lg text-sm font-bold text-gray-600">🤖 봇 배팅자 추가</button>
              <button onClick={handleStart} disabled={busy} className="w-full bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">레이스 시작</button>
            </div>
          ) : <p className="text-center text-gray-500 text-sm">방장이 시작하기를 기다리는 중...</p>}
        </div>
      )}

      {/* 배팅 */}
      {phase === 'BETTING' && (
        <div className="w-full space-y-3">
          <p className="text-center text-sm font-bold">🎯 배팅 <span className="text-hit">{remaining}s</span></p>
          <p className="text-center text-[11px] text-gray-400">
            {st.oddsMode === 'PARIMUTUEL' ? <>🌊 펀드풀 · 실시간 배당(남들이 걸수록 변해요) · 총 판돈 {won(st.totalPool)}</> : <>🎯 고정 배당 · 총 판돈 {won(st.totalPool)}</>}
          </p>

          {/* 권종 탭 */}
          <div className="grid grid-cols-4 gap-1">
            {(['WIN', 'PLACE', 'EXACTA', 'TRIO'] as const).map((t) => (
              <button key={t} onClick={() => changeBetType(t)} className={`py-1.5 rounded-lg border text-xs font-bold ${betType === t ? 'border-hit bg-hit/5 text-hit' : 'border-gray-200 text-gray-400'}`}>{BET_LABEL[t]}</button>
            ))}
          </div>
          <p className="text-center text-[11px] text-gray-400">
            {betType === 'WIN' ? '1등 맞히기 · 각 줄에 단승 배당' : betType === 'PLACE' ? '3등 안에 들면 · 각 줄에 연승 배당' : betType === 'EXACTA' ? '말 2개를 1착→2착 순서로 고르면 배당이 아래에 떠요' : '말 3개를 고르면(순서 무관) 배당이 아래에 떠요'}
          </p>

          <div className="space-y-1.5">
            {st.horses.map((h) => {
              const badge = pickBadge(h.index);
              const sel = badge != null;
              return (
                <button key={h.index} onClick={() => clickHorse(h.index)}
                  className={`w-full flex items-center gap-2 rounded-lg border-2 px-2 py-1.5 text-left ${sel ? 'border-hit bg-hit/5' : 'border-gray-200'}`}>
                  <span className="relative shrink-0">
                    <span className="w-6 h-6 rounded-full flex items-center justify-center text-white text-xs font-bold" style={{ background: LANE_COLORS[h.index % LANE_COLORS.length] }}>{h.index + 1}</span>
                    {badge && <span className="absolute -top-1.5 -right-1.5 bg-hit text-white text-[9px] font-bold rounded-full px-1 leading-tight">{badge}</span>}
                  </span>
                  <span className="text-lg">{h.emoji}</span>
                  <span className="flex-1 min-w-0">
                    <span className="text-sm font-bold">{h.name}</span>
                    <span className="block text-[10px] text-gray-400">
                      <span className="text-amber-500">{stars(h.condition)}</span> {STYLE_LABEL[h.style]}
                      {h.isNew ? ' · 🆕신입' : h.formLine.length > 0 ? ` · ${h.formLine.join('-')}` : ''}{h.streak >= 2 ? ` 🔥${h.streak}` : ''}
                    </span>
                  </span>
                  <span className="text-right shrink-0">
                    <span className="block text-xs font-bold text-hit">단 {h.oddsWin.toFixed(1)}</span>
                    <span className="block text-[10px] text-gray-400">연 {h.oddsPlace.toFixed(1)}</span>
                  </span>
                </button>
              );
            })}
          </div>

          {/* 배팅 슬립 */}
          <div className="rounded-xl border border-gray-200 p-3 space-y-2">
            <div className="flex items-center gap-1">
              <button onClick={() => setBetAmt((a) => Math.max(10, a - 100))} className="w-9 h-9 rounded-lg border border-gray-300 font-bold">−</button>
              <input type="number" inputMode="numeric" value={betAmt === 0 ? '' : betAmt} onChange={(e) => setBetAmt(Math.max(0, Math.floor(Number(e.target.value) || 0)))} className="flex-1 border border-gray-300 rounded-lg px-2 py-2 text-center font-bold" />
              <button onClick={() => setBetAmt((a) => a + 100)} className="w-9 h-9 rounded-lg border border-gray-300 font-bold">+</button>
              <button onClick={() => setBetAmt(Math.floor(st.chips / 10) * 10)} className="px-2 h-9 rounded-lg border border-gray-300 text-xs font-bold">올인</button>
            </div>
            <p className="text-xs text-gray-500 text-center">
              {picks.length > 0 ? `${BET_LABEL[betType]} · ${picks.map((i) => st.horses[i]?.name).join(betType === 'EXACTA' ? ' → ' : ', ')}` : `${BET_LABEL[betType]} · 아래에서 말 ${needPicks}개 선택`}
            </p>
            <div className="rounded-lg bg-hit/5 border border-hit/20 px-3 py-2 text-center">
              {picks.length === needPicks ? (
                <>
                  <span className="text-xs text-gray-500">{BET_LABEL[betType]} 배당 </span>
                  <span className="text-xl font-extrabold text-hit">{betOdds.toFixed(1)}배</span>
                  <span className="block text-xs text-gray-600">적중 시 <b className="text-hit">{won(potential)}</b> 받아요</span>
                </>
              ) : (
                <span className="text-sm text-gray-400">말 <b>{needPicks}개</b>를 고르면 배당이 표시돼요 ({picks.length}/{needPicks})</span>
              )}
            </div>
            {betAmtEff > st.chips && <p className="text-[11px] text-red-500 text-center">보유 칩({won(st.chips)})보다 많이 걸 수 없어요</p>}
            <button onClick={handleBet} disabled={busy || picks.length !== needPicks || betAmtEff < 10 || betAmtEff > st.chips} className="w-full bg-hit text-white font-bold py-2.5 rounded-lg disabled:opacity-40">배팅하기 {picks.length !== needPicks ? `(${picks.length}/${needPicks})` : ''}</button>
          </div>

          {st.myBets.length > 0 && (
            <div className="text-xs text-gray-500">
              <p className="font-bold mb-1">내 배팅</p>
              {st.myBets.map((b, i) => <span key={i} className="inline-block bg-gray-100 rounded px-2 py-0.5 mr-1 mb-1">{BET_LABEL[b.type]} {b.picks.map((h) => st.horses[h]?.name).join(b.type === 'EXACTA' ? '→' : ',')} {won(b.amount)}</span>)}
            </div>
          )}
        </div>
      )}

      {/* 레이스 */}
      {phase === 'RACING' && st.race && (() => {
        const T = st.race.timeline.length;
        const prog = Math.min(1, Math.max(0, (Date.now() + offsetRef.current - st.race.raceStartAt) / (T * st.race.tickMs)));
        const revealed = st.race.eventLog.slice(0, Math.floor(prog * st.race.eventLog.length));
        return (
          <div className="w-full space-y-2">
            <p className="text-center text-sm font-bold text-hit">🏁 레이스 진행 중! {st.raceType === 'SPECIAL' && <span className="text-purple-500">🎪 특수경마</span>}</p>
            <RaceCanvas race={st.race} offsetRef={offsetRef} />
            {st.raceType === 'SPECIAL' && revealed.length > 0 && (
              <div className="rounded-lg bg-purple-50 px-3 py-2 text-xs text-purple-700 space-y-0.5 max-h-24 overflow-y-auto">
                {revealed.slice(-4).map((e, i) => <div key={i}>⚡ {e}</div>)}
              </div>
            )}
            <p className="text-center text-[11px] text-gray-400">결과는 이미 확정 — 모두 같은 화면을 봅니다</p>
          </div>
        );
      })()}

      {/* 결과 */}
      {phase === 'RESULT' && (
        <div className="w-full space-y-3">
          <p className="text-center text-lg font-extrabold">🏆 결과</p>
          <div className="rounded-xl border border-gray-200 p-3">
            {st.finishOrder.slice(0, 3).map((hi, rank) => {
              const h = st.horses[hi];
              return <div key={hi} className="flex items-center gap-2 py-0.5"><span className="text-lg">{['🥇', '🥈', '🥉'][rank]}</span><span className="w-5 h-5 rounded-full flex items-center justify-center text-white text-[10px] font-bold" style={{ background: LANE_COLORS[hi % LANE_COLORS.length] }}>{hi + 1}</span><b className="text-sm">{h?.name}</b><span className="text-[11px] text-gray-400">단 {h?.oddsWin.toFixed(1)}</span></div>;
            })}
          </div>
          <p className={`text-center font-bold ${st.myLastNet > 0 ? 'text-green-600' : st.myLastNet < 0 ? 'text-red-500' : 'text-gray-500'}`}>
            이번 레이스 {st.myLastNet > 0 ? `+${won(st.myLastNet)} 🎉` : st.myLastNet < 0 ? `${won(st.myLastNet)}` : '±0'} · 잔고 {won(st.chips)}
          </p>
          {st.race && st.race.eventLog.length > 0 && (
            <div className="rounded-xl border border-purple-200 bg-purple-50/50 p-3">
              <p className="font-bold text-sm mb-1 text-purple-700">🎪 이번 레이스 사건들</p>
              <div className="text-xs text-purple-700 space-y-0.5 max-h-32 overflow-y-auto">
                {st.race.eventLog.map((e, i) => <div key={i}>⚡ {e}</div>)}
              </div>
            </div>
          )}
          {st.canBonus && account && (
            <button onClick={handleBonus} className="w-full bg-amber-500 text-white font-bold py-2.5 rounded-lg">💸 파산! 재기 보너스 받기 (하루 3회)</button>
          )}
          {st.isHost ? (
            <div className="flex gap-2">
              <button onClick={handleEnd} disabled={busy} className="flex-1 border border-gray-300 py-3 rounded-lg font-bold text-gray-500">종료</button>
              <button onClick={handleNext} disabled={busy} className="flex-[2] bg-hit text-white font-bold py-3 rounded-lg">다음 레이스 ▶</button>
            </div>
          ) : <p className="text-center text-gray-500 text-sm">방장이 다음 레이스를 준비 중...</p>}
        </div>
      )}

      {phase === 'ENDED' && (
        <div className="w-full space-y-3">
          <p className="text-center text-xl font-extrabold">🏁 게임 종료</p>
          <div className="rounded-xl border border-gray-200 p-3">
            <p className="font-bold text-sm mb-1">최종 칩</p>
            {sortedPlayers.map((p, i) => <div key={p.seat} className="flex justify-between text-sm"><span>{i + 1}. {p.bot && '🤖'}{p.nick}</span><b className="text-amber-600">{won(p.chips)}</b></div>)}
          </div>
          <button onClick={handleLeave} className="w-full bg-hit text-white font-bold py-3 rounded-lg">나가기</button>
        </div>
      )}

      {error && <p className="text-red-500 text-sm mt-3 text-center">{error}</p>}
      <RoomChat game="horserace" roomCode={roomCode} clientId={clientId} nick={nick} />
    </main>
  );
}
