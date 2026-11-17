import type { NextConfig } from "next";
const config: NextConfig = {
  output: "standalone",
  devIndicators: false,
  agentRules: false,
  async rewrites() {
    const apiUrl = process.env.API_URL ?? "http://localhost:8080";
    return [
      {
        source: "/api/v1/:path((?!auth/github(?:/callback)?/?$).+)?",
        destination: `${apiUrl}/api/v1/:path*`,
      },
    ];
  },
};
export default config;
