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
let homeKeep = null;
let homeSignature = "";
let homeRefreshToken = 0;

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

function card(item, options) {
  const image = poster(item.poster);
  const play = options && options.play ? "1" : "";
  const bar = play ? progress(item) : "";
  return `<button class="card" data-guid="${esc(item.guid)}" data-type="${esc(item.type)}" data-play="${play}">
    <div class="poster">${image ? `<img src="${image}" alt="">` : ""}${bar}</div>
    <b>${esc(item.title)}</b>
    <span>${esc(item.subtitle || item.type || "")}</span>
  </button>`;
}

function bindCards(root) {
  root.querySelectorAll(".card, .lib, .lib-head").forEach((node) => {
    node.addEventListener("click", () => {
      const type = node.dataset.type;
      const guid = node.dataset.guid;
      if (node.dataset.play === "1") {
        startPlay(guid, "");
        return;
      }
      if (type === "Library" || type === "Directory") open("library", { guid, title: node.dataset.title || "媒体库", page: 1 });
      else open("detail", guid);
    });
  });
  root.querySelectorAll("img").forEach((img) => {
    img.addEventListener("error", () => { img.remove(); });
  });
  bindRows(root);
}

function bindRows(root) {
  root.querySelectorAll(".row").forEach((row) => {
    if (row.parentElement && row.parentElement.classList.contains("scroller")) return;
    const wrap = document.createElement("div");
    wrap.className = "scroller";
    row.parentNode.insertBefore(wrap, row);
    wrap.appendChild(row);
    const left = arrowButton("left", "向左");
    const right = arrowButton("right", "向右");
    wrap.append(left, right);
    const distance = () => Math.max(row.clientWidth - 40, 160);
    left.addEventListener("click", () => scrollRow(row, -distance()));
    right.addEventListener("click", () => scrollRow(row, distance()));
    const update = () => {
      const max = row.scrollWidth - row.clientWidth;
      left.hidden = row.scrollLeft <= 2;
      right.hidden = max <= 2 || row.scrollLeft >= max - 2;
    };
    row.addEventListener("scroll", update);
    update();
    requestAnimationFrame(update);
    if (typeof ResizeObserver !== "undefined") {
      const observer = new ResizeObserver(update);
      observer.observe(row);
    }
  });
}

function scrollRow(row, delta) {
  const max = Math.max(0, row.scrollWidth - row.clientWidth);
  const start = row.scrollLeft;
  const target = Math.min(max, Math.max(0, start + delta));
  if (Math.abs(target - start) < 1) return;
  if (row._scrollFrame) cancelAnimationFrame(row._scrollFrame);
  const duration = 460;
  const began = performance.now();
  const step = (now) => {
    const progress = Math.min(1, (now - began) / duration);
    const eased = 1 - Math.pow(1 - progress, 3);
    row.scrollLeft = start + (target - start) * eased;
    if (progress < 1) row._scrollFrame = requestAnimationFrame(step);
  };
  row._scrollFrame = requestAnimationFrame(step);
}

function arrowButton(side, label) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "scroll-arrow " + side;
  button.setAttribute("aria-label", label);
  button.textContent = side === "left" ? "‹" : "›";
  return button;
}

function chrome(show) {
  searchForm.hidden = !show;
  settingsBtn.hidden = !show;
  backBtn.hidden = stack.length < 2;
}

function open(name, arg, push = true) {
  if (push) {
    keepHome();
    stack.push({ name, arg });
  }
  renderCurrent();
}

function pageScroll() {
  return (document.scrollingElement || document.documentElement).scrollTop;
}

function setPageScroll(value) {
  (document.scrollingElement || document.documentElement).scrollTop = value;
}

function forgetHome() {
  homeKeep = null;
  homeSignature = "";
  homeRefreshToken += 1;
}

function keepHome() {
  const current = stack[stack.length - 1];
  if (!current || current.name !== "home" || !view.childElementCount) return;
  if (homeKeep && view.firstElementChild === homeKeep.node) {
    homeKeep.scroll = pageScroll();
    view.removeChild(homeKeep.node);
    return;
  }
  const node = document.createElement("div");
  while (view.firstChild) node.appendChild(view.firstChild);
  homeKeep = { node, scroll: pageScroll() };
}

function showKeptHome() {
  view.replaceChildren(homeKeep.node);
  const scroll = homeKeep.scroll;
  requestAnimationFrame(() => setPageScroll(scroll));
  refreshHome();
}

async function refreshHome() {
  const token = ++homeRefreshToken;
  try {
    const data = await api("/api/home");
    if (token !== homeRefreshToken) return;
    const current = stack[stack.length - 1];
    if (!current || current.name !== "home") return;
    if (JSON.stringify(data) === homeSignature) return;
    const scroll = pageScroll();
    paintHome(data);
    requestAnimationFrame(() => setPageScroll(scroll));
  } catch (error) {
    if (token !== homeRefreshToken) return;
    if (error.kind === "auth") {
      forgetHome();
      stack.length = 0;
      open("login");
      showToast(error.message);
    }
  }
}

