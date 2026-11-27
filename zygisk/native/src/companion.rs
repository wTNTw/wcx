// companion — Zygisk root companion request handler.
//
// The companion only answers one question: "is this UID + process name an
// enabled WCX injection target?". It deliberately exposes no file copy,
// command execution or other application's data. The target list is written
// by the module's manager integration (WebUI / installer) and is read-only
// here.

use crate::protocol::{
    COMPANION_DISABLED, COMPANION_ENABLED, COMPANION_REQUEST_ENABLED, read_i32_from_fd,
    read_string_from_fd, read_u8_from_fd, write_u8_to_fd,
};
use crate::{loge, logw};
use libc::c_int;
use std::fs;

const TARGETS_PATH: &str = "/data/adb/wcx_zygisk/injection-targets.tsv";
const APP_USER_RANGE: i32 = 100_000;
const WECHAT_PACKAGE: &str = "com.tencent.mm";
const MAX_PROCESS_NAME: usize = 255;

/// WCX accepts the host package and its clone packages (`com.tencent.mm.<x>`),
/// mirroring `PackageNames.isWeChat`. A bare `starts_with` would also accept
/// look-alike packages such as `com.tencent.mmx`, so the separator is required.
pub(crate) fn is_wechat_package(pkg: &str) -> bool {
    pkg == WECHAT_PACKAGE
        || (pkg.len() > WECHAT_PACKAGE.len()
            && pkg.starts_with(WECHAT_PACKAGE)
            && pkg.as_bytes()[WECHAT_PACKAGE.len()] == b'.')
}

/// A process belongs to the enabled package when it is the main process or a
/// named child process (`<package>:...`). Look-alike prefixes are rejected.
pub(crate) fn is_package_process(pkg: &str, process_name: &str) -> bool {
    process_name == pkg
        || (process_name.len() > pkg.len()
            && process_name.starts_with(pkg)
            && process_name.as_bytes()[pkg.len()] == b':')
}

fn is_enabled_target(uid: i32, process_name: &str) -> bool {
    if uid <= 0 || process_name.is_empty() || process_name.len() > MAX_PROCESS_NAME {
        return false;
    }
    let user_id = uid / APP_USER_RANGE;
    let content = match fs::read_to_string(TARGETS_PATH) {
        Ok(s) => s,
        Err(_) => return false,
    };
    for line in content.lines() {
        if line.starts_with('#') || line.is_empty() {
            continue;
        }
        let parts: Vec<&str> = line.splitn(3, '\t').collect();
        if parts.len() != 3 || parts[2] != "1" {
            continue;
        }
        let (row_user, pkg) = (parts[0], parts[1]);
        if !is_wechat_package(pkg) {
            continue;
        }
        let Ok(row_uid) = row_user.parse::<i32>() else {
            continue;
        };
        if row_uid != user_id {
            continue;
        }
        if is_package_process(pkg, process_name) {
            return true;
        }
    }
    false
}

struct RequestHeader {
    request_type: u8,
    uid: i32,
    process_name: String,
}

fn read_header(sock: c_int) -> Option<RequestHeader> {
    let request_type = read_u8_from_fd(sock).ok()?;
    let uid = read_i32_from_fd(sock).ok()?;
    let process_name = read_string_from_fd(sock).ok()?;
    if process_name.len() > MAX_PROCESS_NAME {
        return None;
    }
    Some(RequestHeader {
        request_type,
        uid,
        process_name,
    })
}

/// Called from `zygisk_companion_entry` in lib.rs.
pub fn handle(sock: c_int) {
    let header = match read_header(sock) {
        Some(h) => h,
        None => {
            loge!("WcxZygisk: companion: failed to read request identity");
            return;
        }
    };
    if header.request_type != COMPANION_REQUEST_ENABLED {
        logw!(
            "WcxZygisk: companion: unsupported request type {:#x}",
            header.request_type
        );
        return;
    }
    let status = if is_enabled_target(header.uid, &header.process_name) {
        COMPANION_ENABLED
    } else {
        COMPANION_DISABLED
    };
    if write_u8_to_fd(sock, status).is_err() {
        logw!("WcxZygisk: companion: failed to return target status");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_host_and_clone_packages() {
        assert!(is_wechat_package("com.tencent.mm"));
        assert!(is_wechat_package("com.tencent.mm.work"));
        assert!(!is_wechat_package("com.tencent.mmx"));
        assert!(!is_wechat_package("com.tencent"));
        assert!(!is_wechat_package(""));
    }

    #[test]
    fn matches_main_and_child_processes_only() {
        assert!(is_package_process("com.tencent.mm", "com.tencent.mm"));
        assert!(is_package_process("com.tencent.mm", "com.tencent.mm:push"));
        assert!(is_package_process("com.tencent.mm", "com.tencent.mm:tools"));
        assert!(!is_package_process("com.tencent.mm", "com.tencent.mmx"));
        assert!(!is_package_process("com.tencent.mm", "com.tencent.mmx:push"));
        assert!(!is_package_process("com.tencent.mm", "com.tencent.mmwork"));
        assert!(!is_package_process("com.tencent.mm", "android"));
    }
}
