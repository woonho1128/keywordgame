'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';

type Phase =
  | 'NOT_STARTED' | 'LOBBY' | 'REVEAL' | 'TEAM_BUILD'
  | 'TEAM_VOTE' | 'QUEST' | 'ASSASSIN' | 'ENDED';

type PlayerView = { seat: number; nick: string; role: string | null };
type VoteView = { seat: number; approve: boolean };

type AvState = {
  status: Phase;
  phaseEndsAt: number;
  serverNow: number;
  isHost: boolean;
  joined: boolean;
  seat: number;
  nick: string | null;
  myRole: string | null;
  myTeam: string | null;
  knowledge: string[];
  players: PlayerView[];
  leaderSeat: number;
  amLeader: boolean;
  questNumber: number;
  teamSizes: number[];
  failsRequired: number[];
  questResults: string[];
  successCount: number;
  failCount: number;
  rejectCount: number;
  proposedTeam: number[];
  teamSizeNeeded: number;
  amOnTeam: boolean;
  amReady: boolean;
  readyCount: number;
  myVote: string | null;
  votesCast: number;
  lastVote: VoteView[];
  lastVoteResult: string | null;
  myCard: string | null;
  questSubmitted: number;
  lastQuestFails: number;
  amAssassin: boolean;
  assassinTargetSeat: number;
  merlinSeat: number;
  winner: string | null;
  winReason: string | null;
  playerCount: number;
};

const CLIENT_ID_KEY = 'avalon_client_id';
const NICK_KEY = 'avalon_nick';

function getClientId(): string {
  if (typeof window === 'undefined') return '';
  try {
    let id = localStorage.getItem(CLIENT_ID_KEY) || '';
    if (!id) {
      id = typeof crypto !== 'undefined' && 'randomUUID' in crypto
        ? crypto.randomUUID()
        : `c_${Date.now()}_${Math.random().toString(36).slice(2)}`;
      localStorage.setItem(CLIENT_ID_KEY, id);
    }
    return id;
  } catch {
    return `c_${Math.random().toString(36).slice(2)}`;
  }
}

const ROLE_META: Record<string, { label: string; emoji: string; team: string; desc: string }> = {
  MERLIN: { label: '멀린', emoji: '🧙', team: 'GOOD', desc: '악을 압니다(모드레드 제외). 정체를 들키면 암살당합니다.' },
  PERCIVAL: { label: '퍼시발', emoji: '🛡️', team: 'GOOD', desc: '멀린과 모르가나를 알지만 누가 진짜 멀린인지 모릅니다.' },
  SERVANT: { label: '충성스러운 신하', emoji: '⚔️', team: 'GOOD', desc: '아서왕의 편. 원정을 성공시키세요.' },
  ASSASSIN: { label: '암살자', emoji: '🗡️', team: 'EVIL', desc: '선이 이기면 멀린을 지목해 뒤집을 수 있습니다.' },
  MORGANA: { label: '모르가나', emoji: '🧟', team: 'EVIL', desc: '퍼시발에게 멀린처럼 보입니다.' },
  MORDRED: { label: '모드레드', emoji: '👺', team: 'EVIL', desc: '멀린이 당신을 보지 못합니다.' },
  OBERON: { label: '오베론', emoji: '👹', team: 'EVIL', desc: '동료 악을 모르고, 동료도 당신을 모릅니다.' },
  MINION: { label: '미니언', emoji: '😈', team: 'EVIL', desc: '모드레드의 하수인.' },
};

const PHASE_LABEL: Record<Phase, string> = {
  NOT_STARTED: '', LOBBY: '대기방', REVEAL: '역할 확인', TEAM_BUILD: '원정대 편성',
  TEAM_VOTE: '찬반 투표', QUEST: '원정 수행', ASSASSIN: '암살', ENDED: '종료',
};

const teamColor = (t: string | null) => (t === 'EVIL' ? 'text-red-500' : 'text-blue-600');
const teamLabel = (t: string | null) => (t === 'EVIL' ? '악' : '선');

