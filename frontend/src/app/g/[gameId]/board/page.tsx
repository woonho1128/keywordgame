'use client';

import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import { api } from '@/lib/api';

type Entry = {
  rank: number;
  playerNick: string;
  attemptCount: number;
  timeSpentSec: number | null;
  solvedAt: string;
};

type Resp = {
  totalPlayers: number;
  rankings: Entry[];
};

export default function LeaderboardPage() {
  const params = useParams<{ gameId: string }>();
  const [data, setData] = useState<Resp | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Resp>(`/api/v1/games/${params.gameId}/leaderboard?limit=50`)
      .then(setData)
      .catch((e) => setError(e.message));
  }, [params.gameId]);

  if (error) return <main className="p-8 text-red-500">{error}</main>;
  if (!data) return <main className="p-8 text-gray-400">불러오는 중...</main>;

  return (
    <main className="min-h-screen p-8 max-w-2xl mx-auto">
      <div className="flex items-baseline justify-between mb-6">
        <h1 className="text-2xl font-bold">리더보드</h1>
        <Link href={`/g/${params.gameId}`} className="text-sm text-gray-500 underline">
          ← 게임으로
        </Link>
      </div>

      <p className="text-sm text-gray-500 mb-4">총 {data.totalPlayers}명이 정답을 맞췄습니다.</p>

      {data.rankings.length === 0 ? (
        <div className="text-center text-gray-400 py-12">아직 정답자가 없습니다.</div>
      ) : (
        <table className="w-full">
          <thead className="text-left text-xs text-gray-400 border-b">
            <tr>
              <th className="py-2">#</th>
              <th>닉네임</th>
              <th>시도</th>
              <th>시간</th>
            </tr>
          </thead>
          <tbody>
            {data.rankings.map((e) => (
              <tr key={e.rank} className="border-b">
                <td className="py-2 font-bold">{e.rank}</td>
                <td>{e.playerNick}</td>
                <td>{e.attemptCount}회</td>
                <td>{e.timeSpentSec ? `${e.timeSpentSec}초` : '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </main>
  );
}
