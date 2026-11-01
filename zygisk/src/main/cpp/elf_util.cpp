/*
 * 内存内 ELF 符号解析实现
 *
 * 来源：LSPosed（core/src/main/jni/src/elf_util.cpp）
 *   Copyright (C) 2019 Swift Gan
 *   Copyright (C) 2021 LSPosed Contributors
 * 许可：GNU General Public License v3.0（与本项目 LICENSE 一致）
 * 修改：仅改为本项目日志宏。
 */

#include "elf_util.hpp"

#include <fcntl.h>
#include <malloc.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "logging.hpp"

using namespace SandHook;

template <typename T>
inline constexpr auto offsetOf(ElfW(Ehdr) *head, ElfW(Off) off) {
    return reinterpret_cast<std::conditional_t<std::is_pointer_v<T>, T, T *>>(
            reinterpret_cast<uintptr_t>(head) + off);
}

ElfImg::ElfImg(std::string_view base_name) : elf(base_name) {
    if (!findModuleBase()) {
        base = nullptr;
        return;
    }

    int fd = open(elf.data(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGE("failed to open %s", elf.c_str());
        return;
    }

    size = lseek(fd, 0, SEEK_END);
    if (size <= 0) {
        LOGE("lseek() failed for %s", elf.c_str());
        close(fd);
        return;
    }

    void *mapped = mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (mapped == MAP_FAILED) {
        LOGE("mmap() failed for %s", elf.c_str());
        return;
    }
    header = reinterpret_cast<decltype(header)>(mapped);

    section_header = offsetOf<decltype(section_header)>(header, header->e_shoff);

    auto shoff = reinterpret_cast<uintptr_t>(section_header);
    char *section_str = offsetOf<char *>(header, section_header[header->e_shstrndx].sh_offset);

    for (int i = 0; i < header->e_shnum; i++, shoff += header->e_shentsize) {
        auto *section_h = reinterpret_cast<ElfW(Shdr) *>(shoff);
        char *sname = section_h->sh_name + section_str;
        auto entsize = section_h->sh_entsize;
        switch (section_h->sh_type) {
            case SHT_DYNSYM: {
                if (bias == -4396) {
                    dynsym = section_h;
                    dynsym_offset = section_h->sh_offset;
                    dynsym_start = offsetOf<decltype(dynsym_start)>(header, dynsym_offset);
                }
                break;
            }
            case SHT_SYMTAB: {
                if (strcmp(sname, ".symtab") == 0) {
                    symtab = section_h;
                    symtab_offset = section_h->sh_offset;
                    symtab_size = section_h->sh_size;
                    symtab_count = symtab_size / entsize;
                    symtab_start = offsetOf<decltype(symtab_start)>(header, symtab_offset);
                }
                break;
            }
            case SHT_STRTAB: {
                if (bias == -4396) {
                    strtab = section_h;
                    symstr_offset = section_h->sh_offset;
                    strtab_start = offsetOf<decltype(strtab_start)>(header, symstr_offset);
                }
                if (strcmp(sname, ".strtab") == 0) {
                    symstr_offset_for_symtab = section_h->sh_offset;
                }
                break;
            }
            case SHT_PROGBITS: {
                if (strtab == nullptr || dynsym == nullptr) break;
                if (bias == -4396) {
                    bias = (off_t) section_h->sh_addr - (off_t) section_h->sh_offset;
                }
                break;
            }
            case SHT_HASH: {
                auto *d_un = offsetOf<ElfW(Word)>(header, section_h->sh_offset);
                nbucket_ = d_un[0];
                bucket_ = d_un + 2;
                chain_ = bucket_ + nbucket_;
                break;
            }
            case SHT_GNU_HASH: {
                auto *d_buf =
                        reinterpret_cast<ElfW(Word) *>(((size_t) header) + section_h->sh_offset);
                gnu_nbucket_ = d_buf[0];
                gnu_symndx_ = d_buf[1];
                gnu_bloom_size_ = d_buf[2];
                gnu_shift2_ = d_buf[3];
                gnu_bloom_filter_ = reinterpret_cast<decltype(gnu_bloom_filter_)>(d_buf + 4);
                gnu_bucket_ =
                        reinterpret_cast<decltype(gnu_bucket_)>(gnu_bloom_filter_ + gnu_bloom_size_);
                gnu_chain_ = gnu_bucket_ + gnu_nbucket_ - gnu_symndx_;
                break;
            }
            default:
                break;
        }
    }
}

ElfW(Addr) ElfImg::ElfLookup(std::string_view name, uint32_t hash) const {
    if (nbucket_ == 0) return 0;

    char *strings = reinterpret_cast<char *>(strtab_start);

    for (auto n = bucket_[hash % nbucket_]; n != 0; n = chain_[n]) {
        auto *sym = dynsym_start + n;
        if (name == strings + sym->st_name) {
            return sym->st_value;
        }
    }
    return 0;
}

ElfW(Addr) ElfImg::GnuLookup(std::string_view name, uint32_t hash) const {
    static constexpr auto bloom_mask_bits = sizeof(ElfW(Addr)) * 8;

    if (gnu_nbucket_ == 0 || gnu_bloom_size_ == 0) return 0;

    auto bloom_word = gnu_bloom_filter_[(hash / bloom_mask_bits) % gnu_bloom_size_];
    uintptr_t mask = 0 | (uintptr_t) 1 << (hash % bloom_mask_bits) |
                     (uintptr_t) 1 << ((hash >> gnu_shift2_) % bloom_mask_bits);
    if ((mask & bloom_word) == mask) {
        auto sym_index = gnu_bucket_[hash % gnu_nbucket_];
        if (sym_index >= gnu_symndx_) {
            char *strings = reinterpret_cast<char *>(strtab_start);
            do {
                auto *sym = dynsym_start + sym_index;
                if (((gnu_chain_[sym_index] ^ hash) >> 1) == 0 &&
                    name == strings + sym->st_name) {
                    return sym->st_value;
                }
            } while ((gnu_chain_[sym_index++] & 1) == 0);
        }
    }
    return 0;
}

void ElfImg::MayInitLinearMap() const {
    if (!symtabs_.empty()) return;
    if (symtab_start == nullptr || symstr_offset_for_symtab == 0) return;

    for (ElfW(Off) i = 0; i < symtab_count; i++) {
        unsigned int st_type = ELF_ST_TYPE(symtab_start[i].st_info);
        const char *st_name =
                offsetOf<const char *>(header, symstr_offset_for_symtab + symtab_start[i].st_name);
        if ((st_type == STT_FUNC || st_type == STT_OBJECT) && symtab_start[i].st_size) {
            symtabs_.emplace(st_name, &symtab_start[i]);
        }
    }
}

ElfW(Addr) ElfImg::LinearLookup(std::string_view name) const {
    MayInitLinearMap();
    if (auto i = symtabs_.find(name); i != symtabs_.end()) {
        return i->second->st_value;
    }
    return 0;
}

std::vector<ElfW(Addr)> ElfImg::LinearRangeLookup(std::string_view name) const {
    MayInitLinearMap();
    std::vector<ElfW(Addr)> res;
    for (auto [i, end] = symtabs_.equal_range(name); i != end; ++i) {
        res.emplace_back(i->second->st_value);
    }
    return res;
}

ElfW(Addr) ElfImg::PrefixLookupFirst(std::string_view prefix) const {
    MayInitLinearMap();
    if (auto i = symtabs_.lower_bound(prefix);
        i != symtabs_.end() && i->first.starts_with(prefix)) {
        return i->second->st_value;
    }
    return 0;
}

ElfImg::~ElfImg() {
    if (buffer) {
        free(buffer);
        buffer = nullptr;
    }
    if (header) {
        munmap(header, size);
        header = nullptr;
    }
}

ElfW(Addr) ElfImg::getSymbOffset(std::string_view name, uint32_t gnu_hash,
                                 uint32_t elf_hash) const {
    if (auto offset = GnuLookup(name, gnu_hash); offset > 0) {
        return offset;
    } else if (offset = ElfLookup(name, elf_hash); offset > 0) {
        return offset;
    }
    return LinearLookup(name);
}

static inline bool contains(std::string_view a, std::string_view b) {
    return a.find(b) != std::string_view::npos;
}

bool ElfImg::findModuleBase() {
    off_t load_addr = 0;
    bool found = false;
    FILE *maps = fopen("/proc/self/maps", "re");
    if (maps == nullptr) {
        LOGE("failed to open /proc/self/maps");
        return false;
    }

    char *buff = nullptr;
    size_t len = 0;
    ssize_t nread;

    while ((nread = getline(&buff, &len, maps)) != -1) {
        std::string_view line{buff, static_cast<size_t>(nread)};

        if ((contains(line, "r-xp") || contains(line, "r--p")) && contains(line, elf)) {
            if (auto begin = line.find_last_of(' ');
                begin != std::string_view::npos && line[++begin] == '/') {
                found = true;
                elf = line.substr(begin);
                if (!elf.empty() && elf.back() == '\n') elf.pop_back();
                break;
            }
        }
    }
    if (!found) {
        if (buff) free(buff);
        LOGE("failed to read load address for %s", elf.c_str());
        fclose(maps);
        return false;
    }

    {
        char *next = buff;
        load_addr = static_cast<off_t>(strtoul(buff, &next, 16));
        if (next == buff) {
            LOGE("failed to parse load address for %s", elf.c_str());
        }
    }

    if (buff) free(buff);
    fclose(maps);

    base = reinterpret_cast<void *>(load_addr);
    LOGD("libart base: %p (%s)", base, elf.c_str());
    return true;
}
