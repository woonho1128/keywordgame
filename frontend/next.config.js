/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  async rewrites() {
    // 서버 사이드에서만 평가되므로 NEXT_PUBLIC_ 접두사 불필요.
    // 운영(Oracle Cloud): 8090, 로컬 개발: 8080을 디폴트로.
    const apiBase = process.env.API_BASE || 'http://localhost:8090';
    return [
      {
        source: '/api/:path*',
        destination: `${apiBase}/api/:path*`,
      },
    ];
  },
};

module.exports = nextConfig;
