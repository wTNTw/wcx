/**
 * 在 Root 管理器的 WebUI 里执行 root 命令。
 *
 * **KernelSU / KernelSU Next / APatch 的 WebUI 桥签名**（这一点很关键）：
 *
 *     ksu.exec(command: string, optionsJson: string, callbackName: string)
 *     window[callbackName] = (errno, stdout, stderr) => { … }
 *
 * 第三个参数是**回调函数的全局名字字符串**，不是函数本身；options 也要 JSON 字符串。
 * 传函数进去会让 Java 侧方法调用失败，表现为
 * "Error invoking exec: Java exception was raised during method invocation"。
 * （参考 wekit_zygisk / funbox 两个模块的 webroot/js/kernelsu.js 写法。）
 *
 * 同时保留对「函数回调」类实现的兜底（部分 WebUI 实现/旧版会这么用）。
 */
(function () {
  'use strict';

  const MODULE_DIR = '/data/adb/modules/zygisk-wcx';
  const CONFIG = MODULE_DIR + '/config.sh';
  const CALLBACK_TIMEOUT_MS = 20000;

  let callbackCounter = 0;

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

  function errorText(e) {
    return String((e && e.message) || e || '');
  }

  function flattenStdout(v) {
    if (typeof v === 'string') return v;
    if (v && typeof v === 'object') {
      if (typeof v.stdout === 'string') return v.stdout;
      if (typeof v.result === 'string') return v.result;
    }
    return '';
  }

  /** 命令本身失败（退出码非 0） */
  class CommandError extends Error {}
  /** 桥的调用方式不匹配（换一种签名重试） */
  class SignatureError extends Error {}

  /**
   * KernelSU 风格：第三个参数是全局回调名（字符串）。
   */
  function execByName(bridge, command, options) {
    return new Promise((resolve, reject) => {
      const name = '__wcx_exec_cb_' + Date.now() + '_' + callbackCounter++;
      let settled = false;
      const cleanup = () => {
        settled = true;
        try {
          delete window[name];
        } catch (_) {
          window[name] = undefined;
        }
      };
      const timer = setTimeout(() => {
        if (settled) return;
        cleanup();
        reject(new CommandError('exec 超时（命令可能仍在运行）'));
      }, CALLBACK_TIMEOUT_MS);

      window[name] = function (errno, stdout, stderr) {
        if (settled) return;
        clearTimeout(timer);
        cleanup();
        if (errno && Number(errno) !== 0) {
          reject(new CommandError(String(stderr || stdout || 'exit ' + errno).trim()));
        } else {
          resolve(String(stdout || ''));
        }
      };

      try {
        const ret = bridge.exec(command, JSON.stringify(options || {}), name);
        // 少数实现忽略回调参数、直接返回 Promise<string> 或字符串
        if (isThenable(ret)) {
          ret.then(
            (v) => window[name] && window[name](0, flattenStdout(v), ''),
            (e) => window[name] && window[name](1, '', errorText(e))
          );
        } else if (typeof ret === 'string') {
          window[name] && window[name](0, ret, '');
        }
      } catch (e) {
        clearTimeout(timer);
        cleanup();
        // 大多数此类异常是签名不匹配（例如桥要求函数回调）
        reject(new SignatureError(errorText(e)));
      }
    });
  }

  /**
   * 函数回调风格（部分实现/旧版）：exec(cmd, opts, cb) / exec(cmd, cb) / exec(cmd)。
   */
  function execWithFunction(bridge, command, options) {
    const attempt = (invoke) =>
      new Promise((resolve, reject) => {
        let settled = false;
        const finish = (errno, stdout, stderr) => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          if (errno && Number(errno) !== 0) {
            reject(new CommandError(String(stderr || stdout || 'exit ' + errno).trim()));
          } else {
            resolve(String(stdout || ''));
          }
        };
        const timer = setTimeout(() => {
          if (settled) return;
          settled = true;
          reject(new CommandError('exec 超时（命令可能仍在运行）'));
        }, CALLBACK_TIMEOUT_MS);
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
          reject(new SignatureError(errorText(e)));
        }
      });

    return attempt((cb) => bridge.exec(command, options || {}, cb)).catch((e) => {
      if (!(e instanceof SignatureError)) throw e;
      return attempt((cb) => bridge.exec(command, cb));
    });
  }

  function exec(command, options) {
    const bridge = findBridge();
    if (!bridge) {
      return Promise.reject(
        new CommandError('当前环境没有 WebUI root 桥（Magisk 请用模块「操作」按钮，或终端执行 config.sh）')
      );
    }
    // 先按 KernelSU/APatch 的官方约定（回调名 + JSON options），失败再试函数回调
    return execByName(bridge, command, options).catch((e) => {
      if (!(e instanceof SignatureError)) throw e;
      return execWithFunction(bridge, command, options).catch((f) => {
        if (f instanceof SignatureError) {
          throw new CommandError('无法调用 root 桥：' + f.message);
        }
        throw f;
      });
    });
  }

  function runConfig(args) {
    // 路径不含空格，不加引号，避免某些桥实现二次解析
    return exec('sh ' + CONFIG + ' ' + args, {});
  }

  /** 把 config.sh status 的 key=value 输出解析成对象 */
  function parseKeyValues(text) {
    const out = {};
    String(text || '')
      .split('\n')
      .map((l) => l.replace(/\r$/, ''))
      .forEach((line) => {
        if (!line) return;
        const i = line.indexOf('=');
        if (i <= 0) return;
        out[line.slice(0, i)] = line.slice(i + 1);
      });
    return out;
  }

  function toast(message) {
    const bridge = findBridge();
    if (bridge && typeof bridge.toast === 'function') {
      try {
        bridge.toast(String(message));
      } catch (_) {
        /* 部分管理器没有 toast */
      }
    }
  }

  window.WcxZygisk = {
    moduleDir: MODULE_DIR,
    configPath: CONFIG,
    available: () => !!findBridge(),
    exec: exec,
    targets: () =>
      runConfig('targets').then((out) =>
        String(out)
          .split('\n')
          .map((l) => l.replace(/\r$/, '').trim())
          .filter(Boolean)
          .map((l) => {
            const parts = l.split('\t');
            return { userId: parts[0] || '0', pkg: parts[1] || parts[0], enabled: parts[2] === '1' };
          })
      ),
    // 修改类操作只要成功即可；界面状态由 app.js 再调 targets 刷新
    // 作用域是 (userId, package)，双开/分身才能单独开关
    setTarget: (userId, pkg, enabled) =>
      exec('sh ' + CONFIG + ' set ' + (userId || '0') + ' ' + pkg + ' ' + (enabled ? 1 : 0), {})
        .then(() => true),
    setTargetAll: (pkg, enabled) =>
      exec('sh ' + CONFIG + ' set all ' + pkg + ' ' + (enabled ? 1 : 0), {}).then(() => true),
    reset: () => exec('sh ' + CONFIG + ' reset', {}).then(() => true),
    // 注入状态（模块是否启用、作用域数量、是否已注入、是否有同族注入器抢注）
    status: () => runConfig('status').then(parseKeyValues),
    // 最近 n 行 WCX 日志（logcat 按 tag 过滤）
    logs: (n) => runConfig('logs ' + (Number(n) > 0 ? Number(n) : 150)),
    toast: toast,
  };
})();
