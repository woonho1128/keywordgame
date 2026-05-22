import Link from 'next/link';

export default function HomePage() {
  return (
    <main className="min-h-screen flex flex-col items-center justify-center p-8">
      <h1 className="text-4xl font-bold mb-2">WordPlay</h1>
      <p className="text-gray-500 mb-12">친구들과 즐기는 한국어 단어 게임</p>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6 max-w-5xl w-full">
        <Link
          href="/create/wordguess"
          className="border-2 border-gray-200 rounded-xl p-8 hover:border-hit transition"
        >
          <h2 className="text-2xl font-bold mb-2">WordGuess</h2>
          <p className="text-gray-600">정답의 자모를 맞히는 한국어 Wordle 스타일 게임.</p>
        </Link>

        <Link
          href="/create/wordsim"
          className="border-2 border-gray-200 rounded-xl p-8 hover:border-move transition"
        >
          <h2 className="text-2xl font-bold mb-2">WordSim</h2>
          <p className="text-gray-600">추측 단어가 정답과 얼마나 가까운지 유사도로 겨루는 게임.</p>
        </Link>

        <Link
          href="/create/liehint"
          className="border-2 border-gray-200 rounded-xl p-8 hover:border-move transition"
        >
          <h2 className="text-2xl font-bold mb-2">Lie Hint</h2>
          <p className="text-gray-600">힌트 3개 중 하나의 거짓말까지 찾아야 성공하는 게임.</p>
        </Link>
      </div>
    </main>
  );
}