async function renderCurrent() {
  const current = stack[stack.length - 1];
  chrome(current && current.name !== "login");
  if (current && current.name === "home" && homeKeep) {
    showKeptHome();
    return;
  }
  view.innerHTML = `<p class="muted">正在加载…</p>`;
  try {
    if (current.name === "login") return renderLogin();
    if (current.name === "home") return await renderHome();
    if (current.name === "library") return await renderLibrary(current.arg);
    if (current.name === "search") return await renderSearch(current.arg);
    if (current.name === "detail") return await renderDetail(current.arg);
    if (current.name === "season") return await renderSeason(current.arg);
  } catch (error) {
    if (error.kind === "auth") {
      forgetHome();
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
      forgetHome();
      stack.length = 0;
      open("home");
    } catch (error) {
      showToast(error.message);
      button.disabled = false;
    }
  });
}

async function renderHome() {
  paintHome(await api("/api/home"));
}

function paintHome(data) {
  homeSignature = JSON.stringify(data);
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
  const resume = (data.continue || []).map((item) => card(item, { play: true })).join("");
  view.innerHTML = `<h1>你好，${esc(state.username || state.account || "")}</h1>
    ${state.mpv_ready ? "" : `<p class="muted">没有找到随程序提供的 mpv</p>`}
    ${resume ? `<h2>继续观看</h2><div class="row landscape">${resume}</div>` : ""}
    <h2>媒体库</h2>
    <div class="row libraries">${libraries || `<p class="muted">没有媒体库</p>`}</div>
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
  const playGuid = data.play_guid || data.guid;
  const playable = data.type === "Movie" || data.type === "Video" || data.type === "Episode" || data.media_guid || (data.play_guid && data.play_guid !== data.guid);
  const seriesTitle = data.series_title || data.title;
  const seasons = (data.seasons || []).map((season) => {
    const meta = [season.count ? "共" + season.count + "集" : "", season.year || ""].filter(Boolean).join(" · ");
    return `<button class="card season-card" type="button" data-guid="${esc(season.guid)}">
      <div class="poster">${season.poster ? `<img src="${poster(season.poster)}" alt="">` : ""}</div>
      <b>${esc(season.title)}</b>
      <span>${esc(meta)}</span>
    </button>`;
  }).join("");
  const people = (data.people || []).map((person) => {
    const image = poster(person.image);
    const letter = esc((person.name || person.role || "?").slice(0, 1));
    return `<div class="cast">
      <span class="avatar">${image ? `<img src="${image}" alt="">` : letter}</span>
      <b>${esc(person.name || "")}</b>
      <span>${esc(person.role || "")}</span>
    </div>`;
  }).join("");
  const file = data.file || {};
  const facts = [
    ["位置", file.location],
    ["大小", file.size],
    ["创建时间", file.created],
    ["添加时间", file.added],
    ["视频", file.video],
    ["音频", file.audio],
    ["字幕", file.subtitle],
  ].filter((item) => item[1]).map((item) => `<div class="fact"><span>${item[0]}</span><b>${esc(item[1])}</b></div>`).join("");
  const showEpisodes = !seasons;
  view.innerHTML = `<article class="hero">
      <div class="poster">${data.poster ? `<img src="${poster(data.poster)}" alt="">` : ""}</div>
      <div>
        <h1>${esc(data.type === "TV" || data.type === "Episode" ? seriesTitle : data.title)}</h1>
        <p class="muted">${esc(data.meta || data.type || "")}</p>
        <p class="overview">${esc(data.overview || "暂无简介")}</p>
        <div class="actions">
          ${playable ? `<button class="primary" id="play" type="button">${playLabel(data)}</button>` : ""}
        </div>
      </div>
    </article>
    ${seasons ? `<h2>季</h2><div class="row" id="seasons">${seasons}</div>` : ""}
    <div id="episode-section"></div>
    ${people ? `<h2>演职人员</h2><div class="row cast-row">${people}</div>` : ""}
    ${facts ? `<h2>文件信息</h2><div class="facts">${facts}</div>${file.report ? `<button type="button" id="file-more">查看全部</button>` : ""}` : ""}`;
  view.querySelectorAll("img").forEach((img) => img.addEventListener("error", () => img.remove()));
  const play = view.querySelector("#play");
  if (play) play.addEventListener("click", () => startPlay(playGuid, nextAfter(showEpisodes ? episodes : [], playGuid)));
  const more = view.querySelector("#file-more");
  if (more) more.addEventListener("click", () => showFileReport(file.report));
  if (showEpisodes) paintEpisodes(episodes, playGuid);
  bindRows(view);
  view.querySelectorAll(".season-card").forEach((button) => {
    button.addEventListener("click", () => {
      const season = (data.seasons || []).find((item) => item.guid === button.dataset.guid);
      if (season) open("season", { series: data, season });
    });
  });
}

function playLabel(data) {
  const season = Number(data.season_number) || 0;
  const episode = Number(data.episode_number) || 0;
  if (data.type === "TV" || data.type === "Episode") {
    if (season === 0 && episode > 0) return "特别篇 集 " + episode;
    if (season > 0 && episode > 0) return "季 " + season + " 集 " + episode;
    if (episode > 0) return "第" + episode + "集";
  }
  return Number(data.ts) > 30 ? "继续播放" : "播放";
}

async function renderSeason(arg) {
  const series = arg.series || {};
  const season = arg.season || {};
  const data = await api("/api/episodes?guid=" + encodeURIComponent(season.guid || ""));
  drawSeason(series, season, data.items || []);
}

function drawSeason(series, season, episodes) {
  const seasons = series.seasons || [];
  const resumeGuid = series.play_guid || "";
  const current = episodes.find((episode) => episode.guid === resumeGuid) || episodes[0] || null;
  const number = current ? episodeNumber(current, Math.max(0, episodes.indexOf(current))) : 0;
  const chips = seasons.length > 1 ? `<div class="chips" id="season-tabs">${seasons.map((item) => `<button type="button" class="${item.guid === season.guid ? "active" : ""}" data-guid="${esc(item.guid)}">${esc(item.title)}</button>`).join("")}</div>` : "";
  const meta = [seasonLabel(season), season.count ? "共" + season.count + "集" : "", season.year || ""].filter(Boolean).join(" · ");
  view.innerHTML = `<article class="hero">
      <div class="poster">${season.poster ? `<img src="${poster(season.poster)}" alt="">` : ""}</div>
      <div>
        <h1>${esc(series.series_title || series.title || "")}</h1>
        <p class="muted">${esc(meta)}</p>
        <p class="overview">${esc(season.overview || series.overview || "暂无简介")}</p>
        <div class="actions">
          ${current ? `<button class="primary" id="play" type="button">第${number}集</button>` : ""}
        </div>
      </div>
    </article>
    ${chips}
    <div id="episode-section"></div>`;
  view.querySelectorAll("img").forEach((img) => img.addEventListener("error", () => img.remove()));
  const play = view.querySelector("#play");
  if (play && current) play.addEventListener("click", () => startPlay(current.guid, nextAfter(episodes, current.guid)));
  paintEpisodes(episodes, current ? current.guid : "");
  bindRows(view);
  view.querySelectorAll("#season-tabs button").forEach((button) => {
    button.addEventListener("click", () => {
      const next = seasons.find((item) => item.guid === button.dataset.guid);
      const page = stack[stack.length - 1];
      if (!next || !page || page.name !== "season") return;
      page.arg = { series, season: next };
      renderCurrent();
    });
  });
}

function seasonLabel(season) {
  const title = String(season.title || "").trim();
  const lower = title.toLowerCase();
  if (Number(season.number) === 0 || title.includes("特别") || title.includes("特典") || title.includes("番外") || lower.includes("special") || lower === "sp" || lower.includes("ova") || lower.includes("oad")) return "特别篇";
  if (Number(season.number) > 0) return "第" + season.number + "季";
  return title || "本季";
}

const EPISODE_PAGE = 30;

function episodeNumber(episode, index) {
  const number = Number(episode.episode_number);
  return number > 0 ? number : index + 1;
}

function paintEpisodes(episodes, current) {
  const root = view.querySelector("#episode-section");
  if (!root) return;
  if (!episodes.length) {
    root.innerHTML = "";
    return;
  }
  const maxNumber = episodes.reduce((max, episode, index) => Math.max(max, episodeNumber(episode, index)), episodes.length);
  const pages = Math.max(1, Math.ceil(maxNumber / EPISODE_PAGE));
  let page = 0;
  episodes.forEach((episode, index) => {
    if (episode.guid === current) page = Math.floor((episodeNumber(episode, index) - 1) / EPISODE_PAGE);
  });
  const draw = (selected) => {
    const ranges = pages <= 1 ? "" : Array.from({ length: pages }, (_, index) => {
      const start = index * EPISODE_PAGE + 1;
      const end = Math.min((index + 1) * EPISODE_PAGE, maxNumber);
      return `<button type="button" class="${index === selected ? "active" : ""}" data-page="${index}">${start}-${end}</button>`;
    }).join("");
    const cards = episodes.map((episode, index) => {
      if (Math.floor((episodeNumber(episode, index) - 1) / EPISODE_PAGE) !== selected) return "";
      const image = poster(episode.poster);
      const number = episodeNumber(episode, index);
      const watched = Number(episode.ts) > 0 ? "看过" : "";
      return `<button class="episode${episode.guid === current ? " active" : ""}" type="button" data-guid="${esc(episode.guid)}">
        ${image ? `<img src="${image}" alt="">` : `<span class="still"></span>`}
        <b>${esc(episode.title || ("第" + number + "集"))}</b>
        <span>${esc(episode.subtitle || watched)}</span>
      </button>`;
    }).join("");
    root.innerHTML = `<div class="episode-head"><h2>选集</h2><span>共${episodes.length}集</span></div>
      ${ranges ? `<div class="chips" id="episode-ranges">${ranges}</div>` : ""}
      <div class="row episodes-row">${cards}</div>`;
    root.querySelectorAll("[data-page]").forEach((button) => {
      button.addEventListener("click", () => draw(Number(button.dataset.page)));
    });
    root.querySelectorAll(".episode").forEach((button) => {
      button.addEventListener("click", () => startPlay(button.dataset.guid, nextAfter(episodes, button.dataset.guid)));
    });
    root.querySelectorAll("img").forEach((img) => img.addEventListener("error", () => img.remove()));
    bindRows(root);
  };
  draw(page);
}

function showFileReport(report) {
  const node = document.createElement("div");
  node.className = "dialog";
  node.innerHTML = `<div class="sheet player-sheet">
    <h2>媒体信息</h2>
    <pre class="report">${esc(report || "")}</pre>
    <div class="actions"><button type="button" id="close-report">关闭</button></div>
  </div>`;
  document.body.appendChild(node);
  const close = () => node.remove();
  node.querySelector("#close-report").addEventListener("click", close);
  node.addEventListener("click", (event) => { if (event.target === node) close(); });
}

function nextAfter(episodes, guid) {
  const index = episodes.findIndex((episode) => episode.guid === guid);
  if (index >= 0 && episodes[index + 1]) return episodes[index + 1].guid;
  return "";
}

async function startPlay(guid, nextGuid) {
  try {
    await api("/api/play", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ guid, next_guid: nextGuid || "" }),
    });
  } catch (error) {
    showToast(error.message);
  }
}

function applyTheme(theme) {
  document.body.classList.toggle("theme-glass", theme === "glass");
}

function settingsDialog() {
  const node = document.createElement("div");
  const savedTheme = state.theme === "glass" ? "glass" : "default";
  let theme = savedTheme;
  node.className = "dialog";
  node.innerHTML = `<form class="sheet" id="settings-form">
    <h2>设置</h2>
    <label><span class="muted">弹幕服务器</span><input name="danmu_url" value="${esc(state.danmu_url || "")}" placeholder="http://IP:9321"></label>
    <label><span class="muted">mpv.exe 路径（留空则自动查找）</span><input name="mpv_path" value="${esc(state.mpv_path || "")}"></label>
    <div class="muted">背景</div>
    <div class="chips" id="theme-choices">
      <button type="button" data-theme="default">默认</button>
      <button type="button" data-theme="glass">深色毛玻璃</button>
    </div>
    <label class="check"><span>播放时加载弹幕</span><input name="danmu_on" type="checkbox" ${state.danmu_on ? "checked" : ""}></label>
    <label class="check"><span>剧集播完自动下一集</span><input name="auto_next" type="checkbox" ${state.auto_next ? "checked" : ""}></label>
    <p class="muted">当前版本 ${esc(state.version || "")}。mpv 里空格暂停，左右方向键快进，上下方向键调音量。</p>
    <div class="actions">
      <button class="primary" type="submit">保存</button>
      <button type="button" id="check-update">检查更新</button>
      <button type="button" id="close-settings">关闭</button>
      <button class="danger" type="button" id="logout">退出登录</button>
    </div>
  </form>`;
  document.body.appendChild(node);
  const choices = node.querySelectorAll("#theme-choices button");
  const markTheme = () => {
    choices.forEach((button) => button.classList.toggle("active", button.dataset.theme === theme));
    applyTheme(theme);
  };
  markTheme();
  choices.forEach((button) => {
    button.addEventListener("click", () => {
      theme = button.dataset.theme;
      markTheme();
    });
  });
  const close = () => {
    applyTheme(savedTheme);
    node.remove();
  };
  node.querySelector("#close-settings").addEventListener("click", close);
  node.querySelector("#check-update").addEventListener("click", () => checkUpdate(false));
  node.addEventListener("click", (event) => { if (event.target === node) close(); });
  node.querySelector("#logout").addEventListener("click", async () => {
    await api("/api/logout", { method: "POST" });
    node.remove();
    state = await api("/api/status");
    applyTheme(state.theme);
    forgetHome();
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
        theme,
      }),
    });
    applyTheme(state.theme);
    node.remove();
    showToast("已保存");
  });
}

async function playbackDialog() {
  let info;
  try {
    info = await api("/api/playback");
  } catch (error) {
    showToast(error.message);
    return;
  }
  const node = document.createElement("div");
  node.className = "dialog";
  node.innerHTML = `<form class="sheet player-sheet" id="playback-form"></form>`;
  document.body.appendChild(node);
  const form = node.querySelector("#playback-form");
  const draw = (data) => {
    const sizes = [
      { id: "0.8", label: "小" },
      { id: "1", label: "标准" },
      { id: "1.4", label: "大" },
    ];
    const scale = String(data.danmaku_scale || 1);
    const nearest = sizes.reduce((best, item) => Math.abs(Number(item.id) - Number(scale)) < Math.abs(Number(best.id) - Number(scale)) ? item : best).id;
    form.innerHTML = `<h2>播放设置</h2>
      <h3>弹幕</h3>
      <label class="check"><input name="danmaku" type="checkbox" ${data.danmaku_on ? "checked" : ""} ${data.danmaku_ready ? "" : "disabled"}>显示弹幕</label>
      ${data.danmaku_ready ? "" : `<p class="muted">这一集没有匹配到弹幕。</p>`}
      <div class="chips" data-group="scale">${sizes.map((item) => `<button type="button" data-scale="${item.id}" class="${item.id === nearest ? "active" : ""}">${item.label}</button>`).join("")}</div>
      <h3>音频</h3>
      ${choiceRow(data.audio, "audio")}
      <h3>字幕</h3>
      ${choiceRow(data.subtitle, "subtitle")}
      <h3>倍速</h3>
      ${choiceRow([0.5, 0.75, 1, 1.25, 1.5, 2, 3].map((item) => ({ id: item, label: item + "x", selected: Number(data.speed || 1) === item })), "speed")}
      <h3>画质</h3>
      ${choiceRow((data.qualities || []).map((item) => ({ id: item.index, label: item.label, selected: item.index === data.quality_index })), "quality")}
      <h3>片头片尾</h3>
      <div class="pair">
        <label><span class="muted">片头（秒）</span><input name="opening" type="number" min="0" max="1200" value="${Number(data.skip_opening) || 0}"></label>
        <label><span class="muted">片尾（秒）</span><input name="ending" type="number" min="0" max="1200" value="${Number(data.skip_ending) || 0}"></label>
      </div>
      <div class="actions">
        <button class="primary" type="submit">保存片头片尾</button>
        <button type="button" id="close-playback">关闭</button>
      </div>`;
    form.querySelector("#close-playback").addEventListener("click", () => node.remove());
    form.querySelector("[name=danmaku]").addEventListener("change", async (event) => {
      await applyPlayback("/api/playback/danmaku", { on: event.target.checked, scale: Number(nearest) });
    });
    form.querySelectorAll("[data-scale]").forEach((button) => {
      button.addEventListener("click", async () => {
        const on = form.querySelector("[name=danmaku]").checked;
        await applyPlayback("/api/playback/danmaku", { on, scale: Number(button.dataset.scale) });
        draw(await api("/api/playback"));
      });
    });
    bindChoice(form, "audio", (id) => applyPlayback("/api/playback/audio", { id }));
    bindChoice(form, "subtitle", (id) => applyPlayback("/api/playback/subtitle", { id }));
    bindChoice(form, "speed", async (id) => {
      await applyPlayback("/api/playback/speed", { speed: Number(id) });
    });
    bindChoice(form, "quality", async (id) => {
      await applyPlayback("/api/playback/quality", { index: Number(id) });
      draw(await api("/api/playback"));
    });
  };
  draw(info);
  node.addEventListener("click", (event) => { if (event.target === node) node.remove(); });
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
      await api("/api/playback/skip", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          opening: Number(form.opening.value || 0),
          ending: Number(form.ending.value || 0),
        }),
      });
      showToast("片头片尾已保存");
      node.remove();
    } catch (error) {
      showToast(error.message);
    }
  });
}

function choiceRow(items, group) {
  if (!items || !items.length) return `<p class="muted">没有可切换的选项</p>`;
  return `<div class="chips" data-group="${group}">${items.map((item) => `<button type="button" data-id="${esc(item.id)}" class="${item.selected ? "active" : ""}">${esc(item.label)}</button>`).join("")}</div>`;
}

function bindChoice(form, group, action) {
  const root = form.querySelector(`[data-group="${group}"]`);
  if (!root) return;
  root.querySelectorAll("button").forEach((button) => {
    button.addEventListener("click", async () => {
      await action(button.dataset.id);
      root.querySelectorAll("button").forEach((item) => item.classList.toggle("active", item === button));
    });
  });
}

async function applyPlayback(url, body) {
  try {
    await api(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
  } catch (error) {
    showToast(error.message);
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
    <p class="overview update-log">${esc(info.changelog || "暂无更新说明")}</p>
    <div class="update-track" id="update-track"><span id="update-fill"></span></div>
    <p class="muted" id="update-progress"></p>
    <div class="actions">
      <button class="primary" id="update-now" type="button">立即更新</button>
      ${later}
    </div>
  </div>`;
  document.body.appendChild(node);
  const close = () => {
    if (info.force || node.dataset.downloading) return;
    updateOpen = false;
    node.remove();
  };
  const laterBtn = node.querySelector("#update-later");
  if (laterBtn) laterBtn.addEventListener("click", close);
  node.addEventListener("click", (event) => { if (event.target === node) close(); });
  node.querySelector("#update-now").addEventListener("click", () => downloadUpdate(info.exeUrl, node));
}

