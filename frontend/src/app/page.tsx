import Link from 'next/link';

export default function HomePage() {
  return (
    <main className="min-h-screen flex flex-col items-center justify-center p-8">
      <h1 className="text-4xl font-bold mb-2">WordPlay</h1>
      <p className="text-gray-500 mb-12">친구들과 즐기는 한국어 단어 게임</p>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6 max-w-3xl w-full">
        <Link
          href="/create/wordguess"
          className="border-2 border-gray-200 rounded-xl p-8 hover:border-hit transition"
        >
          <h2 className="text-2xl font-bold mb-2">🟩 WordGuess</h2>
          <p className="text-gray-600">꼬들형 한글 워들. 정답 자모수에 맞춰 보드가 자동 조정됩니다.</p>
        </Link>

        <div
          className="border-2 border-gray-200 rounded-xl p-8 opacity-50 cursor-not-allowed bg-gray-50"
          title="준비중 — 곧 출시 예정"
        >
          <h2 className="text-2xl font-bold mb-2 flex items-center gap-2">
            🔤 WordSim
            <span className="text-xs font-normal bg-gray-300 text-gray-700 px-2 py-0.5 rounded-full">
              준비중
            </span>
          </h2>
          <p className="text-gray-600">꼬맨틀형 의미 유사도 게임. 추측 단어가 정답과 얼마나 가까운지 점수로 표시.</p>
        </div>
      </div>
    </main>
  );
}
