// Transitional local-firmware backend, used only in Companion's :optical process.
// Source image is hash-pinned, PAC-adapted 23G71 VisualPairing. It is not a portable
// rewrite of Apple's spatial reader. Public source-only decoders live in core.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <jni.h>
#include <string>
#include <vector>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>

namespace {
std::vector<std::pair<void*, size_t>> allocations;
size_t allocated = 0;
uint64_t guard = 0x91837ba02944c212;
uint32_t ctype[272]{};
const char* stage = "input";
[[noreturn]] void fail() { _exit(72); }
void* allocate(size_t count, uint64_t) {
    if (count > 8 * 1024 * 1024 || allocated + count > 128 * 1024 * 1024) fail();
    void* p = std::calloc(1, std::max(size_t(1), count));
    if (!p) fail();
    allocated += count; allocations.emplace_back(p, count); return p;
}
void release(void*, uint64_t) { } // one bounded run, whole heap erased on exit
struct FloatPair { float sine, cosine; };
FloatPair sincosPair(float value) { return {std::sin(value), std::cos(value)}; }
void zero(void* output, size_t count) { std::memset(output, 0, count); }
void* copy(void* output, const void* input, size_t count) { return std::memcpy(output, input, count); }
uint32_t classify(int character, int mask) { return character >= 'A' && character <= 'Z' ? mask : 0; }
uint32_t u32(const uint8_t* p) { uint32_t x; std::memcpy(&x, p, 4); return x; }
uint64_t u64(const uint8_t* p) { uint64_t x; std::memcpy(&x, p, 8); return x; }
struct Region { uintptr_t address; size_t size; bool executable; };
std::vector<Region> regions;
void region(uintptr_t address, size_t size, bool executable) {
    stage = "memory mapping";
    const size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    uintptr_t base = address & ~(page - 1);
    size = ((address + size + page - 1) & ~(page - 1)) - base;
    // A requested address is only a hint. Refuse any different mapping rather
    // than MAP_FIXED and accidentally overwrite the running Android process.
    void* p = mmap(reinterpret_cast<void*>(base), size, PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p != reinterpret_cast<void*>(base)) { if (p != MAP_FAILED) munmap(p, size); fail(); }
    regions.push_back({base, size, executable});
}
template<typename T> void stub(uintptr_t address, T function) {
    uint32_t code[] = {0x58000050, 0xd61f0200}; // ldr x16,#8; br x16
    std::memcpy(reinterpret_cast<void*>(address), code, 8);
    auto target = reinterpret_cast<uintptr_t>(function);
    std::memcpy(reinterpret_cast<void*>(address + 8), &target, 8);
}
}

namespace {
void load(const std::vector<uint8_t>& macho) {
    if (macho.size() < 32 || macho.size() > 8 * 1024 * 1024 || u32(macho.data()) != 0xfeedfacf
            || u32(macho.data() + 4) != 0x100000c) fail();
    size_t position = 32;
    for (uint32_t i = 0; i < u32(macho.data() + 16); i++) {
        stage = "Mach-O segment bounds";
        if (position + 8 > macho.size()) fail();
        uint32_t command = u32(macho.data() + position), size = u32(macho.data() + position + 4);
        if (size < 8 || position + size > macho.size()) fail();
        if (command == 0x19) {
            if (size < 72) fail();
            auto p = macho.data() + position;
            uintptr_t address = u64(p + 24);
            size_t length = u64(p + 32), offset = u64(p + 40), fileSize = u64(p + 48);
            if (fileSize && address) {
                if (length > 16 * 1024 * 1024 || fileSize > length || offset > macho.size()
                        || fileSize > macho.size() - offset || address < 0x29acb0000 || address > 0x2b5478000) fail();
                region(address, length, std::memcmp(p + 8, "__TEXT", 6) == 0);
                std::memcpy(reinterpret_cast<void*>(address), macho.data() + offset, fileSize);
            }
        }
        position += size;
    }
    region(0x29bd07000, 4096, true);
    region(0x2adc4f000, 4096, false);
    *reinterpret_cast<uint64_t**>(0x2adc4f468) = &guard;
    *reinterpret_cast<uint32_t**>(0x2adc4f460) = ctype;
    for (int c = 'A'; c <= 'Z'; c++) ctype[15 + c] = 0x40000;
    stub(0x29acd1ad4, allocate); stub(0x29acd1ae4, allocate);
    stub(0x29acd1ba4, copy);
    stub(0x29bd074a0, release); stub(0x29bd074b0, release);
    stub(0x29bd07510, zero); stub(0x29bd074f0, sincosPair);
    stub(0x29bd074e0, classify); stub(0x29bd07500, fail);
    for (auto r : regions) {
        stage = "executable protection";
        if (r.executable) {
            __builtin___clear_cache(reinterpret_cast<char*>(r.address), reinterpret_cast<char*>(r.address + r.size));
            if (mprotect(reinterpret_cast<void*>(r.address), r.size, PROT_READ | PROT_EXEC) != 0) fail();
        }
    }
}
}