function formatSize(bytes) {
  const value = Number(bytes) || 0;
  if (value >= 1048576) return (value / 1048576).toFixed(1) + " MB";
  if (value >= 1024) return Math.round(value / 1024) + " KB";
  return value + " B";
}

function renderDownload(node, state) {
  const track = node.querySelector("#update-track");
  const fill = node.querySelector("#update-fill");
  const progress = node.querySelector("#update-progress");
  track.classList.add("show");
  const total = Number(state.total) || 0;
  const received = Number(state.received) || 0;
  if (total > 0) {
    const percent = Math.min(100, Math.round(received / total * 100));
    track.classList.remove("indeterminate");
    fill.style.transform = "";
    fill.style.width = Math.max(percent, received > 0 ? 2 : 0) + "%";
    progress.textContent = "正在下载 " + percent + "% · " + formatSize(received) + " / " + formatSize(total);
    return;
  }
  track.classList.add("indeterminate");
  fill.style.width = "";
  progress.textContent = received > 0 ? "正在下载 " + formatSize(received) : "正在下载…";
}

async function downloadUpdate(exeUrl, node) {
  const button = node.querySelector("#update-now");
  const later = node.querySelector("#update-later");
  button.disabled = true;
  if (later) later.disabled = true;
  node.dataset.downloading = "1";
  renderDownload(node, { received: 0, total: 0 });
  try {
    await api("/api/update/download", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ exeUrl }),
    });
    for (;;) {
      const state = await api("/api/update/progress");
      if (state.status === "failed") throw new Error(state.message || "下载失败");
      renderDownload(node, state);
      if (state.status === "done") break;
      await new Promise((resolve) => setTimeout(resolve, 400));
    }
    const fill = node.querySelector("#update-fill");
    const track = node.querySelector("#update-track");
    track.classList.remove("indeterminate");
    fill.style.width = "100%";
    node.querySelector("#update-progress").textContent = "下载完成，即将安装并重新打开";
    await api("/api/update/apply", { method: "POST" });
  } catch (error) {
    button.disabled = false;
    if (later) later.disabled = false;
    delete node.dataset.downloading;
    node.querySelector("#update-progress").textContent = error.message;
  }
}

