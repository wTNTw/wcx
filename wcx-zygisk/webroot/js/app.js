(function () {
  'use strict';

  const api = window.WcxZygisk;
  const $targets = document.getElementById('targets');
  const $empty = document.getElementById('targets-empty');
  const $refresh = document.getElementById('refresh');

  const LABEL = {
    'com.tencent.mm': '微信',
  };

  function displayName(pkg) {
    return LABEL[pkg] || pkg;
  }

  /** 主进程是否已注入：按 (userId, pkg) 匹配 config.sh inject 的输出 */
  function injectedMap(rows) {
    const map = new Map();
    for (const row of rows || []) {
      if (row.injected) map.set(row.userId + '\t' + row.pkg, true);
    }
    return map;
  }

  function renderTargets(list, injected, error) {
    $targets.textContent = '';
    $empty.hidden = list.length > 0;

    for (const item of list) {
      const li = document.createElement('li');

      const box = document.createElement('div');
      box.className = 'pkg';

      const line = document.createElement('div');
      line.className = 'pkg-line';
      const name = document.createElement('code');
      name.textContent = item.pkg;
      line.appendChild(name);

      // 状态徽标：模块有异常时优先显示「异常」，否则显示该包是否已注入
      if (error) {
        const badge = document.createElement('span');
        badge.className = 'badge err';
        badge.textContent = '异常';
        line.appendChild(badge);
      } else if (injected.get(item.userId + '\t' + item.pkg)) {
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
          await loadTargets();
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

  async function loadTargets() {
    try {
      const [list, state] = await Promise.all([api.targets(), api.injectStatus()]);
      renderTargets(list, injectedMap(state.rows), state.error);
    } catch (e) {
      $empty.hidden = false;
      api.toast('读取失败：' + e.message);
    }
  }

  async function refresh() {
    $refresh.disabled = true;
    try {
      await loadTargets();
    } finally {
      $refresh.disabled = false;
    }
  }

  $refresh.addEventListener('click', refresh);

  if (!api.available()) {
    $empty.hidden = false;
    $empty.textContent = '当前环境没有 WebUI root 桥（请在 KernelSU / APatch 的管理器里打开本页）';
  }

  refresh();
})();
