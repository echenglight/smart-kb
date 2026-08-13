/* ===== SmartKB 管理台 (原生 JS SPA) ===== */
'use strict';

const $ = (sel) => document.querySelector(sel);
const state = {
  user: null,
  kbs: [],
  convs: [],
  currentConv: null,
  docKbId: null,
  docPollTimer: null,
  streaming: false,
};

/* ---------- 基础工具 ---------- */
async function api(path, options = {}) {
  const resp = await fetch('/api' + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  const body = await resp.json();
  if (body.code === 401) { showLogin(); throw new Error(body.message); }
  if (body.code !== 0) { toast(body.message, true); throw new Error(body.message); }
  return body.data;
}

function toast(msg, isError = false) {
  const el = $('#toast');
  el.textContent = msg;
  el.className = 'toast' + (isError ? ' error' : '');
  clearTimeout(el._timer);
  el._timer = setTimeout(() => el.classList.add('hidden'), 2600);
}

function esc(s) {
  return String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function fmtBytes(n) {
  if (!n) return '-';
  if (n < 1024) return n + ' B';
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1048576).toFixed(1) + ' MB';
}

function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '-'; }

/* ---------- 深/浅主题切换 (首帧初始化在 index.html 内联脚本) ---------- */
function toggleTheme() {
  const next = document.documentElement.dataset.theme === 'light' ? 'dark' : 'light';
  document.documentElement.dataset.theme = next;
  try { localStorage.setItem('skb-theme', next); } catch (e) { /* ignore */ }
}
$('#themeToggle').onclick = toggleTheme;
$('#themeToggleSide').onclick = toggleTheme;

