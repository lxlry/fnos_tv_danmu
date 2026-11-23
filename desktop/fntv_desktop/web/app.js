const view = document.querySelector("#view");
const backBtn = document.querySelector("#back");
const searchForm = document.querySelector("#search-form");
const searchInput = document.querySelector("#search");
const settingsBtn = document.querySelector("#settings");
const nowBar = document.querySelector("#now");
const toast = document.querySelector("#toast");

let state = null;
const stack = [];
let toastTimer = 0;

backBtn.addEventListener("click", () => {
  stack.pop();
  const previous = stack[stack.length - 1];
  if (!previous) return renderCurrent();
  open(previous.name, previous.arg, false);
});
settingsBtn.addEventListener("click", () => settingsDialog());
searchForm.addEventListener("submit", (event) => {
  event.preventDefault();
  const keyword = searchInput.value.trim();
  if (keyword) open("search", keyword);
});

function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[char]));
}

function showToast(message) {
  toast.textContent = message;
  toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove("show"), 2600);
}

async function api(url, options) {
  const response = await fetch(url, options);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data.message || "请求失败");
    error.kind = data.kind || "";
    throw error;
  }
  return data;
}

function poster(path) {
  if (!path) return "";
  return "/img?p=" + encodeURIComponent(path);
}

function progress(item) {
  const duration = Number(item.duration) || 0;
  const position = Number(item.ts) || 0;
  if (duration <= 0 || position <= 0) return "";
  const width = Math.max(2, Math.min(100, Math.round(position / duration * 100)));
  return `<div class="progress"><span style="width:${width}%"></span></div>`;
}

function card(item) {
  const image = poster(item.poster);
  return `<button class="card" data-guid="${esc(item.guid)}" data-type="${esc(item.type)}">
    <div class="poster">${image ? `<img src="${image}" alt="">` : ""}${progress(item)}</div>
    <b>${esc(item.title)}</b>
    <span>${esc(item.subtitle || item.type || "")}</span>
  </button>`;
}

function bindCards(root) {
  root.querySelectorAll(".card, .lib, .lib-head").forEach((node) => {
    node.addEventListener("click", () => {
      const type = node.dataset.type;
      const guid = node.dataset.guid;
      if (type === "Library" || type === "Directory") open("library", { guid, title: node.dataset.title || "媒体库", page: 1 });
      else open("detail", guid);
    });
  });
  root.querySelectorAll("img").forEach((img) => {
    img.addEventListener("error", () => { img.remove(); });
  });
}

function chrome(show) {
  searchForm.hidden = !show;
  settingsBtn.hidden = !show;
  backBtn.hidden = stack.length < 2;
}

function open(name, arg, push = true) {
  if (push) stack.push({ name, arg });
  renderCurrent();
}

async function renderCurrent() {
  const current = stack[stack.length - 1];
  chrome(current && current.name !== "login");
  view.innerHTML = `<p class="muted">正在加载…</p>`;
  try {
    if (current.name === "login") return renderLogin();
    if (current.name === "home") return await renderHome();
    if (current.name === "library") return await renderLibrary(current.arg);
    if (current.name === "search") return await renderSearch(current.arg);
    if (current.name === "detail") return await renderDetail(current.arg);
  } catch (error) {
    if (error.kind === "auth") {
      stack.length = 0;
      open("login");
      showToast(error.message);
      return;
    }
    view.innerHTML = `<p>${esc(error.message)}</p>`;
  }
}

