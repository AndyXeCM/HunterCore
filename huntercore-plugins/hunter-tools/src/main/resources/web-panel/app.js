const LANG_KEY = 'huntercore.panel.language';

function detectLanguage() {
  try {
    const saved = localStorage.getItem(LANG_KEY);
    if (saved === 'zh' || saved === 'en') return saved;
  } catch {
    // Ignore storage failures and fall back to browser language.
  }
  return navigator.language?.toLowerCase().startsWith('zh') ? 'zh' : 'en';
}

const state = {
  session: null,
  csrf: '',
  webUsers: [],
  plugins: [],
  mapUrl: '',
  refreshTimer: null,
  lang: detectLanguage(),
  lastData: null,
  page: 'map'
};

const $ = (id) => document.getElementById(id);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));

const translations = {
  zh: {
    'server.loading': '正在读取服务器状态...',
    'nav.map': '地图',
    'nav.overview': '总览',
    'nav.plugins': '插件',
    'nav.tools': '工具',
    'nav.admin': '管理',
    'language.switch': '切换到 English',
    'session.eyebrow': '网页控制台',
    'session.guest': '访客视图',
    'login.username': 'HunterAuth 用户名',
    'login.password': '密码',
    'login.action': '登录',
    'logout.action': '退出登录',
    'metric.online': '在线',
    'metric.memory': '内存',
    'map.open': '在新标签打开地图',
    'overview.eyebrow': '实时服务器',
    'overview.title': '总览',
    'worlds.title': '世界',
    'players.title': '玩家',
    'plugins.title': '插件',
    'plugins.eyebrow': '插件工作台',
    'optimization.title': '优化',
    'players.loginRequired': '登录后查看玩家详情。',
    'plugins.loginRequired': '登录后查看插件详情。',
    'plugins.count': '{count} 个',
    'worlds.none': '暂无已加载世界。',
    'players.none': '当前没有玩家在线。',
    'tools.eyebrow': 'Minecraft 操作',
    'tools.title': '工具',
    'console.title': '命令控制台',
    'console.placeholder': 'list',
    'console.run': '运行',
    'quick.saveAll': '保存全部',
    'quick.clearWeather': '晴天',
    'quick.day': '白天',
    'command.loginRequired': '登录后运行允许的命令。',
    'command.dispatched': '命令已发送。',
    'command.loggedOut': '已退出登录。',
    'command.loginFailed': '登录失败。',
    'command.loggedIn': '已登录为 {username} ({role})。',
    'command.error': '错误：{message}',
    'actors.title': '实体',
    'actors.name': '名称',
    'actors.spawn': '生成',
    'actors.spawned': '实体已生成。',
    'actors.removed': '实体已移除。',
    'actors.none': '暂无配置实体。',
    'actors.spawnPoint': '出生点',
    'actors.npc': 'NPC',
    'actors.fakePlayer': '假人',
    'actors.villager': '村民',
    'actors.mannequin': '模型假人',
    'actors.notConfigured': '未配置',
    'actors.live': '在线',
    'actors.configured': '已配置',
    'admin.required': '需要管理员会话。',
    'admin.eyebrow': '运维控制',
    'admin.title': '管理',
    'modules.title': '模块',
    'commands.title': '命令开关',
    'webSettings.title': '网页面板',
    'webSettings.serverName': '服务器名称',
    'webSettings.bind': '绑定地址',
    'webSettings.port': '网页端口',
    'webSettings.mapUrl': '地图地址，例如 http://%host%:8100/',
    'webSettings.publicMap': '公开地图',
    'webSettings.save': '保存网页设置',
    'webSettings.saved': '网页设置已保存。',
    'webSettings.restarting': '网页设置已保存，面板会切换到新地址。',
    'webUsers.title': '网页身份',
    'webUsers.username': '用户名',
    'webUsers.password': '可选网页密码',
    'webUsers.commands': '允许命令',
    'webUsers.allowedCommands': 'list spawn 或 *',
    'webUsers.save': '保存身份',
    'webUsers.saved': '网页身份已保存。',
    'webUsers.removed': '网页身份已移除。',
    'webUsers.none': '暂无网页身份配置。',
    'webUsers.webPasswordSet': '已设置网页密码',
    'webUsers.hunterAuthOnly': '仅 HunterAuth',
    'webUsers.commandsOn': '命令开',
    'webUsers.commandsOff': '命令关',
    'allowed.inherit': '继承',
    'allowed.custom': '自定义',
    'allowed.none': '无',
    'action.apply': '应用',
    'action.edit': '编辑',
    'action.remove': '移除',
    'action.enable': '启用',
    'action.disable': '停用',
    'action.reload': '重载',
    'action.update': '更新',
    'action.updated': '已更新。',
    'luck.dispatched': 'LuckPerms 命令已发送。',
    'health.label': '健康',
    'health.heap': '堆内存 {value}%',
    'health.noAlerts': '无活跃告警',
    'role.guest': '访客',
    'role.player': '玩家',
    'role.admin': '管理员',
    'status.ok': '正常',
    'status.warning': '警告',
    'status.critical': '严重',
    'status.disabled': '关闭',
    'plugin.status.enabled': '已启用',
    'plugin.status.disabled': '已停用',
    'plugin.status.installed': '已安装',
    'plugin.unknownVersion': '未知版本',
    'plugin.jarUnknown': '未定位 jar',
    'plugin.loadedRuntime': '运行中插件',
    'plugin.descriptorUnknown': '描述文件未知',
    'plugin.webControls': '网页可控',
    'plugin.protected': '受保护',
    'plugin.updatePlaceholder': 'https://example.com/{name}.jar',
    'plugin.actionCompleted': '插件操作已完成。',
    'plugin.updateCompleted': '插件更新已完成。',
    'plugin.updateUrlRequired': '错误：需要更新 URL。',
    'world.online': '{count} 在线',
    'world.meta': '{chunks} 区块 · {entities} 实体 · 时间 {time}',
    'optimization.cpuThreads': 'CPU 线程',
    'optimization.paperWorkers': 'Paper 工作线程',
    'optimization.divineWorkers': 'DivineMC 工作线程',
    'optimization.nettyIoThreads': 'Netty IO',
    'optimization.forkJoinParallelism': 'ForkJoin',
    'optimization.hunterToolsWorkers': 'HunterTools 工作线程',
    'optimization.webPanelWorkers': '网页工作线程',
    'optimization.guestStatusCacheMillis': '访客缓存'
  },
  en: {
    'server.loading': 'Loading server status...',
    'nav.map': 'Map',
    'nav.overview': 'Overview',
    'nav.plugins': 'Plugins',
    'nav.tools': 'Tools',
    'nav.admin': 'Admin',
    'language.switch': 'Switch to Chinese',
    'session.eyebrow': 'Web console',
    'session.guest': 'Guest view',
    'login.username': 'HunterAuth username',
    'login.password': 'Password',
    'login.action': 'Login',
    'logout.action': 'Logout',
    'metric.online': 'Online',
    'metric.memory': 'Memory',
    'map.open': 'Open map in new tab',
    'overview.eyebrow': 'Live server',
    'overview.title': 'Overview',
    'worlds.title': 'Worlds',
    'players.title': 'Players',
    'plugins.title': 'Plugins',
    'plugins.eyebrow': 'Plugin workspace',
    'optimization.title': 'Optimization',
    'players.loginRequired': 'Login to view player detail.',
    'plugins.loginRequired': 'Login to view plugin detail.',
    'plugins.count': '{count}',
    'worlds.none': 'No worlds loaded.',
    'players.none': 'No players online.',
    'tools.eyebrow': 'Minecraft actions',
    'tools.title': 'Tools',
    'console.title': 'Command console',
    'console.placeholder': 'list',
    'console.run': 'Run',
    'quick.saveAll': 'Save all',
    'quick.clearWeather': 'Clear weather',
    'quick.day': 'Day',
    'command.loginRequired': 'Login to run allowed commands.',
    'command.dispatched': 'Command dispatched.',
    'command.loggedOut': 'Logged out.',
    'command.loginFailed': 'Login failed.',
    'command.loggedIn': 'Logged in as {username} ({role}).',
    'command.error': 'Error: {message}',
    'actors.title': 'Actors',
    'actors.name': 'Name',
    'actors.spawn': 'Spawn',
    'actors.spawned': 'Actor spawned.',
    'actors.removed': 'Actor removed.',
    'actors.none': 'No configured actors.',
    'actors.spawnPoint': 'Spawn',
    'actors.npc': 'NPC',
    'actors.fakePlayer': 'Fake player',
    'actors.villager': 'Villager',
    'actors.mannequin': 'Mannequin',
    'actors.notConfigured': 'not configured',
    'actors.live': 'live',
    'actors.configured': 'configured',
    'admin.required': 'Admin session required.',
    'admin.eyebrow': 'Operator controls',
    'admin.title': 'Admin',
    'modules.title': 'Modules',
    'commands.title': 'Command gates',
    'webSettings.title': 'Web panel',
    'webSettings.serverName': 'Server name',
    'webSettings.bind': 'Bind address',
    'webSettings.port': 'Web port',
    'webSettings.mapUrl': 'Map URL, for example http://%host%:8100/',
    'webSettings.publicMap': 'public map',
    'webSettings.save': 'Save web settings',
    'webSettings.saved': 'Web settings saved.',
    'webSettings.restarting': 'Web settings saved. Panel is restarting on the new address.',
    'webUsers.title': 'Web roles',
    'webUsers.username': 'Username',
    'webUsers.password': 'Optional web password',
    'webUsers.commands': 'commands',
    'webUsers.allowedCommands': 'list spawn or *',
    'webUsers.save': 'Save role',
    'webUsers.saved': 'Web role saved.',
    'webUsers.removed': 'Web role removed.',
    'webUsers.none': 'No web roles configured.',
    'webUsers.webPasswordSet': 'web password set',
    'webUsers.hunterAuthOnly': 'HunterAuth only',
    'webUsers.commandsOn': 'commands on',
    'webUsers.commandsOff': 'commands off',
    'allowed.inherit': 'inherit',
    'allowed.custom': 'custom',
    'allowed.none': 'none',
    'action.apply': 'Apply',
    'action.edit': 'Edit',
    'action.remove': 'Remove',
    'action.enable': 'Enable',
    'action.disable': 'Disable',
    'action.reload': 'Reload',
    'action.update': 'Update',
    'action.updated': 'Updated.',
    'luck.dispatched': 'LuckPerms command dispatched.',
    'health.label': 'Health',
    'health.heap': 'Heap {value}%',
    'health.noAlerts': 'No active alerts',
    'role.guest': 'Guest',
    'role.player': 'Player',
    'role.admin': 'Admin',
    'status.ok': 'ok',
    'status.warning': 'warning',
    'status.critical': 'critical',
    'status.disabled': 'disabled',
    'plugin.status.enabled': 'enabled',
    'plugin.status.disabled': 'disabled',
    'plugin.status.installed': 'installed',
    'plugin.unknownVersion': 'unknown version',
    'plugin.jarUnknown': 'jar not resolved',
    'plugin.loadedRuntime': 'runtime plugin',
    'plugin.descriptorUnknown': 'descriptor unknown',
    'plugin.webControls': 'web controls',
    'plugin.protected': 'protected',
    'plugin.updatePlaceholder': 'https://example.com/{name}.jar',
    'plugin.actionCompleted': 'Plugin action completed.',
    'plugin.updateCompleted': 'Plugin update completed.',
    'plugin.updateUrlRequired': 'Error: update URL is required.',
    'world.online': '{count} online',
    'world.meta': '{chunks} chunks · {entities} entities · time {time}',
    'optimization.cpuThreads': 'CPU threads',
    'optimization.paperWorkers': 'Paper workers',
    'optimization.divineWorkers': 'DivineMC workers',
    'optimization.nettyIoThreads': 'Netty IO',
    'optimization.forkJoinParallelism': 'ForkJoin',
    'optimization.hunterToolsWorkers': 'HunterTools workers',
    'optimization.webPanelWorkers': 'Web workers',
    'optimization.guestStatusCacheMillis': 'Guest cache'
  }
};