const DANMAKU_FIELDS = [
  { key: "area", label: "显示区域", min: 10, max: 80, from: (value) => value, to: (value) => value, text: (value) => value + "%" },
  { key: "speed", label: "速度", min: 30, max: 300, from: (value) => Math.round(value * 100), to: (value) => value / 100, text: (value) => (value / 100).toFixed(1) + "x" },
  { key: "opacity", label: "不透明度", min: 0, max: 100, from: (value) => Math.round(value * 100), to: (value) => value / 100, text: (value) => value + "%" },
  { key: "fontsize", label: "字号", min: 12, max: 40, from: (value) => value, to: (value) => value, text: (value) => String(value) },
  { key: "density", label: "密度", min: 50, max: 100, from: (value) => value, to: (value) => value, text: (value) => value + "%" },
  { key: "max_active", label: "同屏最大", min: 10, max: 80, from: (value) => value, to: (value) => value, text: (value) => String(value) },
  { key: "row_spacing", label: "行间距", min: 120, max: 300, from: (value) => Math.round(value * 100), to: (value) => value / 100, text: (value) => (value / 100).toFixed(1) + "x" },
  { key: "offset", label: "时间偏移", min: 0, max: 240, from: (value) => value + 120, to: (value) => value - 120, text: (value) => (value - 120) + "s" },
  { key: "max_comments", label: "加载上限", min: 100, max: 50000, from: (value) => value, to: (value) => value, text: (value) => String(value) },
];
const DANMAKU_CHECKS = [
  ["on", "弹幕开关"],
  ["scroll", "滚动弹幕"],
  ["top", "顶部弹幕"],
  ["bottom", "底部弹幕"],
  ["time_scale", "时间比例缩放"],
  ["outline", "文字描边"],
];
const DANMAKU_DEFAULTS = {
  on: true, area: 35, speed: 1, opacity: 0.85, fontsize: 22,
  scroll: true, top: true, bottom: true, time_scale: false, outline: true,
  density: 100, max_active: 40, row_spacing: 1.8, offset: 0, max_comments: 50000,
};
let danmakuOpen = false;
let danmakuTimer = 0;