function renderLogin() {
  chrome(false);
  const server = state && state.server || "";
  const account = state && state.account || "";
  const danmu = state && state.danmu_url || "";
  view.innerHTML = `<form class="login" id="login-form">
    <h1>登录飞牛影视</h1>
    <p class="muted">使用飞牛影视的账号。地址示例：http://192.168.1.10:5666</p>
    <label><span>服务器</span><input name="server" value="${esc(server)}" placeholder="http://IP:5666" required></label>
    <label><span>账号</span><input name="username" value="${esc(account)}" autocomplete="username" required></label>
    <label><span>密码</span><input name="password" type="password" autocomplete="current-password" required></label>
    <label><span>访问码（没开可留空）</span><input name="access_code" placeholder="飞牛访问码"></label>
    <label><span>弹幕服务器（可选）</span><input name="danmu_url" value="${esc(danmu)}" placeholder="http://IP:9321"></label>
    <label class="check"><input name="remember" type="checkbox" checked>记住密码</label>
    <button class="primary" type="submit">登录</button>
  </form>`;
  view.querySelector("#login-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = new FormData(event.target);
    const button = event.target.querySelector("button");
    button.disabled = true;
    try {
      await api("/api/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          server: form.get("server"),
          username: form.get("username"),
          password: form.get("password"),
          access_code: form.get("access_code"),
          danmu_url: form.get("danmu_url"),
          remember: form.get("remember") === "on",
        }),
      });
      state = await api("/api/status");
      stack.length = 0;
      open("home");
    } catch (error) {
      showToast(error.message);
      button.disabled = false;
    }
  });
}

async function renderHome() {
  const data = await api("/api/home");
  const libraries = (data.libraries || []).map((item) => `<button class="lib" data-guid="${esc(item.guid)}" data-type="Library" data-title="${esc(item.title)}">
      <div class="poster">${item.poster ? `<img src="${poster(item.poster)}" alt="">` : ""}</div>
      <b>${esc(item.title)}</b>
      <span>${item.count ? esc(item.count) + " 项" : esc(item.category || "媒体库")}</span>
    </button>`).join("");
  const sections = (data.libraries || []).map((item) => {
    const cards = (item.items || []).map(card).join("");
    if (!cards) return "";
    const count = item.count ? item.count + " 项" : "";
    return `<section class="lib-block">
      <button class="lib-head" type="button" data-guid="${esc(item.guid)}" data-type="Library" data-title="${esc(item.title)}">
        <b>${esc(item.title)}</b><span>${esc(count)}</span>
      </button>
      <div class="row">${cards}</div>
    </section>`;
  }).join("");
  const resume = (data.continue || []).map(card).join("");
  view.innerHTML = `<h1>你好，${esc(state.username || state.account || "")}</h1>
    <p class="muted">${esc(state.server || "")}${state.mpv_ready ? "" : " · 没有找到随程序提供的 mpv"}</p>
    ${resume ? `<h2>继续观看</h2><div class="row">${resume}</div>` : ""}
    <h2>媒体库</h2>
    <div class="grid">${libraries || `<p class="muted">没有媒体库</p>`}</div>
    ${sections}`;
  bindCards(view);
}

async function renderLibrary(arg) {
  const data = await api("/api/library?guid=" + encodeURIComponent(arg.guid) + "&page=" + (arg.page || 1));
  const items = (data.items || []).map(card).join("");
  const more = data.total > data.page * data.page_size
    ? `<button class="pager" id="more" type="button">下一页</button>` : "";
  view.innerHTML = `<h1>${esc(arg.title || "媒体库")}</h1>
    <div class="grid">${items || `<p class="muted">这里是空的</p>`}</div>${more}`;
  bindCards(view);
  const moreBtn = view.querySelector("#more");
  if (moreBtn) {
    moreBtn.addEventListener("click", () => {
      arg.page = (arg.page || 1) + 1;
      renderCurrent();
    });
  }
}

async function renderSearch(keyword) {
  const data = await api("/api/search?q=" + encodeURIComponent(keyword));
  const items = (data.items || []).map(card).join("");
  view.innerHTML = `<h1>搜索“${esc(keyword)}”</h1><div class="grid">${items || `<p class="muted">没有结果</p>`}</div>`;
  bindCards(view);
}

async function renderDetail(guid) {
  const data = await api("/api/detail?guid=" + encodeURIComponent(guid));
  drawDetail(data, data.episodes || []);
}

