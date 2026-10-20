// UTF-8 -> UTF-16 conversion of the shims' answers, tested in isolation: the shim .cpp is
// #included (its helpers are file-local), compiled with -DSHIM_VLM or -DSHIM_LLM.
// The shims' stated contract: invalid bytes become U+FFFD, an incomplete final character is
// dropped (vlm) / kept pending (llm), and the result goes to NewString, so it must be valid UTF-16.
#ifdef SHIM_VLM
#include "ultra_vlm.cpp"
static std::u16string conv(const std::string &s) { return utf8ToUtf16(s); }
#else
#include "ultra_llm.cpp"
static std::u16string conv(const std::string &s) { gUtf8Pending.clear(); return takeCompleteUtf16(s); }
#endif
#include <cstdio>

static std::string hex16(const std::u16string &u) {
    std::string o; char b[8];
    for (char16_t c : u) { std::snprintf(b, sizeof b, "%04X ", (unsigned) c); o += b; }
    return o;
}
static bool validUtf16(const std::u16string &u) {
    for (size_t i = 0; i < u.size(); i++) {
        if (u[i] >= 0xD800 && u[i] <= 0xDBFF) { if (i + 1 >= u.size() || u[i + 1] < 0xDC00 || u[i + 1] > 0xDFFF) return false; i++; }
        else if (u[i] >= 0xDC00 && u[i] <= 0xDFFF) return false;
    }
    return true;
}

int main() {
    struct Case { const char *name; std::string in; std::u16string want; };
    const Case cases[] = {
        {"ascii", "ab", u"ab"},
        {"2-byte e-acute", "\xC3\xA9", u"é"},
        {"3-byte euro", "\xE2\x82\xAC", u"€"},
        {"4-byte emoji", "\xF0\x9F\x98\x80", u"\U0001F600"},
        {"lone continuation", "\x84", u"�"},
        {"overlong '9' (C0 B9)", "\xC0\xB9", u"��"},
        {"overlong NUL (C0 80)", "\xC0\x80", u"��"},
        {"overlong 3-byte (E0 80 AF)", "\xE0\x80\xAF", u"���"},
        {"UTF-8 encoded surrogate D800 (ED A0 80)", "\xED\xA0\x80", u"���"},
        {"above U+10FFFF (F4 90 80 80)", "\xF4\x90\x80\x80", u"����"},
        {"F7 BF BF BF (U+1FFFFF)", "\xF7\xBF\xBF\xBF", u"����"},
        {"max valid U+10FFFF", "\xF4\x8F\xBF\xBF", u"\U0010FFFF"},
    };
    int fails = 0;
    for (const auto &c : cases) {
        const std::u16string got = conv(c.in);
        const bool ok = got == c.want && validUtf16(got);
        std::printf("%s %-42s got [%s] valid=%d\n", ok ? "PASS" : "FAIL", c.name, hex16(got).c_str(), (int) validUtf16(got));
        if (!ok) fails++;
    }
    std::printf(fails ? "%d FAILED\n" : "ALL PASS\n", fails);
    return fails;
}
