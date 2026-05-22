/**
 * Spring Boot 백엔드와의 통신 헬퍼.
 * Next.js rewrites로 /api → 백엔드로 프록시.
 *
 * 세션 추적: localStorage('wp_session_<gameId>') 에 sessionKey 저장 후 X-Session-Key 헤더로 전송.
 * 쿠키는 보조 (HttpOnly 환경 대비) — 헤더 우선.
 */

export type ApiResponse<T> = {
  success: boolean;
  data: T | null;
  error: { code: string; message: string } | null;
};

export type GameType = 'WORDSIM' | 'WORDGUESS' | 'LIE_HINT';

const SESSION_KEY_PREFIX = 'wp_session_';

/** 게임별 sessionKey 저장 */
export function setSessionKey(gameId: string, key: string) {
  if (typeof window === 'undefined') return;
  try {
    localStorage.setItem(SESSION_KEY_PREFIX + gameId, key);
  } catch {}
}

/** 게임별 sessionKey 조회 */
export function getSessionKey(gameId: string): string | null {
  if (typeof window === 'undefined') return null;
  try {
    return localStorage.getItem(SESSION_KEY_PREFIX + gameId);
  } catch {
    return null;
  }
}

/**
 * API 호출.
 * @param gameId — 게임 ID (지정 시 해당 게임의 sessionKey를 헤더로 전송)
 */
export async function api<T>(
  path: string,
  init?: RequestInit & { gameId?: string }
): Promise<T> {
  const { gameId, ...rest } = init || {};

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  if (gameId) {
    const sessionKey = getSessionKey(gameId);
    if (sessionKey) headers['X-Session-Key'] = sessionKey;
  }
  Object.assign(headers, rest.headers || {});

  const res = await fetch(path, {
    ...rest,
    credentials: 'include',
    headers,
  });
  const json = (await res.json()) as ApiResponse<T>;
  if (!json.success || json.data === null) {
    throw new Error(json.error?.message || 'Unknown error');
  }
  return json.data;
}
