'use client';

import Link from 'next/link';
import { useState } from 'react';

type Game = {
  href: string;
  title: string;
  desc: string;
  hover: 'hit' | 'move';
  solo: boolean;      // 혼자(1인) 플레이 가능
  soloLabel?: string; // 배지 문구
};

const GAMES: Game[] = [
  { href: '/create/wordguess', title: 'WordGuess', desc: '정답의 자모를 맞히는 한국어 Wordle 스타일 게임 (혼자 플레이).', hover: 'hit', solo: true, soloLabel: '🧩 1인 플레이' },
  { href: '/spyfall', title: '🕵️ 스파이폴', desc: '각자 폰으로 접속해 역할을 확인하고, 숨은 스파이를 찾는 게임 (3~12인).', hover: 'hit', solo: false },
  { href: '/mafia', title: '🎭 마피아', desc: '밤·낮으로 자동 진행되는 마피아. 각자 폰으로 접속 (4~12인).', hover: 'move', solo: false },
  { href: '/mafia-jobs', title: '🕵️‍♂️ 직업 마피아', desc: '경찰·의사·정신병자·관종 등 직업이 있는 마피아 (5~12인).', hover: 'move', solo: false },
  { href: '/avalon', title: '🏰 아발론', desc: '선과 악으로 나뉘어 원정을 다투는 추리 게임 (5~10인).', hover: 'hit', solo: false },
  { href: '/codenames', title: '🔡 코드네임', desc: '두 팀으로 나눠 팀장의 힌트로 단어를 맞히는 팀 게임 (4~8인).', hover: 'hit', solo: false },
  { href: '/rummikub', title: '🁢 루미큐브', desc: '타일로 세트를 만들어 먼저 다 내려놓는 사람이 이기는 게임. 봇과 1인~4인.', hover: 'hit', solo: true, soloLabel: '🤖 봇과 1인 가능' },
  { href: '/halligalli', title: '🔔 할리갈리', desc: '같은 과일 5개가 뜨면 먼저 종을 치는 실시간 순발력 게임. 봇과 1인~6인.', hover: 'hit', solo: true, soloLabel: '🤖 봇과 1인 가능' },
  { href: '/lexio', title: '🀫 렉시오', desc: '타일로 족보를 만들어 먼저 다 내려놓는 빅투 계열 게임. 봇과 1인~5인.', hover: 'hit', solo: true, soloLabel: '🤖 봇과 1인 가능' },
  { href: '/othello', title: '⚫⚪ 오델로', desc: '돌을 뒤집어 더 많이 차지하는 8×8 리버시. 봇(초·중·고급·초고수)과 1인 또는 유저 대전.', hover: 'hit', solo: true, soloLabel: '🤖 봇과 1인 가능' },
  { href: '/gartic', title: '🎨 그림 텔레폰', desc: '문장을 그림으로, 그림을 문장으로 넘기며 엉뚱하게 변해가는 갈틱폰 (3~10인).', hover: 'hit', solo: false },
  { href: '/bingo', title: '🔢 빙고', desc: '3×3~5×5 판을 채우고, 뽑히는 숫자로 먼저 빙고 줄을 완성하면 승리 (2~8인).', hover: 'hit', solo: false },
  { href: '/snake', title: '🐍 지렁이', desc: '먹이를 먹고 커지며 봇과 경쟁하는 지렁이 게임. 랭킹 등록 (혼자 플레이).', hover: 'hit', solo: true, soloLabel: '🤖 봇과 1인 가능' },
  { href: '/territory', title: '🗺️ 땅따먹기', desc: '영역을 그려 땅을 넓히고 봇과 경쟁하는 게임. 랭킹 등록 (혼자 플레이).', hover: 'hit', solo: true, soloLabel: '🤖 봇과 1인 가능' },
];

export default function HomePage() {
  const [soloOnly, setSoloOnly] = useState(false);
  const games = soloOnly ? GAMES.filter((g) => g.solo) : GAMES;
  const soloCount = GAMES.filter((g) => g.solo).length;

  return (
    <main className="min-h-screen flex flex-col items-center justify-center p-8">
      <h1 className="text-5xl font-extrabold mb-2 tracking-tight">🎮 gg</h1>
      <p className="text-gray-500 mb-6">친구들과 모여 하는 파티·보드게임</p>

      <div className="mb-8">
        <button
          onClick={() => setSoloOnly((v) => !v)}
          className={`inline-flex items-center gap-2 rounded-full border-2 px-5 py-2 text-sm font-bold transition ${
            soloOnly ? 'border-hit bg-hit text-white' : 'border-gray-200 text-gray-500 hover:border-hit'
          }`}
        >
          <span className={`inline-flex items-center justify-center w-4 h-4 rounded border-2 ${soloOnly ? 'border-white bg-white text-hit' : 'border-gray-300'}`}>
            {soloOnly && '✓'}
          </span>
          🤖 혼자 가능한 게임만 ({soloCount})
        </button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6 max-w-5xl w-full">
        {games.map((g) => (
          <Link
            key={g.href}
            href={g.href}
            className={`relative border-2 border-gray-200 rounded-xl p-8 transition ${g.hover === 'move' ? 'hover:border-move' : 'hover:border-hit'}`}
          >
            {g.solo && (
              <span className="absolute top-3 right-3 text-[11px] font-bold bg-hit/10 text-hit rounded-full px-2 py-0.5">
                {g.soloLabel}
              </span>
            )}
            <h2 className="text-2xl font-bold mb-2">{g.title}</h2>
            <p className="text-gray-600">{g.desc}</p>
          </Link>
        ))}
      </div>

      {games.length === 0 && <p className="text-gray-400 mt-10">해당하는 게임이 없어요.</p>}
    </main>
  );
}
