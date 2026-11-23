const $ = selector => document.querySelector(selector);
const stage = $('#stage'), boot = $('#boot'), art = $('#rain'), app = $('#app'), fill = $('#timelineFill');
const studies = {
  type: { name: '字标穿行', english: 'TYPE PORTAL', category: '排版 / 字形蒙版', description: '极简字标成为通往工作区的窗口。视线穿过不断放大的字形，进入主界面。', icon: '<path d="M3 5h18M12 5v15M7 20h10"/>' },
  cursor: { name: '游标换行', english: 'ONE MORE LINE', category: '终端 / 换行滚动', description: '从一条简洁的命令开始。游标划过，启动页像终端中的上一行，向上滚出工作区。', icon: '<path d="m3 7 5 5-5 5M12 17h9"/>' },
  louvers: { name: '精密百叶', english: 'MEASURED LIGHT', category: '机械 / 错峰翻转', description: '细分的黑色竖片从中心向两侧依次翻开。几何、克制，主界面保持稳定。', icon: '<path d="M4 4v16M9 4v16M15 4v16M20 4v16"/>' },
  paper: { name: '折页留白', english: 'THE NEXT PAGE', category: '编辑 / 斜向翻页', description: '大字与留白构成一张黑色封面。右下角翻起灰白纸背，沿对角线翻到工作区。', icon: '<path d="M4 3h16v12l-5 6H4ZM15 21v-6h5"/>' },
  iris: { name: '镜头暗房', english: 'APERTURE 08', category: '摄影 / 多叶光圈', description: '从镜头标记与同心圆开始。八边形光圈旋开，像在暗房里打开一束光。', icon: '<path d="m8 3 9 1 5 8-4 8-9 2-7-7 1-8Z"/><path d="m9 8 7 1 1 6-6 3-5-5Z"/>' },
  pixels: { name: '点阵退场', english: 'ORDERED SILENCE', category: '数字 / 有序消隐', description: '黑白点阵与字标构成数字封面。像素沿带有随机性的波面逐格熄灭，露出工作区。', icon: '<path d="M3 3h4v4H3ZM10 3h4v4h-4ZM17 3h4v4h-4ZM3 10h4v4H3ZM10 10h4v4h-4ZM3 17h4v4H3Z"/>' },
  signal: { name: '频段断层', english: 'SIGNAL / OFFSET', category: '信号 / 横向硬切', description: '黑白信号短促错位，随后切成宽窄不同的条带，向左右撕开。保留故障感，没有彩色光效。', icon: '<path d="M3 5h14M7 10h14M2 15h13M9 20h12"/>' },
  gravity: { name: '引力汇点', english: 'ZERO POINT', category: '空间 / 向心收束', description: '几何标记与轨道围绕一个中心。整个启动画面向中心收束，工作区在四周显露。', icon: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="4"/><path d="M12 1v4M12 19v4M1 12h4M19 12h4"/>' },
};
const keys = Object.keys(studies);
let selected = 'type', generation = 0, frame, finishFrame, timer, animations = [];
let width = 1, height = 1, ratio = 1;
const fx = document.createElement('canvas'); fx.className = 'effect-canvas'; stage.appendChild(fx);
const clamp = value => Math.max(0, Math.min(1, value));
const smooth = value => { const t = clamp(value); return t * t * (3 - 2 * t); };
const easeOut = value => 1 - (1 - clamp(value)) ** 3;
function random(seed = 71) { return () => { seed = seed * 16807 % 2147483647; return (seed - 1) / 2147483646; }; }
function word(ctx, text, x, y, size, color = '#eee', align = 'center', font = 'Consolas, monospace', weight = '400') {
  ctx.font = `${weight} ${size}px ${font}`; ctx.fillStyle = color; ctx.textAlign = align; ctx.textBaseline = 'middle'; ctx.fillText(text, x, y);
}
function mark(ctx, x, y, size, color = '#ddd') {
  ctx.fillStyle = color; ctx.fillRect(x - size / 2, y - size / 2, size, size);
  ctx.fillStyle = '#050505'; ctx.fillRect(x - size * .22, y - size * .24, size * .43, size * .48);
}
function label(ctx, text, y = height - 38, align = 'center', x = width / 2) { word(ctx, text, x, y, 9, '#747474', align); }
function hairline(ctx, x, y, w, h = 1, color = '#333') { ctx.fillStyle = color; ctx.fillRect(x, y, w, h); }
function baseScene(ctx) {
  ctx.fillStyle = '#030303'; ctx.fillRect(0, 0, width, height);
  const size = Math.min(width * .085, 85);
  if (selected === 'type') {
    word(ctx, 'OpenCode', width / 2, height * .49, size, '#f5f5f5', 'center', 'Consolas, monospace', '600');
    label(ctx, 'A SPACE FOR YOUR NEXT IDEA', height * .62); hairline(ctx, width * .47, height * .68, width * .06, 2, '#bbb');
  } else if (selected === 'cursor') {
    label(ctx, 'LOCAL WORKSPACE / READY', height * .24, 'left', width * .14);
    word(ctx, '> opencode', width * .14, height * .47, Math.min(44, width * .06), '#e7e7e7', 'left');
    hairline(ctx, width * .14, height * .58, width * .72, 1, '#343434');
    label(ctx, 'ubuntu@local:~/workspace', height * .67, 'left', width * .14);
    hairline(ctx, width * .14, height * .75, 10, 18, '#ddd');
  } else if (selected === 'louvers') {
    for (let i = 1; i < 14; i++) hairline(ctx, width * i / 14, 28, 1, height - 56, '#171717');
    word(ctx, 'OpenCode', width / 2, height * .48, size * .8, '#eee');
    label(ctx, 'PRECISION / 014', height * .64);
    for (let i = 0; i < 14; i++) label(ctx, String(i + 1).padStart(2, '0'), height - 30, 'center', width * (i + .5) / 14);
  } else if (selected === 'paper') {
    const left = width * .12;
    word(ctx, 'Open', left, height * .34, Math.min(height * .23, width * .17), '#e4e4e4', 'left', 'Georgia, serif');
    word(ctx, 'Code.', left, height * .57, Math.min(height * .23, width * .17), '#e4e4e4', 'left', 'Georgia, serif');
    hairline(ctx, left, height * .76, width * .76, 1, '#777');
    label(ctx, 'A NEW CHAPTER', height * .83, 'left', left); label(ctx, '01 / ∞', height * .83, 'right', width * .88);
  } else if (selected === 'iris') {
    const radius = Math.min(width, height) * .31;
    ctx.strokeStyle = '#292929'; ctx.lineWidth = 1;
    for (const r of [radius, radius + 13, radius + 15]) { ctx.beginPath(); ctx.arc(width / 2, height / 2, r, 0, Math.PI * 2); ctx.stroke(); }
    for (let i = 0; i < 48; i++) { const a = i * Math.PI / 24; ctx.beginPath(); ctx.moveTo(width / 2 + Math.cos(a) * (radius + 22), height / 2 + Math.sin(a) * (radius + 22)); ctx.lineTo(width / 2 + Math.cos(a) * (radius + (i % 6 ? 26 : 33)), height / 2 + Math.sin(a) * (radius + (i % 6 ? 26 : 33))); ctx.stroke(); }
    mark(ctx, width / 2, height * .45, 32); word(ctx, 'OpenCode', width / 2, height * .57, 20); label(ctx, 'APERTURE / 08');
  } else if (selected === 'pixels') {
    const step = 18;
    for (let x = step; x < width; x += step) for (let y = step; y < height; y += step) {
      const distance = Math.hypot((x - width / 2) / width, (y - height / 2) / height);
      const r = Math.max(.4, 2.2 - distance * 3); ctx.fillStyle = '#444'; ctx.fillRect(x - r / 2, y - r / 2, r, r);
    }
    ctx.fillStyle = '#030303'; ctx.fillRect(width * .23, height * .36, width * .54, height * .28);
    word(ctx, 'OpenCode', width / 2, height * .48, size * .85, '#eee', 'center', 'Consolas, monospace', '600'); label(ctx, 'EVERY PIXEL / A POSSIBILITY', height * .63);
  } else if (selected === 'signal') {
    for (let y = 0; y < height; y += 4) hairline(ctx, 0, y, width, 1, '#0c0c0c');
    label(ctx, 'FREQUENCY // LOCAL', 38, 'left', 30); label(ctx, '100.00%', 38, 'right', width - 30);
    word(ctx, 'OpenCode', width / 2, height * .48, size, '#eee', 'center', 'Consolas, monospace', '600');
    hairline(ctx, width * .27, height * .64, width * .46, 1, '#666'); label(ctx, 'SIGNAL LOCKED', height * .71);
  } else {
    const r = Math.min(width, height) * .3; ctx.strokeStyle = '#292929';
    for (let i = 1; i <= 4; i++) { ctx.beginPath(); ctx.ellipse(width / 2, height / 2, r * i / 3, r * i / 5, -.35, 0, Math.PI * 2); ctx.stroke(); }
    ctx.fillStyle = '#030303'; ctx.fillRect(width / 2 - 76, height / 2 - 48, 152, 96);
    mark(ctx, width / 2, height / 2 - 14, 38); word(ctx, 'OpenCode', width / 2, height / 2 + 33, 17); label(ctx, 'ALL IDEAS BEGIN AT A POINT');
  }
  label(ctx, 'OPENCODE / READY', height - 18, 'left', 20);
}
function sizeCanvases() {
  const r = stage.getBoundingClientRect(); width = r.width; height = r.height; ratio = Math.min(devicePixelRatio, 1.5);
  for (const c of [art, fx]) { c.width = Math.round(width * ratio); c.height = Math.round(height * ratio); }
  const ctx = art.getContext('2d'); ctx.scale(ratio, ratio); baseScene(ctx);
}
function imageDraw(ctx, image, x = 0, y = 0, w = width, h = height) { ctx.drawImage(image, 0, 0, image.width, image.height, x, y, w, h); }
function typePortal(image) {
  return (ctx, p) => {
    const cover = 1 - smooth((p - .78) / .22);
    ctx.globalAlpha = cover; ctx.fillStyle = '#030303'; ctx.fillRect(0, 0, width, height);
    if (p < .15) { ctx.globalAlpha = cover * (1 - smooth(p / .15)); imageDraw(ctx, image); }
    ctx.globalAlpha = cover;
    const expand = easeOut((p - .06) / .94), size = Math.min(width * .085, 85) * (1 + expand ** 3 * 42);
    if (p > .06) {
      ctx.globalCompositeOperation = 'destination-out';
      word(ctx, 'OpenCode', width / 2 - expand ** 2 * width * .13, height * .49, size, '#fff', 'center', 'Consolas, monospace', '600');
      ctx.globalCompositeOperation = 'source-over';
    }
    ctx.globalAlpha = 1;
  };
}
function cursorLine(image) {
  return (ctx, p) => {
    const travel = easeOut(p / .38), lift = smooth((p - .32) / .68);
    imageDraw(ctx, image, 0, -height * lift);
    const y = height * .58 - height * lift, x = width * (.14 + .72 * travel);
    hairline(ctx, x, y - 8, 9, 17, '#eee');
    if (p < .45) hairline(ctx, width * .14, y, width * .72 * travel, 1, '#b0b0b0');
  };
}
function precisionLouvers(image) {
  return (ctx, p) => {
    const count = 14, w = width / count;
    for (let i = 0; i < count; i++) {
      const delay = Math.abs(i - (count - 1) / 2) * .022, t = smooth((p - delay) / .7), visibleWidth = w * Math.cos(t * Math.PI / 2);
      if (visibleWidth < .1) continue;
      const x = i * w + (w - visibleWidth) / 2;
      ctx.drawImage(image, i * w * ratio, 0, Math.min(w * ratio + 1, image.width - i * w * ratio), image.height, x, 0, visibleWidth, height);
      if (t > .05 && t < .95) hairline(ctx, x, 0, 1, height, '#888');
    }
  };
}
function paperFold(image) {
  return (ctx, p) => {
    const t = smooth(p), cut = (1 - t) * (width + height);
    ctx.save(); ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(cut, 0); ctx.lineTo(0, cut); ctx.closePath(); ctx.clip(); imageDraw(ctx, image); ctx.restore();
    const thickness = Math.sin(p * Math.PI) * Math.min(width, height) * .13;
    if (thickness > .1) {
      const shade = ctx.createLinearGradient(0, cut - thickness, 0, cut); shade.addColorStop(0, '#a5a5a5'); shade.addColorStop(1, '#eee');
      ctx.save(); ctx.beginPath(); ctx.moveTo(cut - thickness, 0); ctx.lineTo(cut, 0); ctx.lineTo(0, cut); ctx.lineTo(0, cut - thickness); ctx.closePath(); ctx.fillStyle = shade; ctx.fill(); ctx.restore();
    }
  };
}
function aperture(image) {
  return (ctx, p) => {
    imageDraw(ctx, image); const radius = smooth(p) * Math.hypot(width, height) * .57, turn = (1 - p) * .48;
    ctx.save(); ctx.translate(width / 2, height / 2); ctx.rotate(turn); ctx.globalCompositeOperation = 'destination-out'; ctx.beginPath();
    for (let i = 0; i < 8; i++) { const a = i * Math.PI / 4; const x = Math.cos(a) * radius, y = Math.sin(a) * radius; i ? ctx.lineTo(x, y) : ctx.moveTo(x, y); } ctx.closePath(); ctx.fill();
    ctx.globalCompositeOperation = 'source-over'; ctx.strokeStyle = '#999'; ctx.lineWidth = 1;
    if (p > .04 && p < .96) { ctx.stroke(); for (let i = 0; i < 8; i++) { const a = i * Math.PI / 4; ctx.beginPath(); ctx.moveTo(Math.cos(a) * radius, Math.sin(a) * radius); ctx.lineTo(Math.cos(a + .4) * radius * 2.7, Math.sin(a + .4) * radius * 2.7); ctx.stroke(); } }
    ctx.restore();
  };
}
function pixelSilence(image) {
  const cells = [], rand = random(21), size = Math.max(15, width / 65);
  for (let y = 0; y < height; y += size) for (let x = 0; x < width; x += size) cells.push({ x, y, threshold: .4 * x / width + .19 * y / height + rand() * .24 });
  return (ctx, p) => {
    for (const cell of cells) {
      const t = clamp((p - cell.threshold) / .07); if (t >= 1) continue;
      const w = Math.min(size, width - cell.x), h = Math.min(size, height - cell.y);
      ctx.drawImage(image, cell.x * ratio, cell.y * ratio, w * ratio, h * ratio, cell.x, cell.y, w, h);
      if (t > 0 && t < .6) { ctx.fillStyle = '#999'; ctx.fillRect(cell.x + w / 2, cell.y + h / 2, 1, 1); }
    }
  };
}
function signalOffset(image) {
  const bands = 24, h = height / bands;
  return (ctx, p) => {
    const rand = random(12 + Math.floor(p * 28));
    for (let i = 0; i < bands; i++) {
      const t = clamp((p - .15 - (i % 5) * .023) / .66), sign = i % 2 ? 1 : -1;
      const x = sign * t ** 2 * width * 1.8 + (p < .36 ? (rand() - .5) * 19 : 0), y = i * h;
      ctx.drawImage(image, 0, y * ratio, image.width, Math.min(h * ratio + 1, image.height - y * ratio), x, y, width, h);
      if (t > .1 && t < .7 && i % 4 === 0) hairline(ctx, x + width * .32, y, width * .12, 1, '#999');
    }
  };
}
function gravityPoint(image) {
  return (ctx, p) => {
    const t = smooth(p), scale = (1 - t) ** 2;
    if (scale > .0001) imageDraw(ctx, image, width * (1 - scale) / 2, height * (1 - scale) / 2, width * scale, height * scale);
    ctx.strokeStyle = '#aaa'; ctx.globalAlpha = Math.sin(p * Math.PI) * .5;
    for (let i = 1; i <= 3; i++) { const r = Math.min(width, height) * .25 * i * (1 - t); ctx.beginPath(); ctx.ellipse(width / 2, height / 2, r, r * .5, -.35, 0, Math.PI * 2); ctx.stroke(); }
    ctx.globalAlpha = 1;
  };
}
const renderers = { type: typePortal, cursor: cursorLine, louvers: precisionLouvers, paper: paperFold, iris: aperture, pixels: pixelSilence, signal: signalOffset, gravity: gravityPoint };
function cancel() {
  clearTimeout(timer); cancelAnimationFrame(frame); if (finishFrame) { finishFrame(); finishFrame = undefined; }
  for (const a of animations) a.cancel(); animations = []; app.style.cssText = ''; boot.style.cssText = ''; fill.style.width = '0%';
  fx.getContext('2d').clearRect(0, 0, fx.width, fx.height);
}
function showStart() { generation++; cancel(); sizeCanvases(); $('#status').textContent = 'READY TO PREVIEW'; $('#play').innerHTML = '▶ &nbsp; 播放过渡'; }
function showEnd() { generation++; cancel(); boot.style.display = 'none'; fill.style.width = '100%'; $('#status').textContent = 'WORKSPACE READY'; }
function canvasAnimation(draw, duration, run) {
  return new Promise(resolve => {
    finishFrame = resolve; const start = performance.now(), ctx = fx.getContext('2d');
    const tick = now => {
      if (run !== generation) { resolve(); return; }
      const p = clamp((now - start) / duration); ctx.setTransform(ratio, 0, 0, ratio, 0, 0); ctx.clearRect(0, 0, width, height); draw(ctx, p);
      if (p < 1) frame = requestAnimationFrame(tick); else { finishFrame = undefined; resolve(); }
    }; frame = requestAnimationFrame(tick);
  });
}
async function play() {
  const run = ++generation; cancel(); sizeCanvases(); $('#status').textContent = 'PREPARING'; $('#play').innerHTML = '↻ &nbsp; 重新播放';
  await new Promise(resolve => setTimeout(resolve, 300)); if (run !== generation) return;
  const image = document.createElement('canvas'); image.width = art.width; image.height = art.height; image.getContext('2d').drawImage(art, 0, 0);
  const duration = Number($('#duration').value); boot.style.visibility = 'hidden'; $('#status').textContent = 'TRANSITIONING';
  const progress = fill.animate([{ width: '0%' }, { width: '100%' }], { duration, fill: 'both' }); animations.push(progress);
  await canvasAnimation(renderers[selected](image), duration, run); if (run !== generation) return;
  cancel(); boot.style.display = 'none'; fill.style.width = '100%'; $('#status').textContent = 'WORKSPACE READY'; $('#play').innerHTML = '↻ &nbsp; 再看一次';
  if ($('#loop').checked) timer = setTimeout(play, 1500);
}
function choose(key, start = true) {
  selected = key; const study = studies[key];
  document.querySelectorAll('.choice').forEach(node => node.setAttribute('aria-pressed', String(node.dataset.effect === key)));
  $('#effectTitle').textContent = `${String(keys.indexOf(key) + 1).padStart(2, '0')} / ${study.name} — ${study.english}`;
  $('#effectDescription').textContent = study.description;
  start ? play() : showStart();
}
$('.choices').innerHTML = keys.map((key, i) => `<button class="choice" data-effect="${key}" aria-pressed="${i === 0}"><svg class="motion-symbol" viewBox="0 0 24 24" aria-hidden="true">${studies[key].icon}</svg><span class="choice-title"><span class="number">${String(i + 1).padStart(2, '0')}</span><strong>${studies[key].name}</strong></span><small>${studies[key].category}</small></button>`).join('');
document.querySelectorAll('.choice').forEach(node => node.onclick = () => choose(node.dataset.effect));
$('#play').onclick = play; $('#start').onclick = showStart; $('#end').onclick = showEnd;
$('#duration').oninput = () => { $('#durationLabel').value = $('#duration').value + 'ms'; };
$('#loop').onchange = () => { $('#loop').checked ? play() : clearTimeout(timer); };
document.addEventListener('keydown', event => { if (event.target.closest('input')) return; if (event.code === 'Space' && !event.target.closest('button')) { event.preventDefault(); play(); } if (/^[1-8]$/.test(event.key)) { event.preventDefault(); choose(keys[Number(event.key) - 1]); } });
new ResizeObserver(() => { if ($('#status').textContent === 'TRANSITIONING') showStart(); else sizeCanvases(); }).observe(stage);
choose('type', false);