/* 迷你 Markdown 渲染: 代码块/行内代码/加粗/标题/列表/引用编号 */
function mdRender(text) {
  let s = esc(text);
  s = s.replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang, code) => `<pre><code>${code}</code></pre>`);
  s = s.replace(/`([^`\n]+)`/g, '<code>$1</code>');
  s = s.replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>');
  s = s.replace(/^### (.+)$/gm, '<h3>$1</h3>');
  s = s.replace(/^## (.+)$/gm, '<h2>$1</h2>');
  s = s.replace(/^# (.+)$/gm, '<h1>$1</h1>');
  s = s.replace(/^[-*] (.+)$/gm, '<li>$1</li>');
  s = s.replace(/(<li>[\s\S]*?<\/li>)(?!\s*<li>)/g, '<ul>$1</ul>');
  s = s.replace(/^\d+\. (.+)$/gm, '<li>$1</li>');
  s = s.replace(/\[(\d+)\]/g, '<sup class="cite" data-n="$1">[$1]</sup>');
  s = s.replace(/\n{2,}/g, '<br><br>').replace(/\n/g, '<br>');
  s = s.replace(/(<\/(?:pre|h1|h2|h3|ul)>)<br>/g, '$1');
  return s;
}

/* ---------- 弹窗 ---------- */
function openModal(title, bodyHtml, footHtml = '') {
  $('#modalTitle').textContent = title;
  $('#modalBody').innerHTML = bodyHtml;
  $('#modalFoot').innerHTML = footHtml;
  $('#modal').classList.remove('hidden');
}
function closeModal() { $('#modal').classList.add('hidden'); }
$('#modalClose').onclick = closeModal;
$('#modal').onclick = (e) => { if (e.target === $('#modal')) closeModal(); };

/* ---------- 登录 ---------- */
let registerMode = false;

function showLogin() {
  $('#loginView').classList.remove('hidden');
  $('#appView').classList.add('hidden');
}

function showApp() {
  $('#loginView').classList.add('hidden');
  $('#appView').classList.remove('hidden');
  $('#nickname').textContent = state.user ? (state.user.nickname || state.user.username) : '';
  loadConvs();
  loadKbs();
}

$('#loginSwitchBtn').onclick = () => {
  registerMode = !registerMode;
  $('#loginNickname').classList.toggle('hidden', !registerMode);
  $('#loginBtn').textContent = registerMode ? '注 册' : '登 录';
  $('#loginSwitchText').textContent = registerMode ? '已有账号?' : '没有账号?';
  $('#loginSwitchBtn').textContent = registerMode ? '去登录' : '去注册';
};

$('#loginBtn').onclick = async () => {
  const username = $('#loginUsername').value.trim();
  const password = $('#loginPassword').value;
  if (!username || !password) return toast('请输入用户名和密码', true);
  if (registerMode) {
    await api('/auth/register', { method: 'POST', body: JSON.stringify({ username, password, nickname: $('#loginNickname').value.trim() }) });
    toast('注册成功, 请登录');
    $('#loginSwitchBtn').click();
    return;
  }
  const data = await api('/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) });
  state.user = data.user;
  showApp();
};

$('#loginPassword').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#loginBtn').click(); });

$('#logoutBtn').onclick = async () => {
  try { await api('/auth/logout', { method: 'POST' }); } catch (e) { /* ignore */ }
  location.reload();
};

/* ---------- 页面切换 ---------- */
$('#nav').addEventListener('click', (e) => {
  const btn = e.target.closest('.nav-item');
  if (!btn) return;
  document.querySelectorAll('.nav-item').forEach(b => b.classList.toggle('active', b === btn));
  document.querySelectorAll('.page').forEach(p => p.classList.add('hidden'));
  $('#page-' + btn.dataset.page).classList.remove('hidden');
  stopDocPolling();
  if (btn.dataset.page === 'kb') loadKbs();
  if (btn.dataset.page === 'doc') initDocPage();
  if (btn.dataset.page === 'stats') loadStats();
  if (btn.dataset.page === 'chat') loadConvs();
});

/* ---------- 知识库 ---------- */
async function loadKbs() {
  state.kbs = await api('/kb');
  const grid = $('#kbGrid');
  if (!state.kbs.length) {
    grid.innerHTML = '<p class="dim">还没有知识库, 点击右上角新建一个吧</p>';
    return;
  }
  grid.innerHTML = state.kbs.map(kb => `
    <div class="kb-card" data-id="${kb.id}">
      <div class="kb-name">📚 ${esc(kb.name)}</div>
      <div class="kb-desc">${esc(kb.description || '暂无描述')}</div>
      <div class="kb-meta">
        <span><b>${kb.docCount ?? 0}</b>文档</span>
        <span><b>${kb.chunkCount ?? 0}</b>分块</span>
        <span>块大小 ${kb.chunkSize} / 重叠 ${kb.chunkOverlap}</span>
      </div>
      <div class="kb-actions">
        <button class="btn sm" data-act="docs">管理文档</button>
        <button class="btn sm" data-act="edit">编辑</button>
        <button class="btn sm danger" data-act="del">删除</button>
      </div>
    </div>`).join('');
}

$('#kbGrid').addEventListener('click', async (e) => {
  const card = e.target.closest('.kb-card');
  if (!card) return;
  const kbId = Number(card.dataset.id);
  const act = e.target.dataset.act;
  const kb = state.kbs.find(k => k.id === kbId);
  if (act === 'del') {
    if (!confirm(`删除知识库「${kb.name}」及其全部文档?`)) return;
    await api('/kb/' + kbId, { method: 'DELETE' });
    toast('已删除');
    loadKbs();
  } else if (act === 'edit') {
    kbForm(kb);
  } else {
    state.docKbId = kbId;
    document.querySelector('[data-page="doc"]').click();
  }
});

function kbForm(kb) {
  openModal(kb ? '编辑知识库' : '新建知识库', `
    <div class="form-item"><label>名称</label><input id="fKbName" value="${esc(kb?.name || '')}"></div>
    <div class="form-item"><label>描述</label><textarea id="fKbDesc" rows="2">${esc(kb?.description || '')}</textarea></div>
    <div class="form-item"><label>分块大小(字符, 100-2000)。块太小语义不完整, 太大噪声多, 经验值 300~800</label>
      <input id="fKbSize" type="number" value="${kb?.chunkSize ?? 500}"></div>
    <div class="form-item"><label>分块重叠(字符)。相邻块共享尾部, 防止答案被切碎在边界</label>
      <input id="fKbOverlap" type="number" value="${kb?.chunkOverlap ?? 80}"></div>`,
    `<button class="btn" onclick="closeModal()">取消</button>
     <button class="btn primary" id="fKbSave">保存</button>`);
  $('#fKbSave').onclick = async () => {
    const body = JSON.stringify({
      name: $('#fKbName').value.trim(),
      description: $('#fKbDesc').value.trim(),
      chunkSize: Number($('#fKbSize').value),
      chunkOverlap: Number($('#fKbOverlap').value),
    });
    if (kb) await api('/kb/' + kb.id, { method: 'PUT', body });
    else await api('/kb', { method: 'POST', body });
    closeModal();
    toast('已保存');
    loadKbs();
  };
}
$('#createKbBtn').onclick = () => kbForm(null);

/* ---------- 文档管理 ---------- */
async function initDocPage() {
  await loadKbs().catch(() => {});
  const sel = $('#docKbSelect');
  sel.innerHTML = state.kbs.map(kb => `<option value="${kb.id}">${esc(kb.name)}</option>`).join('');
  if (state.docKbId && state.kbs.some(k => k.id === state.docKbId)) sel.value = state.docKbId;
  state.docKbId = Number(sel.value) || null;
  $('#searchResult').innerHTML = '';
  if (state.docKbId) loadDocs();
  else $('#docTbody').innerHTML = '<tr><td colspan="8" class="dim">请先创建知识库</td></tr>';
}

$('#docKbSelect').onchange = () => { state.docKbId = Number($('#docKbSelect').value); loadDocs(); };

async function loadDocs() {
  if (!state.docKbId) return;
  const docs = await api('/doc?kbId=' + state.docKbId);
  $('#docTbody').innerHTML = docs.length ? docs.map(d => `
    <tr>
      <td>${esc(d.name)}</td>
      <td>${esc(d.fileType)}</td>
      <td>${fmtBytes(d.fileSize)}</td>
      <td><span class="badge ${d.status}">${statusText(d.status)}</span>
          ${d.errorMsg ? `<div class="dim" style="font-size:11px;max-width:220px">${esc(d.errorMsg)}</div>` : ''}</td>
      <td>${d.chunkCount ?? 0}</td>
      <td>${d.charCount ?? 0}</td>
      <td>${fmtTime(d.createdAt)}</td>
      <td class="row">
        <button class="btn sm" data-act="chunks" data-id="${d.id}">分块</button>
        <button class="btn sm" data-act="reindex" data-id="${d.id}">重建</button>
        <button class="btn sm danger" data-act="del" data-id="${d.id}">删除</button>
      </td>
    </tr>`).join('')
    : '<tr><td colspan="8" class="dim">暂无文档, 点击右上角上传 (支持 PDF/Word/PPT/Markdown/TXT/HTML)</td></tr>';

  // 有排队/解析中的文档时轮询刷新
  stopDocPolling();
  if (docs.some(d => d.status === 'PENDING' || d.status === 'PARSING')) {
    state.docPollTimer = setTimeout(loadDocs, 2000);
  }
}

function statusText(s) {
  return { PENDING: '排队中', PARSING: '解析中', INDEXED: '已索引', FAILED: '失败' }[s] || s;
}

function stopDocPolling() {
  if (state.docPollTimer) { clearTimeout(state.docPollTimer); state.docPollTimer = null; }
}

$('#uploadBtn').onclick = () => {
  if (!state.docKbId) return toast('请先选择知识库', true);
  $('#fileInput').click();
};

$('#fileInput').onchange = async () => {
  const file = $('#fileInput').files[0];
  if (!file) return;
  const form = new FormData();
  form.append('file', file);
  const resp = await fetch(`/api/doc/upload?kbId=${state.docKbId}`, { method: 'POST', body: form });
  const body = await resp.json();
  $('#fileInput').value = '';
  if (body.code !== 0) return toast(body.message, true);
  toast('上传成功, 正在后台解析索引...');
  loadDocs();
};

$('#docTbody').addEventListener('click', async (e) => {
  const id = e.target.dataset.id;
  if (!id) return;
  const act = e.target.dataset.act;
  if (act === 'del') {
    if (!confirm('删除该文档及其分块?')) return;
    await api('/doc/' + id, { method: 'DELETE' });
    toast('已删除');
    loadDocs();
  } else if (act === 'reindex') {
    await api(`/doc/${id}/reindex`, { method: 'POST' });
    toast('已提交重建索引');
    loadDocs();
  } else if (act === 'chunks') {
    const chunks = await api(`/doc/${id}/chunks`);
    openModal(`文档分块 (共 ${chunks.length} 块)`, chunks.map(c => `
      <div class="chunk-card">
        <div class="chunk-head">
          <span class="score-tag">#${c.chunkIndex}</span>
          ${c.titlePath ? `<span>📑 ${esc(c.titlePath)}</span>` : ''}
          <span>${c.charCount} 字符</span>
          <span>${c.embedded ? '✅ 已向量化' : '⚠️ 未向量化(仅关键词)'}</span>
        </div>
        <div class="chunk-content">${esc(c.content)}</div>
      </div>`).join('') || '<p class="dim">无分块</p>');
  }
});

/* 检索测试 */
$('#searchBtn').onclick = async () => {
  const query = $('#searchInput').value.trim();
  if (!query || !state.docKbId) return toast('请选择知识库并输入查询词', true);
  $('#searchResult').innerHTML = '<p class="dim">检索中...</p>';
  const data = await api('/rag/search', {
    method: 'POST',
    body: JSON.stringify({ kbId: state.docKbId, query }),
  });
  const chunks = data.chunks || [];
  $('#searchResult').innerHTML = `
    <div class="section-title">检索结果 ${chunks.length} 条 (检索词: ${esc(data.searchQuery)})</div>
    ${chunks.map(c => `
      <div class="chunk-card">
        <div class="chunk-head">
          <span>📄 ${esc(c.docName)}</span>
          ${c.titlePath ? `<span>📑 ${esc(c.titlePath)}</span>` : ''}
          ${scoreTags(c)}
        </div>
        <div class="chunk-content">${esc(c.content)}</div>
      </div>`).join('') || '<p class="dim">未检索到相关内容</p>'}`;
};
$('#searchInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#searchBtn').click(); });

function scoreTags(c) {
  const tags = [];
  if (c.vectorScore != null) tags.push(`<span class="score-tag">向量 ${c.vectorScore.toFixed(3)}</span>`);
  if (c.keywordScore != null) tags.push(`<span class="score-tag">关键词 ${c.keywordScore.toFixed(3)}</span>`);
  if (c.rrfScore != null) tags.push(`<span class="score-tag">RRF ${c.rrfScore.toFixed(4)}</span>`);
  if (c.rerankScore != null) tags.push(`<span class="score-tag">重排 ${c.rerankScore}</span>`);
  return tags.join('');
}

/* ---------- 会话 ---------- */
async function loadConvs() {
  state.convs = await api('/conversation');
  renderConvs();
}

function renderConvs() {
  $('#convList').innerHTML = state.convs.map(c => `
    <div class="conv-item ${state.currentConv?.id === c.id ? 'active' : ''}" data-id="${c.id}">
      <div class="conv-title">${esc(c.title)}</div>
      <div class="conv-meta">
        <span>📚 ${esc(c.kbName || '')}</span>
        <a class="conv-del" data-del="${c.id}">删除</a>
      </div>
    </div>`).join('');
}

$('#convList').addEventListener('click', async (e) => {
  const delId = e.target.dataset.del;
  if (delId) {
    if (!confirm('删除该会话?')) return;
    await api('/conversation/' + delId, { method: 'DELETE' });
    if (state.currentConv?.id === Number(delId)) { state.currentConv = null; $('#msgList').innerHTML = ''; $('#chatHead').textContent = '选择或新建一个对话开始提问'; }
    loadConvs();
    return;
  }
  const item = e.target.closest('.conv-item');
  if (!item) return;
  const conv = state.convs.find(c => c.id === Number(item.dataset.id));
  await openConv(conv);
});

$('#newConvBtn').onclick = async () => {
  await loadKbs().catch(() => {});
  if (!state.kbs.length) return toast('请先创建知识库并上传文档', true);
  openModal('新建对话', `
    <div class="form-item"><label>选择知识库 (问答只在该库内检索)</label>
      <select id="fConvKb" class="select" style="width:100%">
        ${state.kbs.map(kb => `<option value="${kb.id}">${esc(kb.name)} (${kb.docCount ?? 0} 文档)</option>`).join('')}
      </select></div>`,
    `<button class="btn" onclick="closeModal()">取消</button>
     <button class="btn primary" id="fConvCreate">创建</button>`);
  $('#fConvCreate').onclick = async () => {
    const conv = await api('/conversation', { method: 'POST', body: JSON.stringify({ kbId: Number($('#fConvKb').value) }) });
    closeModal();
    await loadConvs();
    await openConv(state.convs.find(c => c.id === conv.id) || conv);
  };
};

async function openConv(conv) {
  state.currentConv = conv;
  renderConvs();
  $('#chatHead').textContent = `${conv.title}  ·  📚 ${conv.kbName || ''}`;
  const messages = await api(`/conversation/${conv.id}/messages`);
  const list = $('#msgList');
  list.innerHTML = '';
  for (const m of messages) {
    if (m.role === 'user') appendUserMsg(m.content);
    else {
      const el = appendAssistantMsg();
      const citations = m.citations ? JSON.parse(m.citations) : [];
      finishAssistantMsg(el, m.content, citations, m.rewrittenQuery,
        m.promptTokens || m.completionTokens ? { promptTokens: m.promptTokens, completionTokens: m.completionTokens } : null);
    }
  }
  list.scrollTop = list.scrollHeight;
}

/* ---------- 问答 (SSE 流式) ---------- */
function appendUserMsg(text) {
  const el = document.createElement('div');
  el.className = 'msg user';
  el.textContent = text;
  $('#msgList').appendChild(el);
  return el;
}

function appendAssistantMsg() {
  const el = document.createElement('div');
  el.className = 'msg assistant';
  el.innerHTML = '<div class="sources-bar"></div><div class="msg-body cursor-blink"></div><div class="msg-foot"></div>';
  el._citations = [];
  $('#msgList').appendChild(el);
  return el;
}

function renderSources(el, data) {
  el._citations = data.chunks || [];
  const bar = el.querySelector('.sources-bar');
  if (!el._citations.length) {
    bar.innerHTML = '<span>🔍 未检索到相关片段</span>';
    return;
  }
  bar.innerHTML = `<span>🔍 引用 ${el._citations.length} 个片段</span>`
    + (data.rewritten ? `<span class="rewrite-tag">改写: ${esc(data.searchQuery)}</span>` : '')
    + el._citations.map((c, i) => `<span class="source-chip" data-i="${i}">[${c.n}] ${esc(c.docName)}</span>`).join('');
}

function finishAssistantMsg(el, text, citations, rewrittenQuery, usage) {
  el._citations = citations || el._citations;
  const body = el.querySelector('.msg-body');
  body.classList.remove('cursor-blink');
  body.innerHTML = mdRender(text || '(空回答)');
  if (el._citations.length) {
    renderSources(el, { chunks: el._citations, rewritten: !!rewrittenQuery, searchQuery: rewrittenQuery });
  } else {
    el.querySelector('.sources-bar').remove();
  }
  if (usage) {
    el.querySelector('.msg-foot').textContent =
      `输入 ${usage.promptTokens ?? 0} tokens · 输出 ${usage.completionTokens ?? 0} tokens`;
  }
}

/* 引用点击 -> 查看片段详情 */
$('#msgList').addEventListener('click', (e) => {
  const msg = e.target.closest('.msg');
  if (!msg || !msg._citations) return;
  let citation = null;
  if (e.target.classList.contains('source-chip')) citation = msg._citations[Number(e.target.dataset.i)];
  if (e.target.classList.contains('cite')) citation = msg._citations.find(c => c.n === Number(e.target.dataset.n));
  if (!citation) return;
  openModal(`引用 [${citation.n}] · ${citation.docName}`, `
    <div class="chunk-card">
      <div class="chunk-head">
        ${citation.titlePath ? `<span>📑 ${esc(citation.titlePath)}</span>` : ''}
        ${scoreTags(citation)}
      </div>
      <div class="chunk-content">${esc(citation.content)}</div>
    </div>`);
});

async function sendQuestion() {
  const question = $('#questionInput').value.trim();
  if (!question || state.streaming) return;
  if (!state.currentConv) return toast('请先新建或选择一个对话', true);
  $('#questionInput').value = '';
  state.streaming = true;
  $('#sendBtn').disabled = true;

  const empty = $('#msgList').querySelector('.chat-empty');
  if (empty) empty.remove();
  appendUserMsg(question);
  const el = appendAssistantMsg();
  const body = el.querySelector('.msg-body');
  let answer = '';
  let rewrittenQuery = null;
  $('#msgList').scrollTop = $('#msgList').scrollHeight;

  try {
    const resp = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId: state.currentConv.id, question }),
    });
    if (!resp.ok || !resp.body) throw new Error('请求失败: ' + resp.status);

    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const events = buffer.split('\n\n');
      buffer = events.pop();
      for (const block of events) {
        let event = 'message';
        let data = '';
        for (const line of block.split('\n')) {
          if (line.startsWith('event:')) event = line.slice(6).trim();
          else if (line.startsWith('data:')) data += line.slice(5).trim();
        }
        if (!data) continue;
        const payload = JSON.parse(data);
        if (event === 'sources') {
          renderSources(el, payload);
          if (payload.rewritten) rewrittenQuery = payload.searchQuery;
        } else if (event === 'delta') {
          answer += payload.t;
          body.innerHTML = mdRender(answer);
          body.classList.add('cursor-blink');
          $('#msgList').scrollTop = $('#msgList').scrollHeight;
        } else if (event === 'done') {
          finishAssistantMsg(el, answer, null, rewrittenQuery, payload);
        } else if (event === 'error') {
          body.classList.remove('cursor-blink');
          body.innerHTML = mdRender(answer) + `<p style="color:var(--red)">⚠ ${esc(payload.message)}</p>`;
        }
      }
    }
    body.classList.remove('cursor-blink');
    // 首问后刷新会话标题
    if (state.currentConv.title === '新对话') loadConvs();
  } catch (err) {
    body.classList.remove('cursor-blink');
    body.innerHTML += `<p style="color:var(--red)">⚠ ${esc(err.message)}</p>`;
  } finally {
    state.streaming = false;
    $('#sendBtn').disabled = false;
  }
}

$('#sendBtn').onclick = sendQuestion;
$('#questionInput').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendQuestion(); }
});

/* ---------- 统计 ---------- */
async function loadStats() {
  const data = await api('/stats/overview');
  const byType = data.byType || {};
  const typeNames = { CHAT: '对话生成', EMBEDDING: '向量化', REWRITE: '查询改写', RERANK: '重排序' };
  $('#statCards').innerHTML = `
    <div class="stat-card"><div class="stat-label">总调用次数</div><div class="stat-value">${data.totalCalls}</div></div>
    <div class="stat-card"><div class="stat-label">总 Token 消耗</div><div class="stat-value">${data.totalTokens}</div></div>
    <div class="stat-card"><div class="stat-label">平均耗时</div><div class="stat-value">${data.avgLatencyMs} ms</div></div>
    <div class="stat-card"><div class="stat-label">失败次数</div><div class="stat-value">${data.failures}</div></div>
    ${Object.entries(byType).map(([type, v]) => `
      <div class="stat-card"><div class="stat-label">${typeNames[type] || type}</div>
        <div class="stat-value">${v.calls}</div><div class="stat-sub">${v.tokens} tokens</div></div>`).join('')}`;
  $('#statsTbody').innerHTML = (data.recent || []).map(r => `
    <tr>
      <td>${fmtTime(r.createdAt)}</td>
      <td>${typeNames[r.callType] || r.callType}</td>
      <td>${esc(r.model || '-')}</td>
      <td>${r.promptTokens ?? 0}</td>
      <td>${r.completionTokens ?? 0}</td>
      <td>${r.latencyMs} ms</td>
      <td>${r.success ? '✅' : '❌'}</td>
      <td class="dim">${esc(r.remark || '')}</td>
    </tr>`).join('') || '<tr><td colspan="8" class="dim">暂无调用记录</td></tr>';
}
$('#statsRefreshBtn').onclick = loadStats;

/* ---------- 启动: 尝试恢复登录态 ---------- */
(async function init() {
  try {
    state.user = await api('/auth/me');
    showApp();
  } catch (e) {
    showLogin();
  }
})();
