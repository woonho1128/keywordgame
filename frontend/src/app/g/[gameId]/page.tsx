'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { api, GameType, getSessionKey, setSessionKey } from '@/lib/api';
import { SyllableResult } from '@/lib/hangul';
import { ShareButton } from '@/components/common/ShareButton';
import { ShareResultButton } from '@/components/common/ShareResultButton';
import { HangulBoard } from '@/components/wordguess/HangulBoard';
import { JamoInputCells } from '@/components/wordguess/JamoInputCells';
import { GuessHistory, WordSimGuess } from '@/components/wordsim/GuessHistory';
import {
  buildLieHintShareText,
  buildWordGuessShareText,
  buildWordSimShareText,
  formatTime,
} from '@/lib/share';

function ReferenceScoreCell({ label, sim }: { label: string; sim: number | null }) {
  return (
    <div className="text-center">
      <div className="text-gray-400">{label}</div>
      <div className="font-bold text-gray-700 text-sm">
        {sim != null ? (sim * 100).toFixed(2) : '-'}
      </div>
    </div>
  );
}

type GameInfo = {
  gameId: string;
  gameType: GameType;
  title: string | null;
  wordLength: number;
  jamoCount: number | null;
  hintText: string | null;
  creatorNick: string | null;
  playCount: number;
  solvedCount: number;
  maxAttempts: number | null;
  top1Similarity: number | null;
  top10Similarity: number | null;
  top100Similarity: number | null;
  top1000Similarity: number | null;
  lieHints: string[] | null;
  lieHintCount: number | null;
};

type StartResp = {
  recordId: number;
  sessionKey: string;
  gameType: GameType;
  wordLength: number;
  hintText: string | null;
  attemptCount: number;
  status: 'IN_PROGRESS' | 'SOLVED' | 'GAVE_UP';
};

type GuessResp = {
  guessWord: string;
  guessOrder: number;
  isCorrect: boolean;
  letterResult: SyllableResult[] | null;
  inDictionary: boolean | null;
  similarity: number | null;
  similarityScore: number | null;
  rank: number | null;
  liePhase: boolean | null;
  status: 'IN_PROGRESS' | 'SOLVED' | 'GAVE_UP';
  revealedAnswer: string | null;
  timeSpentSec: number | null;
};

type LieHintResp = {
  lieCorrect: boolean;
  status: 'IN_PROGRESS' | 'SOLVED' | 'GAVE_UP';
  revealedAnswer: string | null;
  revealedLieIndex: number | null;
  timeSpentSec: number | null;
};

type GuessHistoryItem = {
  guessWord: string;
  letterResult: SyllableResult[];
  isCorrect: boolean;
};

type LieGuessItem = {
  guessWord: string;
  isCorrect: boolean;
};

type PlayState = {
  gameType: GameType;
  status: 'IN_PROGRESS' | 'SOLVED' | 'GAVE_UP';
  attemptCount: number;
  playerNick: string;
  timeSpentSec: number | null;
  revealedAnswer: string | null;
  revealedLieIndex: number | null;
  liePhase: boolean | null;
  guesses: {
    guessWord: string;
    guessOrder: number;
    isCorrect: boolean;
    letterResult: SyllableResult[] | null;
    similarity: number | null;
    rank: number | null;
    extraResult: string | null;
  }[];
};

function gameLabel(type: GameType) {
  if (type === 'WORDGUESS') return 'WordGuess';
  if (type === 'WORDSIM') return 'WordSim';
  return 'Lie Hint';
}