function t(key, values = {}) {
  const table = translations[state.lang] || translations.en;
  const fallback = translations.en[key] || key;
  const value = table[key] || fallback;
  return value.replace(/\{([A-Za-z0-9_]+)\}/g, (_, name) => String(values[name] ?? ''));
}

function roleLabel(role) {
  return t(`role.${role === 'admin' ? 'admin' : role === 'player' ? 'player' : 'guest'}`);
}

function statusLabel(status) {
  return t(`status.${status || 'ok'}`);
}

function pluginStatusLabel(status) {
  return t(`plugin.status.${status || 'disabled'}`);
}

const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
})[char]);

const severityClass = (value) => ['ok', 'warning', 'critical', 'disabled'].includes(value) ? value : 'ok';
const liquidGlassSelector = [
  '.topNav',
  '.sessionDock',
  '.panel',
  '.actionToast',
  '.pluginItem',
  '.toggleItem',
  '.primaryButton',
  '.secondaryButton',
  '.smallButton',
  '.quickRow button',
  'input',
  'select',
  '.navButton',
  '.productMark',
  '.statusPill',
  '.roleBadge',
  '.stateChip'
].join(',');

let toastTimer = 0;

function showToast(message) {
  const toast = $('actionToast');
  if (!toast || !message) return;
  toast.textContent = message;
  toast.hidden = false;
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => {
    toast.hidden = true;
  }, 4200);
}