namespace {
alignas(16) uint8_t reader[48]{};
bool active = false;
unsigned frames = 0;
void erase(void* pointer, size_t size) {
    volatile uint8_t* p = static_cast<uint8_t*>(pointer);
    for (size_t i = 0; i < size; i++) p[i] = 0;
}
void destroy(JNIEnv*, jobject) {
    for (auto allocation : allocations) { erase(allocation.first, allocation.second); std::free(allocation.first); }
    allocations.clear(); allocated = 0;
    for (auto r : regions) munmap(reinterpret_cast<void*>(r.address), r.size);
    regions.clear(); erase(reader, sizeof(reader)); active = false; frames = 0;
}
jboolean initialize(JNIEnv* env, jobject self, jbyteArray image, jint width, jint height) {
    destroy(env, self);
    if (!image || width < 16 || width > 960 || (width % 16) || height < 16 || height > 540) return JNI_FALSE;
    jsize length = env->GetArrayLength(image);
    if (length < 32 || length > 8 * 1024 * 1024) return JNI_FALSE;
    std::vector<uint8_t> macho(length);
    env->GetByteArrayRegion(image, 0, length, reinterpret_cast<jbyte*>(macho.data()));
    if (env->ExceptionCheck()) return JNI_FALSE;
    load(macho);
    uint32_t config[] = {0, static_cast<uint32_t>(height), static_cast<uint32_t>(width), static_cast<uint32_t>(width * 2), 1};
    std::memcpy(reader, config, sizeof(config));
    using Reset = int(*)(void*);
    if (reinterpret_cast<Reset>(0x29acbedd0)(reader)) { destroy(env, self); return JNI_FALSE; }
    active = true; return JNI_TRUE;
}
jbyteArray process(JNIEnv* env, jobject, jbyteArray frame) {
    if (!active || !frame || frames >= 90 || u64(reader + 32)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Optical session unavailable"); return nullptr;
    }
    size_t length = u32(reader + 4) * u32(reader + 8) * 2;
    if (static_cast<size_t>(env->GetArrayLength(frame)) != length) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid optical frame"); return nullptr;
    }
    std::vector<uint8_t> uv(length);
    env->GetByteArrayRegion(frame, 0, length, reinterpret_cast<jbyte*>(uv.data()));
    if (env->ExceptionCheck()) return nullptr;
    using Process = int(*)(void*, void*, unsigned, unsigned, unsigned, void*);
    uint8_t detected[8]{};
    int status = reinterpret_cast<Process>(0x29acbf358)(reader, uv.data(),
            u32(reader + 4), u32(reader + 8), u32(reader + 12), detected);
    erase(uv.data(), uv.size()); frames++;
    if (status) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Optical reader rejected frame"); return nullptr;
    }
    if (!u64(reader + 32)) return nullptr;
    jbyteArray output = env->NewByteArray(110);
    if (output) env->SetByteArrayRegion(output, 0, 110, reinterpret_cast<jbyte*>(u64(reader + 32)));
    return output;
}
}
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass type = env->FindClass("dev/applewatchandroid/companion/apple_watch_companion/OpticalNativeReader");
    if (!type) return JNI_ERR;
    JNINativeMethod methods[] = {
        {const_cast<char*>("initialize"), const_cast<char*>("([BII)Z"), reinterpret_cast<void*>(initialize)},
        {const_cast<char*>("process"), const_cast<char*>("([B)[B"), reinterpret_cast<void*>(process)},
        {const_cast<char*>("destroy"), const_cast<char*>("()V"), reinterpret_cast<void*>(destroy)}
    };
    if (env->RegisterNatives(type, methods, 3) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