function drawDetail(data, episodes) {
  const seasons = (data.seasons || []).map((season, index) =>
    `<button type="button" class="season${index === 0 ? " active" : ""}" data-guid="${esc(season.guid)}">${esc(season.title)}</button>`).join("");
  const playable = data.type === "Movie" || data.type === "Video" || data.type === "Episode" || data.media_guid;
  view.innerHTML = `<article class="hero">
      <div class="poster">${data.poster ? `<img src="${poster(data.poster)}" alt="">` : ""}</div>
      <div>
        <h1>${esc(data.title)}</h1>
        <p class="muted">${esc(data.meta || data.type || "")}</p>
        <p class="overview">${esc(data.overview || "暂无简介")}</p>
        <div class="actions">
          ${playable ? `<button class="primary" id="play" type="button">${Number(data.ts) > 30 ? "继续播放" : "播放"}</button>` : ""}
        </div>
        ${seasons ? `<div class="chips">${seasons}</div>` : ""}
      </div>
    </article>
    <div class="episodes" id="episodes"></div>`;
  view.querySelectorAll("img").forEach((img) => img.addEventListener("error", () => img.remove()));
  const play = view.querySelector("#play");
  if (play) play.addEventListener("click", () => startPlay(data.guid, nextAfter(episodes, data.guid)));
  fillEpisodes(episodes, data.guid);
  view.querySelectorAll(".season").forEach((button) => {
    button.addEventListener("click", async () => {
      view.querySelectorAll(".season").forEach((one) => one.classList.remove("active"));
      button.classList.add("active");
      const list = await api("/api/episodes?guid=" + encodeURIComponent(button.dataset.guid));
      fillEpisodes(list.items || [], "");
    });
  });
}

function fillEpisodes(episodes, current) {
  const root = view.querySelector("#episodes");
  if (!root) return;
  root.innerHTML = episodes.map((episode) => {
    const image = poster(episode.poster);
    return `<button class="episode" type="button" data-guid="${esc(episode.guid)}">
      ${image ? `<img src="${image}" alt="">` : "<span></span>"}
      <b>${esc(episode.title)}</b>
      <span>${Number(episode.ts) > 0 ? "看过" : ""}</span>
    </button>`;
  }).join("");
  root.querySelectorAll(".episode").forEach((button) => {
    if (button.dataset.guid === current) button.style.outline = "1px solid var(--accent)";
    button.addEventListener("click", () => startPlay(button.dataset.guid, nextAfter(episodes, button.dataset.guid)));
  });
  root.querySelectorAll("img").forEach((img) => img.addEventListener("error", () => img.remove()));
}

function nextAfter(episodes, guid) {
  const index = episodes.findIndex((episode) => episode.guid === guid);
  if (index >= 0 && episodes[index + 1]) return episodes[index + 1].guid;
  return "";
}

async function startPlay(guid, nextGuid) {
  try {
    const result = await api("/api/play", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ guid, next_guid: nextGuid || "" }),
    });
    showToast("正在播放 " + (result.title || ""));
    refreshNow();
  } catch (error) {
    showToast(error.message);
  }
}

function settingsDialog() {
  const node = document.createElement("div");
  node.className = "dialog";
  node.innerHTML = `<form class="sheet" id="settings-form">
    <h2>设置</h2>
    <label><span class="muted">弹幕服务器</span><input name="danmu_url" value="${esc(state.danmu_url || "")}" placeholder="http://IP:9321"></label>
    <label><span class="muted">mpv.exe 路径（留空则自动查找）</span><input name="mpv_path" value="${esc(state.mpv_path || "")}"></label>
    <label class="check"><input name="danmu_on" type="checkbox" ${state.danmu_on ? "checked" : ""}>播放时加载弹幕</label>
    <label class="check"><input name="auto_next" type="checkbox" ${state.auto_next ? "checked" : ""}>剧集播完自动下一集</label>
    <p class="muted">当前版本 ${esc(state.version || "")}。mpv 里空格暂停，左右方向键快进，上下方向键调音量。</p>
    <div class="actions">
      <button class="primary" type="submit">保存</button>
      <button type="button" id="check-update">检查更新</button>
      <button type="button" id="close-settings">关闭</button>
      <button class="danger" type="button" id="logout">退出登录</button>
    </div>
  </form>`;
  document.body.appendChild(node);
  node.querySelector("#close-settings").addEventListener("click", () => node.remove());
  node.querySelector("#check-update").addEventListener("click", () => checkUpdate(false));
  node.addEventListener("click", (event) => { if (event.target === node) node.remove(); });
  node.querySelector("#logout").addEventListener("click", async () => {
    await api("/api/logout", { method: "POST" });
    node.remove();
    state = await api("/api/status");
    stack.length = 0;
    open("login");
  });
  node.querySelector("#settings-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = new FormData(event.target);
    state = await api("/api/settings", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        danmu_url: form.get("danmu_url"),
        mpv_path: form.get("mpv_path"),
        danmu_on: form.get("danmu_on") === "on",
        auto_next: form.get("auto_next") === "on",
      }),
    });
    node.remove();
    showToast("已保存");
  });
}

