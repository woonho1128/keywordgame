import type { Metadata } from 'next';

/**
 * 게임별 Open Graph 메타태그.
 * 카카오톡 등 메신저는 페이지의 og:title / og:description 을 읽어 미리보기
 * 카드를 만든다. page.tsx가 클라이언트 컴포넌트라 metadata를 못 내보내므로
 * 서버 컴포넌트인 이 레이아웃에서 generateMetadata 로 처리한다.
 */

const GAME_TYPE_LABEL: Record<string, string> = {
  WORDGUESS: '🟩 WordGuess',
  WORDSIM: '🔤 WordSim',
};

export async function generateMetadata(
  { params }: { params: { gameId: string } }
): Promise<Metadata> {
  // 서버 사이드 fetch는 Next rewrites를 안 타므로 백엔드 절대주소 사용
  const apiBase = process.env.API_BASE || 'http://localhost:8090';
  try {
    const res = await fetch(`${apiBase}/api/v1/games/${params.gameId}`, {
      cache: 'no-store',
    });
    const json = await res.json();
    const game = json?.data;
    if (!game) return {};

    const typeLabel = GAME_TYPE_LABEL[game.gameType] ?? 'WordPlay';
    const title = game.title || 'WordPlay 게임';
    const descParts = [typeLabel, `출제자 ${game.creatorNick || '익명'}`];
    if (game.hintText) descParts.push(`힌트: ${game.hintText}`);
    const description = descParts.join(' · ');

    return {
      title: `${title} | WordPlay`,
      description,
      openGraph: {
        title,
        description,
        siteName: 'WordPlay',
        locale: 'ko_KR',
        type: 'website',
      },
    };
  } catch {
    return {};
  }
}

export default function GameLayout({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
