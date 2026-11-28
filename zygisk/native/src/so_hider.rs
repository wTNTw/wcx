//! Hides WCX's own native libraries from this process's memory map.
//!
//! Only paths that resolve inside the WCX payload native-library directory and
//! whose file name is on a fixed allow-list are touched. The mapping's original
//! range and protection are preserved. A segment is re-verified immediately
//! before it is replaced, and any failure leaves that segment untouched and is
//! reported instead of being silently skipped.

use crate::{loge, logi, logw};
use libc::c_int;

/// Libraries the module loads from its own payload directory. Hiding anything
/// else is out of scope.
const HIDDEN_LIBRARIES: &[&str] = &["libwekit_native.so", "libdexkit.so", "libmmkv.so"];

#[derive(Debug)]
pub struct MapEntry {
    pub start: usize,
    pub end: usize,
    pub prot: c_int,
    pub path: String,
}

/// Parse one line of `/proc/self/maps`.
/// Returns `None` for anonymous or pseudo-file entries.
pub fn parse_maps_line(line: &[u8]) -> Option<MapEntry> {
    let s = std::str::from_utf8(line).ok()?;
    let mut parts = s.splitn(6, ' ');
    let range = parts.next()?;
    let perms = parts.next()?;
    let _offset = parts.next()?;
    let _dev = parts.next()?;
    let _inode = parts.next()?;
    let path = parts.next().unwrap_or("").trim();
    if path.is_empty() || path.starts_with('[') {
        return None;
    }
    let (start_s, end_s) = range.split_once('-')?;
    let start = usize::from_str_radix(start_s, 16).ok()?;
    let end = usize::from_str_radix(end_s, 16).ok()?;
    if end <= start {
        return None;
    }
    let mut prot = 0i32;
    if perms.contains('r') {
        prot |= libc::PROT_READ;
    }
    if perms.contains('w') {
        prot |= libc::PROT_WRITE;
    }
    if perms.contains('x') {
        prot |= libc::PROT_EXEC;
    }
    Some(MapEntry {
        start,
        end,
        prot,
        path: path.to_owned(),
    })
}

/// A directory may be reachable through more than one spelling
/// (`/data/user/0/<pkg>` and `/data/data/<pkg>` are the same directory). Both
/// the given path and its canonical form are accepted so an exact match does
/// not silently miss every segment.
fn directory_spellings(dir: &str) -> Vec<String> {
    let mut spellings = vec![dir.to_owned()];
    if let Ok(canonical) = std::fs::canonicalize(dir) {
        if let Some(canonical) = canonical.to_str() {
            if canonical != dir {
                spellings.push(canonical.to_owned());
            }
        }
    }
    spellings
}

/// Removes the kernel's `" (deleted)"` marker from a maps path.
///
/// A mapped file that was unlinked afterwards keeps its mapping but the kernel
/// appends " (deleted)" to the path, which would otherwise defeat the exact
/// file-name match.
pub fn strip_deleted_suffix(path: &str) -> &str {
    path.strip_suffix(" (deleted)").unwrap_or(path)
}

/// Matches `<dir>/<allow-listed library>` for any accepted spelling of `dir`.
pub fn is_wcx_library_in_any(path: &str, dirs: &[String]) -> bool {
    dirs.iter().any(|dir| is_wcx_library(path, dir))
}

/// True when `path` is exactly `<dir>/<allow-listed library>`.
///
/// The comparison is on the canonical directory plus an exact file name, so a
/// same-named library belonging to another module or application can never
/// match even when both are mapped into this process.
pub fn is_wcx_library(path: &str, dir: &str) -> bool {
    if dir.is_empty() {
        return false;
    }
    let Some((parent, name)) = path.rsplit_once('/') else {
        return false;
    };
    if parent != dir {
        return false;
    }
    HIDDEN_LIBRARIES.contains(&name)
}

fn read_maps() -> Vec<MapEntry> {
    let fd = unsafe { libc::open(c"/proc/self/maps".as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
    if fd < 0 {
        return Vec::new();
    }
    let mut bytes = Vec::with_capacity(65536);
    let mut buf = [0u8; 4096];
    loop {
        let n = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), buf.len()) };
        if n <= 0 {
            break;
        }
        bytes.extend_from_slice(&buf[..n as usize]);
    }
    unsafe { libc::close(fd) };
    bytes
        .split(|&b| b == b'\n')
        .filter_map(parse_maps_line)
        .collect()
}

/// Verify that exactly one mapping of `needle`'s range with `prot` still exists.
fn still_mapped(needle: &MapEntry) -> bool {
    read_maps().iter().any(|e| {
        e.start == needle.start && e.end == needle.end && e.prot == needle.prot && e.path == needle.path
    })
}

