(function () {
  'use strict';

  const api = window.WcxZygisk;
  const $master = document.getElementById('master');
  const $masterDesc = document.getElementById('master-desc');
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
          item.enabled = want;
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

  async function loadMaster() {
    try {
      const st = await api.status();
      $master.checked = st.enabled;
      $masterDesc.textContent = st.enabled ? '注入已开启' : '注入已关闭';
      return st.enabled;
    } catch (e) {
      $master.checked = false;
      $master.disabled = true;
      $masterDesc.textContent = '无法读取状态';
      setStatus(e.message, true);
      return false;
    }
  }

  async function loadTargets() {
    try {
      const list = await api.targets();
      renderTargets(list);
      if (list.length) setStatus('共 ' + list.length + ' 个作用域目标');
    } catch (e) {
      $empty.hidden = false;
      setStatus(e.message, true);
    }
  }

  async function refresh() {
    $refresh.disabled = true;
    try {
      await loadMaster();
      await loadTargets();
    } finally {
      $refresh.disabled = false;
    }
  }

  $master.addEventListener('change', async () => {
    const want = $master.checked;
    $master.disabled = true;
    try {
      await api.setMaster(want);
      $masterDesc.textContent = want ? '注入已开启' : '注入已关闭';
      setStatus(want ? '已开启注入（重启微信后生效）' : '已关闭注入（重启微信后生效）');
    } catch (e) {
      $master.checked = !want;
      setStatus('操作失败：' + e.message, true);
    } finally {
      $master.disabled = false;
    }
  });

  $refresh.addEventListener('click', refresh);

  if (!api.available()) {
    $master.disabled = true;
    setStatus('当前环境不支持 WebUI root 桥；可在终端执行 config.sh 或使用模块的“操作”按钮', true);
  }

  refresh();
})();
