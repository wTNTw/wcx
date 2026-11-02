(function () {
  'use strict';

  const api = window.WcxZygisk;
  const $targets = document.getElementById('targets');
  const $empty = document.getElementById('targets-empty');
  const $loading = document.getElementById('targets-loading');
  const $refresh = document.getElementById('refresh');

  // 上次的结果：让 WebUI 一打开就有内容，之后再悄悄刷新（root 调用要几百毫秒）
  const CACHE_KEY = 'wcx.zygisk.state.v1';

  const LABEL = {
    'com.tencent.mm': '微信',
  };

  function displayName(pkg) {
    return LABEL[pkg] || pkg;
  }

  function readCache() {
    try {
      const raw = window.localStorage.getItem(CACHE_KEY);
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      if (!parsed || !Array.isArray(parsed.rows)) return null;
      return parsed;
    } catch (_) {
      return null;
    }
  }

  function writeCache(state) {
    try {
      window.localStorage.setItem(CACHE_KEY, JSON.stringify(state));
    } catch (_) {
      /* 某些 WebView 禁用了 storage，忽略 */
    }
  }

  function render(state, stale) {
    const list = state.rows || [];
    $loading.hidden = true;
    $targets.textContent = '';
    $empty.hidden = list.length > 0;
    $targets.className = 'targets' + (stale ? ' stale' : '');

    for (const item of list) {
      const li = document.createElement('li');

      const box = document.createElement('div');
      box.className = 'pkg';

      const line = document.createElement('div');
      line.className = 'pkg-line';
      const name = document.createElement('code');
      name.textContent = item.pkg;
      line.appendChild(name);

      // 模块有注入级异常时统一显示「异常」，否则显示该包主进程是否已注入
      if (state.error) {
        const badge = document.createElement('span');
        badge.className = 'badge err';
        badge.textContent = '异常';
        line.appendChild(badge);
      } else if (item.injected) {
        const badge = document.createElement('span');
        badge.className = 'badge ok';
        badge.textContent = '已注入';
        line.appendChild(badge);
      }

      const meta = document.createElement('span');
      meta.textContent = displayName(item.pkg) + ' · 用户 ' + (item.userId || '0');

      box.appendChild(line);
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
          await load(true);
        } catch (e) {
          input.checked = !want;
          api.toast('操作失败：' + e.message);
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

  async function load(force) {
    if (!api.available()) return;
    try {
      // 只发一次 root 调用：作用域 + 注入状态 + 异常标志一次拿齐
      const state = await api.state();
      writeCache(state);
      render(state, false);
    } catch (e) {
      if (force) {
        $loading.hidden = true;
        api.toast('读取失败：' + e.message);
      }
    }
  }

  async function refresh() {
    $refresh.disabled = true;
    try {
      await load(true);
    } finally {
      $refresh.disabled = false;
    }
  }

  $refresh.addEventListener('click', refresh);

  if (!api.available()) {
    $loading.hidden = true;
    $empty.hidden = false;
    $empty.textContent = '当前环境没有 WebUI root 桥（请在 KernelSU / APatch 的管理器里打开本页）';
    return;
  }

  // 先渲染上次的结果（有缓存就是 0 延迟），再拉最新状态
  const cached = readCache();
  if (cached) render(cached, true);
  load(Boolean(cached));
})();