export default function AvalonPage() {
  const [clientId, setClientId] = useState('');
  const [st, setSt] = useState<AvState | null>(null);
  const [remaining, setRemaining] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [nick, setNick] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [incPM, setIncPM] = useState(true);
  const [incMordred, setIncMordred] = useState(false);
  const [incOberon, setIncOberon] = useState(false);
  const [showRoles, setShowRoles] = useState(false);
  const [showRules, setShowRules] = useState(false);
  const [team, setTeam] = useState<number[]>([]);

  const [showAdmin, setShowAdmin] = useState(false);
  const [adminInput, setAdminInput] = useState('');

  const cidRef = useRef('');
  const offsetRef = useRef(0);
  const endsAtRef = useRef(0);
  const inflight = useRef(false);

  const fetchState = useCallback(async () => {
    const cid = cidRef.current;
    if (!cid || inflight.current) return;
    inflight.current = true;
    try {
      const res = await api<AvState>(`/api/v1/avalon/me?clientId=${encodeURIComponent(cid)}`);
      setSt(res);
      offsetRef.current = res.serverNow - Date.now();
      endsAtRef.current = res.phaseEndsAt;
    } catch { /* ignore */ } finally {
      inflight.current = false;
    }
  }, []);

  useEffect(() => {
    const id = getClientId();
    setClientId(id);
    cidRef.current = id;
    try { setNick(localStorage.getItem(NICK_KEY) || ''); } catch {}
    fetchState();
    const poll = setInterval(fetchState, 1000);
    const ticker = setInterval(() => {
      if (endsAtRef.current > 0) {
        const now = Date.now() + offsetRef.current;
        setRemaining(Math.max(0, Math.ceil((endsAtRef.current - now) / 1000)));
      } else setRemaining(0);
    }, 250);
    return () => { clearInterval(poll); clearInterval(ticker); };
  }, [fetchState]);

  const post = useCallback(async (path: string, body?: unknown) => {
    setBusy(true);
    setError(null);
    try {
      const res = await api<AvState>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined });
      setSt(res);
      offsetRef.current = res.serverNow - Date.now();
      endsAtRef.current = res.phaseEndsAt;
      return res;
    } catch (e) {
      setError(e instanceof Error ? e.message : '오류가 발생했습니다');
      return null;
    } finally { setBusy(false); }
  }, []);

  const cid = () => encodeURIComponent(clientId);
  const saveNick = (n: string) => { try { localStorage.setItem(NICK_KEY, n); } catch {} };

  const handleCreate = () => {
    const n = nick.trim();
    if (!n) return setError('닉네임을 입력하세요');
    saveNick(n);
    post(`/api/v1/avalon/new?clientId=${cid()}`, {
      nick: n, includePercivalMorgana: incPM, includeMordred: incMordred, includeOberon: incOberon,
    }).then((r) => r && setShowCreate(false));
  };
  const handleJoin = () => {
    const n = nick.trim();
    if (!n) return setError('닉네임을 입력하세요');
    saveNick(n);
    post(`/api/v1/avalon/join?clientId=${cid()}`, { nick: n });
  };
  const handleStart = () => post(`/api/v1/avalon/start?clientId=${cid()}`);
  const handleReady = () => post(`/api/v1/avalon/ready?clientId=${cid()}`);
  const handlePropose = () => post(`/api/v1/avalon/propose?clientId=${cid()}`, { team }).then((r) => r && setTeam([]));
  const handleVote = (approve: boolean) => post(`/api/v1/avalon/vote?clientId=${cid()}`, { value: approve });
  const handleQuest = (success: boolean) => post(`/api/v1/avalon/quest?clientId=${cid()}`, { value: success });
  const handleAssassinate = (target: number) => post(`/api/v1/avalon/assassinate?clientId=${cid()}`, { target });
  const handleAdminReset = async () => {
    const code = adminInput.trim();
    if (!code) return;
    const res = await post(`/api/v1/avalon/reset?code=${encodeURIComponent(code)}`);
    if (res) { setShowAdmin(false); setAdminInput(''); }
  };

  const nickOf = (seat: number) => st?.players.find((p) => p.seat === seat)?.nick ?? `${seat}번`;
  const toggleTeam = (seat: number) => {
    if (!st) return;
    setTeam((cur) => {
      if (cur.includes(seat)) return cur.filter((s) => s !== seat);
      if (cur.length >= st.teamSizeNeeded) return cur;
      return [...cur, seat];
    });
  };

  if (!st) {
    return <main className="min-h-screen flex items-center justify-center"><p className="text-gray-400">불러오는 중...</p></main>;
  }
  const phase = st.status;

  return (
    <main className="min-h-screen flex flex-col items-center p-6 max-w-md mx-auto w-full">
      <div className="w-full flex items-center justify-between mb-3">
        <h1 className="text-2xl font-bold">🏰 아발론</h1>
        {phase !== 'NOT_STARTED' && phase !== 'LOBBY' && (
          <div className="text-right">
            <div className="text-sm font-bold text-gray-700">{PHASE_LABEL[phase]}</div>
            {st.phaseEndsAt > 0 && <div className="text-lg font-extrabold tabular-nums">{remaining}s</div>}
          </div>
        )}
      </div>

      {phase !== 'NOT_STARTED' && phase !== 'LOBBY' && questTrack()}

      {st.joined && st.myRole && phase !== 'ENDED' && (
        <div className="w-full my-3 rounded-xl border border-gray-200 p-3 flex items-center justify-between">
          <span className="text-sm text-gray-500">내 역할 <span className={`ml-1 font-bold ${teamColor(st.myTeam)}`}>({teamLabel(st.myTeam)})</span></span>
          <span className={`font-bold ${teamColor(st.myTeam)}`}>{ROLE_META[st.myRole]?.emoji} {ROLE_META[st.myRole]?.label}</span>
        </div>
      )}

      <div className="flex-1 w-full">
        {phase === 'NOT_STARTED' && renderNotStarted()}
        {phase === 'LOBBY' && renderLobby()}
        {phase === 'REVEAL' && renderReveal()}
        {phase === 'TEAM_BUILD' && renderTeamBuild()}
        {phase === 'TEAM_VOTE' && renderVote()}
        {phase === 'QUEST' && renderQuest()}
        {phase === 'ASSASSIN' && renderAssassin()}
        {phase === 'ENDED' && renderEnded()}
        {error && <p className="text-red-500 text-sm mt-4 text-center">{error}</p>}
      </div>

      <div className="w-full mt-6 pt-4 border-t border-gray-100 flex justify-center">
        {showAdmin ? (
          <div className="flex items-center gap-2">
            <input type="password" value={adminInput} onChange={(e) => setAdminInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleAdminReset()} placeholder="관리자 코드"
              className="border border-gray-300 rounded-lg px-3 py-2 text-sm w-32 focus:outline-none focus:border-red-400" />
            <button onClick={handleAdminReset} className="bg-gray-700 text-white text-sm px-3 py-2 rounded-lg">초기화</button>
          </div>
        ) : (
          <button onClick={() => setShowAdmin(true)} className="text-xs text-gray-300 hover:text-gray-500">🔒 관리자</button>
        )}
      </div>
    </main>
  );

  function questTrack() {
    return (
      <div className="w-full flex items-center justify-center gap-2">
        {st!.teamSizes.map((sz, i) => {
          const result = st!.questResults[i];
          const current = i === st!.questNumber - 1 && !result && phase !== 'ENDED';
          const bg = result === 'SUCCESS' ? 'bg-blue-500 text-white border-blue-500'
            : result === 'FAIL' ? 'bg-red-500 text-white border-red-500'
            : current ? 'border-gray-800 text-gray-800' : 'border-gray-300 text-gray-400';
          return (
            <div key={i} className={`w-9 h-9 rounded-full border-2 flex flex-col items-center justify-center text-xs font-bold ${bg}`}>
              {sz}{st!.failsRequired[i] === 2 && <span className="text-[8px] leading-none">2실패</span>}
            </div>
          );
        })}
        {phase !== 'ENDED' && (
          <span className="ml-2 text-xs text-gray-400">거부 {st!.rejectCount}/5</span>
        )}
      </div>
    );
  }

  function rulesHelp() {
    return (
      <div className="rounded-xl border border-gray-200 p-4 text-sm space-y-3 mt-3 text-left">
        <div className="flex items-center justify-between">
          <p className="font-bold">게임 방법</p>
          <button onClick={() => setShowRules(false)} className="text-xs text-gray-400">닫기 ✕</button>
        </div>
        <div>
          <p className="font-bold text-blue-600">🎯 목표</p>
          <p className="text-gray-600">🔵 선은 <b>원정 3회 성공</b>, 🔴 악은 <b>원정 3회 실패</b>(또는 멀린 암살)로 승리.</p>
        </div>
        <div>
          <p className="font-bold">▶ 한 원정 진행</p>
          <ol className="list-decimal list-inside text-gray-600 space-y-1">
            <li>리더가 원정대 인원을 <b>지목</b>합니다.</li>
            <li><b>전원 찬반 투표</b> → 과반 찬성이면 출발, 아니면 리더가 다음 사람으로 넘어가 다시 지목.</li>
            <li className="text-red-500">거부가 <b>5번 연속</b>되면 악이 즉시 승리!</li>
            <li>원정대는 <b>비밀 카드</b> 제출 — 선은 <b>성공만</b>, 악은 성공/실패 선택 가능.</li>
            <li><b>실패 카드</b>가 필요 수(보통 1장, 특정 원정은 2장) 이상이면 그 원정 실패.</li>
          </ol>
        </div>
        <div>
          <p className="font-bold">🏆 승패</p>
          <ul className="list-disc list-inside text-gray-600 space-y-1">
            <li>원정 <b>3성공</b> → 선이 이길 뻔하지만, <b>암살자가 멀린을 지목</b>해 맞히면 <span className="text-red-500 font-bold">악 승리로 역전</span>!</li>
            <li>원정 <b>3실패</b> 또는 5연속 거부 → <span className="text-red-500 font-bold">악 승리</span>.</li>
          </ul>
        </div>
        <div>
          <p className="font-bold">💡 팁</p>
          <p className="text-gray-600">선은 대화로 신뢰를 찾고, 악은 몰래 원정을 실패시키되 들키지 마세요. 멀린은 아는 걸 흘리되 <b>정체를 숨겨야</b> 암살을 피합니다.</p>
        </div>
      </div>
    );
  }

  function rolesHelp() {
    return (
      <div className="rounded-xl border border-gray-200 p-4 text-sm space-y-2 mt-3">
        <div className="flex items-center justify-between"><p className="font-bold">직업 설명</p>
          <button onClick={() => setShowRoles(false)} className="text-xs text-gray-400">닫기 ✕</button></div>
        {Object.entries(ROLE_META).map(([k, m]) => (
          <div key={k}><span className={`font-bold ${teamColor(m.team)}`}>{m.emoji} {m.label}</span>
            <span className="text-gray-500"> — {m.desc}</span></div>
        ))}
      </div>
    );
  }

  function renderNotStarted() {
    if (showCreate) {
      return (
        <div className="space-y-5 mt-4">
          <div>
            <label className="block text-sm font-medium mb-1">내 닉네임 *</label>
            <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="yono"
              className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-blue-500" />
          </div>
          <div className="space-y-2 bg-gray-50 rounded-lg p-4">
            <p className="text-sm font-medium">선택 직업</p>
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={incPM} onChange={(e) => setIncPM(e.target.checked)} /> 🛡️ 퍼시발 + 🧟 모르가나</label>
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={incMordred} onChange={(e) => setIncMordred(e.target.checked)} /> 👺 모드레드 (멀린이 못 봄)</label>
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={incOberon} onChange={(e) => setIncOberon(e.target.checked)} /> 👹 오베론 (동료 악을 모름)</label>
          </div>
          <div className="flex gap-2">
            <button onClick={() => setShowCreate(false)} className="flex-1 border border-gray-300 py-3 rounded-lg">취소</button>
            <button onClick={handleCreate} disabled={busy} className="flex-[2] bg-blue-600 text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-50">
              {busy ? '생성 중...' : '방 만들기'}
            </button>
          </div>
        </div>
      );
    }
    return (
      <div className="text-center space-y-6 mt-6">
        <p className="text-gray-500">선과 악으로 나뉘어 원정을 다투는 추리 게임. 각자 폰으로 접속하세요.</p>
        <button onClick={() => setShowCreate(true)} className="bg-blue-600 text-white font-bold py-3 px-8 rounded-lg hover:opacity-90">새 방 만들기</button>
        <div className="flex justify-center gap-4">
          <button onClick={() => { setShowRules((v) => !v); setShowRoles(false); }} className="text-sm text-gray-400 underline">게임 방법 보기</button>
          <button onClick={() => { setShowRoles((v) => !v); setShowRules(false); }} className="text-sm text-gray-400 underline">직업 설명 보기</button>
        </div>
        {showRules && rulesHelp()}
        {showRoles && rolesHelp()}
        <p className="text-xs text-gray-400">5~10명</p>
      </div>
    );
  }

  function renderLobby() {
    const canStart = st!.playerCount >= 5;
    return (
      <div className="space-y-5 mt-2">
        <div className="rounded-xl border border-gray-200 p-4">
          <p className="text-sm font-bold mb-2">참가자 ({st!.playerCount}/10)</p>
          <div className="flex flex-wrap gap-2">
            {st!.players.map((p) => {
              const me = p.seat === st!.seat;
              return <span key={p.seat} className={`bg-gray-100 rounded-full px-3 py-1 text-sm ${me ? 'ring-2 ring-blue-500 font-bold' : ''}`}>{p.nick}{me && ' (나)'}</span>;
            })}
          </div>
        </div>
        <div className="flex gap-4">
          <button onClick={() => { setShowRules((v) => !v); setShowRoles(false); }} className="text-sm text-gray-400 underline">게임 방법 보기</button>
          <button onClick={() => { setShowRoles((v) => !v); setShowRules(false); }} className="text-sm text-gray-400 underline">직업 설명 보기</button>
        </div>
        {showRules && rulesHelp()}
        {showRoles && rolesHelp()}
        {!st!.joined ? (
          <div className="flex gap-2">
            <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임"
              className="flex-1 border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-blue-500" />
            <button onClick={handleJoin} disabled={busy} className="bg-blue-600 text-white font-bold px-5 rounded-lg disabled:opacity-50">참가</button>
          </div>
        ) : st!.isHost ? (
          <button onClick={handleStart} disabled={busy || !canStart}
            className="w-full bg-blue-600 text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-40">
            {canStart ? '게임 시작' : '최소 5명 필요'}
          </button>
        ) : <p className="text-center text-gray-500 text-sm">방장이 시작하기를 기다리는 중...</p>}
      </div>
    );
  }

  function renderReveal() {
    return (
      <div className="mt-4 space-y-4 text-center">
        <p className="text-gray-600">역할과 정보를 확인하세요.</p>
        {st!.knowledge.length > 0 && (
          <div className="bg-gray-50 rounded-lg p-4 text-sm text-left space-y-1">
            {st!.knowledge.map((k, i) => <div key={i}>🔎 {k}</div>)}
          </div>
        )}
        {st!.amReady ? (
          <p className="text-gray-500 text-sm">다른 사람 확인 대기 중... ({st!.readyCount}/{st!.playerCount})</p>
        ) : (
          <button onClick={handleReady} disabled={busy} className="w-full bg-blue-600 text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-50">확인 완료</button>
        )}
      </div>
    );
  }

  function boardButtons(onPick: (seat: number) => void, selected: number[], disabled?: boolean) {
    return (
      <div className="grid grid-cols-2 gap-2">
        {st!.players.map((p) => {
          const me = p.seat === st!.seat;
          const sel = selected.includes(p.seat);
          const leader = p.seat === st!.leaderSeat;
          return (
            <button key={p.seat} onClick={() => onPick(p.seat)} disabled={busy || disabled}
              className={`rounded-lg border py-3 px-2 text-sm font-medium transition active:scale-95 ${sel ? 'border-blue-600 bg-blue-50 text-blue-700 font-bold' : 'border-gray-300 hover:bg-gray-50'}`}>
              {leader && '👑 '}{p.nick}{me && ' (나)'}
            </button>
          );
        })}
      </div>
    );
  }

  function renderTeamBuild() {
    return (
      <div className="mt-2 space-y-4">
        {st!.lastVoteResult === 'REJECTED' && <p className="text-center text-xs text-red-400">직전 원정대가 거부되었습니다.</p>}
        {st!.lastQuestFails >= 0 && st!.questResults.length > 0 && (
          <p className="text-center text-xs text-gray-500">
            직전 원정: {st!.questResults[st!.questResults.length - 1] === 'SUCCESS' ? '✅ 성공' : `❌ 실패 (실패 카드 ${st!.lastQuestFails}장)`}
          </p>
        )}
        {st!.amLeader ? (
          <>
            <p className="font-bold text-center">👑 원정대 {st!.teamSizeNeeded}명을 뽑으세요 ({team.length}/{st!.teamSizeNeeded})</p>
            {boardButtons(toggleTeam, team)}
            <button onClick={handlePropose} disabled={busy || team.length !== st!.teamSizeNeeded}
              className="w-full bg-blue-600 text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-40">원정대 제안</button>
          </>
        ) : (
          <>
            <p className="text-center text-gray-600">👑 <b>{nickOf(st!.leaderSeat)}</b>님이 원정대 {st!.teamSizeNeeded}명을 뽑는 중...</p>
            {boardButtons(() => {}, [], true)}
          </>
        )}
      </div>
    );
  }

  function renderVote() {
    return (
      <div className="mt-2 space-y-4">
        <p className="font-bold text-center">이 원정대를 보낼까요?</p>
        <div className="flex flex-wrap gap-2 justify-center">
          {st!.proposedTeam.map((s) => (
            <span key={s} className="bg-blue-50 text-blue-700 rounded-full px-3 py-1 text-sm font-medium">
              {s === st!.leaderSeat && '👑 '}{nickOf(s)}
            </span>
          ))}
        </div>
        {st!.myVote ? (
          <p className="text-center text-gray-500 text-sm">투표 완료 ({st!.myVote === 'APPROVE' ? '찬성' : '반대'}) · 대기 중 {st!.votesCast}/{st!.playerCount}</p>
        ) : (
          <div className="flex gap-2">
            <button onClick={() => handleVote(true)} disabled={busy} className="flex-1 bg-blue-600 text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-50">👍 찬성</button>
            <button onClick={() => handleVote(false)} disabled={busy} className="flex-1 bg-gray-600 text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-50">👎 반대</button>
          </div>
        )}
      </div>
    );
  }

  function renderQuest() {
    return (
      <div className="mt-2 space-y-4">
        <p className="font-bold text-center">원정 수행 중</p>
        <div className="flex flex-wrap gap-2 justify-center">
          {st!.proposedTeam.map((s) => (
            <span key={s} className="bg-gray-100 rounded-full px-3 py-1 text-sm">{nickOf(s)}</span>
          ))}
        </div>
        {st!.amOnTeam ? (
          st!.myCard ? (
            <p className="text-center text-gray-500 text-sm">카드 제출 완료 · 대기 중 {st!.questSubmitted}/{st!.proposedTeam.length}</p>
          ) : (
            <div className="flex gap-2">
              <button onClick={() => handleQuest(true)} disabled={busy} className="flex-1 bg-blue-600 text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-50">✅ 성공</button>
              {st!.myTeam === 'EVIL' && (
                <button onClick={() => handleQuest(false)} disabled={busy} className="flex-1 bg-red-500 text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-50">❌ 실패</button>
              )}
            </div>
          )
        ) : (
          <p className="text-center text-gray-500 text-sm">원정대가 아닙니다. 결과를 기다리세요. ({st!.questSubmitted}/{st!.proposedTeam.length})</p>
        )}
      </div>
    );
  }

  function renderAssassin() {
    return (
      <div className="mt-2 space-y-4">
        <p className="text-center text-red-500 font-bold">🗡️ 선이 원정 3승! 암살자가 멀린을 지목합니다.</p>
        {st!.amAssassin ? (
          <>
            <p className="text-center text-gray-600 text-sm">누가 멀린일까요? 맞히면 악의 승리입니다.</p>
            {boardButtons((seat) => handleAssassinate(seat), [])}
          </>
        ) : (
          <p className="text-center text-gray-500">암살자가 멀린을 찾는 중...</p>
        )}
      </div>
    );
  }

  function renderEnded() {
    const evil = st!.winner === 'EVIL';
    return (
      <div className="text-center mt-4 space-y-4">
        <p className="text-5xl">{evil ? '🗡️' : '🏰'}</p>
        <p className={`text-3xl font-extrabold ${evil ? 'text-red-500' : 'text-blue-600'}`}>{evil ? '악의 승리!' : '선의 승리!'}</p>
        {st!.winReason && <p className="text-sm text-gray-500">{st!.winReason}</p>}
        <div className="rounded-xl border border-gray-200 p-4 text-left">
          <p className="text-sm font-bold mb-2">전체 정체 공개</p>
          <div className="space-y-1">
            {st!.players.map((p) => {
              const me = p.seat === st!.seat;
              const m = p.role ? ROLE_META[p.role] : null;
              const isMerlin = p.seat === st!.merlinSeat;
              return (
                <div key={p.seat} className="flex justify-between text-sm">
                  <span className={me ? 'font-bold' : ''}>{p.nick}{me && ' (나)'}{isMerlin && ' 🎯'}</span>
                  <span className={`font-medium ${m ? teamColor(m.team) : ''}`}>{m ? `${m.emoji} ${m.label}` : '-'}</span>
                </div>
              );
            })}
          </div>
        </div>
        <button onClick={() => { setShowCreate(true); setSt({ ...st!, status: 'NOT_STARTED' }); }}
          className="w-full bg-blue-600 text-white font-bold py-3 rounded-lg hover:opacity-90">🔄 새 방 만들기</button>
      </div>
    );
  }
}
