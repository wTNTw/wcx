/**
 * 在 Root 管理器的 WebUI 里执行 root 命令。
 *
 * 不同实现（KernelSU / KernelSU Next / APatch / WebUI X）的 `exec` 签名不一致。
 * 之前只按 KernelSU 文档的 exec(cmd, {cwd}, cb) 调用，遇到 2 参实现会直接抛
 * "Error invoking exec: Java exception was raised during method invocation"。
 * 现在按常见形态依次尝试，并且只有在错误像「签名不匹配」时才换下一种：
 *
 *   1) exec(cmd, {}, (errno, stdout, stderr) => …)
 *   2) exec(cmd, (errno, stdout, stderr) => …)
 *   3) exec(cmd) -> Promise<string> | string
 */
(function () {
  'use strict';

  const MODULE_DIR = '/data/adb/modules/zygisk-wcx';
  const CONFIG = MODULE_DIR + '/config.sh';

  function findBridge() {
    const candidates = [window.ksu, window.apatch, window.KernelSU, window.kernelsu];
    for (const b of candidates) {
      if (b && typeof b.exec === 'function') return b;
    }
    return null;
  }

  function isThenable(v) {
    return !!v && typeof v.then === 'function';
  }

  function flattenStdout(v) {
    if (typeof v === 'string') return v;
    if (v && typeof v === 'object') {
      if (typeof v.stdout === 'string') return v.stdout;
      if (typeof v.result === 'string') return v.result;
    }
    return '';
  }

  function errorText(e) {
    return String((e && e.message) || e || '');
  }

  /** 只有这种错误才值得换一种签名重试；命令本身失败（exit 1）不会重试 */
  function looksLikeSignatureError(e) {
    return /Java exception|cannot be invoked|not a function|is not a function|undefined is not|IllegalArgument|ClassCast|NoSuchMethod|Wrong argument/i.test(
      errorText(e)
    );
  }

  function tryWithCallback(invoke, timeoutMs) {
    return new Promise((resolve, reject) => {
      let settled = false;
      const finish = (errno, stdout, stderr) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        if (errno && Number(errno) !== 0) {
          reject(new Error(String(stderr || stdout || 'exit ' + errno).trim()));
        } else {
          resolve(String(stdout || ''));
        }
      };
      const timer = setTimeout(() => {
        if (!settled) {
          settled = true;
          reject(new Error('exec 超时'));
        }
      }, timeoutMs || 20000);
      try {
        const ret = invoke(finish);
        if (isThenable(ret)) {
          ret.then((v) => finish(0, flattenStdout(v), ''), (e) => finish(1, '', errorText(e)));
        } else if (typeof ret === 'string') {
          finish(0, ret, '');
        }
      } catch (e) {
        settled = true;
        clearTimeout(timer);
        reject(e instanceof Error ? e : new Error(errorText(e)));
      }
    });
  }

  function runOnce(bridge, cmd) {
    return tryWithCallback((cb) => bridge.exec(cmd, {}, cb)).catch((e) => {
      if (!looksLikeSignatureError(e)) throw e;
      // 2 参实现（把回调当第 2 个参数）；1 参实现返回 string/Promise 时这里也能兜住
      return tryWithCallback((cb) => bridge.exec(cmd, cb));
    });
  }

  function runConfig(args) {
    const bridge = findBridge();
    if (!bridge) {
      return Promise.reject(
        new Error('当前环境没有 WebUI root 桥（Magisk 请用模块「操作」按钮，或终端执行 config.sh）')
      );
    }
    // 路径不含空格，不加引号，避免某些桥实现二次解析
    return runOnce(bridge, 'sh ' + CONFIG + ' ' + args).then(parseStatus);
  }

  /** config.sh status 输出：enabled=1\n----\n<每行一个包名> */
  function parseStatus(text) {
    const lines = String(text)
      .split('\n')
      .map((l) => l.replace(/\r$/, '').trim());
    const enabledLine = lines.find((l) => l.indexOf('enabled=') === 0);
    const enabled = enabledLine === 'enabled=1';
    const sep = lines.indexOf('----');
    const targets = sep >= 0 ? lines.slice(sep + 1).filter(Boolean) : [];
    return { enabled, targets, raw: text };
  }

  window.WcxZygisk = {
    moduleDir: MODULE_DIR,
    configPath: CONFIG,
    available: () => !!findBridge(),
    status: () => runConfig('status'),
    targets: () =>
      runConfig('targets').then((s) =>
        String(s.raw)
          .split('\n')
          .map((l) => l.replace(/\r$/, '').trim())
          .filter(Boolean)
          .map((l) => {
            const parts = l.split('\t');
            return { userId: parts[0] || '0', pkg: parts[1] || parts[0], enabled: parts[2] === '1' };
          })
      ),
    setTarget: (pkg, enabled) => runConfig('set ' + pkg + ' ' + (enabled ? 1 : 0)),
    setMaster: (enabled) => runConfig('master ' + (enabled ? 'on' : 'off')),
    reset: () => runConfig('reset'),
  };
})();
