const state = {
  session: null,
  csrf: '',
  webUsers: [],
  plugins: [],
  mapUrl: '',
  refreshTimer: null
};

const $ = (id) => document.getElementById(id);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));

const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
})[char]);

const severityClass = (value) => ['ok', 'warning', 'critical', 'disabled'].includes(value) ? value : 'ok';

function setOutput(message, output = '') {
  $('commandResult').textContent = output ? `${message}\n\n${output}` : message;
}

function dataItem(left, right = '', meta = '') {
  return `<div class="dataItem"><span>${esc(left)}${meta ? `<small>${esc(meta)}</small>` : ''}</span><strong>${esc(right)}</strong></div>`;
}

function toggleItem(left, checked, attrs = '', disabled = false) {
  return `<label class="toggleItem"><span>${esc(left)}</span><input type="checkbox" ${checked ? 'checked' : ''} ${disabled ? 'disabled' : ''} ${attrs}></label>`;
}

function actorLine(actor) {
  const location = actor.world
    ? `${actor.world} ${Number(actor.x).toFixed(1)} ${Number(actor.y).toFixed(1)} ${Number(actor.z).toFixed(1)}`
    : 'not configured';
  return `<div class="dataItem">
    <span>${esc(actor.displayName)}<small>${actor.live ? 'live' : 'configured'} · ${esc(actor.module)} · ${esc(actor.kind)} · ${esc(location)}</small></span>
    <span class="actorActions"><button type="button" data-actor-remove="true" data-actor-module="${esc(actor.module)}" data-actor-id="${esc(actor.id)}">Remove</button></span>
  </div>`;
}

function allowedLine(user) {
  if (!user.allowedCommandsConfigured) return 'inherit';
  return user.allowedCommands?.length ? user.allowedCommands.join(', ') : 'none';
}

function webUserLine(user) {
  return `<div class="dataItem">
    <span>${esc(user.displayName)}<small>${esc(user.role)} · ${user.passwordConfigured ? 'web password set' : 'HunterAuth only'} · commands ${user.commandExecution ? 'on' : 'off'} · ${esc(allowedLine(user))}</small></span>
    <span class="userActions">
      <button type="button" data-user-edit="${esc(user.id)}">Edit</button>
      <button type="button" data-user-remove="${esc(user.id)}">Remove</button>
    </span>
  </div>`;
}

