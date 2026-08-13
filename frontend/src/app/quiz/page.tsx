'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

type PlayerView = { seat: number; name: string; host: boolean; me: boolean; left: boolean; score: number; correct: number; streak: number; bestStreak: number; answered: boolean; wrong: number; skipped: number; solved: number };
type MyLast = { right: boolean | null; answer: string; explain: string | null };
type Reveal = { round: number; answer: string; explain: string | null; rightSeats: number[]; given: Record<string, string> };
type State = {
  phase: string; mode: string; level: number; totalRounds: number; round: number;
  questionSec: number; limitSec: number;
  isHost: boolean; joined: boolean; players: PlayerView[];
  topic: string | null; kind: string | null; question: string | null;
  choices: string[]; hint: string | null;
  myAnswer: string | null; myAnswerRight: boolean; canAnswer: boolean;
  lastReveal: Reveal | null; myLast: MyLast | null; log: string[]; deadline: number; serverNow: number;
};
type Room = { code: string; status: string; playerCount: number; host: string };
type RankRow = { rank: number; nick: string; correct: number; solved: number; at: number };

function cid(): string {
  if (typeof window === 'undefined') return '';
  let id = localStorage.getItem('quiz_client_id');
  if (!id) { id = Math.random().toString(36).slice(2) + Date.now().toString(36); localStorage.setItem('quiz_client_id', id); }
  return id;
}

const SEAT_COLORS = ['#6366f1', '#f43f5e', '#10b981', '#f59e0b', '#8b5cf6', '#ec4899', '#14b8a6', '#f97316', '#3b82f6', '#84cc16'];
const CHOICE_LABEL = ['A', 'B', 'C', 'D'];

/** 난이도 1~10을 말로. 방을 만들 때 감을 잡게 해준다. */
const LEVEL_LABEL: Record<number, string> = {
  1: '아주 쉬움', 2: '쉬움', 3: '쉬움', 4: '보통', 5: '보통',
  6: '조금 어려움', 7: '어려움', 8: '어려움', 9: '매우 어려움', 10: '마니아',
};

