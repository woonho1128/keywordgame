'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';

type Phase =
  | 'NOT_STARTED' | 'LOBBY' | 'NIGHT' | 'MORNING'
  | 'DISCUSS' | 'VOTE' | 'EXECUTE' | 'ENDED';

type PlayerView = { seat: number; nick: string; alive: boolean; role: string | null };
type VoteView = { targetSeat: number; count: number };

type MafiaState = {
  status: Phase;
  round: number;
  phaseEndsAt: number;
  serverNow: number;
  isHost: boolean;
  joined: boolean;
  seat: number;
  nick: string | null;
  myRole: string | null;
  myTeam: string | null;
  alive: boolean;
  players: PlayerView[];
  actionKind: string;
  selectable: number[];
  myTarget: number;
  fellowMafia: number[];
  copLog: string[];
  nightMessage: string | null;
  nightDeadSeat: number;
  executedSeat: number;
  voteTally: VoteView[];
  winner: string | null;
  aliveCount: number;
  totalMafia: number;
  playerCount: number;
  mafiaPickTally: { targetSeat: number; count: number }[];
};

const CLIENT_ID_KEY = 'mafia_client_id';
const NICK_KEY = 'mafia_nick';

function getClientId(): string {
  if (typeof window === 'undefined') return '';
  try {
    let id = localStorage.getItem(CLIENT_ID_KEY) || '';
    if (!id) {
      id =
        typeof crypto !== 'undefined' && 'randomUUID' in crypto
          ? crypto.randomUUID()
          : `c_${Date.now()}_${Math.random().toString(36).slice(2)}`;
      localStorage.setItem(CLIENT_ID_KEY, id);
    }
    return id;
  } catch {
    return `c_${Math.random().toString(36).slice(2)}`;
  }
}

const ROLE_META: Record<string, { label: string; emoji: string; color: string }> = {
  MAFIA: { label: '마피아', emoji: '🔪', color: 'text-red-500' },
  POLICE: { label: '경찰', emoji: '👮', color: 'text-blue-500' },
  DOCTOR: { label: '의사', emoji: '🩺', color: 'text-green-600' },
  CITIZEN: { label: '시민', emoji: '🧑', color: 'text-gray-700' },
};

const PHASE_LABEL: Record<Phase, string> = {
  NOT_STARTED: '', LOBBY: '대기방', NIGHT: '🌙 밤', MORNING: '☀️ 아침',
  DISCUSS: '💬 토론', VOTE: '🗳️ 투표', EXECUTE: '⚖️ 처형', ENDED: '🏁 종료',
};

const ACTION_LABEL: Record<string, string> = {
  MAFIA_KILL: '🔪 제거할 대상을 고르세요',
  POLICE_CHECK: '🔎 조사할 대상을 고르세요',
  DOCTOR_SAVE: '🩺 보호할 대상을 고르세요',
  CITIZEN_WATCH: '🌙 밤 - 지켜볼 사람을 한 명 고르세요',
  VOTE: '🗳️ 처형할 사람에게 투표하세요',
};