// SAFETY: raw pointer/syscall use is confined to this function; `len` and
// `prot` come from a just-verified `/proc/self/maps` entry.
unsafe fn remap_segment(entry: &MapEntry) -> bool {
    let len = entry.end - entry.start;
    let orig_prot = entry.prot;
    if orig_prot == libc::PROT_NONE {
        return true;
    }
    let read_prot_changed = orig_prot & libc::PROT_READ == 0;
    if read_prot_changed && libc::mprotect(entry.start as *mut _, len, orig_prot | libc::PROT_READ) != 0
    {
        return false;
    }
    let mfd = libc::syscall(
        libc::SYS_memfd_create,
        c"wcx".as_ptr(),
        libc::MFD_CLOEXEC as libc::c_ulong,
    ) as c_int;
    if mfd < 0 {
        if read_prot_changed {
            libc::mprotect(entry.start as *mut _, len, orig_prot);
        }
        return false;
    }
    if libc::ftruncate(mfd, len as libc::off_t) < 0 {
        libc::close(mfd);
        if read_prot_changed {
            libc::mprotect(entry.start as *mut _, len, orig_prot);
        }
        return false;
    }
    let mut written = 0usize;
    while written < len {
        let n = libc::write(mfd, (entry.start + written) as *const _, len - written);
        if n <= 0 {
            loge!("WcxZygisk: so_hider: write to memfd failed");
            libc::close(mfd);
            if read_prot_changed {
                libc::mprotect(entry.start as *mut _, len, orig_prot);
            }
            return false;
        }
        written += n as usize;
    }
    if read_prot_changed {
        libc::mprotect(entry.start as *mut _, len, orig_prot);
    }
    // Replace only if the segment is unchanged since verification, so a
    // concurrently modified mapping is never clobbered blindly.
    if !still_mapped(entry) {
        logw!(
            "WcxZygisk: so_hider: mapping changed before remap: {}",
            entry.path
        );
        libc::close(mfd);
        return false;
    }
    let addr = libc::mmap(
        entry.start as *mut _,
        len,
        orig_prot,
        libc::MAP_PRIVATE | libc::MAP_FIXED,
        mfd,
        0,
    );
    libc::close(mfd);
    addr != libc::MAP_FAILED
}

/// Remap every WCX payload library in `dir` that is currently mapped.
///
/// Returns the number of remapped segments, or `-1` when at least one matching
/// segment could not be remapped. A return value of `0` means nothing matched.
pub fn hide_libraries_in_dir(dir: &str) -> i32 {
    if dir.is_empty() || !dir.starts_with('/') {
        loge!("WcxZygisk: so_hider: refusing non-absolute payload directory");
        return -1;
    }
    let spellings = directory_spellings(dir);
    let entries: Vec<MapEntry> = read_maps()
        .into_iter()
        .filter(|e| is_wcx_library_in_any(strip_deleted_suffix(&e.path), &spellings))
        .collect();
    if entries.is_empty() {
        logw!("WcxZygisk: so_hider: no payload library mapped in {dir}");
        return 0;
    }
    let mut count = 0i32;
    let mut failed = false;
    for entry in &entries {
        if unsafe { remap_segment(entry) } {
            count += 1;
        } else {
            failed = true;
            loge!("WcxZygisk: so_hider: remap failed for {}", entry.path);
        }
    }
    logi!("WcxZygisk: so_hider: remapped {count} segment(s) under {dir}");
    if failed { -1 } else { count }
}

#[cfg(test)]
mod tests {
    use super::*;

    const DIR: &str = "/data/user/0/com.tencent.mm/files/.wcx-native/payload/arm64";

    #[test]
    fn parse_normal_line() {
        let line = b"7f4a000000-7f4a001000 r-xp 00000000 fd:00 12345 /data/app/libdexkit.so";
        let e = parse_maps_line(line).expect("parses");
        assert_eq!(e.start, 0x7f4a000000);
        assert_eq!(e.end, 0x7f4a001000);
        assert!(e.prot & libc::PROT_READ != 0);
        assert!(e.prot & libc::PROT_EXEC != 0);
        assert_eq!(e.path, "/data/app/libdexkit.so");
    }

    #[test]
    fn skips_anonymous_and_pseudo_entries() {
        assert!(parse_maps_line(b"7f00000000-7f00001000 rw-p 00000000 00:00 0").is_none());
        assert!(parse_maps_line(b"7f00000000-7f00001000 r--p 00000000 00:00 0 [vvar]").is_none());
    }

    #[test]
    fn matches_only_allow_listed_libraries_in_payload_dir() {
        assert!(is_wcx_library(
            &format!("{DIR}/libwekit_native.so"),
            DIR
        ));
        assert!(is_wcx_library(&format!("{DIR}/libdexkit.so"), DIR));
        assert!(is_wcx_library(&format!("{DIR}/libmmkv.so"), DIR));
    }

    #[test]
    fn rejects_other_directories_and_names() {
        assert!(!is_wcx_library("/data/app/lib/libdexkit.so", DIR));
        assert!(!is_wcx_library(&format!("{DIR}/libwekit_native.so.1"), DIR));
        assert!(!is_wcx_library(&format!("{DIR}/libhchat_crash.so"), DIR));
        assert!(!is_wcx_library(&format!("{DIR}/../arm64/libdexkit.so"), DIR));
        assert!(!is_wcx_library("libdexkit.so", DIR));
        assert!(!is_wcx_library(&format!("{DIR}/libdexkit.so"), ""));
    }

    #[test]
    fn accepts_alias_spelling_of_the_same_directory() {
        let alias = "/data/data/com.tencent.mm/files/.wcx-native/payload/arm64".to_owned();
        let spellings = vec![DIR.to_owned(), alias.clone()];
        assert!(is_wcx_library_in_any(
            &format!("{alias}/libdexkit.so"),
            &spellings
        ));
        assert!(!is_wcx_library_in_any(&format!("{alias}/libhchat_crash.so"), &spellings));
    }

    #[test]
    fn strips_kernel_deleted_marker() {
        let deleted = format!("{DIR}/libmmkv.so (deleted)");
        assert_eq!(strip_deleted_suffix(&deleted), format!("{DIR}/libmmkv.so"));
        assert!(is_wcx_library(strip_deleted_suffix(&deleted), DIR));
        // A name that merely ends with the words is not a deleted marker alone.
        assert_eq!(strip_deleted_suffix("a (deleted) b"), "a (deleted) b");
    }

    #[test]
    fn rejects_non_absolute_directory() {
        assert_eq!(hide_libraries_in_dir("relative/dir"), -1);
        assert_eq!(hide_libraries_in_dir(""), -1);
    }
}
