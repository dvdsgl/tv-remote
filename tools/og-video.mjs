// Renders docs/og.mp4: the 1200x630 social card with the hero animation playing, for
// og:video. The phone markup is taken from docs/index.html, so it always matches the site.
//
//   node tools/og-video.mjs        # needs chromium and ffmpeg (libx264)
//
// Each frame pauses every animation at an exact time (a negative animation-delay) and is
// captured at 2x through the Chrome DevTools Protocol, then piped into ffmpeg.
import { spawn } from "node:child_process";
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const LOOP = 20, FPS = 30, W = 1200, H = 630;

const index = readFileSync(join(root, "docs/index.html"), "utf8");
const start = index.indexOf('<div class="demo"');
const demo = index.slice(start, index.indexOf("<h1>", start)).trim();

const page = `<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="${join(root, "docs/demo.css")}">
<style>
@font-face { font-family: Inter; font-weight: 400; src: url(${join(root, "docs/fonts/inter_regular.ttf")}); }
@font-face { font-family: Inter; font-weight: 500; src: url(${join(root, "docs/fonts/inter_medium.ttf")}); }
@font-face { font-family: Inter; font-weight: 600; src: url(${join(root, "docs/fonts/inter_semibold.ttf")}); }
* { box-sizing: border-box; }
html, body { margin: 0; width: ${W}px; height: ${H}px; overflow: hidden; background: #fff; font-family: Inter; color: #1d1d1f; }
.text { position: absolute; left: 80px; top: 0; bottom: 0; width: 600px; display: flex; flex-direction: column; justify-content: center; }
.eyebrow { font-size: 26px; font-weight: 600; letter-spacing: -0.01em; margin: 0 0 18px; display: flex; align-items: center; gap: 12px; }
.eyebrow img { width: 40px; height: 40px; }
h1 { font-size: 68px; line-height: 1.04; font-weight: 600; letter-spacing: -0.035em; margin: 0; }
p { font-size: 26px; color: #6e6e73; margin: 26px 0 0; line-height: 1.35; }
.phone { position: absolute; right: 110px; top: 18px; }
.phone .demo { --s: .70; box-shadow: inset 0 0 0 1px #5a5a60, inset 0 0 0 3px #0a0a0b, 0 24px 48px -16px rgba(0,0,0,.3); }
.demo, .demo * { animation-delay: calc(var(--d, 0) * -1s) !important; animation-play-state: paused !important; }
</style></head><body>
<div class="text"><div class="eyebrow"><img src="${join(root, "docs/icon.svg")}">Remote</div>
<h1>Droid, meet Lasso.</h1><p>Apple TV remote for Android.<br>Free, with no ads.</p></div>
<div class="phone">${demo}</div>
</body></html>`;

const dir = mkdtempSync(join(tmpdir(), "og-video-"));
const html = join(dir, "card.html");
writeFileSync(html, page);

const chrome = spawn("chromium", ["--headless=new", "--no-sandbox", "--hide-scrollbars", "--remote-debugging-port=9334",
  `--user-data-dir=${join(dir, "profile")}`, "about:blank"], { stdio: "ignore" });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let targets;
for (let i = 0; i < 50 && !targets; i++) { await sleep(200); try { targets = await (await fetch("http://127.0.0.1:9334/json")).json(); } catch {} }
const ws = new WebSocket(targets.find((t) => t.type === "page").webSocketDebuggerUrl);
await new Promise((r) => (ws.onopen = r));
let id = 0; const pending = new Map();
ws.onmessage = (m) => { const d = JSON.parse(m.data); if (pending.has(d.id)) { pending.get(d.id)(d.result); pending.delete(d.id); } };
const send = (method, params = {}) => new Promise((r) => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });

await send("Emulation.setDeviceMetricsOverride", { width: W, height: H, deviceScaleFactor: 2, mobile: false });
await send("Page.enable");
await send("Page.navigate", { url: "file://" + html });
await sleep(1500);

const out = join(root, "docs/og.mp4");
const ffmpeg = spawn("ffmpeg", ["-y", "-loglevel", "error", "-f", "image2pipe", "-framerate", String(FPS), "-i", "-",
  "-vf", `scale=${W}:${H}:flags=lanczos`, "-c:v", "libx264", "-preset", "slow", "-crf", "24", "-pix_fmt", "yuv420p",
  "-movflags", "+faststart", out], { stdio: ["pipe", "inherit", "inherit"] });

const frames = LOOP * FPS;
for (let f = 0; f < frames; f++) {
  await send("Runtime.evaluate", { expression: `document.documentElement.style.setProperty('--d', ${f / FPS})` });
  const { data } = await send("Page.captureScreenshot", { format: "png" });
  if (!ffmpeg.stdin.write(Buffer.from(data, "base64"))) await new Promise((r) => ffmpeg.stdin.once("drain", r));
  if (f % 60 === 0) process.stdout.write(`\r${f}/${frames}`);
}
ffmpeg.stdin.end();
await new Promise((r) => ffmpeg.on("close", r));
ws.close(); chrome.kill(); rmSync(dir, { recursive: true, force: true });
console.log(`\rdocs/og.mp4 (${frames} frames)`);