function sliderRow(field, settings) {
  const value = field.from(Number(settings[field.key]));
  return `<label class="slider-row"><span>${field.label}</span><input type="range" data-key="${field.key}" min="${field.min}" max="${field.max}" step="1" value="${value}"><b>${field.text(value)}</b></label>`;
}

async function danmakuDialog() {
  if (danmakuOpen) return;
  let info;
  try {
    info = await api("/api/danmaku/settings");
  } catch (error) {
    showToast(error.message);
    return;
  }
  danmakuOpen = true;
  const node = document.createElement("div");
  node.className = "dialog";
  node.innerHTML = `<form class="sheet player-sheet" id="danmaku-form"></form>`;
  document.body.appendChild(node);
  const form = node.querySelector("#danmaku-form");
  const close = () => {
    danmakuOpen = false;
    clearTimeout(danmakuTimer);
    node.remove();
    closePlayerPanel(null);
  };
  const ready = !!info.ready;
  const checkRow = (key, label, settings) => `<label class="check"><span>${label}</span><input type="checkbox" data-key="${key}" ${settings[key] ? "checked" : ""}></label>`;
  const draw = (settings) => {
    const top = DANMAKU_FIELDS.slice(0, 4).map((field) => sliderRow(field, settings)).join("");
    const bottom = DANMAKU_FIELDS.slice(4).map((field) => sliderRow(field, settings)).join("");
    const later = DANMAKU_CHECKS.slice(1).map(([key, label]) => checkRow(key, label, settings)).join("");
    form.innerHTML = `<div class="sheet-head"><h2>弹幕设置</h2><button type="button" id="danmaku-reset">重置</button></div>
      ${checkRow("on", "弹幕开关", settings)}
      ${top}
      ${later}
      ${bottom}
      ${ready ? "" : `<p class="muted">这一集还没有匹配到弹幕，设置会在匹配到之后生效。</p>`}
      <div class="actions"><button type="button" id="danmaku-close">关闭</button></div>`;
    form.querySelector("#danmaku-reset").addEventListener("click", async () => {
      const saved = await saveDanmaku(DANMAKU_DEFAULTS);
      if (saved) draw(saved);
    });
    form.querySelector("#danmaku-close").addEventListener("click", close);
    form.querySelectorAll("input").forEach((input) => {
      input.addEventListener("input", () => {
        const field = DANMAKU_FIELDS.find((item) => item.key === input.dataset.key);
        if (field) input.parentElement.querySelector("b").textContent = field.text(Number(input.value));
        clearTimeout(danmakuTimer);
        danmakuTimer = setTimeout(() => saveDanmaku(readDanmaku(form)), 180);
      });
    });
  };
  draw(info.settings || DANMAKU_DEFAULTS);
  node.addEventListener("click", (event) => { if (event.target === node) close(); });
}