export default function PlayPage() {
  const params = useParams<{ gameId: string }>();
  const router = useRouter();

  const [game, setGame] = useState<GameInfo | null>(null);
  const [nick, setNick] = useState('');
  const [started, setStarted] = useState(false);
  const [status, setStatus] = useState<'IN_PROGRESS' | 'SOLVED' | 'GAVE_UP'>('IN_PROGRESS');
  const [guessInput, setGuessInput] = useState('');
  const [history, setHistory] = useState<GuessHistoryItem[]>([]);
  const [simHistory, setSimHistory] = useState<WordSimGuess[]>([]);
  const [lastSimGuess, setLastSimGuess] = useState<WordSimGuess | undefined>();
  const [lieHistory, setLieHistory] = useState<LieGuessItem[]>([]);
  const [liePhase, setLiePhase] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [revealedAnswer, setRevealedAnswer] = useState<string | null>(null);
  const [revealedLieIndex, setRevealedLieIndex] = useState<number | null>(null);
  const [timeSpentSec, setTimeSpentSec] = useState<number | null>(null);
  const [attemptCount, setAttemptCount] = useState(0);
  const [restoring, setRestoring] = useState(false);

  useEffect(() => {
    api<GameInfo>(`/api/v1/games/${params.gameId}`)
      .then(setGame)
      .catch((e) => setError(e.message));
  }, [params.gameId]);

  useEffect(() => {
    if (!getSessionKey(params.gameId)) return;
    setRestoring(true);
    api<PlayState>(`/api/v1/games/${params.gameId}/state`, { gameId: params.gameId })
      .then((st) => {
        setStarted(true);
        setStatus(st.status);
        setAttemptCount(st.attemptCount);
        setNick(st.playerNick);
        setLiePhase(Boolean(st.liePhase));
        if (st.timeSpentSec != null) setTimeSpentSec(st.timeSpentSec);
        if (st.revealedAnswer) setRevealedAnswer(st.revealedAnswer);
        if (st.revealedLieIndex != null) setRevealedLieIndex(st.revealedLieIndex);

        if (st.gameType === 'WORDGUESS') {
          setHistory(
            st.guesses
              .filter((g) => g.letterResult != null)
              .map((g) => ({
                guessWord: g.guessWord,
                letterResult: g.letterResult!,
                isCorrect: g.isCorrect,
              }))
          );
        } else if (st.gameType === 'WORDSIM') {
          const sims: WordSimGuess[] = st.guesses
            .filter((g) => g.similarity != null)
            .map((g) => ({
              guessWord: g.guessWord,
              similarity: g.similarity!,
              rank: g.rank,
              isCorrect: g.isCorrect,
            }));
          setSimHistory(sims);
          if (sims.length) setLastSimGuess(sims[sims.length - 1]);
        } else {
          setLieHistory(st.guesses.map((g) => ({
            guessWord: g.guessWord,
            isCorrect: g.isCorrect,
          })));
        }
      })
      .catch(() => {})
      .finally(() => setRestoring(false));
  }, [params.gameId]);

  const handleStart = async () => {
    setError(null);
    if (!nick.trim()) return setError('닉네임을 입력해주세요.');
    try {
      const res = await api<StartResp>(`/api/v1/games/${params.gameId}/start`, {
        method: 'POST',
        body: JSON.stringify({ playerNick: nick.trim() }),
      });
      setSessionKey(params.gameId, res.sessionKey);
      setStarted(true);
      setStatus(res.status);
      setAttemptCount(res.attemptCount);
    } catch (e) {
      setError(e instanceof Error ? e.message : '게임 시작에 실패했습니다.');
    }
  };

  const handleGuess = async () => {
    setError(null);
    const word = guessInput.trim();
    if (!word) return;

    try {
      const res = await api<GuessResp>(`/api/v1/games/${params.gameId}/guess`, {
        method: 'POST',
        body: JSON.stringify({ guessWord: word }),
        gameId: params.gameId,
      });

      if (res.letterResult) {
        setHistory((h) => [...h, {
          guessWord: res.guessWord,
          letterResult: res.letterResult!,
          isCorrect: res.isCorrect,
        }]);
      }

      if (game?.gameType === 'WORDSIM') {
        if (res.inDictionary === false) {
          setError('사전에 없는 단어입니다.');
        } else if (res.similarity != null) {
          const entry: WordSimGuess = {
            guessWord: res.guessWord,
            similarity: res.similarity,
            rank: res.rank,
            isCorrect: res.isCorrect,
          };
          setSimHistory((h) => h.some((x) => x.guessWord === entry.guessWord) ? h : [...h, entry]);
          setLastSimGuess(entry);
        }
      }

      if (game?.gameType === 'LIE_HINT') {
        setLieHistory((h) => [...h, { guessWord: res.guessWord, isCorrect: res.isCorrect }]);
        if (res.liePhase) setLiePhase(true);
      }

      setAttemptCount(res.guessOrder);
      if (res.status === 'SOLVED') {
        setStatus('SOLVED');
        if (res.timeSpentSec != null) setTimeSpentSec(res.timeSpentSec);
      } else if (res.status === 'GAVE_UP') {
        setStatus('GAVE_UP');
        if (res.revealedAnswer) setRevealedAnswer(res.revealedAnswer);
        if (res.timeSpentSec != null) setTimeSpentSec(res.timeSpentSec);
      }

      setGuessInput('');
    } catch (e) {
      setError(e instanceof Error ? e.message : '추측에 실패했습니다.');
    }
  };

  const handleLieHint = async (selectedLieIndex: number) => {
    setError(null);
    try {
      const res = await api<LieHintResp>(`/api/v1/games/${params.gameId}/lie-hint`, {
        method: 'POST',
        body: JSON.stringify({ selectedLieIndex }),
        gameId: params.gameId,
      });
      setLiePhase(false);
      setStatus(res.status);
      if (res.revealedAnswer) setRevealedAnswer(res.revealedAnswer);
      if (res.revealedLieIndex != null) setRevealedLieIndex(res.revealedLieIndex);
      if (res.timeSpentSec != null) setTimeSpentSec(res.timeSpentSec);
    } catch (e) {
      setError(e instanceof Error ? e.message : '거짓 힌트 선택에 실패했습니다.');
    }
  };

  const handleGiveUp = async () => {
    if (!confirm('정말 포기하시겠습니까?')) return;
    try {
      const res = await api<{ answerWord: string; totalAttempts: number; revealedLieIndex: number | null }>(
        `/api/v1/games/${params.gameId}/giveup`,
        { method: 'POST', gameId: params.gameId }
      );
      setStatus('GAVE_UP');
      setLiePhase(false);
      setRevealedAnswer(res.answerWord);
      setRevealedLieIndex(res.revealedLieIndex);
      setAttemptCount(res.totalAttempts);
    } catch (e) {
      setError(e instanceof Error ? e.message : '포기에 실패했습니다.');
    }
  };

  if (error && !game) return <main className="p-8 text-red-500">{error}</main>;
  if (!game) return <main className="p-8 text-gray-400">불러오는 중...</main>;
  if (!started && restoring) return <main className="p-8 text-gray-400">이어하기 정보를 불러오는 중...</main>;

  const shareUrl = typeof window !== 'undefined' ? window.location.href : '';
  const hints = game.lieHints ?? [];

  const resultShareText = game.gameType === 'WORDGUESS'
    ? buildWordGuessShareText({
        solved: status === 'SOLVED',
        attempts: attemptCount,
        maxAttempts: game.maxAttempts ?? 5,
        timeSpentSec,
        history,
        url: shareUrl,
      })
    : game.gameType === 'WORDSIM'
      ? buildWordSimShareText({
          solved: status === 'SOLVED',
          attempts: attemptCount,
          timeSpentSec,
          url: shareUrl,
        })
      : buildLieHintShareText({
          solved: status === 'SOLVED',
          attempts: attemptCount,
          timeSpentSec,
          url: shareUrl,
        });

  if (!started) {
    return (
      <main className="min-h-screen p-8 max-w-xl mx-auto">
        <div className="flex items-baseline justify-between mb-4">
          <h1 className="text-2xl font-bold">{gameLabel(game.gameType)}</h1>
          <div className="flex gap-4 items-baseline text-sm text-gray-500">
            <button onClick={() => router.push('/')} className="underline hover:text-hit">홈</button>
            <button onClick={() => router.push(`/g/${params.gameId}/board`)} className="underline hover:text-hit">
              리더보드
            </button>
          </div>
        </div>

        <div className="bg-gray-50 rounded-lg p-4 mb-4">
          {game.title && <p className="text-base font-bold text-gray-800 mb-1">{game.title}</p>}
          <p className="text-sm text-gray-500">출제자: {game.creatorNick || '익명'}</p>
          {game.gameType === 'WORDGUESS' && game.jamoCount != null && (
            <p className="text-sm text-gray-500">정답 자모 수: {game.jamoCount}개</p>
          )}
          <p className="text-sm text-gray-500">플레이 {game.playCount}회 · 성공 {game.solvedCount}명</p>
          {game.hintText && <p className="mt-2"><span className="font-bold">힌트:</span> {game.hintText}</p>}
          {game.gameType === 'LIE_HINT' && (
            <div className="mt-3 space-y-2">
              <p className="text-sm text-gray-600">
                힌트 3개 중 하나는 거짓입니다. 정답 단어를 맞힌 뒤, 어떤 힌트가 거짓인지까지 골라야 성공입니다.
              </p>
              {hints.map((hint, index) => (
                <div key={index} className="rounded-lg bg-white border px-3 py-2 text-sm">
                  <span className="font-bold text-gray-500">{index + 1}.</span> {hint}
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="mb-6 border border-gray-200 rounded-lg p-4 bg-yellow-50/40">
          <p className="text-xs text-gray-500 mb-2">친구에게 공유</p>
          <p className="text-xs text-gray-700 mb-3 break-all font-mono bg-white rounded p-2 border">{shareUrl}</p>
          <ShareButton url={shareUrl} label="URL 복사" />
        </div>

        <label className="block text-sm font-medium mb-1">닉네임</label>
        <input
          type="text"
          value={nick}
          onChange={(e) => setNick(e.target.value)}
          placeholder="플레이어"
          maxLength={50}
          className="w-full border border-gray-300 rounded-lg px-3 py-2 mb-4"
        />
        {error && <p className="text-red-500 text-sm mb-2">{error}</p>}
        <button onClick={handleStart} className="w-full bg-hit text-white font-bold py-3 rounded-lg hover:opacity-90">
          게임 시작
        </button>
      </main>
    );
  }

  return (
    <main className="min-h-screen p-8 max-w-2xl mx-auto">
      <div className="flex items-baseline justify-between mb-6">
        <h1 className="text-2xl font-bold">{gameLabel(game.gameType)}</h1>
        <div className="flex gap-4 items-baseline text-sm text-gray-500">
          <button onClick={() => router.push('/')} className="underline hover:text-hit">홈</button>
          <button onClick={() => router.push(`/g/${params.gameId}/board`)} className="underline hover:text-hit">
            리더보드
          </button>
        </div>
      </div>

      <div className="flex flex-wrap gap-3 mb-3">
        {game.gameType === 'WORDGUESS' && game.jamoCount != null && (
          <div className="inline-flex items-center gap-2 bg-gray-50 rounded-lg px-3 py-2 text-sm">
            <span className="text-gray-500">정답 자모</span>
            <span className="font-bold text-gray-800">{game.jamoCount}개</span>
          </div>
        )}
        {game.gameType === 'WORDGUESS' && game.maxAttempts != null && (
          <div className="inline-flex items-center gap-2 bg-gray-50 rounded-lg px-3 py-2 text-sm">
            <span className="text-gray-500">남은 시도</span>
            <span className="font-bold text-gray-800">{Math.max(0, game.maxAttempts - attemptCount)}/{game.maxAttempts}</span>
          </div>
        )}
        {game.hintText && <div className="flex-1 min-w-0 bg-yellow-50 rounded-lg px-3 py-2 text-sm">{game.hintText}</div>}
      </div>

      {game.gameType === 'WORDSIM' && game.top1Similarity != null && (
        <div className="mb-6 bg-gray-50 rounded-lg p-3 text-xs grid grid-cols-4 gap-2">
          <ReferenceScoreCell label="1위" sim={game.top1Similarity} />
          <ReferenceScoreCell label="10위" sim={game.top10Similarity} />
          <ReferenceScoreCell label="100위" sim={game.top100Similarity} />
          <ReferenceScoreCell label="1000위" sim={game.top1000Similarity} />
        </div>
      )}

      {game.gameType === 'LIE_HINT' && (
        <div className="mb-6 space-y-3">
          <div className="rounded-lg bg-yellow-50 border border-yellow-200 px-4 py-3 text-sm text-gray-700">
            <p className="font-bold mb-1">게임 방법</p>
            <p>
              아래 힌트 3개 중 <span className="font-bold">하나는 거짓</span>입니다.
              먼저 정답 단어를 추측해 맞히고, 이어서 어떤 힌트가 거짓인지 고르세요.
              정답 단어와 거짓 힌트를 <span className="font-bold">모두</span> 맞혀야 성공입니다.
            </p>
          </div>
          <div className="space-y-2">
            {hints.map((hint, index) => {
              const revealed = status === 'GAVE_UP' && revealedLieIndex === index;
              return (
                <div
                  key={index}
                  className={`rounded-lg border px-4 py-3 text-sm ${
                    revealed ? 'border-red-300 bg-red-50 text-red-700' : 'border-gray-200 bg-gray-50'
                  }`}
                >
                  <span className="font-bold">{index + 1}.</span> {hint}
                  {revealed && <span className="ml-2 font-bold">거짓 힌트</span>}
                </div>
              );
            })}
          </div>
        </div>
      )}

      {game.gameType === 'WORDGUESS' && <HangulBoard history={history} />}

      {game.gameType === 'WORDSIM' && (
        <GuessHistory
          history={simHistory}
          lastGuess={lastSimGuess}
          inputSlot={
            status === 'IN_PROGRESS' ? (
              <form onSubmit={(e) => { e.preventDefault(); handleGuess(); }} className="sticky top-0 z-10 bg-white py-2 flex gap-2">
                <input
                  type="text"
                  value={guessInput}
                  onChange={(e) => setGuessInput(e.target.value)}
                  placeholder="단어 입력"
                  maxLength={20}
                  className="flex-1 border border-gray-300 rounded-lg px-3 py-2"
                />
                <button type="submit" disabled={!guessInput.trim()} className="bg-move text-white font-bold px-6 rounded-lg disabled:opacity-40">
                  추측
                </button>
              </form>
            ) : undefined
          }
        />
      )}

      {status === 'IN_PROGRESS' && (
        <div className="mt-8 space-y-3">
          {game.gameType === 'WORDGUESS' && game.jamoCount && (
            <JamoInputCells
              jamoCount={game.jamoCount}
              value={guessInput}
              onChange={setGuessInput}
              onSubmit={handleGuess}
            />
          )}

          {game.gameType === 'LIE_HINT' && !liePhase && (
            <form onSubmit={(e) => { e.preventDefault(); handleGuess(); }} className="flex gap-2">
              <input
                type="text"
                value={guessInput}
                onChange={(e) => setGuessInput(e.target.value)}
                placeholder="정답 단어 입력"
                maxLength={20}
                className="flex-1 border border-gray-300 rounded-lg px-3 py-2"
              />
              <button type="submit" disabled={!guessInput.trim()} className="bg-move text-white font-bold px-6 rounded-lg disabled:opacity-40">
                추측
              </button>
            </form>
          )}

          {game.gameType === 'LIE_HINT' && liePhase && (
            <div className="border-2 border-move rounded-lg p-4 bg-yellow-50 space-y-3">
              <p className="font-bold text-gray-800">정답입니다. 이제 거짓 힌트를 고르세요.</p>
              <div className="grid grid-cols-1 gap-2">
                {hints.map((hint, index) => (
                  <button
                    key={index}
                    type="button"
                    onClick={() => handleLieHint(index)}
                    className="text-left border border-gray-300 bg-white rounded-lg px-4 py-3 hover:border-move"
                  >
                    <span className="font-bold">{index + 1}.</span> {hint}
                  </button>
                ))}
              </div>
            </div>
          )}

          <button
            type="button"
            onClick={handleGiveUp}
            className="w-full border border-gray-300 py-2 rounded-lg text-gray-500 hover:text-red-500"
          >
            포기
          </button>
        </div>
      )}

      {game.gameType === 'LIE_HINT' && (
        <div className="mt-8">
          <p className="text-sm font-medium text-gray-500 mb-2">시도 내용</p>
          {lieHistory.length === 0 ? (
            <div className="text-center text-gray-400 py-6">아직 시도가 없습니다.</div>
          ) : (
            <div>
              {lieHistory.map((item, index) => (
                <div key={`${item.guessWord}-${index}`} className="flex items-center justify-between border-b py-2">
                  <span><span className="text-gray-400 mr-2">{index + 1}</span>{item.guessWord}</span>
                  <span className={item.isCorrect ? 'font-bold text-hit' : 'text-gray-400'}>
                    {item.isCorrect ? '정답' : '아님'}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {status === 'SOLVED' && (
        <div className="mt-8 space-y-3">
          <div className="bg-green-50 rounded-lg p-4 text-center">
            <span className="font-bold">성공!</span>
            {' · '}
            {game.gameType === 'WORDGUESS' && game.maxAttempts
              ? `${attemptCount}/${game.maxAttempts}`
              : `${attemptCount}번 시도`}
            {timeSpentSec != null && ` · ${formatTime(timeSpentSec)}`}
          </div>
          <ShareResultButton text={resultShareText} label="결과 복사" />
        </div>
      )}

      {status === 'GAVE_UP' && (
        <div className="mt-8 space-y-3">
          <div className="bg-gray-50 rounded-lg p-4 text-center">
            <div>정답은 <span className="font-bold text-hit">{revealedAnswer}</span> 입니다.</div>
            {game.gameType === 'LIE_HINT' && revealedLieIndex != null && (
              <div className="text-sm text-gray-600 mt-1">
                거짓 힌트는 {revealedLieIndex + 1}번입니다.
              </div>
            )}
            <div className="text-sm text-gray-500 mt-1">
              {attemptCount}번 시도{timeSpentSec != null && ` · ${formatTime(timeSpentSec)}`}
            </div>
          </div>
          <ShareResultButton text={resultShareText} label="결과 복사" />
        </div>
      )}

      {error && <p className="text-red-500 text-sm mt-4">{error}</p>}
    </main>
  );
}
