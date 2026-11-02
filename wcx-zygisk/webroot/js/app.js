(function () {
  'use strict';

  const api = window.WcxZygisk;
  const $targets = document.getElementById('targets');
  const $empty = document.getElementById('targets-empty');
  const $refresh = document.getElementById('refresh');
  const $status = document.getElementById('status');

  const LABEL = {
    'com.tencent.mm': '微信',
  };

  function setStatus(text, isError) {
    $status.textContent = text;
    $status.className = isError ? 'hint err' : 'hint';
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
          await api.setTarget(item.pkg, want);
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
    } finally {
      $refresh.disabled = false;
    }
  }

  $refresh.addEventListener('click', refresh);

  if (!api.available()) {
    $empty.hidden = false;
    setStatus('当前环境没有 WebUI root 桥（请在 KernelSU / APatch 的管理器里打开本页）', true);
  }

  refresh();
})();
