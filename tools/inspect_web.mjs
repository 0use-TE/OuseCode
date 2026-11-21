// Attach only to OuseCode's debug WebView after adb forwards its DevTools socket.
// No cookies, credentials, provider configuration, or account data are printed.
import { writeFile } from "node:fs/promises";
const port = Number(process.env.OUSECODE_CDP_PORT || 39291);
const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const page = pages.find(p => p.type === "page" && p.url.startsWith("http://127.0.0.1:"));
if (!page) throw new Error("OuseCode local page not found");
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
let id = 0;
const pending = new Map();
ws.onmessage = event => {
  const data = JSON.parse(event.data);
  if (data.id) { const task = pending.get(data.id); pending.delete(data.id); if (data.error) task.reject(new Error(data.error.message)); else task.resolve(data.result); }
};
function send(method, params = {}) { return new Promise((resolve, reject) => { const key = ++id; const timer = setTimeout(() => { pending.delete(key); reject(new Error(`CDP timeout: ${method}`)); }, 15000); pending.set(key, { resolve: value => { clearTimeout(timer); resolve(value); }, reject: error => { clearTimeout(timer); reject(error); } }); ws.send(JSON.stringify({ id: key, method, params })); }); }
await send("Runtime.enable");
if (process.env.OUSECODE_ACTION === "add-project") {
  await send("Runtime.evaluate", { expression: `[...document.querySelectorAll('button')].find(x=>x.getAttribute('aria-label')==='添加项目'||x.innerText==='添加项目')?.click()` });
  await new Promise(resolve => setTimeout(resolve, 500));
}
if (process.env.OUSECODE_ACTION === "enter-root") {
  await send("Runtime.evaluate", { expression: `[...document.querySelectorAll('button')].find(x=>x.innerText==='root')?.click()` });
  await new Promise(resolve => setTimeout(resolve, 500));
}
if (process.env.OUSECODE_ACTION === "workspace-path") {
  await send("Runtime.evaluate", { expression: `(()=>{const x=document.querySelector('input[placeholder*="搜索项目"]');if(x){Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(x,'/root/workspace');x.dispatchEvent(new Event('input',{bubbles:true}));}})()` });
  await new Promise(resolve => setTimeout(resolve, 800));
}
if (process.env.OUSECODE_ACTION === "enter-workspace") {
  await send("Runtime.evaluate", { expression: `[...document.querySelectorAll('[role="dialog"] *')].filter(x=>x.textContent.trim()==='workspace').at(-1)?.click()` });
  await new Promise(resolve => setTimeout(resolve, 500));
}
if (process.env.OUSECODE_ACTION === "select-folder") {
  await send("Runtime.evaluate", { expression: `[...document.querySelectorAll('button')].find(x=>x.innerText==='选择文件夹')?.click()` });
  await new Promise(resolve => setTimeout(resolve, 500));
}
if (process.env.OUSECODE_ACTION === "new-session") {
  await send("Runtime.evaluate", { expression: `[...document.querySelectorAll('button')].find(x=>x.innerText==='新建会话')?.click()` });
  await new Promise(resolve => setTimeout(resolve, 500));
}
const dom = await send("Runtime.evaluate", { expression: `JSON.stringify({origin:location.origin, ready:document.readyState, inputs:[...document.querySelectorAll('textarea,input,[contenteditable="true"]')].map(x=>({tag:x.tagName,type:x.type,placeholder:x.placeholder||x.getAttribute('data-placeholder')})), buttonCount:document.querySelectorAll('button').length})`, returnByValue: true });
console.log(dom.result.value);
if (process.env.OUSECODE_SCREENSHOT) {
  const shot = await send("Page.captureScreenshot", { format: "png" });
  await writeFile(process.env.OUSECODE_SCREENSHOT, Buffer.from(shot.data, "base64"));
  console.log("Screenshot saved");
}
ws.close();
