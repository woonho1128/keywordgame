import Link from 'next/link';

export default function HomePage() {
  return (
    <main className="min-h-screen flex flex-col items-center justify-center p-8">
      <h1 className="text-5xl font-extrabold mb-2 tracking-tight">🎮 gg</h1>
      <p className="text-gray-500 mb-12">친구들과 모여 하는 파티·보드게임</p>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6 max-w-5xl w-full">
        <Link
          href="/create/wordguess"
          className="border-2 border-gray-200 rounded-xl p-8 hover:border-hit transition"
        >
          <h2 className="text-2xl font-bold mb-2">WordGuess</h2>
          <p className="text-gray-600">정답의 자모를 맞히는 한국어 Wordle 스타일 게임.</p>
        </Link>

        <Link
          href="/spyfall"
          className="border-2 border-gray-200 rounded-xl p-8 hover:border-hit transition"
        >
          <h2 className="text-2xl font-bold mb-2">🕵️ 스파이폴</h2>
          <p className="text-gray-600">각자 폰으로 접속해 역할을 확인하고, 숨은 스파이를 찾는 게임 (3~12인).</p>
        </Link>

        <Link
          href="/mafia"
          className="border-2 border-gray-200 rounded-xl p-8 hover:border-move transition"
        >
          <h2 className="text-2xl font-bold mb-2">🎭 마피아</h2>
          <p className="text-gray-600">밤·낮으로 자동 진행되는 마피아. 각자 폰으로 접속 (4~12인).</p>
        </Link>

        <Link
          href="/mafia-jobs"
          className="border-2 border-gray-200 rounded-xl p-8 hover:border-move transition"
        >
          <h2 className="text-2xl font-bold mb-2">🕵️‍♂️ 직업 마피아</h2>
          <p className="text-gray-600">경찰·의사·정신병자·관종 등 직업이 있는 마피아 (5~12인).</p>
        </Link>

        <Link
          href="/avalon"
          className="border-2 border-gray-200 rounded-xl p-8 hover:border-hit transition"
        >
          <h2 className="text-2xl font-bold mb-2">🏰 아발론</h2>
          <p className="text-gray-600">선과 악으로 나뉘어 원정을 다투는 추리 게임 (5~10인).</p>
        </Link>

        <Link
          href="/codenames"
          className="border-2 border-gray-200 rounded-xl p-8 hover:border-hit transition"
        >
          <h2 className="text-2xl font-bold mb-2">🔡 코드네임</h2>
          <p className="text-gray-600">두 팀으로 나눠 팀장의 힌트로 단어를 맞히는 팀 게임 (4~8인).</p>
        </Link>

        <Link
          href="/rummikub"
          className="border-2 border-gray-200 rounded-xl p-8 hover:border-hit transition"
        >
          <h2 className="text-2xl font-bold mb-2">🁢 루미큐브</h2>
          <p className="text-gray-600">타일로 세트를 만들어 먼저 다 내려놓는 사람이 이기는 게임 (2~4인).</p>
        </Link>

        <Link
          href="/halligalli"
          className="border-2 border-gray-200 rounded-xl p-8 hover:border-hit transition"
        >
          <h2 className="text-2xl font-bold mb-2">🔔 할리갈리</h2>
          <p className="text-gray-600">같은 과일 5개가 뜨면 먼저 종을 치는 실시간 순발력 게임 (2~6인).</p>
        </Link>

        <Link
          href="/gartic"
          className="border-2 border-gray-200 rounded-xl p-8 hover:border-hit transition"
        >
          <h2 className="text-2xl font-bold mb-2">🎨 그림 텔레폰</h2>
          <p className="text-gray-600">문장을 그림으로, 그림을 문장으로 넘기며 엉뚱하게 변해가는 갈틱폰 (3~10인).</p>
        </Link>

        <Link
          href="/bingo"
          className="border-2 border-gray-200 rounded-xl p-8 hover:border-hit transition"
        >
          <h2 className="text-2xl font-bold mb-2">🔢 빙고</h2>
          <p className="text-gray-600">3×3~5×5 판을 채우고, 뽑히는 숫자로 먼저 빙고 줄을 완성하면 승리 (2~8인).</p>
        </Link>
      </div>
    </main>
  );
}