export default function QuizPage() {
  const id = useRef('');
  const [nick, setNick] = useState('');
  const [screen, setScreen] = useState<'entry' | 'lobby' | 'game'>('entry');
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [level, setLevel] = useState(5);
  const [rounds, setRounds] = useState(10);
  const [questionSec, setQuestionSec] = useState(20);
  const [mode, setMode] = useState<'CLASSIC' | 'SPRINT'>('CLASSIC');
  const [limitSec, setLimitSec] = useState(120);
  const [joinCode, setJoinCode] = useState('');
  const [rooms, setRooms] = useState<Room[]>([]);
  const [ss, setSs] = useState<State | null>(null);
  const [typed, setTyped] = useState('');
  const [busy, setBusy] = useState(false);
  const [aiOn, setAiOn] = useState(true);
  const roomRef = useRef<string | null>(null); roomRef.current = roomCode;
  const syncRef = useRef({ serverNow: 0, at: 0 });
  const [, forceTick] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  // 시간 배틀 순위표(난이도·제한시간별). 방과 무관하게 남는 기록이다.
  const [rankLevel, setRankLevel] = useState(5);
  const [rankLimit, setRankLimit] = useState(120);
  const [ranks, setRanks] = useState<RankRow[]>([]);
  const loadRanks = useCallback(async (lv: number, ls: number) => {
    try { setRanks(await api<RankRow[]>(`/api/v1/quiz/ranks?level=${lv}&limitSec=${ls}&limit=10`)); }
    catch { setRanks([]); }
  }, []);

  useEffect(() => {
    id.current = cid();
    try { setNick(localStorage.getItem('arcade_nick') || ''); } catch {}
    api<boolean>('/api/v1/quiz/ai-available').then(setAiOn).catch(() => {});
  }, []);

  // 순위표는 입장 화면에서 시간 배틀을 골랐을 때, 그리고 배틀이 끝난 뒤에 보인다.
  const rankOpen = (screen === 'entry' && mode === 'SPRINT')
    || (ss?.phase === 'ENDED' && ss?.mode === 'SPRINT');

  // 방을 만들 때 고른 난이도·시간을 순위표 기본값으로 맞춘다.
  useEffect(() => { setRankLevel(level); }, [level]);
  useEffect(() => { setRankLimit(limitSec); }, [limitSec]);

  // 배틀이 끝나면 방금 뛴 난이도·시간의 순위부터 보여준다(내 기록이 들어간 그 판).
  useEffect(() => {
    if (ss?.phase === 'ENDED' && ss?.mode === 'SPRINT') { setRankLevel(ss.level); setRankLimit(ss.limitSec); }
  }, [ss?.phase, ss?.mode, ss?.level, ss?.limitSec]);

  useEffect(() => { if (rankOpen) loadRanks(rankLevel, rankLimit); }, [rankOpen, rankLevel, rankLimit, loadRanks]);

  const loadRooms = useCallback(async () => { try { setRooms(await api(`/api/v1/quiz/rooms`)); } catch {} }, []);
  useEffect(() => { if (screen === 'entry') { loadRooms(); const t = setInterval(loadRooms, 3000); return () => clearInterval(t); } }, [screen, loadRooms]);

  /*
   * 상태 폴링(1초).
   *
   * 끝난 판은 더 바뀔 게 없어 완전히 멈춘다(종료된 방은 서버가 40초 뒤 정리한다).
   *
   * 숨겨진 탭은 볼 사람이 없으니 늦춘다 — 단 아예 멈추면 안 된다. 서버는 "3분간
   * 이 방으로 요청이 없으면 아무도 안 보고 있다"고 판단해 방을 지우므로(RoomRegistry
   * ABANDONED_MS), 잠깐 다른 탭에 다녀오면 방이 사라진다. 그래서 살아 있다는 신호만
   * {@code HIDDEN_POLL_TICKS}초마다 보낸다(호출 97% 감소, 3분 한도 대비 6배 여유).
   */
  const HIDDEN_POLL_TICKS = 30;
  const doneRef = useRef(false);
  useEffect(() => { doneRef.current = false; }, [roomCode]);

  useEffect(() => {
    if (screen === 'entry' || !roomCode) return;
    let alive = true;
    const poll = async () => {
      try {
        const s = await api<State>(`/api/v1/quiz/me?roomCode=${roomCode}&clientId=${id.current}`);
        if (!alive) return;
        syncRef.current = { serverNow: s.serverNow, at: Date.now() };
        setSs(s);
        doneRef.current = s.phase === 'ENDED';
        if (s.phase !== 'LOBBY' && screen === 'lobby') setScreen('game');
        if (s.phase === 'LOBBY' && screen === 'game') setScreen('lobby');
      } catch {}
    };
    let hiddenTicks = 0;
    const tick = () => {
      if (doneRef.current) return;
      if (document.hidden) {
        if (++hiddenTicks < HIDDEN_POLL_TICKS) return;   // 방이 지워지지 않을 만큼만
        hiddenTicks = 0;
      } else {
        hiddenTicks = 0;
      }
      poll();
    };
    poll();
    const t = setInterval(tick, 1000);
    // 탭으로 돌아오면 곧바로 한 번 받아 화면을 맞춘다.
    const onVisible = () => { if (!document.hidden && !doneRef.current) poll(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      alive = false;
      clearInterval(t);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [screen, roomCode]);

  useEffect(() => {
    if (screen !== 'game') return;
    const t = setInterval(() => forceTick((x) => x + 1), 250);
    return () => clearInterval(t);
  }, [screen]);

  // 새 문제가 뜨면 입력칸을 비우고 포커스를 준다(주관식을 바로 타이핑할 수 있게).
  useEffect(() => {
    setTyped('');
    if (ss?.canAnswer && ss.kind === 'TEXT') inputRef.current?.focus();
  }, [ss?.round, ss?.phase, ss?.canAnswer, ss?.kind]);

  const create = async () => {
    const n = nick.trim(); if (!n) return; try { localStorage.setItem('arcade_nick', n); } catch {}
    try {
      const res = await api<{ roomCode: string; state: State }>(`/api/v1/quiz/new?clientId=${id.current}`,
        { method: 'POST', body: JSON.stringify({ nick: n, level, rounds, questionSec, mode, limitSec }) });
      setRoomCode(res.roomCode); setSs(res.state); setScreen('lobby');
    } catch (e: any) { alert(e?.message || '방 생성 실패'); }
  };
  const join = async (code: string, nickOverride?: string) => {
    const n = (nickOverride ?? nick).trim(); if (!n || !code) return; setNick(n);
    try { localStorage.setItem('arcade_nick', n); } catch {}
    try {
      const s = await api<State>(`/api/v1/quiz/join?roomCode=${code}&clientId=${id.current}`,
        { method: 'POST', body: JSON.stringify({ nick: n }) });
      setRoomCode(code.toUpperCase()); setSs(s); setScreen(s.phase === 'LOBBY' ? 'lobby' : 'game');
    } catch (e: any) { alert(e?.message || '참가 실패'); }
  };

  useEffect(() => {
    const j = new URLSearchParams(window.location.search).get('join');
    if (!j) return;
    let n = ''; try { n = (localStorage.getItem('arcade_nick') || '').trim(); } catch {}
    if (!n) return;
    id.current = id.current || cid();
    join(j.toUpperCase(), n);
    try { window.history.replaceState({}, '', '/quiz'); } catch {}
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const startMatch = async () => {
    setBusy(true);
    try { setSs(await api(`/api/v1/quiz/start?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); setScreen('game'); }
    catch (e: any) { alert(e?.message); } finally { setBusy(false); }
  };
  const send = async (value: string) => {
    if (!value.trim() || busy) return;
    setBusy(true);
    try { setSs(await api(`/api/v1/quiz/answer?roomCode=${roomCode}&clientId=${id.current}&value=${encodeURIComponent(value)}`, { method: 'POST', body: '{}' })); }
    catch (e: any) { alert(e?.message); } finally { setBusy(false); }
  };
  const skip = async () => {
    if (busy) return;
    setBusy(true);
    try { setSs(await api(`/api/v1/quiz/skip?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); }
    catch (e: any) { alert(e?.message); } finally { setBusy(false); }
  };
  const leave = async () => {
    const rc = roomRef.current;
    if (rc) { try { await api(`/api/v1/quiz/leave?roomCode=${rc}&clientId=${id.current}`, { method: 'POST', body: '{}' }); } catch {} }
    setScreen('entry'); setRoomCode(null); setSs(null);
  };

  const beaconLeave = () => {
    const rc = roomRef.current;
    if (!rc || !id.current) return;
    try { navigator.sendBeacon(`/api/v1/quiz/leave?roomCode=${rc}&clientId=${encodeURIComponent(id.current)}`); } catch {}
  };
  const home = <Link href="/" aria-label="홈으로" onClick={beaconLeave} className="text-xl leading-none text-slate-500 hover:text-slate-800">🏠</Link>;

  const localNow = syncRef.current.at ? syncRef.current.serverNow + (Date.now() - syncRef.current.at) : (ss?.serverNow ?? 0);
  const remainMs = ss && ss.deadline > 0 ? Math.max(0, ss.deadline - localNow) : 0;
  const remainSec = Math.ceil(remainMs / 1000);
  const sprint = ss?.mode === 'SPRINT';
  const asking = ss?.phase === 'ASKING';
  const revealing = ss?.phase === 'REVEAL';
  const ended = ss?.phase === 'ENDED';
  const barTotal = sprint ? (ss?.limitSec ?? 0) : (ss?.questionSec ?? 0);
  const barPct = ss && asking && barTotal > 0
    ? Math.max(0, Math.min(100, (remainMs / (barTotal * 1000)) * 100)) : 0;

  const active = ss ? ss.players.filter((p) => !p.left) : [];
  const ranked = [...active].sort((a, b) => sprint
    ? (b.correct - a.correct || a.wrong - b.wrong)
    : (b.score - a.score || b.correct - a.correct));
  const rightSet = new Set(ss?.lastReveal?.rightSeats ?? []);

  const limitLabel = (n: number) => (n >= 60 ? `${n / 60}분` : `${n}초`);
  const MEDAL = ['🥇', '🥈', '🥉'];

  /**
   * 시간 배틀 순위표. 난이도 1~10 · 제한시간별로 따로 매긴다.
   *
   * 난이도가 섞이면 순위가 무의미해서(1레벨 30개 vs 10레벨 5개) 조건을 골라 보게 했다.
   */
  const rankBoard = (
    <div className="rounded-2xl border-2 border-rose-200 bg-white overflow-hidden">
      <p className="px-4 py-2.5 bg-rose-50 border-b border-rose-100 text-sm font-extrabold text-rose-700 flex items-center justify-between">
        <span>🏅 시간 배틀 순위</span>
        <span className="text-[11px] font-normal text-rose-400">난이도 {rankLevel} · {limitLabel(rankLimit)}</span>
      </p>
      <div className="px-3 pt-3 space-y-2">
        <div>
          <p className="text-[11px] font-bold text-slate-400 mb-1">난이도</p>
          <div className="grid grid-cols-10 gap-1">
            {Array.from({ length: 10 }, (_, i) => i + 1).map((lv) => (
              <button key={lv} onClick={() => setRankLevel(lv)}
                className={`py-1.5 rounded-md text-xs font-bold border ${rankLevel === lv ? 'border-rose-400 bg-rose-50 text-rose-700' : 'border-slate-200 text-slate-400'}`}>
                {lv}
              </button>
            ))}
          </div>
        </div>
        <div>
          <p className="text-[11px] font-bold text-slate-400 mb-1">제한시간</p>
          <div className="grid grid-cols-4 gap-1">
            {[60, 120, 180, 300].map((n) => (
              <button key={n} onClick={() => setRankLimit(n)}
                className={`py-1.5 rounded-md text-xs font-bold border ${rankLimit === n ? 'border-rose-400 bg-rose-50 text-rose-700' : 'border-slate-200 text-slate-400'}`}>
                {limitLabel(n)}
              </button>
            ))}
          </div>
        </div>
      </div>
      {ranks.length === 0 ? (
        <p className="px-4 py-6 text-center text-xs text-slate-400">아직 기록이 없어요 — 첫 주인공이 되어보세요!</p>
      ) : (
        <div className="mt-3 divide-y divide-slate-50 border-t border-slate-100">
          <p className="px-4 py-1.5 flex items-center justify-between text-[10px] font-bold text-slate-300">
            <span>순위 · 닉네임</span><span>맞힌 개수 / 푼 문제</span>
          </p>
          {ranks.map((r) => (
            <div key={`${r.rank}-${r.nick}-${r.at}`} className="flex items-center justify-between px-4 py-2 text-sm">
              <span className="flex items-center gap-2 min-w-0">
                <span className={`w-6 text-center flex-none font-extrabold ${r.rank <= 3 ? '' : 'text-slate-300 text-xs'}`}>
                  {MEDAL[r.rank - 1] ?? `${r.rank}위`}
                </span>
                <span className="font-bold truncate">{r.nick}</span>
              </span>
              <span className="flex-none">
                <span className="font-extrabold text-rose-600">{r.correct}개</span>
                <span className="text-[11px] text-slate-400 ml-1.5">/ {r.solved}문제</span>
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );

  return (
    <main className="min-h-screen flex flex-col items-center p-3 sm:p-5 max-w-6xl xl:max-w-[1400px] mx-auto w-full text-slate-800">
      <style>{`
        @keyframes qz-pop { 0% { transform: scale(.9); opacity: 0 } 100% { transform: scale(1); opacity: 1 } }
        @keyframes qz-shake { 0%,100% { transform: translateX(0) } 25% { transform: translateX(-6px) } 75% { transform: translateX(6px) } }
      `}</style>

      <div className="w-full flex items-center justify-between mb-3 sm:mb-4">
        <div className="flex items-center gap-2 sm:gap-3">
          {home}
          <div>
            <h1 className="text-xl sm:text-3xl font-extrabold tracking-tight">🧠 상식 퀴즈</h1>
            <p className="hidden sm:block text-xs text-slate-400 -mt-0.5">AI가 매번 새로 출제해요 — 빨리 맞히면 점수가 더!</p>
          </div>
        </div>
        {roomCode && (
          <div className="flex items-center gap-2">
            <span className="hidden sm:inline text-xs font-bold text-slate-400">방 {roomCode}</span>
            <button onClick={leave} className="text-xs px-3 py-1.5 rounded-lg bg-slate-700 text-slate-100 font-bold">나가기</button>
          </div>
        )}
      </div>

      {/* 입장 */}
      {screen === 'entry' && (
        <div className="w-full max-w-md space-y-4">
          <div className="rounded-2xl overflow-hidden border-2 border-indigo-200">
            <div className="bg-gradient-to-br from-indigo-100 via-sky-50 to-emerald-100 px-4 py-5 text-center">
              <p className="text-4xl mb-1">🧠❓</p>
              <p className="text-sm font-bold text-slate-600">역사·동물·나라·과학… 주제도 문제도 매번 새로</p>
              <p className="text-xs text-slate-400 mt-1">혼자서도 바로 시작할 수 있어요</p>
            </div>
          </div>
          {!aiOn && (
            <p className="text-xs text-center text-amber-600 bg-amber-50 border border-amber-200 rounded-xl py-2">
              지금은 AI 출제가 꺼져 있어 준비된 문제로 진행돼요
            </p>
          )}
          <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임"
            className="w-full border-2 border-slate-200 rounded-xl px-4 py-3 focus:outline-none focus:border-indigo-400" />

          <div className="rounded-2xl border-2 border-slate-200 p-3 space-y-3">
            <div>
              <p className="text-sm font-bold text-slate-500 mb-1.5">모드</p>
              <div className="grid grid-cols-2 gap-2">
                <button onClick={() => setMode('CLASSIC')}
                  className={`py-2.5 rounded-lg text-sm font-bold border-2 ${mode === 'CLASSIC' ? 'border-indigo-400 bg-indigo-50 text-indigo-700' : 'border-slate-200 text-slate-500'}`}>
                  📋 문제 수 정하기
                </button>
                <button onClick={() => setMode('SPRINT')}
                  className={`py-2.5 rounded-lg text-sm font-bold border-2 ${mode === 'SPRINT' ? 'border-rose-400 bg-rose-50 text-rose-700' : 'border-slate-200 text-slate-500'}`}>
                  ⚡ 시간 배틀
                </button>
              </div>
              <p className="text-[11px] text-slate-400 mt-1.5">
                {mode === 'CLASSIC'
                  ? '정해진 문제 수를 함께 풀어요. 빨리 맞히면 점수가 더!'
                  : '제한시간 동안 무제한! 답하면 바로 다음 문제 — 많이 맞힌 사람이 이겨요.'}
              </p>
            </div>
            <div>
              <p className="text-sm font-bold text-slate-500 flex items-center justify-between">
                <span>난이도</span>
                <span className="text-indigo-600">{level} · {LEVEL_LABEL[level]}</span>
              </p>
              <input type="range" min={1} max={10} value={level} onChange={(e) => setLevel(Number(e.target.value))}
                className="w-full mt-2 accent-indigo-500" />
              <div className="flex justify-between text-[10px] text-slate-300 px-0.5">
                <span>1</span><span>5</span><span>10</span>
              </div>
            </div>
            {mode === 'CLASSIC' ? (
              <>
                <div>
                  <p className="text-sm font-bold text-slate-500 mb-1.5">문제 수</p>
                  <div className="grid grid-cols-4 gap-2">
                    {[5, 10, 20, 30].map((n) => (
                      <button key={n} onClick={() => setRounds(n)}
                        className={`py-2 rounded-lg text-sm font-bold border-2 ${rounds === n ? 'border-indigo-400 bg-indigo-50 text-indigo-700' : 'border-slate-200 text-slate-500'}`}>
                        {n}
                      </button>
                    ))}
                  </div>
                </div>
                <div>
                  <p className="text-sm font-bold text-slate-500 mb-1.5">문제당 시간</p>
                  <div className="grid grid-cols-4 gap-2">
                    {[10, 15, 20, 30].map((n) => (
                      <button key={n} onClick={() => setQuestionSec(n)}
                        className={`py-2 rounded-lg text-sm font-bold border-2 ${questionSec === n ? 'border-indigo-400 bg-indigo-50 text-indigo-700' : 'border-slate-200 text-slate-500'}`}>
                        {n}초
                      </button>
                    ))}
                  </div>
                </div>
              </>
            ) : (
              <div>
                <p className="text-sm font-bold text-slate-500 mb-1.5">제한시간</p>
                <div className="grid grid-cols-4 gap-2">
                  {[60, 120, 180, 300].map((n) => (
                    <button key={n} onClick={() => setLimitSec(n)}
                      className={`py-2 rounded-lg text-sm font-bold border-2 ${limitSec === n ? 'border-rose-400 bg-rose-50 text-rose-700' : 'border-slate-200 text-slate-500'}`}>
                      {n < 60 ? `${n}초` : `${n / 60}분`}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>

          <button onClick={create} disabled={!nick.trim()}
            className="w-full bg-gradient-to-b from-indigo-500 to-indigo-600 text-white font-extrabold py-3 rounded-xl shadow-md disabled:opacity-40 active:scale-[0.98] transition">
            방 만들기 (혼자 가능)
          </button>

          <div className="flex gap-2">
            <input value={joinCode} onChange={(e) => setJoinCode(e.target.value.toUpperCase())} maxLength={4} placeholder="방 코드"
              className="flex-1 border-2 border-slate-200 rounded-xl px-4 py-3 tracking-[0.3em] font-bold text-center focus:outline-none focus:border-indigo-400" />
            <button onClick={() => join(joinCode)} disabled={!nick.trim() || joinCode.length < 4}
              className="px-5 rounded-xl bg-slate-700 text-white font-bold disabled:opacity-40">참가</button>
          </div>

          {rooms.length > 0 && (
            <div className="rounded-2xl border-2 border-slate-200 divide-y">
              {rooms.map((r) => (
                <button key={r.code} onClick={() => join(r.code)} disabled={!nick.trim()}
                  className="w-full flex items-center justify-between px-4 py-3 text-left hover:bg-slate-50 disabled:opacity-40">
                  <span className="font-bold tracking-widest">{r.code}</span>
                  <span className="text-xs text-slate-400">{r.host} · {r.playerCount}명 · {r.status === 'WAITING' ? '대기중' : '진행중'}</span>
                </button>
              ))}
            </div>
          )}

          {mode === 'SPRINT' && rankBoard}
        </div>
      )}

      {/* 로비 */}
      {screen === 'lobby' && ss && (
        <div className="w-full max-w-md mx-auto space-y-4">
          <div className="text-center rounded-2xl border-2 border-indigo-200 bg-gradient-to-b from-indigo-50 to-white py-4">
            <p className="text-sm text-slate-400">방 코드</p>
            <p className="text-4xl font-extrabold tracking-[0.3em] text-indigo-600 pl-2">{roomCode}</p>
            <p className="text-xs text-slate-400 mt-1">
              {ss.mode === 'SPRINT'
                ? `⚡ 시간 배틀 · 난이도 ${ss.level} · ${ss.limitSec >= 60 ? `${ss.limitSec / 60}분` : `${ss.limitSec}초`} 무제한`
                : `난이도 ${ss.level} · ${ss.totalRounds}문제 · 문제당 ${ss.questionSec}초`}
            </p>
          </div>
          <div className="rounded-2xl border-2 border-slate-200 p-3 space-y-1">
            {ss.players.map((p, i) => (
              <div key={i} className="flex items-center gap-2 text-sm px-2 py-1.5 rounded-lg hover:bg-slate-50">
                <span className="w-3.5 h-3.5 rounded-full border border-white shadow" style={{ background: SEAT_COLORS[i % SEAT_COLORS.length] }} />
                <span className="font-bold">{p.host ? '👑 ' : ''}{p.name}{p.me ? ' (나)' : ''}</span>
              </div>
            ))}
          </div>
          {ss.isHost ? (
            <button onClick={startMatch} disabled={busy}
              className="w-full bg-gradient-to-b from-indigo-500 to-indigo-600 text-white font-extrabold py-3 rounded-xl shadow-md disabled:opacity-40">
              {busy ? '문제 준비 중…' : '퀴즈 시작'}
            </button>
          ) : <p className="text-center text-sm text-slate-400">방장이 시작하기를 기다리는 중…</p>}
          <p className="text-center text-[11px] text-slate-400">문제는 AI가 그때그때 만들어요. 처음 시작할 때 몇 초 걸릴 수 있어요.</p>
        </div>
      )}

      {/* 게임 */}
      {screen === 'game' && ss && (
        <div className="w-full grid grid-cols-1 lg:grid-cols-[1fr_300px] gap-3 sm:gap-4 lg:gap-5 items-start">
          <div className="flex flex-col gap-3 min-w-0">
            {/* 진행 표시 */}
            <div className="flex items-center justify-between text-xs sm:text-sm">
              <span className={`px-3 py-1.5 rounded-full font-bold ${sprint ? 'bg-rose-100 text-rose-800' : 'bg-indigo-100 text-indigo-800'}`}>
                {sprint
                  ? `⚡ ${ended ? '완료' : `${ss.round}번째`} · 난이도 ${ss.level}`
                  : `${ended ? '완료' : `${ss.round} / ${ss.totalRounds}`} · 난이도 ${ss.level}`}
              </span>
              {ss.topic && !ended && (
                <span className="px-3 py-1.5 rounded-full bg-slate-100 text-slate-600 font-bold">{ss.topic}</span>
              )}
              {asking && (
                <span className={`font-extrabold tabular-nums ${remainSec <= 10 ? 'text-rose-500' : 'text-slate-400'}`}>
                  {sprint && remainSec >= 60
                    ? `${Math.floor(remainSec / 60)}:${String(remainSec % 60).padStart(2, '0')}`
                    : `${remainSec}초`}
                </span>
              )}
            </div>

            {asking && (
              <div className="h-1.5 w-full rounded-full bg-slate-100 overflow-hidden">
                <div className={`h-full transition-[width] duration-200 ${barPct <= 25 ? 'bg-rose-400' : 'bg-indigo-400'}`}
                  style={{ width: `${barPct}%` }} />
              </div>
            )}

            {/* 문제 */}
            {!ended && ss.question && (
              <div key={ss.round} className="rounded-2xl border-2 border-slate-200 bg-white p-4 sm:p-6" style={{ animation: 'qz-pop .25s ease' }}>
                <p className="text-lg sm:text-2xl font-extrabold leading-snug">{ss.question}</p>
                {ss.kind === 'TEXT' && ss.hint && (
                  <p className="mt-3 text-sm text-slate-400">
                    힌트 <span className="font-extrabold tracking-widest text-slate-500">{ss.hint}</span>
                    <span className="ml-2 text-[11px] text-indigo-400">주관식 +{50}% 점수</span>
                  </p>
                )}
              </div>
            )}

            {/*
              버튼이 잠겼을 때 이유를 밝힌다. 아무 설명 없이 눌리지 않으면 고장으로 보인다
              ("답 클릭이 안 된다"는 제보를 재현할 수 없었는데, 잠긴 이유가 보이면 바로 안다).
            */}
            {!sprint && !ended && ss.kind && !ss.canAnswer && (
              <p className="text-center text-xs text-slate-400">
                {busy ? '전송 중…'
                  : revealing ? '정답 공개 중 — 곧 다음 문제예요'
                  : ss.myAnswer != null ? '이미 답했어요 — 다음 문제를 기다려요'
                  : !ss.joined ? '관전 중 — 답할 수 없어요'
                  : '지금은 답할 수 없어요'}
              </p>
            )}

            {/* 답 입력 */}
            {!ended && ss.kind === 'CHOICE' && (
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                {ss.choices.map((c, i) => {
                  const mine = ss.myAnswer === String(i);
                  const isAnswer = revealing && ss.lastReveal?.answer === c;
                  return (
                    <button key={i} onClick={() => send(String(i))} disabled={!ss.canAnswer || busy}
                      className={`flex items-center gap-3 rounded-xl border-2 px-4 py-3 text-left font-bold transition
                        ${isAnswer ? 'border-emerald-400 bg-emerald-50'
                          : mine ? (ss.myAnswerRight ? 'border-emerald-400 bg-emerald-50' : 'border-rose-400 bg-rose-50')
                          : 'border-slate-200 bg-white'}
                        ${ss.canAnswer ? 'hover:border-indigo-300 active:scale-[0.99]' : 'opacity-90'}`}>
                      <span className={`w-7 h-7 rounded-lg flex items-center justify-center text-sm flex-none
                        ${isAnswer || (mine && ss.myAnswerRight) ? 'bg-emerald-500 text-white' : mine ? 'bg-rose-500 text-white' : 'bg-slate-100 text-slate-500'}`}>
                        {CHOICE_LABEL[i]}
                      </span>
                      <span className="min-w-0">{c}</span>
                    </button>
                  );
                })}
              </div>
            )}

            {!ended && ss.kind === 'TEXT' && (
              <div className="flex gap-2">
                <input ref={inputRef} value={typed} onChange={(e) => setTyped(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && ss.canAnswer && send(typed)}
                  disabled={!ss.canAnswer || busy} maxLength={40}
                  placeholder={ss.canAnswer ? '답을 입력하고 Enter' : ss.myAnswer ? `제출: ${ss.myAnswer}` : '대기 중…'}
                  className="flex-1 border-2 border-slate-200 rounded-xl px-4 py-3 focus:outline-none focus:border-indigo-400 disabled:bg-slate-50" />
                <button onClick={() => send(typed)} disabled={!ss.canAnswer || busy || !typed.trim()}
                  className="px-5 rounded-xl bg-indigo-500 text-white font-bold disabled:opacity-40">제출</button>
              </div>
            )}

            {/* 스프린트: 직전 문제 결과를 짧게 + 모르는 문제 넘기기 */}
            {sprint && !ended && (
              <div className="flex items-center gap-2">
                <button onClick={skip} disabled={busy || !ss.canAnswer}
                  className="px-4 py-2.5 rounded-xl border-2 border-slate-200 text-slate-500 font-bold text-sm disabled:opacity-40">
                  모르겠어요 ⏭
                </button>
                {ss.myLast && (
                  <p key={ss.round} className="text-sm flex-1 min-w-0 truncate" style={{ animation: 'qz-pop .25s ease' }}>
                    <span className={`font-extrabold ${ss.myLast.right === true ? 'text-emerald-600' : ss.myLast.right === false ? 'text-rose-500' : 'text-slate-400'}`}>
                      {ss.myLast.right === true ? '⭕ 정답' : ss.myLast.right === false ? '❌ 오답' : '⏭ 넘김'}
                    </span>
                    <span className="text-slate-400 ml-1.5">정답: {ss.myLast.answer}</span>
                  </p>
                )}
              </div>
            )}

            {/* 내 제출 결과 */}
            {!sprint && !ended && ss.myAnswer != null && (
              <p className={`text-center font-extrabold ${ss.myAnswerRight ? 'text-emerald-600' : 'text-rose-500'}`}
                style={{ animation: ss.myAnswerRight ? 'qz-pop .3s ease' : 'qz-shake .3s ease' }}>
                {ss.myAnswerRight ? '⭕ 정답!' : '❌ 오답'}
              </p>
            )}

            {/* 정답 공개 */}
            {!sprint && revealing && ss.lastReveal && (
              <div className="rounded-2xl border-2 border-emerald-300 bg-emerald-50 p-4" style={{ animation: 'qz-pop .3s ease' }}>
                <p className="font-extrabold text-emerald-800">정답: {ss.lastReveal.answer}</p>
                {ss.lastReveal.explain && <p className="text-sm text-emerald-700 mt-1">{ss.lastReveal.explain}</p>}
                {active.length > 1 && (
                  <div className="mt-3 flex flex-wrap gap-1.5">
                    {active.map((p) => (
                      <span key={p.seat}
                        className={`text-xs px-2 py-1 rounded-full border font-bold ${rightSet.has(p.seat) ? 'bg-white border-emerald-300 text-emerald-700' : 'bg-white border-slate-200 text-slate-400'}`}>
                        {rightSet.has(p.seat) ? '⭕' : '❌'} {p.name}
                        {ss.lastReveal?.given?.[String(p.seat)] && !rightSet.has(p.seat) && (
                          <span className="ml-1 text-slate-300">({ss.lastReveal.given[String(p.seat)]})</span>
                        )}
                      </span>
                    ))}
                  </div>
                )}
              </div>
            )}

            {/* 종료 */}
            {ended && (
              <div className="text-center rounded-3xl border-2 border-indigo-400 bg-gradient-to-b from-indigo-50 to-sky-50 px-6 py-6 shadow" style={{ animation: 'qz-pop .35s ease' }}>
                <p className="text-4xl mb-1">🏆</p>
                <p className="text-xl sm:text-2xl font-extrabold">{ranked[0]?.name} {active.length > 1 ? '우승!' : '완료!'}</p>
                <div className="mt-3 space-y-1.5 text-left max-w-sm mx-auto">
                  {ranked.map((p, i) => (
                    <div key={p.seat} className="flex items-center justify-between rounded-xl bg-white border px-3 py-2">
                      <span className="font-bold flex items-center gap-2">
                        <span className="text-slate-400 text-sm w-5">{i + 1}위</span>
                        {p.name}{p.me ? ' (나)' : ''}
                      </span>
                      <span className="text-sm">
                        <span className="font-extrabold">{sprint ? `${p.correct}개` : `${p.score}점`}</span>
                        <span className="text-slate-400 ml-2">
                          {sprint
                            ? `${p.solved}문제 풀이 · 오답 ${p.wrong} · 최고 ${p.bestStreak}연속`
                            : `${p.correct}/${ss.totalRounds}개 · 최고 ${p.bestStreak}연속`}
                        </span>
                      </span>
                    </div>
                  ))}
                </div>
                <button onClick={leave} className="mt-4 w-full max-w-sm bg-indigo-500 text-white font-bold py-3 rounded-xl">
                  새 퀴즈 하기
                </button>
              </div>
            )}

            {/* 시간 배틀은 판이 끝나면 전체 순위 안에서 내 기록이 어디인지 보여준다. */}
            {ended && sprint && rankBoard}
          </div>

          {/* 점수판 */}
          <div className="flex flex-col gap-3">
            <div className="rounded-2xl border-2 border-slate-200 overflow-hidden bg-white">
              <p className="px-3 py-2 text-xs font-extrabold text-slate-400 bg-slate-50 border-b border-slate-100 flex items-center justify-between">
                <span>{sprint ? '순위표' : '점수판'}</span>
                {sprint && <span className="font-normal text-[10px] text-slate-300">맞힌 개수 순</span>}
              </p>
              {ranked.map((p, i) => (
                <div key={p.seat} className={`px-3 py-2 border-b border-slate-50 last:border-b-0 ${p.me ? 'bg-indigo-50/50' : ''}`}>
                  <div className="flex items-center justify-between">
                    <span className="font-bold text-sm flex items-center gap-1.5 min-w-0">
                      <span className="text-[10px] text-slate-400 w-4">{i + 1}</span>
                      <span className="w-2.5 h-2.5 rounded-full flex-none" style={{ background: SEAT_COLORS[p.seat % SEAT_COLORS.length] }} />
                      <span className="truncate">{p.name}{p.me ? ' (나)' : ''}</span>
                      {asking && p.answered && <span className="text-[10px] text-emerald-500 flex-none">✓</span>}
                    </span>
                    <span className="font-extrabold text-sm flex-none">{sprint ? `${p.correct}개` : p.score}</span>
                  </div>
                  <p className="text-[11px] text-slate-400 mt-0.5">
                    {sprint
                      ? `${p.solved}문제 · 오답 ${p.wrong}${p.skipped > 0 ? ` · 넘김 ${p.skipped}` : ''}`
                      : `${p.correct}개 정답`}
                    {p.streak > 1 ? ` · 🔥${p.streak}연속` : ''}
                  </p>
                </div>
              ))}
            </div>

            {ss.log.length > 0 && (
              <details className="rounded-2xl border-2 border-slate-200 bg-white overflow-hidden">
                <summary className="px-3 py-2 text-xs font-extrabold text-slate-400 cursor-pointer select-none">진행 기록</summary>
                <div className="px-3 pb-2.5 max-h-48 overflow-y-auto text-xs text-slate-500 space-y-1 border-t border-slate-100 pt-2">
                  {[...ss.log].reverse().map((l, i) => <p key={ss.log.length - i}>{l}</p>)}
                </div>
              </details>
            )}
          </div>
        </div>
      )}

      {roomCode && <RoomChat game="quiz" roomCode={roomCode} clientId={id.current} nick={nick} />}
    </main>
  );
}
