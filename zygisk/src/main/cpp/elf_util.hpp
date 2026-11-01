/*
 * 内存内 ELF 符号解析（用于给 LSPlant 提供 libart.so 符号）
 *
 * 来源：LSPosed（core/src/main/jni/include/elf_util.h、src/elf_util.cpp）
 *   Copyright (C) 2019 Swift Gan
 *   Copyright (C) 2021 LSPosed Contributors
 * 许可：GNU General Public License v3.0（与本项目 LICENSE 一致）
 * 修改：仅改为本项目日志宏、拆掉 LSPosed 的 config.h 依赖。
 */

#pragma once

#include <linux/elf.h>
#include <link.h>
#include <sys/types.h>

#include <map>
#include <string>
#include <string_view>
#include <type_traits>
#include <vector>

#ifndef SHT_GNU_HASH
#define SHT_GNU_HASH 0x6ffffff6
#endif

namespace SandHook {

class ElfImg {
public:
    explicit ElfImg(std::string_view elf);

    template <typename T = void *>
        requires(std::is_pointer_v<T>)
    constexpr const T getSymbAddress(std::string_view name) const {
        auto offset = getSymbOffset(name, GnuHash(name), ElfHash(name));
        if (offset > 0 && base != nullptr) {
            return reinterpret_cast<T>(
                    static_cast<ElfW(Addr)>((uintptr_t) base + offset - bias));
        }
        return nullptr;
    }

    template <typename T = void *>
        requires(std::is_pointer_v<T>)
    constexpr const T getSymbPrefixFirstAddress(std::string_view prefix) const {
        auto offset = PrefixLookupFirst(prefix);
        if (offset > 0 && base != nullptr) {
            return reinterpret_cast<T>(
                    static_cast<ElfW(Addr)>((uintptr_t) base + offset - bias));
        }
        return nullptr;
    }

    template <typename T = void *>
        requires(std::is_pointer_v<T>)
    const std::vector<T> getAllSymbAddress(std::string_view name) const {
        auto offsets = LinearRangeLookup(name);
        std::vector<T> res;
        res.reserve(offsets.size());
        for (const auto &offset : offsets) {
            res.emplace_back(reinterpret_cast<T>(
                    static_cast<ElfW(Addr)>((uintptr_t) base + offset - bias)));
        }
        return res;
    }

    bool isValid() const { return base != nullptr; }

    const std::string name() const { return elf; }

    ~ElfImg();

    ElfImg(const ElfImg &) = delete;
    ElfImg &operator=(const ElfImg &) = delete;

private:
    ElfW(Addr) getSymbOffset(std::string_view name, uint32_t gnu_hash,
                             uint32_t elf_hash) const;

    ElfW(Addr) ElfLookup(std::string_view name, uint32_t hash) const;

    ElfW(Addr) GnuLookup(std::string_view name, uint32_t hash) const;

    ElfW(Addr) LinearLookup(std::string_view name) const;

    std::vector<ElfW(Addr)> LinearRangeLookup(std::string_view name) const;

    ElfW(Addr) PrefixLookupFirst(std::string_view prefix) const;

    constexpr static uint32_t ElfHash(std::string_view name);

    constexpr static uint32_t GnuHash(std::string_view name);

    bool findModuleBase();

    void MayInitLinearMap() const;

    std::string elf;
    void *base = nullptr;
    char *buffer = nullptr;
    off_t size = 0;
    off_t bias = -4396;
    ElfW(Ehdr) *header = nullptr;
    ElfW(Shdr) *section_header = nullptr;
    ElfW(Shdr) *symtab = nullptr;
    ElfW(Shdr) *strtab = nullptr;
    ElfW(Shdr) *dynsym = nullptr;
    ElfW(Sym) *symtab_start = nullptr;
    ElfW(Sym) *dynsym_start = nullptr;
    ElfW(Sym) *strtab_start = nullptr;
    ElfW(Off) symtab_count = 0;
    ElfW(Off) symstr_offset = 0;
    ElfW(Off) symstr_offset_for_symtab = 0;
    ElfW(Off) symtab_offset = 0;
    ElfW(Off) dynsym_offset = 0;
    ElfW(Off) symtab_size = 0;

    uint32_t nbucket_{};
    uint32_t *bucket_ = nullptr;
    uint32_t *chain_ = nullptr;

    uint32_t gnu_nbucket_{};
    uint32_t gnu_symndx_{};
    uint32_t gnu_bloom_size_{};
    uint32_t gnu_shift2_{};
    uintptr_t *gnu_bloom_filter_ = nullptr;
    uint32_t *gnu_bucket_ = nullptr;
    uint32_t *gnu_chain_ = nullptr;

    mutable std::map<std::string_view, ElfW(Sym) *> symtabs_;
};

constexpr uint32_t ElfImg::ElfHash(std::string_view name) {
    uint32_t h = 0, g;
    for (unsigned char p : name) {
        h = (h << 4) + p;
        g = h & 0xf0000000;
        h ^= g;
        h ^= g >> 24;
    }
    return h;
}

constexpr uint32_t ElfImg::GnuHash(std::string_view name) {
    uint32_t h = 5381;
    for (unsigned char p : name) {
        h += (h << 5) + p;
    }
    return h;
}

}  // namespace SandHook