function setOutput(message, output = '') {
  $('commandResult').dataset.placeholder = 'false';
  $('commandResult').textContent = output ? `${message}\n\n${output}` : message;
  showToast(message);
}

function setCommandPlaceholder() {
  $('commandResult').dataset.placeholder = 'true';
  $('commandResult').textContent = t('command.loginRequired');
}

function dataItem(left, right = '', meta = '') {
  return `<div class="dataItem"><span>${esc(left)}${meta ? `<small>${esc(meta)}</small>` : ''}</span><strong>${esc(right)}</strong></div>`;
}

function toggleItem(left, checked, attrs = '', disabled = false) {
  return `<label class="toggleItem"><span>${esc(left)}</span><input type="checkbox" ${checked ? 'checked' : ''} ${disabled ? 'disabled' : ''} ${attrs}></label>`;
}

function translateOptions(selectId, labels) {
  const select = $(selectId);
  if (!select) return;
  Array.from(select.options).forEach((option) => {
    if (labels[option.value]) option.textContent = labels[option.value];
  });
}

function applyTranslations() {
  document.documentElement.lang = state.lang === 'zh' ? 'zh-CN' : 'en';
  document.title = state.lang === 'zh' ? 'HunterCore 面板' : 'HunterCore Panel';
  $$('[data-i18n]').forEach((element) => {
    element.textContent = t(element.dataset.i18n);
  });
  $$('[data-i18n-placeholder]').forEach((element) => {
    element.setAttribute('placeholder', t(element.dataset.i18nPlaceholder));
  });
  $$('[data-i18n-title]').forEach((element) => {
    element.setAttribute('title', t(element.dataset.i18nTitle));
    element.setAttribute('aria-label', t(element.dataset.i18nTitle));
  });
  const languageToggle = $('languageToggle');
  if (languageToggle) languageToggle.textContent = state.lang === 'zh' ? 'EN' : '中文';
  const webSettingsButton = $('webSettingsForm')?.querySelector('button[type="submit"]');
  if (webSettingsButton) webSettingsButton.textContent = t('webSettings.save');
  translateOptions('webUserRole', { player: roleLabel('player'), admin: roleLabel('admin') });
  translateOptions('webUserAllowedMode', {
    inherit: t('allowed.inherit'),
    custom: t('allowed.custom'),
    none: t('allowed.none')
  });
  translateOptions('actorModule', { npcs: t('actors.npc'), 'fake-players': t('actors.fakePlayer') });
  translateOptions('actorKind', { villager: t('actors.villager'), mannequin: t('actors.mannequin') });
  const commandResult = $('commandResult');
  if (commandResult?.dataset.placeholder !== 'false') setCommandPlaceholder();
}

