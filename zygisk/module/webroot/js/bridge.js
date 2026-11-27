// Target-configuration backend access. Every call runs the module's config.sh
// with an explicit argument list; no shell interpolation of user input happens
// here, and the command validates its own arguments again.

import { exec } from "./kernelsu.js";

const MODULE_DIR = "/data/adb/modules/wcx_zygisk";
const CONFIG = `${MODULE_DIR}/config.sh`;

async function run(args) {
  const command = ["/system/bin/sh", CONFIG, ...args]
    .map((part) => `'${String(part).replaceAll("'", `'\\''`)}'`)
    .join(" ");
  const result = await exec(command, { cwd: MODULE_DIR });
  return result;
}

function lines(text) {
  return text
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

/** Returns [{ userId, packageName, enabled }]. */
export async function listTargets() {
  const { errno, stdout, stderr } = await run(["list"]);
  if (errno !== 0) throw new Error(stderr.trim() || `list failed (${errno})`);
  return lines(stdout).map((line) => {
    const [userId, packageName, enabled] = line.split("\t");
    return { userId, packageName, enabled: enabled === "1" };
  });
}

export async function refreshTargets() {
  const { errno, stderr } = await run(["refresh"]);
  if (errno !== 0) throw new Error(stderr.trim() || `refresh failed (${errno})`);
}

export async function setTarget(userId, packageName, enabled) {
  const { errno, stderr } = await run(["set", String(userId), packageName, enabled ? "1" : "0"]);
  if (errno !== 0) throw new Error(stderr.trim() || `set failed (${errno})`);
}

export async function readLog() {
  const { stdout } = await run(["log"]);
  return stdout;
}
