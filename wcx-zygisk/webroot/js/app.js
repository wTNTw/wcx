(function () {
  'use strict';

  const api = window.WcxZygisk;
  const $targets = document.getElementById('targets');
  const $empty = document.getElementById('targets-empty');
  const $refresh = document.getElementById('refresh');
  const $status = document.getElementById('status');
  const $chips = document.getElementById('chips');
  const $banner = document.getElementById('banner');
  const $hints = document.getElementById('hints');
  const $logs = document.getElementById('logs');
  const $refreshStatus = document.getElementById('refresh-status');
  const $refreshLogs = document.getElementById('refresh-logs');
  const $copyLogs = document.getElementById('copy-logs');

  const LOG_LINES = 150;

  const LABEL = {
    'com.tencent.mm': '微信',
  };

  function setStatus(text, isError) {
    $status.textContent = text;
    $status.className = isError ? 'hint err' : 'hint';
  }

  function chip(text, tone) {
    const el = document.createElement('span');
    el.className = 'chip' + (tone ? ' ' + tone : '');
    el.textContent = text;
    return el;
  }

  function showBanner(html, tone) {
    if (!html) {
      $banner.hidden = true;
      $banner.textContent = '';
      return;
    }
    $banner.hidden = false;
    $banner.className = 'banner' + (tone ? ' ' + tone : '');
    $banner.textContent = html;
  }

  function renderStatus(st) {
    $chips.textContent = '';

    const injected = st.injected === '1';
    const enabled = st.enabled === '1';

    $chips.appendChild(
      chip(injected ? '已注入微信' : enabled ? '等待微信启动' : '模块已停用', injected ? 'ok' : enabled ? 'warn' : 'err')
    );
    $chips.appendChild(chip('作用域 ' + (st.targets_enabled || '0') + '/' + (st.targets_total || '0')));
    $chips.appendChild(chip('模块 ' + (st.version || '?') + (enabled ? '' : ' · 停用'), enabled ? null : 'err'));
    if (st.injector_lib !== '1') $chips.appendChild(chip('缺少注入器库', 'err'));
    if (st.payload_apk !== '1') $chips.appendChild(chip('缺少 module.apk', 'err'));
    if (st.force_file === '1') $chips.appendChild(chip('force 已开启', 'warn'));

    // 同族注入器冲突：wekit_zygisk 之类也在注入微信时两个模块会互相踩 hook
    if (st.conflict) {
      showBanner(
        '检测到同时启用的注入器：' +
          st.conflict +
          '。两者都会 hook 微信的 ART，同时存在会让微信启动即崩溃（SIGILL），请只保留一个。',
        'err'
      );
    } else if (!enabled) {
      showBanner('模块已被 Root 管理器停用（存在 disable/remove 文件），注入不会发生。', 'warn');
    } else if (enabled && !injected) {
      showBanner('微信当前进程还没被注入：强制停止微信后重新打开，再看这里的日志。', 'warn');
    } else {
      showBanner('');
    }

    if (st.procs) {
      $chips.appendChild(chip(st.procs.split(',').length + ' 个微信进程'));
    }
  }

  const HINT_RULES = [
    {
      re: /文件式 dex 加载为空/,
      tone: 'warn',
      text: '主进程回退到内存式 dex 加载（正常行为，只是稍慢）。',
    },
    {
      re: /NoClassDefFoundError|ClassNotFoundException/,
      tone: 'warn',
      text: '有个别功能与当前微信版本的类名不匹配（feature 维护问题，不影响注入链路）。',
    },
    {
      re: /UnsatisfiedLinkError|dlopen failed|cannot locate symbol/,
      tone: 'err',
      text: '原生库加载失败：检查模块 zip 里的 lib 是否与手机 ABI 一致（本包只支持 arm64-v8a）。',
    },
    {
      re: /LSPlant init failed|LSPlant init 失败|RegisterNatives failed/,
      tone: 'err',
      text: 'LSPlant/注册失败：多半是另一个 hook 框架（LSPosed、wekit_zygisk 等）同时注入了微信。',
    },
    {
      re: /不在注入作用域内/,
      tone: 'warn',
      text: '有微信进程被作用域跳过：在当前页把对应的「用户 + 微信」开关打开。',
    },
    {
      re: /SIGILL|Fatal signal/,
      tone: 'err',
      text: '出现 ART 崩溃（SIGILL）：通常是两个注入器同时 hook 微信，请停用其一后重启设备。',
    },
  ];

  function renderHints(logText) {
    $hints.textContent = '';
    const seen = new Set();
    const items = [];
    for (const rule of HINT_RULES) {
      if (rule.re.test(logText) && !seen.has(rule.text)) {
        seen.add(rule.text);
        items.push(rule);
      }
    }
    if (!items.length) {
      $hints.hidden = true;
      return;
    }
    for (const rule of items) {
      const li = document.createElement('li');
      li.className = 'hint-item ' + rule.tone;
      li.textContent = rule.text;
      $hints.appendChild(li);
    }
    $hints.hidden = false;
  }

  // 已知的「正常但会打 E」的行（例如主进程回退内存式加载），先归到警告
  const BENIGN = /文件式 dex 加载为空|已回退|重试/;

  function classify(line) {
    if (BENIGN.test(line)) return ' log-warn';
    if (/\sE\s/.test(line) || /Exception|Error|失败|异常/.test(line)) return ' log-err';
    if (/\sW\s/.test(line) || /跳过|不在注入作用域内/.test(line)) return ' log-warn';
    return '';
  }

  function renderLogs(text) {
    const lines = String(text || '')
      .split('\n')
      .map((l) => l.replace(/\r$/, ''))
      .filter((l) => l.trim().length && !l.startsWith('--------- beginning of'));

    if (!lines.length) {
      $logs.textContent = '暂无 WCX 日志（微信还没被注入，或 logcat 已被清空）。';
      renderHints('');
      return;
    }

    $logs.textContent = '';
    for (const line of lines) {
      const span = document.createElement('span');
      span.className = 'log-line' + classify(line);
      span.textContent = line + '\n';
      $logs.appendChild(span);
    }
    renderHints(lines.join('\n'));
  }

  async function loadStatus() {
    if (!api.available()) return;
    $refreshStatus.disabled = true;
    try {
      renderStatus(await api.status());
    } catch (e) {
      showBanner('读取状态失败：' + e.message, 'err');
    } finally {
      $refreshStatus.disabled = false;
    }
  }

  async function loadLogs() {
    if (!api.available()) return;
    $refreshLogs.disabled = true;
    try {
      renderLogs(await api.logs(LOG_LINES));
    } catch (e) {
      $logs.textContent = '读取日志失败：' + e.message;
    } finally {
      $refreshLogs.disabled = false;
    }
  }

  function displayName(pkg) {
    return LABEL[pkg] || pkg;
  }

  function renderTargets(list) {
    $targets.textContent = '';
    $empty.hidden = list.length > 0;

    for (const item of list) {
      const li = document.createElement('li');

      const box = document.createElement('div');
      box.className = 'pkg';
      const name = document.createElement('code');
      name.textContent = item.pkg;
      const meta = document.createElement('span');
      meta.textContent = displayName(item.pkg) + ' · 用户 ' + (item.userId || '0');
      box.appendChild(name);
      box.appendChild(meta);

      const label = document.createElement('label');
      label.className = 'switch';
      const input = document.createElement('input');
      input.type = 'checkbox';
      input.checked = !!item.enabled;
      input.addEventListener('change', async () => {
        const want = input.checked;
        input.disabled = true;
        try {
          await api.setTarget(item.userId, item.pkg, want);
          await loadTargets();
          setStatus(want ? '已启用注入 ' + item.pkg : '已停止注入 ' + item.pkg);
        } catch (e) {
          input.checked = !want;
          setStatus('操作失败：' + e.message, true);
        } finally {
          input.disabled = false;
        }
      });
      const track = document.createElement('span');
      track.className = 'track';
      const thumb = document.createElement('span');
      thumb.className = 'thumb';
      track.appendChild(thumb);
      label.appendChild(input);
      label.appendChild(track);

      li.appendChild(box);
      li.appendChild(label);
      $targets.appendChild(li);
    }
  }

  async function loadTargets() {
    try {
      const list = await api.targets();
      renderTargets(list);
      if (list.length) {
        setStatus('共 ' + list.length + ' 个作用域目标');
      } else {
        setStatus('没有可注入的微信');
      }
    } catch (e) {
      $empty.hidden = false;
      setStatus('读取失败：' + e.message, true);
    }
  }

  async function refresh() {
    $refresh.disabled = true;
    try {
      await loadTargets();
      await loadStatus();
      await loadLogs();
    } finally {
      $refresh.disabled = false;
    }
  }

  $refresh.addEventListener('click', refresh);
  $refreshStatus.addEventListener('click', async () => {
    await loadStatus();
    await loadLogs();
  });
  $refreshLogs.addEventListener('click', loadLogs);
  $copyLogs.addEventListener('click', async () => {
    const text = $logs.textContent || '';
    try {
      await navigator.clipboard.writeText(text);
      setStatus('日志已复制');
    } catch (_) {
      setStatus('复制失败：请长按选择日志文本', true);
    }
  });

  if (!api.available()) {
    $empty.hidden = false;
    setStatus('当前环境没有 WebUI root 桥（请在 KernelSU / APatch 的管理器里打开本页）', true);
  }

  refresh();
})();