async function refreshNow() {
  try {
    const playing = await api("/api/playing");
    if (!playing.running) {
      nowBar.hidden = true;
      return;
    }
    const minutes = Math.floor((playing.position || 0) / 60);
    const seconds = String((playing.position || 0) % 60).padStart(2, "0");
    nowBar.hidden = false;
    nowBar.innerHTML = `<span>${esc(playing.title)} · ${minutes}:${seconds}</span><button id="stop-play" type="button">停止</button>`;
    nowBar.querySelector("#stop-play").addEventListener("click", async () => {
      await api("/api/stop", { method: "POST" });
      nowBar.hidden = true;
    });
  } catch (error) {
    nowBar.hidden = true;
  }
}

let updateOpen = false;

async function checkUpdate(quiet) {
  try {
    const result = await api("/api/update?quiet=" + (quiet ? "1" : "0"));
    if (result.status === "available") showUpdateDialog(result);
    else if (!quiet && result.message) showToast(result.message);
  } catch (error) {
    if (!quiet) showToast(error.message);
  }
}

function showUpdateDialog(info) {
  if (updateOpen) return;
  updateOpen = true;
  const node = document.createElement("div");
  node.className = "dialog";
  const later = info.force ? "" : `<button type="button" id="update-later">以后再说</button>`;
  node.innerHTML = `<div class="sheet">
    <h2>${esc(info.title || "发现新版本")}</h2>
    <p class="overview">${esc(info.changelog || "暂无更新说明")}</p>
    <p class="muted" id="update-progress"></p>
    <div class="actions">
      <button class="primary" id="update-now" type="button">立即更新</button>
      ${later}
    </div>
  </div>`;
  document.body.appendChild(node);
  const close = () => {
    if (info.force) return;
    updateOpen = false;
    node.remove();
  };
  const laterBtn = node.querySelector("#update-later");
  if (laterBtn) laterBtn.addEventListener("click", close);
  node.addEventListener("click", (event) => { if (event.target === node) close(); });
  node.querySelector("#update-now").addEventListener("click", () => downloadUpdate(info.exeUrl, node));
}

async function downloadUpdate(exeUrl, node) {
  const button = node.querySelector("#update-now");
  const progress = node.querySelector("#update-progress");
  button.disabled = true;
  progress.textContent = "正在下载…";
  try {
    await api("/api/update/download", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ exeUrl }),
    });
    for (;;) {
      const state = await api("/api/update/progress");
      if (state.status === "failed") throw new Error(state.message || "下载失败");
      if (state.total) {
        const percent = Math.min(100, Math.round(state.received / state.total * 100));
        progress.textContent = "正在下载 " + percent + "%";
      }
      if (state.status === "done") break;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    progress.textContent = "下载完成，即将安装并重新打开";
    await api("/api/update/apply", { method: "POST" });
  } catch (error) {
    button.disabled = false;
    progress.textContent = error.message;
  }
}

async function boot() {
  try {
    state = await api("/api/status");
  } catch (error) {
    view.innerHTML = `<p>${esc(error.message)}</p>`;
    return;
  }
  open(state.logged_in ? "home" : "login");
  setInterval(refreshNow, 3000);
  checkUpdate(true);
}

boot();
