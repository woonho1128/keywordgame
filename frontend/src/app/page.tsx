'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { api } from '@/lib/api';

// 코드 해석 결과(게임 키) → localStorage 슬러그. 대부분 경로와 동일, mafia-jobs만 다름.
const GAME_SLUG: Record<string, string> = {
  mafia: 'mafia', 'mafia-jobs': 'jobmafia', avalon: 'avalon', codenames: 'codenames',
  rummikub: 'rummikub', halligalli: 'halligalli', lexio: 'lexio', bingo: 'bingo',
  gartic: 'gartic', othello: 'othello', coup: 'coup', omok: 'omok', horserace: 'horserace',
  sixnimmt: 'sixnimmt', 'tetris-battle': 'tetris-battle', yacht: 'yacht', mojo: 'mojo', yut: 'yut',
};
// 엔트리형(방을 localStorage로 복원하지 않는) 게임 → ?join=CODE 쿼리로 자동 참가
const ENTRY_GAMES = new Set(['sixnimmt', 'tetris-battle', 'yacht', 'mojo', 'yut']);

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
  { href: '/coup', title: '🎴 쿠 (Coup)', desc: '정체를 숨기고 속고 속이는 블러핑 심리전. 의심·차단으로 서로 견제 (봇과 2~6인).', hover: 'move', solo: true, soloLabel: '🤖 봇과 1인 가능' },
  { href: '/omok', title: '⚫ 오목', desc: '5목을 먼저 만들면 승리하는 고전 보드게임. 봇(초·중·고급) 또는 유저와 대전. 자유룰/금수룰.', hover: 'hit', solo: true, soloLabel: '🤖 봇과 1인 가능' },
  { href: '/horserace', title: '🏇 경마', desc: '말에 가상 칩을 걸고 배당을 노리는 경마. 봇과 혼자 또는 여러 명이 배팅 대결(놀이용 칩).', hover: 'move', solo: true, soloLabel: '🤖 봇과 1인 가능' },
  { href: '/tetris', title: '🧱 테트리스', desc: '블록을 쌓아 줄을 지우는 고전 낙하 퍼즐. 마라톤·스프린트 모드 + 랭킹. T-스핀·홀드 지원 (혼자 플레이).', hover: 'hit', solo: true, soloLabel: '🧩 1인 플레이' },
  { href: '/tetris-battle', title: '🧱⚔️ 테트리스 배틀', desc: '줄을 지워 상대에게 방해 줄을 보내는 대전 테트리스. 1v1·배틀로얄(최대 6인), 봇과 혼자도 가능. 우승 랭킹.', hover: 'move', solo: true, soloLabel: '🤖 봇과 1인 가능' },
  { href: '/sixnimmt', title: '🐮 젝스님트', desc: '카드를 동시에 내어 6번째가 되면 벌점을 먹는 눈치 카드게임. 벌점 적게 먹기! 봇과 2~10인.', hover: 'hit', solo: true, soloLabel: '🤖 봇과 1인 가능' },
  { href: '/mojo', title: '🎴 모죠', desc: '카드를 내며 비교하고, 모죠타임에 뒷면 카드를 공개하는 눈치·도박 카드게임. 색상별 최고 숫자만 벌점! 봇과 2~8인.', hover: 'hit', solo: true, soloLabel: '🤖 봇과 1인 가능' },
  { href: '/yut', title: '🎋 윷놀이', desc: '윷을 던져 말 4개를 먼저 빼내는 한국 전통 보드게임. 잡기·업기·지름길·백도, 개인전·팀전(2:2). 봇과 2~4인.', hover: 'move', solo: true, soloLabel: '🤖 봇과 1인 가능' },
  { href: '/marble', title: '🎱 마블 레이스', desc: '이름을 넣으면 귀여운 마블들이 물리 코스를 튕기며 내려가 순위를 정하는 랜덤 뽑기. 누가 살지·순서 정하기 (혼자 플레이).', hover: 'hit', solo: true, soloLabel: '🧩 1인 플레이' },
  { href: '/2048', title: '🔢 2048', desc: '같은 숫자 타일을 밀어 합쳐 2048을 만드는 중독성 퍼즐. 최고 점수 랭킹 (혼자 플레이).', hover: 'hit', solo: true, soloLabel: '🧩 1인 플레이' },
  { href: '/yacht', title: '🎲 야찌', desc: '주사위 5개를 굴려 족보를 채우는 다이스 게임. 친구·봇과 방에서 대결하고 최고 점수 랭킹 도전. 봇과 1인~8인.', hover: 'hit', solo: true, soloLabel: '🤖 봇과 1인 가능' },
  { href: '/gartic', title: '🎨 그림 텔레폰', desc: '문장을 그림으로, 그림을 문장으로 넘기며 엉뚱하게 변해가는 갈틱폰 (3~10인).', hover: 'hit', solo: false },
  { href: '/bingo', title: '🔢 빙고', desc: '3×3~5×5 판을 채우고, 뽑히는 숫자로 먼저 빙고 줄을 완성하면 승리 (2~8인).', hover: 'hit', solo: false },
  { href: '/snake', title: '🐍 지렁이', desc: '먹이를 먹고 커지며 봇과 경쟁하는 지렁이 게임. 랭킹 등록 (혼자 플레이).', hover: 'hit', solo: true, soloLabel: '🤖 봇과 1인 가능' },
  { href: '/territory', title: '🗺️ 땅따먹기', desc: '영역을 그려 땅을 넓히고 봇과 경쟁하는 게임. 랭킹 등록 (혼자 플레이).', hover: 'hit', solo: true, soloLabel: '🤖 봇과 1인 가능' },
  { href: '/monopoly', title: '🏙️ 부루마블', desc: '도시를 사고 건물을 올려 통행료로 상대를 파산시키는 보드게임. 독점 라인·랜드마크·인수·황금열쇠. 봇과 2~4인. (개발 중 · 목업)', hover: 'move', solo: true, soloLabel: '🛠️ 개발 중' },
];