export default function MafiaPage() {
  const [clientId, setClientId] = useState('');
  const [st, setSt] = useState<MafiaState | null>(null);
  const [remaining, setRemaining] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 방 만들기 폼
  const [nick, setNick] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [nightSec, setNightSec] = useState(30);
  const [discussSec, setDiscussSec] = useState(90);
  const [voteSec, setVoteSec] = useState(30);
  const [showCreate, setShowCreate] = useState(false);

  // 관리자
  const [showAdmin, setShowAdmin] = useState(false);
  const [adminInput, setAdminInput] = useState('');

  const cidRef = useRef('');
  const offsetRef = useRef(0); // serverNow - Date.now()
  const endsAtRef = useRef(0);
  const inflight = useRef(false);

  const fetchState = useCallback(async () => {
    const cid = cidRef.current;
    if (!cid || inflight.current) return;
    inflight.current = true;
    try {
      const res = await api<MafiaState>(`/api/v1/mafia/me?clientId=${encodeURIComponent(cid)}`);
      setSt(res);
      offsetRef.current = res.serverNow - Date.now();
      endsAtRef.current = res.phaseEndsAt;
    } catch {
      // 폴링 중 일시 오류는 무시(마지막 상태 유지)
    } finally {
      inflight.current = false;
    }
  }, []);

  useEffect(() => {
    const id = getClientId();
    setClientId(id);
    cidRef.current = id;
    try {
      setNick(localStorage.getItem(NICK_KEY) || '');
    } catch {}
    fetchState();
    const poll = setInterval(fetchState, 1000);
    const ticker = setInterval(() => {
      if (endsAtRef.current > 0) {
        const now = Date.now() + offsetRef.current;
        setRemaining(Math.max(0, Math.ceil((endsAtRef.current - now) / 1000)));
      } else {
        setRemaining(0);
      }
    }, 250);
    return () => {
      clearInterval(poll);
      clearInterval(ticker);
    };
  }, [fetchState]);

  const post = useCallback(
    async (path: string, body?: unknown) => {
      setBusy(true);
      setError(null);
      try {
        const res = await api<MafiaState>(path, {
          method: 'POST',
          body: body ? JSON.stringify(body) : undefined,
        });
        setSt(res);
        offsetRef.current = res.serverNow - Date.now();
        endsAtRef.current = res.phaseEndsAt;
        return res;
      } catch (e) {
        setError(e instanceof Error ? e.message : '오류가 발생했습니다');
        return null;
      } finally {
        setBusy(false);
      }
    },
    []
  );

  const saveNick = (n: string) => {
    try {
      localStorage.setItem(NICK_KEY, n);
    } catch {}
  };

  const handleCreate = () => {
    const n = nick.trim();
    if (!n) return setError('닉네임을 입력하세요');
    saveNick(n);
    post(`/api/v1/mafia/new?clientId=${encodeURIComponent(clientId)}`, {
      nick: n, nightSec, discussSec, voteSec,
    }).then((r) => r && setShowCreate(false));
  };

  const handleJoin = () => {
    const n = nick.trim();
    if (!n) return setError('닉네임을 입력하세요');
    saveNick(n);
    post(`/api/v1/mafia/join?clientId=${encodeURIComponent(clientId)}`, { nick: n });
  };

  const handleStart = () => post(`/api/v1/mafia/start?clientId=${encodeURIComponent(clientId)}`);

  const handleAct = (target: number) =>
    post(`/api/v1/mafia/night-action?clientId=${encodeURIComponent(clientId)}`, { target });

  const handleVote = (target: number) =>
    post(`/api/v1/mafia/vote?clientId=${encodeURIComponent(clientId)}`, { target });

  const handleAdminReset = async () => {
    const code = adminInput.trim();
    if (!code) return;
    const res = await post(`/api/v1/mafia/reset?code=${encodeURIComponent(code)}`);
    if (res) {
      setShowAdmin(false);
      setAdminInput('');
    }
  };

  const nickOf = (seat: number) => st?.players.find((p) => p.seat === seat)?.nick ?? `${seat}번`;

  // ---------- 렌더 ----------
  if (!st) {
    return (
      <main className="min-h-screen flex items-center justify-center">
        <p className="text-gray-400">불러오는 중...</p>
      </main>
    );
  }

  const phase = st.status;

  return (
    <main className="min-h-screen flex flex-col items-center p-6 max-w-md mx-auto w-full">
      {/* 헤더 */}
      <div className="w-full flex items-center justify-between mb-4">
        <h1 className="text-2xl font-bold">🎭 마피아</h1>
        {phase !== 'NOT_STARTED' && phase !== 'LOBBY' && (
          <div className="text-right">
            <div className="text-sm font-bold text-gray-700">
              {st.round}일차 · {PHASE_LABEL[phase]}
            </div>
            {st.phaseEndsAt > 0 && (
              <div className="text-lg font-extrabold tabular-nums text-gray-800">{remaining}s</div>
            )}
          </div>
        )}
      </div>

      {/* 내 역할 배지 */}
      {st.joined && st.myRole && phase !== 'ENDED' && (
        <div className="w-full mb-4 rounded-xl border border-gray-200 p-3 flex items-center justify-between">
          <span className="text-sm text-gray-500">내 역할</span>
          <span className={`font-bold ${ROLE_META[st.myRole]?.color}`}>
            {ROLE_META[st.myRole]?.emoji} {ROLE_META[st.myRole]?.label}
            {!st.alive && <span className="ml-2 text-gray-400">(사망)</span>}
          </span>
        </div>
      )}

      {/* 본문 */}
      <div className="flex-1 w-full">
        {phase === 'NOT_STARTED' && renderNotStarted()}
        {phase === 'LOBBY' && renderLobby()}
        {phase === 'NIGHT' && renderNight()}
        {phase === 'MORNING' && renderMorning()}
        {phase === 'DISCUSS' && renderDiscuss()}
        {phase === 'VOTE' && renderVote()}
        {phase === 'EXECUTE' && renderExecute()}
        {phase === 'ENDED' && renderEnded()}

        {error && <p className="text-red-500 text-sm mt-4 text-center">{error}</p>}
      </div>

      {/* 관리자 초기화 */}
      <div className="w-full mt-8 pt-4 border-t border-gray-100 flex justify-center">
        {showAdmin ? (
          <div className="flex items-center gap-2">
            <input
              type="password"
              value={adminInput}
              onChange={(e) => setAdminInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleAdminReset()}
              placeholder="관리자 코드"
              className="border border-gray-300 rounded-lg px-3 py-2 text-sm w-32 focus:outline-none focus:border-red-400"
            />
            <button onClick={handleAdminReset} className="bg-gray-700 text-white text-sm px-3 py-2 rounded-lg">
              초기화
            </button>
          </div>
        ) : (
          <button onClick={() => setShowAdmin(true)} className="text-xs text-gray-300 hover:text-gray-500">
            🔒 관리자
          </button>
        )}
      </div>
    </main>
  );

  // ===== 화면별 =====

  function renderNotStarted() {
    if (!showCreate) {
      return (
        <div className="text-center space-y-6 mt-6">
          <p className="text-gray-500">아직 방이 없어요. 새 방을 만들어 친구들을 초대하세요.</p>
          <button
            onClick={() => setShowCreate(true)}
            className="bg-hit text-white font-bold py-3 px-8 rounded-lg hover:opacity-90"
          >
            새 방 만들기
          </button>
          <p className="text-xs text-gray-400">4~12명 · 각자 자기 폰으로 접속</p>
        </div>
      );
    }
    return renderCreateForm();
  }

  function renderCreateForm() {
    return (
      <div className="space-y-5 mt-4">
        <div>
          <label className="block text-sm font-medium mb-1">내 닉네임 *</label>
          <input
            value={nick}
            onChange={(e) => setNick(e.target.value)}
            maxLength={16}
            placeholder="yono"
            className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit"
          />
        </div>
        <button
          onClick={() => setShowAdvanced((v) => !v)}
          className="text-sm text-gray-500 underline"
        >
          {showAdvanced ? '고급 설정 접기' : '고급 설정 (타이머)'}
        </button>
        {showAdvanced && (
          <div className="space-y-3 bg-gray-50 rounded-lg p-4">
            {[
              { label: '밤', v: nightSec, set: setNightSec, min: 15, max: 120 },
              { label: '토론', v: discussSec, set: setDiscussSec, min: 15, max: 300 },
              { label: '투표', v: voteSec, set: setVoteSec, min: 10, max: 120 },
            ].map((row) => (
              <div key={row.label} className="flex items-center justify-between text-sm">
                <span>{row.label} 시간</span>
                <span className="flex items-center gap-2">
                  <input
                    type="range" min={row.min} max={row.max} step={5} value={row.v}
                    onChange={(e) => row.set(Number(e.target.value))}
                  />
                  <span className="w-10 text-right font-bold">{row.v}s</span>
                </span>
              </div>
            ))}
          </div>
        )}
        <div className="flex gap-2">
          <button onClick={() => setShowCreate(false)} className="flex-1 border border-gray-300 py-3 rounded-lg">
            취소
          </button>
          <button
            onClick={handleCreate}
            disabled={busy}
            className="flex-[2] bg-hit text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-50"
          >
            {busy ? '생성 중...' : '방 만들기'}
          </button>
        </div>
      </div>
    );
  }

  function renderLobby() {
    const canStart = st!.playerCount >= 4;
    return (
      <div className="space-y-5 mt-2">
        <div className="rounded-xl border border-gray-200 p-4">
          <p className="text-sm font-bold mb-2">참가자 ({st!.playerCount}/12)</p>
          <div className="flex flex-wrap gap-2">
            {st!.players.map((p) => {
              const me = p.seat === st!.seat;
              return (
                <span key={p.seat} className={`bg-gray-100 rounded-full px-3 py-1 text-sm ${me ? 'ring-2 ring-hit font-bold' : ''}`}>
                  {p.nick}{me && ' (나)'}
                </span>
              );
            })}
            {st!.players.length === 0 && <span className="text-gray-400 text-sm">아직 없음</span>}
          </div>
        </div>

        {!st!.joined ? (
          <div className="space-y-2">
            <label className="block text-sm font-medium">닉네임으로 참가</label>
            <div className="flex gap-2">
              <input
                value={nick}
                onChange={(e) => setNick(e.target.value)}
                maxLength={16}
                placeholder="닉네임"
                className="flex-1 border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit"
              />
              <button onClick={handleJoin} disabled={busy} className="bg-hit text-white font-bold px-5 rounded-lg disabled:opacity-50">
                참가
              </button>
            </div>
          </div>
        ) : st!.isHost ? (
          <button
            onClick={handleStart}
            disabled={busy || !canStart}
            className="w-full bg-red-500 text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-40"
          >
            {canStart ? '게임 시작' : '최소 4명 필요'}
          </button>
        ) : (
          <p className="text-center text-gray-500 text-sm">방장이 시작하기를 기다리는 중...</p>
        )}
        <p className="text-center text-xs text-gray-400">
          같은 주소를 친구들에게 공유하세요. 각자 닉네임으로 참가하면 됩니다.
        </p>
      </div>
    );
  }

  function targetButtons(seats: number[], onPick: (seat: number) => void, current: number) {
    return (
      <div className="grid grid-cols-2 gap-2">
        {seats.map((seat) => (
          <button
            key={seat}
            onClick={() => onPick(seat)}
            disabled={busy}
            className={`rounded-lg border py-3 px-2 text-sm font-medium transition active:scale-95 ${
              current === seat
                ? 'border-hit bg-green-50 text-hit font-bold'
                : 'border-gray-300 hover:bg-gray-50'
            }`}
          >
            {nickOf(seat)}
          </button>
        ))}
      </div>
    );
  }

  function aliveBoard() {
    return (
      <div className="mt-6">
        <p className="text-xs text-gray-400 mb-2">생존 {st!.aliveCount}명 / 전체 {st!.playerCount}명</p>
        <div className="flex flex-wrap gap-2">
          {st!.players.map((p) => {
            const me = p.seat === st!.seat;
            return (
              <span
                key={p.seat}
                className={`rounded-full px-3 py-1 text-sm ${
                  p.alive ? 'bg-gray-100 text-gray-700' : 'bg-gray-50 text-gray-300 line-through'
                } ${me ? 'ring-2 ring-hit font-bold' : ''}`}
              >
                {p.nick}{me && ' (나)'}
                {p.role && <span className="ml-1">{ROLE_META[p.role]?.emoji}</span>}
              </span>
            );
          })}
        </div>
      </div>
    );
  }

  function renderNight() {
    if (!st!.joined || !st!.alive) {
      return (
        <div className="text-center mt-8 space-y-2">
          <p className="text-4xl">🌙</p>
          <p className="text-gray-500">{st!.joined ? '사망하여 관전 중입니다.' : '밤이 깊었습니다.'}</p>
          {aliveBoard()}
        </div>
      );
    }
    const kind = st!.actionKind;
    const isMafia = kind === 'MAFIA_KILL';
    return (
      <div className="mt-2 space-y-4">
        <p className="font-bold text-center">{ACTION_LABEL[kind] ?? '🌙 밤입니다'}</p>
        {isMafia && st!.fellowMafia.length > 1 && (
          <p className="text-center text-xs text-red-400">
            동료 마피아: {st!.fellowMafia.map(nickOf).join(', ')} · 실시간으로 지목이 공유됩니다
          </p>
        )}
        {targetButtons(st!.selectable, handleAct, st!.myTarget)}

        {/* 마피아: 동료들의 실시간 지목 현황(다수결로 최종 대상 결정) */}
        {isMafia && st!.mafiaPickTally.length > 0 && (
          <div className="bg-red-50 rounded-lg p-3 text-sm space-y-1">
            <p className="text-xs text-red-400 font-medium">동료 지목 현황 (다수결)</p>
            {st!.mafiaPickTally
              .slice()
              .sort((a, b) => b.count - a.count)
              .map((v) => (
                <div key={v.targetSeat} className="flex justify-between text-red-700">
                  <span>{nickOf(v.targetSeat)}</span>
                  <span className="font-bold">{v.count}표</span>
                </div>
              ))}
          </div>
        )}

        {kind === 'POLICE_CHECK' && st!.copLog.length > 0 && (
          <div className="bg-blue-50 rounded-lg p-3 text-sm text-blue-700 space-y-1">
            {st!.copLog.map((l, i) => (
              <div key={i}>{l}</div>
            ))}
          </div>
        )}
        {st!.myTarget > 0 && (
          <p className="text-center text-xs text-gray-400">
            내 선택: <b>{nickOf(st!.myTarget)}</b> · 시간 내 변경 가능
          </p>
        )}
        {aliveBoard()}
      </div>
    );
  }

  function renderMorning() {
    return (
      <div className="text-center mt-8 space-y-3">
        <p className="text-5xl">☀️</p>
        <p className="text-lg font-bold text-gray-800">{st!.nightMessage}</p>
        {aliveBoard()}
      </div>
    );
  }

  function renderDiscuss() {
    return (
      <div className="text-center mt-6 space-y-3">
        <p className="text-5xl">💬</p>
        <p className="text-gray-600">자유롭게 토론하세요. 곧 투표가 시작됩니다.</p>
        {st!.nightMessage && <p className="text-sm text-gray-400">{st!.nightMessage}</p>}
        {aliveBoard()}
      </div>
    );
  }

  function renderVote() {
    const tally = st!.voteTally;
    return (
      <div className="mt-2 space-y-4">
        <p className="font-bold text-center">{ACTION_LABEL.VOTE}</p>
        {st!.joined && st!.alive ? (
          <>
            {targetButtons(st!.selectable, handleVote, st!.myTarget)}
            <button
              onClick={() => handleVote(-1)}
              disabled={busy}
              className={`w-full rounded-lg border py-2 text-sm ${
                st!.myTarget === -1 && st!.seat > 0 ? 'border-gray-500 bg-gray-100' : 'border-gray-300'
              }`}
            >
              기권
            </button>
          </>
        ) : (
          <p className="text-center text-gray-500 text-sm">관전 중 — 투표할 수 없습니다.</p>
        )}
        {tally.length > 0 && (
          <div className="space-y-1">
            <p className="text-xs text-gray-400">현재 득표</p>
            {tally
              .slice()
              .sort((a, b) => b.count - a.count)
              .map((v) => (
                <div key={v.targetSeat} className="flex justify-between text-sm">
                  <span>{nickOf(v.targetSeat)}</span>
                  <span className="font-bold">{v.count}표</span>
                </div>
              ))}
          </div>
        )}
      </div>
    );
  }

  function renderExecute() {
    const executed = st!.executedSeat;
    return (
      <div className="text-center mt-8 space-y-3">
        <p className="text-5xl">⚖️</p>
        {executed > 0 ? (
          <>
            <p className="text-lg font-bold text-gray-800">
              {nickOf(executed)}님이 처형되었습니다.
            </p>
            {(() => {
              const p = st!.players.find((x) => x.seat === executed);
              return p?.role ? (
                <p className={`font-bold ${ROLE_META[p.role]?.color}`}>
                  정체: {ROLE_META[p.role]?.emoji} {ROLE_META[p.role]?.label}
                </p>
              ) : null;
            })()}
          </>
        ) : (
          <p className="text-lg font-bold text-gray-600">동표로 아무도 처형되지 않았습니다.</p>
        )}
        {aliveBoard()}
      </div>
    );
  }

  function renderEnded() {
    const win = st!.winner === 'MAFIA';
    return (
      <div className="text-center mt-6 space-y-4">
        <p className="text-6xl">{win ? '🔪' : '🎉'}</p>
        <p className={`text-3xl font-extrabold ${win ? 'text-red-500' : 'text-hit'}`}>
          {win ? '마피아 승리!' : '시민 승리!'}
        </p>
        <div className="rounded-xl border border-gray-200 p-4 text-left">
          <p className="text-sm font-bold mb-2">전체 정체 공개</p>
          <div className="space-y-1">
            {st!.players.map((p) => {
              const me = p.seat === st!.seat;
              return (
                <div key={p.seat} className="flex justify-between text-sm">
                  <span className={`${p.alive ? '' : 'text-gray-400 line-through'} ${me ? 'font-bold' : ''}`}>
                    {p.nick}{me && ' (나)'}
                  </span>
                  <span className={`font-medium ${p.role ? ROLE_META[p.role]?.color : ''}`}>
                    {p.role ? `${ROLE_META[p.role]?.emoji} ${ROLE_META[p.role]?.label}` : '-'}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
        <button
          onClick={() => {
            setShowCreate(true);
            setSt({ ...st!, status: 'NOT_STARTED' });
          }}
          className="w-full bg-hit text-white font-bold py-3 rounded-lg hover:opacity-90"
        >
          🔄 새 방 만들기
        </button>
      </div>
    );
  }
}