function rerenderCachedStatus() {
  updateSessionChrome();
  if (!state.lastData) return;
  const data = state.lastData;
  renderHealth(data.health);
  renderOverview(data);
  renderActorWorlds(data.worlds);
  renderActors(data.actorDetails);
  renderOperations(data.modules);
  renderWebUsers(data.webUsers);
  renderWebSettings(data.webSettings);
}

function setLanguage(lang) {
  state.lang = lang === 'en' ? 'en' : 'zh';
  try {
    localStorage.setItem(LANG_KEY, state.lang);
  } catch {
    // Language still changes for the current page even when storage is blocked.
  }
  applyTranslations();
  rerenderCachedStatus();
}

function pageFromLocation() {
  const value = window.location.hash.replace(/^#\/?/, '');
  return ['map', 'overview', 'plugins', 'tools', 'admin'].includes(value) ? value : 'map';
}

function showPage(page, push = true) {
  const targetPage = page === 'admin' && !state.session?.admin ? 'overview' : page;
  state.page = targetPage;
  $$('.pageView').forEach((view) => {
    const active = view.id === targetPage;
    view.hidden = !active;
    view.classList.toggle('isActive', active);
  });
  $$('.navButton[data-page-target]').forEach((button) => {
    button.classList.toggle('isActive', button.dataset.pageTarget === targetPage);
  });
  if (push && window.location.hash !== `#${targetPage}`) {
    history.pushState(null, '', `#${targetPage}`);
  }
  window.scrollTo(0, 0);
}

function actorLine(actor) {
  const location = actor.world
    ? `${actor.world} ${Number(actor.x).toFixed(1)} ${Number(actor.y).toFixed(1)} ${Number(actor.z).toFixed(1)}`
    : t('actors.notConfigured');
  return `<div class="dataItem">
    <span>${esc(actor.displayName)}<small>${actor.live ? t('actors.live') : t('actors.configured')} · ${esc(actor.module)} · ${esc(actor.kind)} · ${esc(location)}</small></span>
    <span class="actorActions"><button type="button" data-actor-remove="true" data-actor-module="${esc(actor.module)}" data-actor-id="${esc(actor.id)}">${esc(t('action.remove'))}</button></span>
  </div>`;
}

function allowedLine(user) {
  if (!user.allowedCommandsConfigured) return t('allowed.inherit');
  return user.allowedCommands?.length ? user.allowedCommands.join(', ') : t('allowed.none');
}

function webUserLine(user) {
  return `<div class="dataItem">
    <span>${esc(user.displayName)}<small>${esc(roleLabel(user.role))} · ${user.passwordConfigured ? t('webUsers.webPasswordSet') : t('webUsers.hunterAuthOnly')} · ${user.commandExecution ? t('webUsers.commandsOn') : t('webUsers.commandsOff')} · ${esc(allowedLine(user))}</small></span>
    <span class="userActions">
      <button type="button" data-user-edit="${esc(user.id)}">${esc(t('action.edit'))}</button>
      <button type="button" data-user-remove="${esc(user.id)}">${esc(t('action.remove'))}</button>
    </span>
  </div>`;
}

function pluginLine(plugin, admin) {
  const loaded = plugin.loaded !== false;
  const enabled = Boolean(plugin.enabled);
  const controllable = Boolean(plugin.controllable);
  const updateable = Boolean(plugin.updateable);
  const status = plugin.status || (loaded ? (enabled ? 'enabled' : 'disabled') : 'installed');
  const statusClass = status === 'enabled' ? 'ok' : status === 'installed' ? 'warning' : 'critical';
  const meta = [
    plugin.version || t('plugin.unknownVersion'),
    plugin.sourceJar || t('plugin.jarUnknown'),
    plugin.descriptor || (loaded ? t('plugin.loadedRuntime') : t('plugin.descriptorUnknown')),
    controllable ? t('plugin.webControls') : t('plugin.protected')
  ].join(' · ');
  if (!admin) {
    return dataItem(plugin.name, pluginStatusLabel(status), meta);
  }
  const controlDisabled = controllable ? '' : 'disabled';
  const reloadDisabled = controllable && loaded ? '' : 'disabled';
  const updateDisabled = updateable ? '' : 'disabled';
  return `<div class="pluginItem ${status === 'enabled' ? 'isEnabled' : status === 'installed' ? 'isInstalled' : 'isDisabled'}">
    <div class="pluginTop">
      <span>${esc(plugin.name)}<small>${esc(meta)}</small></span>
      <strong class="stateChip ${statusClass}">${esc(pluginStatusLabel(status))}</strong>
    </div>
    <div class="pluginActions">
      <button type="button" class="smallButton pluginActionPrimary" data-plugin-name="${esc(plugin.name)}" data-plugin-action="${enabled ? 'disable' : 'enable'}" ${controlDisabled}>${enabled ? esc(t('action.disable')) : esc(t('action.enable'))}</button>
      <button type="button" class="smallButton" data-plugin-name="${esc(plugin.name)}" data-plugin-action="reload" ${reloadDisabled}>${esc(t('action.reload'))}</button>
    </div>
    <form class="pluginUpdateForm" data-plugin-update="${esc(plugin.name)}">
      <input name="updateUrl" placeholder="${esc(t('plugin.updatePlaceholder', { name: plugin.name }))}" ${updateDisabled}>
      <button type="submit" class="smallButton" ${updateDisabled}>${esc(t('action.update'))}</button>
    </form>
  </div>`;
}

async function json(url, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (state.csrf) headers['X-HunterCore-CSRF'] = state.csrf;
  if (options.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
  const response = await fetch(url, { credentials: 'same-origin', ...options, headers });
  const payload = await response.json();
  if (!response.ok) {
    throw new Error(payload.error || `HTTP ${response.status}`);
  }
  return payload;
}

function setAdminVisibility(admin) {
  $$('.adminOnly').forEach((element) => {
    if (element.classList.contains('pageView')) {
      if (!admin) element.hidden = true;
      return;
    }
    element.hidden = !admin;
  });
}

function updateSessionChrome() {
  const session = state.session;
  const admin = Boolean(session?.admin);
  setAdminVisibility(admin);
  if (!admin && state.page === 'admin') showPage('overview');
  $('logoutButton').hidden = !session;
  $('loginForm').hidden = Boolean(session);
  $('sessionTitle').textContent = session
    ? `${session.username} · ${roleLabel(session.role)}${session.authSource ? ` · ${session.authSource}` : ''}`
    : t('session.guest');
  $('sessionBadge').textContent = session ? roleLabel(session.role) : roleLabel('guest');
  $('sessionBadge').className = `roleBadge ${admin ? 'ok' : ''}`;
}

function renderHealth(health) {
  const safe = health || { status: 'disabled', alerts: [] };
  $('healthStatus').textContent = statusLabel(safe.status || 'ok');
  $('healthStatus').className = `statusPill ${severityClass(safe.status)}`;
  $('healthList').innerHTML = safe.alerts?.length
    ? safe.alerts.map((alert) => dataItem(alert.label, alert.detail, statusLabel(alert.severity))).join('')
    : dataItem(t('health.label'), t('health.heap', { value: Number(safe.memoryUsagePercent || 0).toFixed(1) }), t('health.noAlerts'));
}

function renderOverview(data) {
  $('worlds').innerHTML = (data.worlds || [])
    .map((world) => dataItem(
      world.name,
      t('world.online', { count: world.players }),
      t('world.meta', { chunks: world.loadedChunks, entities: world.entities, time: world.time })
    ))
    .join('') || `<p class="mutedState">${esc(t('worlds.none'))}</p>`;

  $('optimizationList').innerHTML = [
    dataItem(t('optimization.cpuThreads'), data.optimization.cpuThreads),
    dataItem(t('optimization.paperWorkers'), data.optimization.paperWorkers),
    dataItem(t('optimization.divineWorkers'), data.optimization.divineWorkers),
    dataItem(t('optimization.nettyIoThreads'), data.optimization.nettyIoThreads),
    dataItem(t('optimization.forkJoinParallelism'), data.optimization.forkJoinParallelism),
    dataItem(t('optimization.hunterToolsWorkers'), data.optimization.hunterToolsWorkers),
    dataItem(t('optimization.webPanelWorkers'), data.optimization.webPanelWorkers),
    dataItem(t('optimization.guestStatusCacheMillis'), `${data.optimization.guestStatusCacheMillis}ms`)
  ].join('');

  $('playerList').innerHTML = data.players
    ? data.players.map((player) => dataItem(player.name, `${player.ping}ms`, player.world)).join('') || `<p class="mutedState">${esc(t('players.none'))}</p>`
    : `<p class="mutedState">${esc(t('players.loginRequired'))}</p>`;

  $('pluginList').innerHTML = data.plugins
    ? data.plugins.map((plugin) => pluginLine(plugin, Boolean(data.session?.admin))).join('')
    : `<p class="mutedState">${esc(t('plugins.loginRequired'))}</p>`;
  $('pluginList').classList.toggle('mutedState', !data.plugins);
  if ($('pluginCountBadge')) {
    $('pluginCountBadge').textContent = data.plugins ? t('plugins.count', { count: data.plugins.length }) : '--';
  }
  if (data.plugins) state.plugins = data.plugins;
}

function renderActorWorlds(worlds) {
  const selected = $('actorWorld').value;
  const names = (worlds || []).map((world) => world.name);
  $('actorWorld').innerHTML = `<option value="">${esc(t('actors.spawnPoint'))}</option>` + names.map((name) => `<option value="${esc(name)}">${esc(name)}</option>`).join('');
  if (names.includes(selected)) $('actorWorld').value = selected;
}

function renderActors(actors) {
  if (!state.session?.admin) return;
  $('actorList').classList.remove('mutedState');
  $('actorList').innerHTML = actors?.length ? actors.map(actorLine).join('') : `<p class="mutedState">${esc(t('actors.none'))}</p>`;
}

function renderOperations(modules) {
  if (!state.session?.admin) return;
  $('moduleControls').innerHTML = (modules || [])
    .map((module) => toggleItem(module.name, module.enabled, `data-module="${esc(module.name)}"`, !module.toggleable))
    .join('');
  $('commandControls').innerHTML = (modules || [])
    .filter((module) => module.commands?.length)
    .map((module) => `<div class="commandGroup"><h4>${esc(module.name)}</h4>${module.commands
      .map((command) => toggleItem(command.name, command.enabled, `data-command-module="${esc(module.name)}" data-command="${esc(command.name)}"`))
      .join('')}</div>`)
    .join('');
}

function renderWebUsers(users) {
  if (!state.session?.admin) return;
  state.webUsers = users || [];
  $('webUserList').classList.remove('mutedState');
  $('webUserList').innerHTML = state.webUsers.length ? state.webUsers.map(webUserLine).join('') : `<p class="mutedState">${esc(t('webUsers.none'))}</p>`;
}

function renderWebSettings(settings) {
  if (!state.session?.admin || !settings) return;
  if (document.activeElement && $('webSettingsForm').contains(document.activeElement)) return;
  $('webServerName').value = settings.serverName || '';
  $('webBindAddress').value = settings.bindAddress || '';
  $('webPort').value = settings.port || '';
  $('webMapUrl').value = settings.mapUrl || '';
  $('webPublicMap').checked = Boolean(settings.publicMap);
  $('webAddressLine').textContent = settings.address || '';
}

async function refresh() {
  const data = await json('/api/status');
  state.lastData = data;
  state.session = data.session;
  state.csrf = data.session?.csrf || state.csrf;
  $('serverNameTitle').textContent = data.server.name || 'HunterCore';
  $('serverLine').textContent = `${data.server.software || 'Minecraft'} · ${data.server.version}`;
  $('tps').textContent = Number(data.server.tps1).toFixed(2);
  $('mspt').textContent = Number(data.server.mspt).toFixed(1);
  $('players').textContent = `${data.server.online}/${data.server.maxPlayers}`;
  $('memory').textContent = data.server.memory;
  updateSessionChrome();
  renderHealth(data.health);
  renderOverview(data);
  renderActorWorlds(data.worlds);
  renderActors(data.actorDetails);
  renderOperations(data.modules);
  renderWebUsers(data.webUsers);
  renderWebSettings(data.webSettings);
}

async function refreshMap() {
  const map = await json('/api/map');
  if (!map.ok || !map.url || map.url === state.mapUrl) return;
  state.mapUrl = map.url;
  $('mapLink').href = map.url;
  $('mapFrame').src = map.url;
}

async function runCommand(command) {
  const payload = JSON.stringify({ command });
  const result = await json('/api/command', { method: 'POST', body: payload });
  setOutput(result.message || t('command.dispatched'), result.output || '');
  await refresh();
}

function editWebUser(id) {
  const user = state.webUsers.find((candidate) => candidate.id === id);
  if (!user) return;
  $('webUserName').value = user.displayName;
  $('webUserRole').value = user.role;
  $('webUserPassword').value = '';
  $('webUserCommandExecution').checked = Boolean(user.commandExecution);
  $('webUserAllowedMode').value = user.allowedCommandsConfigured
    ? (user.allowedCommands?.length ? 'custom' : 'none')
    : 'inherit';
  $('webUserAllowedCommands').value = user.allowedCommands?.join(', ') || '';
}

function updateActorKind() {
  const fake = $('actorModule').value === 'fake-players';
  $('actorKind').disabled = fake;
  if (fake) $('actorKind').value = 'mannequin';
}

function liquidGlassElement(target) {
  return target instanceof Element ? target.closest(liquidGlassSelector) : null;
}

function updateLiquidGlassPointer(element, event) {
  const rect = element.getBoundingClientRect();
  if (rect.width <= 0 || rect.height <= 0) return;
  const x = Math.max(0, Math.min(100, ((event.clientX - rect.left) / rect.width) * 100));
  const y = Math.max(0, Math.min(100, ((event.clientY - rect.top) / rect.height) * 100));
  const tiltX = ((x - 50) / 50) * 3.6;
  const tiltY = ((50 - y) / 50) * 3.2;
  element.style.setProperty('--glass-x', `${x.toFixed(2)}%`);
  element.style.setProperty('--glass-y', `${y.toFixed(2)}%`);
  element.style.setProperty('--tilt-x', `${tiltX.toFixed(2)}deg`);
  element.style.setProperty('--tilt-y', `${tiltY.toFixed(2)}deg`);
}

function relaxLiquidGlass(element) {
  element.style.setProperty('--tilt-x', '0deg');
  element.style.setProperty('--tilt-y', '0deg');
  element.style.setProperty('--press', '0');
}

function bindLiquidGlass() {
  let pressed = null;

  document.addEventListener('pointermove', (event) => {
    const element = liquidGlassElement(event.target);
    if (!element) return;
    updateLiquidGlassPointer(element, event);
  }, { passive: true });

  document.addEventListener('pointerdown', (event) => {
    const element = liquidGlassElement(event.target);
    if (!element) return;
    updateLiquidGlassPointer(element, event);
    element.style.setProperty('--press', '1');
    pressed = element;
  }, { passive: true });

  document.addEventListener('pointerup', () => {
    if (!pressed) return;
    pressed.style.setProperty('--press', '0');
    pressed = null;
  }, { passive: true });

  document.addEventListener('pointercancel', () => {
    if (!pressed) return;
    pressed.style.setProperty('--press', '0');
    pressed = null;
  }, { passive: true });

  document.addEventListener('pointerout', (event) => {
    const element = liquidGlassElement(event.target);
    if (!element) return;
    if (event.relatedTarget instanceof Node && element.contains(event.relatedTarget)) return;
    relaxLiquidGlass(element);
  }, { passive: true });
}

function bindServerIcon() {
  const mark = $('productMark');
  const image = $('serverIcon');
  if (!mark || !(image instanceof HTMLImageElement)) return;
  image.addEventListener('load', () => {
    mark.classList.add('hasIcon');
    image.hidden = false;
  });
  image.addEventListener('error', () => {
    mark.classList.remove('hasIcon');
    image.hidden = true;
  });
  image.src = `/assets/server-icon.png?${Date.now()}`;
}

function bindEvents() {
  $$('.navButton[data-page-target]').forEach((button) => {
    button.addEventListener('click', () => {
      showPage(button.dataset.pageTarget);
    });
  });

  window.addEventListener('popstate', () => showPage(pageFromLocation(), false));

  $('languageToggle').addEventListener('click', () => {
    setLanguage(state.lang === 'zh' ? 'en' : 'zh');
  });

  $('loginForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
      const payload = JSON.stringify({ username: $('username').value, password: $('password').value });
      const result = await json('/api/login', { method: 'POST', body: payload });
      $('password').value = '';
      state.session = result.session;
      state.csrf = result.session?.csrf || '';
      setOutput(t('command.loggedIn', { username: result.session.username, role: roleLabel(result.session.role) }));
      await refresh();
    } catch {
      $('password').value = '';
      setOutput(t('command.loginFailed'));
    }
  });

  $('logoutButton').addEventListener('click', async () => {
    await json('/api/logout', { method: 'POST' });
    state.session = null;
    state.csrf = '';
    setOutput(t('command.loggedOut'));
    await refresh();
  });

  $('commandForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
      await runCommand($('commandInput').value);
    } catch (error) {
      setOutput(t('command.error', { message: error.message }));
    }
  });

  $$('.quickRow [data-command]').forEach((button) => {
    button.addEventListener('click', async () => {
      $('commandInput').value = button.dataset.command;
      try {
        await runCommand(button.dataset.command);
      } catch (error) {
        setOutput(t('command.error', { message: error.message }));
      }
    });
  });

  $('actorModule').addEventListener('change', updateActorKind);

  $('actorForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    const payload = {
      module: $('actorModule').value,
      kind: $('actorKind').value,
      name: $('actorName').value
    };
    if ($('actorWorld').value || $('actorX').value || $('actorY').value || $('actorZ').value) {
      payload.world = $('actorWorld').value;
      payload.x = $('actorX').value;
      payload.y = $('actorY').value;
      payload.z = $('actorZ').value;
    }
    try {
      const result = await json('/api/admin/actor/spawn', { method: 'POST', body: JSON.stringify(payload) });
      setOutput(result.message || t('actors.spawned'), result.output || '');
      if (result.ok) $('actorName').value = '';
      await refresh();
    } catch (error) {
      setOutput(t('command.error', { message: error.message }));
    }
  });

  $('actorList').addEventListener('click', async (event) => {
    const target = event.target;
    if (!(target instanceof HTMLButtonElement) || !target.dataset.actorRemove) return;
    try {
      const payload = { module: target.dataset.actorModule, id: target.dataset.actorId };
      const result = await json('/api/admin/actor/remove', { method: 'POST', body: JSON.stringify(payload) });
      setOutput(result.message || t('actors.removed'), result.output || '');
      await refresh();
    } catch (error) {
      setOutput(t('command.error', { message: error.message }));
    }
  });

  $('opsPanel').addEventListener('change', async (event) => {
    const target = event.target;
    if (!(target instanceof HTMLInputElement)) return;
    const endpoint = target.dataset.module ? '/api/admin/module' : '/api/admin/command';
    const payload = target.dataset.module
      ? { module: target.dataset.module, enabled: String(target.checked) }
      : { module: target.dataset.commandModule, command: target.dataset.command, enabled: String(target.checked) };
    try {
      const result = await json(endpoint, { method: 'POST', body: JSON.stringify(payload) });
      setOutput(result.message || t('action.updated'), result.output || '');
      await refresh();
    } catch (error) {
      target.checked = !target.checked;
      setOutput(t('command.error', { message: error.message }));
    }
  });

  $('luckForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    const payload = {
      action: $('luckAction').value,
      target: $('luckTarget').value,
      group: $('luckGroup').value,
      permission: $('luckPermission').value,
      value: $('luckValue').value
    };
    try {
      const result = await json('/api/admin/luckperms', { method: 'POST', body: JSON.stringify(payload) });
      setOutput(result.message || t('luck.dispatched'), result.output || '');
      await refresh();
    } catch (error) {
      setOutput(t('command.error', { message: error.message }));
    }
  });

  $('webSettingsForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    const payload = {
      serverName: $('webServerName').value,
      bindAddress: $('webBindAddress').value,
      port: $('webPort').value,
      mapUrl: $('webMapUrl').value,
      publicMap: String($('webPublicMap').checked)
    };
    try {
      const result = await json('/api/admin/web-settings', { method: 'POST', body: JSON.stringify(payload) });
      setOutput(result.restart ? t('webSettings.restarting') : t('webSettings.saved'));
      if (result.settings) renderWebSettings(result.settings);
      await refresh();
      await refreshMap();
    } catch (error) {
      setOutput(t('command.error', { message: error.message }));
    }
  });

  $('webUserForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    const payload = {
      username: $('webUserName').value,
      role: $('webUserRole').value,
      password: $('webUserPassword').value,
      commandExecution: String($('webUserCommandExecution').checked),
      allowedCommandsMode: $('webUserAllowedMode').value,
      allowedCommands: $('webUserAllowedCommands').value
    };
    try {
      await json('/api/admin/web-user/save', { method: 'POST', body: JSON.stringify(payload) });
      $('webUserPassword').value = '';
      setOutput(t('webUsers.saved'));
      await refresh();
    } catch (error) {
      setOutput(t('command.error', { message: error.message }));
    }
  });

  $('webUserList').addEventListener('click', async (event) => {
    const target = event.target;
    if (!(target instanceof HTMLButtonElement)) return;
    if (target.dataset.userEdit) {
      editWebUser(target.dataset.userEdit);
      return;
    }
    if (!target.dataset.userRemove) return;
    try {
      await json('/api/admin/web-user/remove', { method: 'POST', body: JSON.stringify({ username: target.dataset.userRemove }) });
      setOutput(t('webUsers.removed'));
      await refresh();
    } catch (error) {
      setOutput(t('command.error', { message: error.message }));
    }
  });

  $('pluginList').addEventListener('click', async (event) => {
    const target = event.target;
    if (!(target instanceof HTMLButtonElement) || !target.dataset.pluginAction) return;
    try {
      const payload = {
        plugin: target.dataset.pluginName,
        action: target.dataset.pluginAction
      };
      target.disabled = true;
      const result = await json('/api/admin/plugin', { method: 'POST', body: JSON.stringify(payload) });
      setOutput(result.message || t('plugin.actionCompleted'), result.output || '');
      await refresh();
    } catch (error) {
      setOutput(t('command.error', { message: error.message }));
      await refresh();
    }
  });

  $('pluginList').addEventListener('submit', async (event) => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement) || !form.dataset.pluginUpdate) return;
    event.preventDefault();
    const input = form.querySelector('input[name="updateUrl"]');
    const button = form.querySelector('button[type="submit"]');
    const url = input instanceof HTMLInputElement ? input.value.trim() : '';
    if (!url) {
      setOutput(t('plugin.updateUrlRequired'));
      return;
    }
    try {
      if (button instanceof HTMLButtonElement) button.disabled = true;
      const payload = { plugin: form.dataset.pluginUpdate, url };
      const result = await json('/api/admin/plugin-update', { method: 'POST', body: JSON.stringify(payload) });
      setOutput(result.message || t('plugin.updateCompleted'), result.output || '');
      if (input instanceof HTMLInputElement) input.value = '';
      await refresh();
    } catch (error) {
      setOutput(t('command.error', { message: error.message }));
      await refresh();
    }
  });
}

applyTranslations();
bindLiquidGlass();
bindServerIcon();
bindEvents();
showPage(pageFromLocation(), false);
updateActorKind();
refresh().catch((error) => {
  $('serverLine').textContent = error.message;
});
refreshMap().catch(() => {});
state.refreshTimer = setInterval(() => {
  refresh().catch(() => {});
}, 5000);