export default function HomePage() {
  const [soloOnly, setSoloOnly] = useState(false);
  const games = soloOnly ? GAMES.filter((g) => g.solo) : GAMES;
  const soloCount = GAMES.filter((g) => g.solo).length;

  const [joinNick, setJoinNick] = useState('');
  const [joinCode, setJoinCode] = useState('');
  const [joinErr, setJoinErr] = useState('');
  const [joining, setJoining] = useState(false);

  useEffect(() => { try { setJoinNick(localStorage.getItem('arcade_nick') || ''); } catch {} }, []);

  const quickJoin = async () => {
    const nick = joinNick.trim();
    const code = joinCode.trim().toUpperCase();
    if (!nick) { setJoinErr('닉네임을 입력하세요'); return; }
    if (code.length < 4) { setJoinErr('코드 4자리를 입력하세요'); return; }
    setJoining(true); setJoinErr('');
    try {
      const res = await api<{ game: string; code: string }>(`/api/v1/rooms/resolve?code=${encodeURIComponent(code)}`);
      const game = res.game;
      const slug = GAME_SLUG[game] || game;
      try {
        localStorage.setItem('arcade_nick', nick);
        localStorage.setItem(`${slug}_nick`, nick);
        localStorage.setItem(`${slug}_room`, res.code);
      } catch {}
      // 엔트리형은 ?join=으로 완전 자동 참가, 그 외는 방 로비로 진입(닉 미리 채워짐)
      window.location.href = ENTRY_GAMES.has(game) ? `/${game}?join=${res.code}` : `/${game}`;
    } catch (e) {
      setJoinErr(e instanceof Error ? e.message : '방을 찾을 수 없습니다');
      setJoining(false);
    }
  };

  return (
    <main className="min-h-screen flex flex-col items-center justify-center p-8">
      <h1 className="text-5xl font-extrabold mb-2 tracking-tight">🎮 gg</h1>
      <p className="text-gray-500 mb-6">친구들과 모여 하는 파티·보드게임</p>

      <div className="mb-6 w-full max-w-md rounded-2xl border-2 border-gray-200 p-4">
        <p className="text-sm font-bold text-gray-600 mb-2">🔑 코드로 방 바로 입장</p>
        <div className="flex flex-col sm:flex-row gap-2">
          <input value={joinNick} onChange={(e) => setJoinNick(e.target.value)} maxLength={16} placeholder="닉네임 (필수)"
            className="flex-1 border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-hit" />
          <div className="flex gap-2">
            <input value={joinCode} onChange={(e) => { setJoinCode(e.target.value.toUpperCase()); setJoinErr(''); }} maxLength={4}
              onKeyDown={(e) => e.key === 'Enter' && quickJoin()} placeholder="코드" inputMode="text"
              className="w-24 border border-gray-300 rounded-lg px-3 py-2 text-sm uppercase tracking-widest font-bold focus:outline-none focus:border-hit" />
            <button onClick={quickJoin} disabled={joining || !joinNick.trim() || joinCode.trim().length < 4}
              className="px-5 bg-hit text-white font-bold rounded-lg text-sm disabled:opacity-40">{joining ? '…' : '입장'}</button>
          </div>
        </div>
        {joinErr && <p className="text-red-500 text-xs mt-2">{joinErr}</p>}
        <p className="text-[11px] text-gray-400 mt-2">친구가 만든 방 코드를 입력하면 해당 게임 방으로 바로 들어가요.</p>
      </div>

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