function pluginLine(plugin, admin) {
  const enabled = Boolean(plugin.enabled);
  const controllable = Boolean(plugin.controllable);
  const updateable = Boolean(plugin.updateable);
  const status = enabled ? 'enabled' : 'disabled';
  const statusClass = enabled ? 'ok' : 'critical';
  const meta = [
    plugin.version || 'unknown version',
    plugin.sourceJar || 'jar not resolved',
    controllable ? 'web controls' : 'protected'
  ].join(' · ');
  if (!admin) {
    return dataItem(plugin.name, enabled ? plugin.version : 'disabled', meta);
  }
  const controlDisabled = controllable ? '' : 'disabled';
  const updateDisabled = updateable ? '' : 'disabled';
  return `<div class="pluginItem ${enabled ? 'isEnabled' : 'isDisabled'}">
    <div class="pluginTop">
      <span>${esc(plugin.name)}<small>${esc(meta)}</small></span>
      <strong class="stateChip ${statusClass}">${esc(status)}</strong>
    </div>
    <div class="pluginActions">
      <button type="button" class="smallButton" data-plugin-name="${esc(plugin.name)}" data-plugin-action="${enabled ? 'disable' : 'enable'}" ${controlDisabled}>${enabled ? 'Disable' : 'Enable'}</button>
      <button type="button" class="smallButton" data-plugin-name="${esc(plugin.name)}" data-plugin-action="reload" ${controlDisabled}>Reload</button>
    </div>
    <form class="pluginUpdateForm" data-plugin-update="${esc(plugin.name)}">
      <input name="updateUrl" placeholder="https://example.com/${esc(plugin.name)}.jar" ${updateDisabled}>
      <button type="submit" class="smallButton" ${updateDisabled}>Update</button>
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
    element.hidden = !admin;
  });
}

function updateSessionChrome() {
  const session = state.session;
  const admin = Boolean(session?.admin);
  setAdminVisibility(admin);
  $('logoutButton').hidden = !session;
  $('loginForm').hidden = Boolean(session);
  $('sessionTitle').textContent = session
    ? `${session.username} · ${session.role}${session.authSource ? ` · ${session.authSource}` : ''}`
    : 'Guest view';
  $('sessionBadge').textContent = session ? session.role : 'Guest';
  $('sessionBadge').className = `roleBadge ${admin ? 'ok' : ''}`;
}

function renderHealth(health) {
  const safe = health || { status: 'disabled', alerts: [] };
  $('healthStatus').textContent = safe.status || 'ok';
  $('healthStatus').className = `statusPill ${severityClass(safe.status)}`;
  $('healthList').innerHTML = safe.alerts?.length
    ? safe.alerts.map((alert) => dataItem(alert.label, alert.detail, alert.severity)).join('')
    : dataItem('Health', `Heap ${Number(safe.memoryUsagePercent || 0).toFixed(1)}%`, 'No active alerts');
}

function renderOverview(data) {
  $('worlds').innerHTML = (data.worlds || [])
    .map((world) => dataItem(world.name, `${world.players} online`, `${world.loadedChunks} chunks · ${world.entities} entities · time ${world.time}`))
    .join('') || '<p class="mutedState">No worlds loaded.</p>';

  $('optimizationList').innerHTML = [
    dataItem('CPU threads', data.optimization.cpuThreads),
    dataItem('Paper workers', data.optimization.paperWorkers),
    dataItem('DivineMC workers', data.optimization.divineWorkers),
    dataItem('Netty IO', data.optimization.nettyIoThreads),
    dataItem('ForkJoin', data.optimization.forkJoinParallelism),
    dataItem('HunterTools workers', data.optimization.hunterToolsWorkers),
    dataItem('Web workers', data.optimization.webPanelWorkers),
    dataItem('Guest cache', `${data.optimization.guestStatusCacheMillis}ms`)
  ].join('');

  $('playerList').innerHTML = data.players
    ? data.players.map((player) => dataItem(player.name, `${player.ping}ms`, player.world)).join('') || '<p class="mutedState">No players online.</p>'
    : '<p class="mutedState">Login to view player detail.</p>';

  $('pluginList').innerHTML = data.plugins
    ? data.plugins.map((plugin) => pluginLine(plugin, Boolean(data.session?.admin))).join('')
    : '<p class="mutedState">Login to view plugin detail.</p>';
  if (data.plugins) state.plugins = data.plugins;
}

function renderActorWorlds(worlds) {
  const selected = $('actorWorld').value;
  const names = (worlds || []).map((world) => world.name);
  $('actorWorld').innerHTML = '<option value="">Spawn</option>' + names.map((name) => `<option value="${esc(name)}">${esc(name)}</option>`).join('');
  if (names.includes(selected)) $('actorWorld').value = selected;
}

function renderActors(actors) {
  if (!state.session?.admin) return;
  $('actorList').classList.remove('mutedState');
  $('actorList').innerHTML = actors?.length ? actors.map(actorLine).join('') : '<p class="mutedState">No configured actors.</p>';
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
  $('webUserList').innerHTML = state.webUsers.length ? state.webUsers.map(webUserLine).join('') : '<p class="mutedState">No web roles configured.</p>';
}

function renderWebSettings(settings) {
  if (!state.session?.admin || !settings) return;
  if (document.activeElement && $('webSettingsForm').contains(document.activeElement)) return;
  $('webBindAddress').value = settings.bindAddress || '';
  $('webPort').value = settings.port || '';
  $('webMapUrl').value = settings.mapUrl || '';
  $('webPublicMap').checked = Boolean(settings.publicMap);
  $('webAddressLine').textContent = settings.address || '';
}

async function refresh() {
  const data = await json('/api/status');
  state.session = data.session;
  state.csrf = data.session?.csrf || state.csrf;
  $('serverLine').textContent = `${data.server.name} · ${data.server.version}`;
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
  setOutput(result.message || 'Command dispatched.', result.output || '');
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

function bindEvents() {
  $$('.navButton').forEach((button) => {
    button.addEventListener('click', () => {
      $$('.navButton').forEach((candidate) => candidate.classList.remove('isActive'));
      button.classList.add('isActive');
      const target = button.dataset.scrollTarget === 'map' ? document.body : $(button.dataset.scrollTarget);
      target?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  });

  $('loginForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
      const payload = JSON.stringify({ username: $('username').value, password: $('password').value });
      const result = await json('/api/login', { method: 'POST', body: payload });
      $('password').value = '';
      state.session = result.session;
      state.csrf = result.session?.csrf || '';
      setOutput(`Logged in as ${result.session.username} (${result.session.role}).`);
      await refresh();
    } catch {
      $('password').value = '';
      setOutput('Login failed.');
    }
  });

  $('logoutButton').addEventListener('click', async () => {
    await json('/api/logout', { method: 'POST' });
    state.session = null;
    state.csrf = '';
    setOutput('Logged out.');
    await refresh();
  });

  $('commandForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
      await runCommand($('commandInput').value);
    } catch (error) {
      setOutput(`Error: ${error.message}`);
    }
  });

  $$('.quickRow [data-command]').forEach((button) => {
    button.addEventListener('click', async () => {
      $('commandInput').value = button.dataset.command;
      try {
        await runCommand(button.dataset.command);
      } catch (error) {
        setOutput(`Error: ${error.message}`);
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
      setOutput(result.message || 'Actor spawned.', result.output || '');
      if (result.ok) $('actorName').value = '';
      await refresh();
    } catch (error) {
      setOutput(`Error: ${error.message}`);
    }
  });

  $('actorList').addEventListener('click', async (event) => {
    const target = event.target;
    if (!(target instanceof HTMLButtonElement) || !target.dataset.actorRemove) return;
    try {
      const payload = { module: target.dataset.actorModule, id: target.dataset.actorId };
      const result = await json('/api/admin/actor/remove', { method: 'POST', body: JSON.stringify(payload) });
      setOutput(result.message || 'Actor removed.', result.output || '');
      await refresh();
    } catch (error) {
      setOutput(`Error: ${error.message}`);
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
      setOutput(result.message || 'Updated.', result.output || '');
      await refresh();
    } catch (error) {
      target.checked = !target.checked;
      setOutput(`Error: ${error.message}`);
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
      setOutput(result.message || 'LuckPerms command dispatched.', result.output || '');
      await refresh();
    } catch (error) {
      setOutput(`Error: ${error.message}`);
    }
  });

  $('webSettingsForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    const payload = {
      bindAddress: $('webBindAddress').value,
      port: $('webPort').value,
      mapUrl: $('webMapUrl').value,
      publicMap: String($('webPublicMap').checked)
    };
    try {
      const result = await json('/api/admin/web-settings', { method: 'POST', body: JSON.stringify(payload) });
      setOutput(result.restart ? 'Web settings saved. Panel is restarting on the new address.' : 'Web settings saved.');
      if (result.settings) renderWebSettings(result.settings);
      await refreshMap();
    } catch (error) {
      setOutput(`Error: ${error.message}`);
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
      setOutput('Web role saved.');
      await refresh();
    } catch (error) {
      setOutput(`Error: ${error.message}`);
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
      setOutput('Web role removed.');
      await refresh();
    } catch (error) {
      setOutput(`Error: ${error.message}`);
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
      setOutput(result.message || 'Plugin action completed.', result.output || '');
      await refresh();
    } catch (error) {
      setOutput(`Error: ${error.message}`);
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
      setOutput('Error: update URL is required.');
      return;
    }
    try {
      if (button instanceof HTMLButtonElement) button.disabled = true;
      const payload = { plugin: form.dataset.pluginUpdate, url };
      const result = await json('/api/admin/plugin-update', { method: 'POST', body: JSON.stringify(payload) });
      setOutput(result.message || 'Plugin update completed.', result.output || '');
      if (input instanceof HTMLInputElement) input.value = '';
      await refresh();
    } catch (error) {
      setOutput(`Error: ${error.message}`);
      await refresh();
    }
  });
}

bindEvents();
updateActorKind();
refresh().catch((error) => {
  $('serverLine').textContent = error.message;
});
refreshMap().catch(() => {});
state.refreshTimer = setInterval(() => {
  refresh().catch(() => {});
}, 5000);
