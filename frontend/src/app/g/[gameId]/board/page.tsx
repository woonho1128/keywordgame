'use client';

import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import { api } from '@/lib/api';

type Entry = {
  rank: number | null;
  playerNick: string;
  status: 'SOLVED' | 'GAVE_UP';
  attemptCount: number;
  timeSpentSec: number | null;
  finishedAt: string;
  selectedLieIndex: number | null;
};

type Resp = {
  totalPlayers: number;
  rankings: Entry[];
  failures: Entry[];
  detailVisible: boolean;
};

/** 선택 상세 한 줄 텍스트 (게임을 끝낸 사람에게만 노출). */
function detailText(e: Entry): string {
  if (e.selectedLieIndex != null) {
    const mark = e.status === 'SOLVED' ? '정답' : '오답';
    return `거짓 힌트 ${e.selectedLieIndex + 1}번 선택 · ${mark}`;
  }
  return '거짓 힌트를 고르기 전에 포기';
}

export default function LeaderboardPage() {
  const params = useParams<{ gameId: string }>();
  const [data, setData] = useState<Resp | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  useEffect(() => {
    // gameId를 넘겨 X-Session-Key 헤더를 함께 보냄 (상세 공개 자격 판정용)
    api<Resp>(`/api/v1/games/${params.gameId}/leaderboard?limit=50`, { gameId: params.gameId })
      .then(setData)
      .catch((e) => setError(e.message));
  }, [params.gameId]);

  if (error) return <main className="p-8 text-red-500">{error}</main>;
  if (!data) return <main className="p-8 text-gray-400">불러오는 중...</main>;

  const toggle = (key: string) => {
    setExpanded((cur) => {
      const next = new Set(cur);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const renderEntry = (e: Entry, key: string, leftLabel: string) => {
    const isOpen = expanded.has(key);
    return (
      <div key={key} className="border-b">
        <div
          className={`flex items-center justify-between py-2 ${
            data.detailVisible ? 'cursor-pointer hover:bg-gray-50' : ''
          }`}
          onClick={data.detailVisible ? () => toggle(key) : undefined}
        >
          <div className="flex items-center gap-3">
            <span className="font-bold text-gray-400 w-6 text-center">{leftLabel}</span>
            <span>{e.playerNick}</span>
            <span
              className={`text-xs px-1.5 py-0.5 rounded ${
                e.status === 'SOLVED'
                  ? 'bg-green-100 text-green-700'
                  : 'bg-red-100 text-red-600'
              }`}
            >
              {e.status === 'SOLVED' ? '성공' : '실패'}
            </span>
          </div>
          <div className="flex items-center gap-3 text-sm text-gray-500">
            <span>{e.attemptCount}회</span>
            <span>{e.timeSpentSec != null ? `${e.timeSpentSec}초` : '-'}</span>
            {data.detailVisible && (
              <span className="text-gray-300">{isOpen ? '▲' : '▼'}</span>
            )}
          </div>
        </div>
        {data.detailVisible && isOpen && (
          <div className="pb-2 pl-9 text-sm text-gray-600">{detailText(e)}</div>
        )}
      </div>
    );
  };

  return (
    <main className="min-h-screen p-8 max-w-2xl mx-auto">
      <div className="flex items-baseline justify-between mb-6">
        <h1 className="text-2xl font-bold">🏆 리더보드</h1>
        <div className="flex gap-4 items-baseline text-sm text-gray-500">
          <Link href="/" className="underline hover:text-hit">🏠 홈</Link>
          <Link href={`/g/${params.gameId}`} className="underline hover:text-hit">← 게임으로</Link>
        </div>
      </div>

      <p className="text-sm text-gray-500 mb-4">총 {data.totalPlayers}명이 정답을 맞췄습니다.</p>

      {data.rankings.length === 0 ? (
        <div className="text-center text-gray-400 py-12">아직 정답자가 없습니다.</div>
      ) : (
        <div>
          {data.rankings.map((e) => renderEntry(e, `s-${e.rank}`, String(e.rank)))}
        </div>
      )}

      {data.failures.length > 0 && (
        <div className="mt-8">
          <h2 className="text-sm font-bold text-gray-500 mb-2">실패한 도전 ({data.failures.length})</h2>
          <div>
            {data.failures.map((e, i) => renderEntry(e, `f-${i}`, '·'))}
          </div>
        </div>
      )}

      {data.failures.length > 0 && !data.detailVisible && (
        <p className="mt-4 text-xs text-gray-400">
          게임을 끝내면 각 도전자가 거짓 힌트로 무엇을 골랐는지 볼 수 있습니다.
        </p>
      )}
    </main>
  );
}
