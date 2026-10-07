package com.agent.ultra.local;

import java.util.*;

/**
 * Drives ultra_vlm.cpp through the VisionNative mirror with the tiny random Qwen2.5-VL pair.
 * Checks (exit code = number of failures):
 *  - nativeVlmFree(0) is a no-op (VisionNative.available relies on it)
 *  - bad mmproj path -> 0; mmproj that is not a projector (the text GGUF) -> 0
 *  - RGB shorter than w*h*3 -> null
 *  - the same describe twice on one handle gives the identical answer (KV cleared, positions
 *    restart at 0; a stale KV would make the M-RoPE position check fail or change the answer)
 *  - a second, different image (403x896, VisionModel's scaled phone screenshot) then the first
 *    image again: identical to the first answer
 *  - pinned engine (cpus [0]) loads and describes
 *  - answers are valid Java strings (no lone surrogates), length printed
 * -Dmodel, -Dmmproj, -Diters (repeat count for the leak run).
 */
public final class VlmHarness {
    static final String MODEL = System.getProperty("model", "/tmp/jarvis-harness/vlm/tiny-qwen25vl.gguf");
    static final String MMPROJ = System.getProperty("mmproj", "/tmp/jarvis-harness/vlm/tiny-qwen25vl-mmproj.gguf");
    static final VisionNative V = VisionNative.INSTANCE;
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    static byte[] image(int w, int h, int seed) {
        byte[] b = new byte[w * h * 3];
        int o = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            b[o++] = (byte) ((x * 255 / w + seed) & 0xFF);
            b[o++] = (byte) ((y * 255 / h) & 0xFF);
            b[o++] = (byte) (((x / 20 + y / 20) % 2 == 0) ? 230 : 25);
        }
        return b;
    }

    static long rssKb() {
        try {
            for (String l : java.nio.file.Files.readAllLines(java.nio.file.Paths.get("/proc/self/status")))
                if (l.startsWith("VmRSS:")) return Long.parseLong(l.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) { }
        return -1;
    }

    static boolean validUtf16(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) { if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return false; i++; }
            else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    static String describe(long h, byte[] rgb, int w, int hh, String prompt, int max) {
        long t0 = System.nanoTime();
        String out = V.nativeVlmDescribe(h, rgb, w, hh, prompt, max, 4);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  describe " + w + "x" + hh + " max=" + max + " -> " + (out == null ? "null" : ("len=" + out.length()
                + " cps=" + out.codePointCount(0, out.length()) + " validUtf16=" + validUtf16(out))) + " in " + ms + " ms");
        return out;
    }

    public static void main(String[] a) {
        System.loadLibrary(System.getProperty("lib", "ultra_vlm"));
        int iters = Integer.getInteger("iters", 1);

        V.nativeVlmFree(0L);
        check(true, "nativeVlmFree(0) returned");

        check(V.nativeVlmLoad(MODEL, "/nonexistent/mmproj.gguf", 4, 4096, new int[0]) == 0, "bad mmproj path -> 0");
        check(V.nativeVlmLoad(MODEL, MODEL, 4, 4096, new int[0]) == 0, "text GGUF as mmproj -> 0");
        check(V.nativeVlmLoad("/nonexistent/model.gguf", MMPROJ, 4, 4096, new int[0]) == 0, "bad model path -> 0");

        long h = V.nativeVlmLoad(MODEL, MMPROJ, 4, 4096, new int[0]);
        check(h != 0, "load tiny Qwen2.5-VL pair -> handle");
        if (h == 0) System.exit(1);

        byte[] img1 = image(300, 600, 0);
        check(V.nativeVlmDescribe(h, Arrays.copyOf(img1, img1.length - 1), 300, 600, "x", 8, 4) == null, "RGB one byte short -> null");

        String prompt = "Describe this screen. What app is open? 😀";
        long rss0 = 0;
        for (int it = 0; it < iters; it++) {
            if (it == 1) rss0 = rssKb();   // after the first round: allocator and graph caches warm
            if (it > 0 && it % 20 == 0) System.out.println("  RSS at round " + it + ": " + rssKb() + " kB");
            String r1 = describe(h, img1, 300, 600, prompt, 48);
            String r2 = describe(h, img1, 300, 600, prompt, 48);
            check(r1 != null && r2 != null, "two describes returned strings");
            check(r1 != null && r1.equals(r2), "same input twice -> identical answer (KV cleared between calls)");
            byte[] img2 = image(403, 896, 77);
            String r3 = describe(h, img2, 403, 896, "What is shown?", 32);
            check(r3 != null, "403x896 describe returned a string");
            String r4 = describe(h, img1, 300, 600, prompt, 48);
            check(r1 != null && r1.equals(r4), "first image again after a different image -> identical answer");
            String r5 = describe(h, img1, 300, 600, "<__media__>\nmarker already in prompt", 4);
            check(r5 != null, "prompt with its own marker -> string");
            if (it == 0 && r1 != null) System.out.println("  sample answer: " + r1.replace("\n", "\\n"));
        }
        if (iters > 1) System.out.println("  RSS after round 1: " + rss0 + " kB, after round " + iters + ": " + rssKb() + " kB ("
                + (iters - 1) * 5 + " describes in between)");
        V.nativeVlmFree(h);
        check(true, "free");

        long hp = V.nativeVlmLoad(MODEL, MMPROJ, 2, 2048, new int[]{0, 1});
        check(hp != 0, "pinned load (cpus [0,1], 2 threads)");
        if (hp != 0) {
            check(describe(hp, img1, 300, 600, "pinned", 16) != null, "pinned describe");
            V.nativeVlmFree(hp);
        }
        int cycles = Integer.getInteger("loadCycles", 0);
        long c0 = 0;
        for (int c = 0; c < cycles; c++) {
            if (c == 1) c0 = rssKb();
            long hc = V.nativeVlmLoad(MODEL, MMPROJ, 4, 4096, new int[0]);
            if (hc == 0) { check(false, "load cycle " + c); break; }
            V.nativeVlmDescribe(hc, img1, 300, 600, "cycle", 4, 4);
            V.nativeVlmFree(hc);
        }
        if (cycles > 1) System.out.println("  RSS after load cycle 1: " + c0 + " kB, after " + cycles + ": " + rssKb() + " kB");
        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        System.exit(fails);
    }
}
