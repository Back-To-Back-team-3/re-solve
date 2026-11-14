import { loadEnvFile } from "node:process";
import { spawn } from "node:child_process";
import { cpSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
try {
  loadEnvFile(fileURLToPath(new URL("../../.env", import.meta.url)));
} catch (error) {
  if (error.code !== "ENOENT") throw error;
}
const { values } = parseArgs({
  options: {
    port: { type: "string", short: "p", default: process.env.PORT || "3000" },
    hostname: { type: "string", default: "0.0.0.0" },
  },
});
const server = new URL("../.next/standalone/server.js", import.meta.url);
if (!existsSync(server)) throw new Error("먼저 npm run build를 실행해 주세요.");
cpSync(
  new URL("../public/", import.meta.url),
  new URL("../.next/standalone/public/", import.meta.url),
  { recursive: true },
);
cpSync(
  new URL("../.next/static/", import.meta.url),
  new URL("../.next/standalone/.next/static/", import.meta.url),
  { recursive: true },
);
const child = spawn(process.execPath, [fileURLToPath(server)], {
  stdio: "inherit",
  env: { ...process.env, PORT: values.port, HOSTNAME: values.hostname },
});
for (const signal of ["SIGINT", "SIGTERM"])
  process.on(signal, () => child.kill(signal));
child.on("exit", (code) => process.exit(code ?? 0));
