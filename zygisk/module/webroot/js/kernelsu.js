// Minimal KernelSU/APatch WebUI bridge (API 3.x shape).
//
// Both managers expose `globalThis.ksu`; the callback is a global function name
// that the host invokes with (errno, stdout, stderr).

const callbacks = new Map();
let nextId = 1;

function callbackName(prefix) {
  const name = `wcx_${prefix}_${nextId++}`;
  return name;
}

function registerCallback(name, resolve) {
  callbacks.set(name, resolve);
  globalThis[name] = (errno, stdout, stderr) => {
    callbacks.delete(name);
    delete globalThis[name];
    resolve({ errno: Number(errno) || 0, stdout: stdout ?? "", stderr: stderr ?? "" });
  };
}

export function exec(command, options = {}) {
  return new Promise((resolve, reject) => {
    if (typeof globalThis.ksu?.exec !== "function") {
      reject(new Error("ksu.exec is not available; open this page from the root manager"));
      return;
    }
    const name = callbackName("exec");
    registerCallback(name, resolve);
    try {
      globalThis.ksu.exec(command, JSON.stringify(options), name);
    } catch (error) {
      callbacks.delete(name);
      delete globalThis[name];
      reject(error);
    }
  });
}

export function toast(message) {
  if (typeof globalThis.ksu?.toast === "function") {
    globalThis.ksu.toast(String(message));
  }
}
