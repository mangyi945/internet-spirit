/* 语法检查：抽出 index.html 里的内联 <script> 拼成一个 js，再 node --check
 * 用法：node syntax_check.mjs [文件]
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

const P = process.argv[2] || 'E:\\工\\index.html';
const html = readFileSync(P, 'utf8');
const re = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi;
let m, n = 0, parts = [];
while ((m = re.exec(html))) {
  const attrs = m[1] || '';
  if (/\bsrc\s*=/i.test(attrs)) continue;          // 外链脚本跳过
  if (/type\s*=\s*["'](?!text\/javascript|application\/javascript)/i.test(attrs)) continue;
  n++;
  parts.push('/* ---- block ' + n + ' ---- */\n' + m[2]);
}
const out = process.env.TEMP + '\\hljjs_check.js';
writeFileSync(out, parts.join('\n;\n'), 'utf8');
console.log('blocks', n, 'chars', parts.join('\n;\n').length);
try {
  execFileSync(process.execPath, ['--check', out], { stdio: 'pipe' });
  console.log('SYNTAX OK');
} catch (e) {
  console.log('SYNTAX FAIL');
  console.log(String(e.stderr || e.stdout || e.message).split('\n').slice(0, 12).join('\n'));
  process.exit(1);
}
