'use client';

import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';

type Row = { rank: number; nick: string; score: number };

export default function Leaderboard({ game, refreshKey, highlight }: { game: string; refreshKey?: number; highlight?: string }) {
  const [rows, setRows] = useState<Row[]>([]);
  const load = useCallback(async () => {
    try { setRows(await api<Row[]>(`/api/v1/scores/${game}?limit=10`)); } catch {}
  }, [game]);
  useEffect(() => { load(); }, [load, refreshKey]);

  return (
    <div className="w-full rounded-xl border border-gray-200 p-3">
      <p className="text-sm font-bold text-gray-600 mb-2">🏆 랭킹 TOP 10</p>
      {rows.length === 0 ? (
        <p className="text-gray-300 text-sm text-center py-3">아직 기록이 없어요</p>
      ) : (
        <div className="space-y-0.5">
          {rows.map((r) => (
            <div key={`${r.rank}-${r.nick}`} className={`flex items-center justify-between text-sm py-0.5 px-1 rounded ${highlight && r.nick === highlight ? 'bg-hit/10' : ''}`}>
              <span className="font-bold text-gray-700">{r.rank <= 3 ? ['🥇', '🥈', '🥉'][r.rank - 1] : `${r.rank}.`} {r.nick}</span>
              <span className="text-hit font-bold">{r.score.toLocaleString()}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