function readDanmaku(form) {
  const body = {};
  form.querySelectorAll("[data-key]").forEach((input) => {
    const field = DANMAKU_FIELDS.find((item) => item.key === input.dataset.key);
    if (input.type === "checkbox") body[input.dataset.key] = input.checked;
    else if (field) body[input.dataset.key] = field.to(Number(input.value));
  });
  return body;
}

async function saveDanmaku(body) {
  try {
    const data = await api("/api/danmaku/settings", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    return data.settings || body;
  } catch (error) {
    showToast(error.message);
    return null;
  }
}

let skipOpen = false;

function skipClock(sec) {
  const value = Math.max(0, Math.floor(Number(sec) || 0));
  return Math.floor(value / 60) + "分" + (value % 60) + "秒";
}

async function skipDialog() {
  if (skipOpen) return;
  skipOpen = true;
  const node = document.createElement("div");
  node.className = "dialog";
  node.innerHTML = `<div class="sheet player-sheet" id="skip-sheet"></div>`;
  document.body.appendChild(node);
  const sheet = node.querySelector("#skip-sheet");
  let page = "menu";
  let which = "opening";
  let timer = 0;
  const close = () => {
    skipOpen = false;
    clearInterval(timer);
    node.remove();
    closePlayerPanel(null);
  };
  const load = async (quiet) => {
    try {
      return await api("/api/skip");
    } catch (error) {
      if (!quiet) showToast(error.message);
      return null;
    }
  };
  const save = async (opening, ending) => {
    await api("/api/playback/skip", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ opening, ending }),
    });
  };
  const draw = async () => {
    const data = await load();
    if (!data || !node.isConnected) return;
    clearInterval(timer);
    timer = 0;
    if (page === "menu") {
      sheet.innerHTML = `<div class="sheet-head"><h2>片头片尾</h2><button type="button" id="skip-close">关闭</button></div>
        <p class="muted">生效范围：${esc(data.title || "当前视频")}</p>
        <button class="skip-row" type="button" data-edit="opening"><b>片头时长</b><span>${skipClock(data.opening)} &gt;</span></button>
        <button class="skip-row" type="button" data-edit="ending"><b>片尾时长</b><span>${skipClock(data.ending)} &gt;</span></button>`;
      sheet.querySelector("#skip-close").addEventListener("click", close);
      sheet.querySelectorAll("[data-edit]").forEach((button) => {
        button.addEventListener("click", () => {
          which = button.dataset.edit;
          page = "edit";
          draw();
        });
      });
      return;
    }
    if (page === "custom") {
      const current = which === "opening" ? data.opening : data.ending;
      sheet.innerHTML = `<div class="sheet-head"><button type="button" id="skip-back">返回</button><h2>自定义${which === "opening" ? "片头" : "片尾"}时长</h2></div>
        <div class="wheels">
          <input id="skip-min" type="number" min="0" max="20" value="${Math.min(20, Math.floor(current / 60))}">
          <span>分</span>
          <input id="skip-sec" type="number" min="0" max="59" value="${current % 60}">
          <span>秒</span>
        </div>
        <div class="actions">
          <button type="button" id="skip-zero">重置</button>
          <button class="primary" type="button" id="skip-ok">确定</button>
        </div>`;
      sheet.querySelector("#skip-back").addEventListener("click", () => { page = "edit"; draw(); });
      sheet.querySelector("#skip-zero").addEventListener("click", () => {
        sheet.querySelector("#skip-min").value = "0";
        sheet.querySelector("#skip-sec").value = "0";
      });
      sheet.querySelector("#skip-ok").addEventListener("click", async () => {
        const total = Math.max(0, Math.min(20, Number(sheet.querySelector("#skip-min").value) || 0)) * 60
          + Math.max(0, Math.min(59, Number(sheet.querySelector("#skip-sec").value) || 0));
        try {
          await save(which === "opening" ? total : data.opening, which === "ending" ? total : data.ending);
          page = "edit";
          draw();
        } catch (error) {
          showToast(error.message);
        }
      });
      return;
    }
    const saved = which === "opening" ? data.opening : data.ending;
    const liveValue = which === "opening" ? data.position : (data.duration > 0 ? Math.max(0, data.duration - data.position) : -1);
    sheet.innerHTML = `<div class="sheet-head"><button type="button" id="skip-back">返回</button><h2>${which === "opening" ? "片头时长" : "片尾时长"}</h2><button type="button" id="skip-reset">重置</button></div>
      <p class="muted">生效范围：${esc(data.title || "当前视频")}</p>
      <div class="skip-clock">${skipClock(saved)}</div>
      <div class="skip-live">
        <div><b>${which === "opening" ? "当前播放时间" : "距离片尾"}</b><div class="muted" id="skip-live">${liveValue < 0 ? "片长未知" : skipClock(liveValue)}</div></div>
        <button type="button" id="skip-use">${which === "opening" ? "设为片头" : "设为片尾"}</button>
      </div>
      <button class="skip-row" type="button" id="skip-custom"><b>自定义</b><span>&gt;</span></button>`;
    sheet.querySelector("#skip-back").addEventListener("click", () => { page = "menu"; draw(); });
    sheet.querySelector("#skip-custom").addEventListener("click", () => { page = "custom"; draw(); });
    sheet.querySelector("#skip-reset").addEventListener("click", async () => {
      try {
        await save(which === "opening" ? 0 : data.opening, which === "ending" ? 0 : data.ending);
        draw();
      } catch (error) {
        showToast(error.message);
      }
    });
    sheet.querySelector("#skip-use").addEventListener("click", async () => {
      const fresh = await load();
      if (!fresh) return;
      if (which === "ending" && !(Number(fresh.duration) > 0)) {
        showToast("还不知道片长，稍后再设片尾");
        return;
      }
      try {
        await save(
          which === "opening" ? fresh.position : fresh.opening,
          which === "ending" ? Math.max(0, fresh.duration - fresh.position) : fresh.ending,
        );
        draw();
      } catch (error) {
        showToast(error.message);
      }
    });
    timer = setInterval(async () => {
      const fresh = await load(true);
      const liveNode = sheet.querySelector("#skip-live");
      if (!fresh || !liveNode) return;
      const value = which === "opening" ? fresh.position : (fresh.duration > 0 ? Math.max(0, fresh.duration - fresh.position) : -1);
      liveNode.textContent = value < 0 ? "片长未知" : skipClock(value);
    }, 500);
  };
  node.addEventListener("click", (event) => { if (event.target === node) close(); });
  draw();
}

function closePlayerPanel(node) {
  if (document.body.classList.contains("panel")) {
    api("/api/panel/close", { method: "POST" }).catch(() => {});
    return;
  }
  if (node) node.remove();
}

async function watchDanmakuSettings() {
  for (;;) {
    await new Promise((resolve) => setTimeout(resolve, 700));
    try {
      const data = await api("/api/danmaku/request");
      if (data.open) {
        if (data.kind === "skip") skipDialog();
        else danmakuDialog();
      }
    } catch (error) {}
  }
}

async function boot() {
  const panel = new URLSearchParams(location.search).get("panel");
  if (panel === "danmaku" || panel === "skip") {
    document.body.classList.add("panel");
    try {
      state = await api("/api/status");
    } catch (error) {
      state = {};
    }
    chrome(false);
    applyTheme(state.theme);
    if (panel === "skip") skipDialog();
    else danmakuDialog();
    return;
  }
  try {
    state = await api("/api/status");
  } catch (error) {
    view.innerHTML = `<p>${esc(error.message)}</p>`;
    return;
  }
  open(state.logged_in ? "home" : "login");
  applyTheme(state.theme);
  checkUpdate(true);
  watchDanmakuSettings();
}

boot();
