import { listTargets, readLog, refreshTargets, setTarget } from "./bridge.js";

const statusEl = document.getElementById("status");
const listEl = document.getElementById("targets");
const countEl = document.getElementById("enabled-count");
const refreshBtn = document.getElementById("refresh");
const exportBtn = document.getElementById("export");
const logPanel = document.getElementById("log-panel");
const logEl = document.getElementById("log");
const closeLogBtn = document.getElementById("close-log");
const toastEl = document.getElementById("toast");

let toastTimer = 0;

function showToast(message) {
  toastEl.textContent = message;
  toastEl.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastEl.classList.remove("show"), 2600);
}

function setStatus(text, visible = true) {
  statusEl.textContent = text;
  statusEl.hidden = !visible;
}

function render(targets) {
  listEl.replaceChildren();
  const enabled = targets.filter((target) => target.enabled).length;
  countEl.textContent = String(enabled);
  if (targets.length === 0) {
    setStatus("没有已记录的微信实例。点“扫描已安装实例”后选择要注入的实例。");
    listEl.hidden = true;
    return;
  }
  setStatus("", false);
  listEl.hidden = false;
  for (const target of targets) {
    const item = document.createElement("li");

    const label = document.createElement("div");
    const id = document.createElement("div");
    id.className = "target-id";
    id.textContent = target.packageName;
    const meta = document.createElement("div");
    meta.className = "target-meta";
    meta.textContent = `Android 用户 ${target.userId} · 主进程与 :... 子进程`;
    label.append(id, meta);

    const control = document.createElement("label");
    control.className = "switch";
    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = target.enabled;
    input.addEventListener("change", async () => {
      input.disabled = true;
      try {
        await setTarget(target.userId, target.packageName, input.checked);
        target.enabled = input.checked;
        countEl.textContent = String(
          Array.from(listEl.querySelectorAll("input[type=checkbox]")).filter((box) => box.checked)
            .length,
        );
        showToast(input.checked ? "已启用，重启微信后生效" : "已关闭，重启微信后生效");
      } catch (error) {
        input.checked = !input.checked;
        showToast(`保存失败：${error.message}`);
      } finally {
        input.disabled = false;
      }
    });
    control.append(input);
    item.append(label, control);
    listEl.append(item);
  }
}

async function load() {
  try {
    render(await listTargets());
  } catch (error) {
    setStatus(`读取失败：${error.message}`);
  }
}

refreshBtn.addEventListener("click", async () => {
  refreshBtn.disabled = true;
  setStatus("正在扫描 Android 用户与已安装包…");
  try {
    await refreshTargets();
    await load();
    showToast("扫描完成，新发现的实例默认关闭");
  } catch (error) {
    setStatus(`扫描失败：${error.message}`);
  } finally {
    refreshBtn.disabled = false;
  }
});

exportBtn.addEventListener("click", async () => {
  logPanel.hidden = false;
  logEl.textContent = "正在读取…";
  try {
    logEl.textContent = (await readLog()) || "(日志为空)";
  } catch (error) {
    logEl.textContent = `读取日志失败：${error.message}`;
  }
});

closeLogBtn.addEventListener("click", () => {
  logPanel.hidden = true;
});

if (typeof globalThis.ksu?.exec !== "function") {
  setStatus("请从 KernelSU 或 APatch 的模块页面打开本页面。");
} else {
  load();
}
