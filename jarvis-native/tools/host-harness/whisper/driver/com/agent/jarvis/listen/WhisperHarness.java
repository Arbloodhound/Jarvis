package com.agent.jarvis.listen;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Drives jarvis_whisper.cpp through the WhisperNative mirror. The model is whisper.cpp's own
 * for-tests-ggml-tiny.en.bin (tiny hparams + vocab, NO tensors: whisper_model_load accepts it
 * "assuming empty model for testing", so the weights are whatever the allocator left), so the
 * text is meaningless; the proof is the JNI path: init, whisper_full, the reply's byte format
 * ("<meanP>\t<text>" [NUL + "W\t..."/"V\t..." lines]), VAD on/off, pinning, free.
 * Exit code = number of failures.
 */
public final class WhisperHarness {
    static final WhisperNative W = WhisperNative.INSTANCE;
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }

    static float[] readWav(String path) throws Exception {
        byte[] b = Files.readAllBytes(Paths.get(path));
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int p = 12;
        while (p + 8 <= b.length) {
            String id = new String(b, p, 4, StandardCharsets.US_ASCII);
            int len = bb.getInt(p + 4);
            if (id.equals("data")) {
                float[] f = new float[len / 2];
                for (int i = 0; i < f.length; i++) f[i] = bb.getShort(p + 8 + 2 * i) / 32768f;
                return f;
            }
            p += 8 + len;
        }
        throw new RuntimeException("no data chunk");
    }

    /** Mirrors WhisperNative.parse's split: head before NUL, "<p>\t<text>", then timing lines. */
    static String describe(byte[] raw) {
        if (raw == null) return "null";
        if (raw.length == 0) return "empty (nothing said)";
        String s = new String(raw, StandardCharsets.UTF_8);
        int nul = s.indexOf('\0');
        String head = nul < 0 ? s : s.substring(0, nul);
        int tab = head.indexOf('\t');
        int words = 0, vads = 0, bad = 0;
        if (nul >= 0) for (String line : s.substring(nul + 1).split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t", 4);
            if (parts[0].equals("W") && parts.length == 4) words++;
            else if (parts[0].equals("V") && parts.length == 3) vads++;
            else bad++;
        }
        return "bytes=" + raw.length + " meanP=" + (tab < 0 ? "?" : head.substring(0, tab)) + " textChars=" + (tab < 0 ? -1 : head.length() - tab - 1)
                + " nul=" + (nul >= 0) + " W=" + words + " V=" + vads + " malformed=" + bad;
    }

    static byte[] run(long h, float[] pcm, int threads, int[] cpus, boolean timing, String label) {
        long t0 = System.nanoTime();
        byte[] r = W.nativeTranscribe(h, pcm, threads, "Um, uh, so, like, you know, I mean, hmm. Jarvis.", cpus, timing);
        System.out.println("  " + label + ": " + describe(r) + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        if (r != null && r.length > 0) {
            String s = new String(r, StandardCharsets.UTF_8);
            int nul = s.indexOf('\0');
            if (nul >= 0) System.out.println("    timing: " + s.substring(nul + 1).replace("\t", "|").replace("\n", " / "));
        }
        return r;
    }

    public static void main(String[] a) throws Exception {
        System.loadLibrary(System.getProperty("lib", "jarvis_whisper"));
        String model = System.getProperty("model"), vad = System.getProperty("vad"), wav = System.getProperty("wav");
        W.nativeFree(0L);
        check(true, "nativeFree(0) returned");
        check(W.nativeInit("/nonexistent/ggml.bin", "") == 0, "bad model path -> 0");

        float[] pcm = readWav(wav);
        float[] silence = new float[16000 * 2];

        long h = W.nativeInit(model, "");
        check(h != 0, "init (VAD off)");
        if (h == 0) System.exit(1);
        byte[] r1 = run(h, pcm, 4, new int[0], false, "jfk timing=off");
        check(r1 != null, "transcribe returned a byte[]");
        check(r1 != null && (r1.length == 0 || new String(r1, StandardCharsets.UTF_8).indexOf('\0') < 0), "timing off: no NUL section");
        byte[] r2 = run(h, pcm, 4, new int[0], true, "jfk timing=on");
        check(r2 != null, "transcribe timing=on returned a byte[]");
        byte[] r3 = run(h, pcm, 2, new int[]{0, 1}, false, "jfk pinned [0,1]");
        check(r3 != null, "pinned [0,1] transcribe returned a byte[]");
        System.out.println("  info: pinned/2 threads output " + (Arrays.equals(r1, r3) ? "==" : "!=") + " unpinned/4 threads output");
        run(h, silence, 4, new int[0], true, "2 s silence timing=on");
        W.nativeFree(h);
        check(true, "free (VAD off)");

        long hv = W.nativeInit(model, vad);
        check(hv != 0, "init (VAD on: " + vad + ")");
        if (hv != 0) {
            byte[] v1 = run(hv, pcm, 4, new int[0], true, "jfk VAD timing=on");
            check(v1 != null, "VAD transcribe returned a byte[]");
            byte[] v2 = run(hv, silence, 4, new int[0], true, "2 s silence VAD timing=on");
            check(v2 != null, "VAD on silence returned a byte[]");
            W.nativeFree(hv);
        }
        long hb = W.nativeInit(model, "/nonexistent/vad.bin");
        check(hb != 0, "init with a bad VAD path still succeeds (VAD loads per transcribe)");
        if (hb != 0) {
            byte[] b1 = run(hb, pcm, 4, new int[0], false, "jfk bad VAD path");
            check(b1 != null, "bad VAD path: transcribe returns a byte[] (empty = failed)");
            W.nativeFree(hb);
        }
        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        System.exit(fails);
    }
}
