'use client';

export default function CreateWordSimPage() {
  return (
    <main className="min-h-screen p-8 max-w-2xl mx-auto">
      <h1 className="text-3xl font-bold mb-6">WordSim 출제</h1>
      <p className="text-gray-500">
        TODO: 정답 단어, 힌트, 닉네임 입력 폼 + POST /api/v1/games 호출
      </p>
      <p className="text-sm text-gray-400 mt-2">
        ⚠️ Phase 3에서 활성화 — WordSim 사전 적재 후 사용 가능
      </p>
    </main>
  );
}
