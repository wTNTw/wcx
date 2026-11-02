/**
 * 在 Root 管理器的 WebUI 里执行 root 命令。
 *
 * 支持：
 *   - KernelSU / KernelSU Next：window.ksu.exec(cmd, opts, cb) 或 Promise
 *   - APatch：window.apatch.exec(...)（同时兼容 ksu 命名）
 *   - 独立 WebUI 实现（如 WebUI X）：同上
 *
 * 统一成 Promise<string>，失败时 reject。
 */
(function () {
  'use strict';

  const MODULE_DIR = '/data/adb/modules/zygisk-wcx';
  const CONFIG = MODULE_DIR + '/config.sh';

  function pickBridge() {
    const candidates = [window.ksu, window.apatch, window.KernelSU, window.kernelsu];
    for (const b of candidates) {
      if (b && typeof b.exec === 'function') return b;
    }
    return null;
  }

  function execRaw(bridge, cmd) {
    return new Promise((resolve, reject) => {
      let settled = false;
      const done = (errno, stdout, stderr) => {
        if (settled) return;
        settled = true;
        if (errno && errno !== 0) reject(new Error((stderr || stdout || ('exit ' + errno)).trim()));
        else resolve(stdout || '');
      };
      try {
        const ret = bridge.exec(cmd, { cwd: MODULE_DIR }, done);
        // 某些实现返回 Promise 而不是用回调
        if (ret && typeof ret.then === 'function') {
          ret.then(
            (v) => done(0, typeof v === 'string' ? v : (v && v.stdout) || ''),
            (e) => done(1, '', String(e))
          );
        }
      } catch (e) {
        done(1, '', String(e));
      }
    });
  }

  /** 执行一条 config.sh 子命令 */
  function runConfig(args) {
    const bridge = pickBridge();
    if (!bridge) {
      return Promise.reject(new Error('当前环境没有 WebUI root 桥（Magisk 请用模块的“操作”按钮或终端跑 config.sh）'));
    }
    return execRaw(bridge, 'sh "' + CONFIG + '" ' + args).then(parseStatus);
  }

  /** config.sh status 的输出形如：enabled=1\n----\ncom.tencent.mm */
  function parseStatus(text) {
    const lines = String(text).split('\n').map((l) => l.trim());
    const enabledLine = lines.find((l) => l.startsWith('enabled='));
    const enabled = enabledLine ? enabledLine === 'enabled=1' : false;
    const sep = lines.indexOf('----');
    const targets = sep >= 0 ? lines.slice(sep + 1).filter(Boolean) : [];
    return { enabled, targets, raw: text };
  }

  window.WcxZygisk = {
    moduleDir: MODULE_DIR,
    configPath: CONFIG,
    available: () => !!pickBridge(),
    status: () => runConfig('status'),
    targets: () =>
      runConfig('targets').then((s) => {
        // targets 输出是 <uid>\t<pkg>\t<0|1>，不是上面的 status 结构，重新解析
        return String(s.raw)
          .split('\n')
          .map((l) => l.trim())
          .filter(Boolean)
          .map((l) => {
            const [userId, pkg, state] = l.split('\t');
            return { userId, pkg, enabled: state === '1' };
          });
      }),
    setTarget: (pkg, enabled) => runConfig('set "' + pkg + '" ' + (enabled ? 1 : 0)),
    setMaster: (enabled) => runConfig('master ' + (enabled ? 'on' : 'off')),
  };
})();
