/**
 * Spring Boot 백엔드와의 통신 헬퍼.
 * Next.js rewrites로 /api → 백엔드로 프록시.
 */

export type ApiResponse<T> = {
  success: boolean;
  data: T | null;
  error: { code: string; message: string } | null;
};

export type GameType = 'WORDSIM' | 'WORDGUESS';

export async function api<T>(
  path: string,
  init?: RequestInit
): Promise<T> {
  const res = await fetch(path, {
    ...init,
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers || {}),
    },
  });
  const json = (await res.json()) as ApiResponse<T>;
  if (!json.success || json.data === null) {
    throw new Error(json.error?.message || 'Unknown error');
  }
  return json.data;
}
