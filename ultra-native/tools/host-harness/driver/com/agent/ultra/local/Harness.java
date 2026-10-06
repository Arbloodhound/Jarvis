package com.agent.ultra.local;

import java.nio.file.*;
import java.util.*;

/**
 * Exercises the JNI shim on the host with the tiny random model.
 *  1. sequence: one engine runs A, A, A+B, A+B+C, B, A in order (KV state carries over
 *     between calls if the shim reuses it); a FRESH engine runs each prompt alone.
 *     Every pair must be identical, so any prefix-reuse bug shows up as a MISMATCH.
 *  2. threads: generate with 1 then 4 threads (llama_set_n_threads path).
 *  3. inject: the callback returns "</think>" once, as ThinkBudget does.
 *  4. affinity: Cpus_allowed_list of every thread of this process, sampled during
 *     generation, is printed as "affinity <list> x<count>".
 * Exit code 0 = all checks passed.
 */
public final class Harness {
    static final String MODEL = System.getProperty("model");
    static final int CTX = 512, MAX = 24;
    // HARNESS_CPUS="2,3" pins the engine; unset/empty = unpinned (the old behaviour).
    static final int[] CPUS = parseCpus(System.getenv("HARNESS_CPUS"));
    static int[] parseCpus(String v) {
        if (v == null || v.trim().isEmpty()) return new int[0];
        String[] p = v.trim().split(",");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) r[i] = Integer.parseInt(p[i].trim());
        return r;
    }
    static String readStatus(Path dir) {
        try {
            for (String line : Files.readAllLines(dir.resolve("status")))
                if (line.startsWith("Cpus_allowed_list:")) return line.substring(18).trim();
        } catch (Exception ignored) { }
        return "?";
    }
    static String callerAffinity() { return readStatus(Paths.get("/proc/thread-self")); }
    static final Set<String> callerDuring = new TreeSet<>();
    static int callerNotRestored = 0;

    static String gen(long h, String prompt, int threads, TokenCallback cb) {
        StringBuilder sb = new StringBuilder();
        String before = callerAffinity();
        int n = LlmNative.nativeGenerate(h, prompt, MAX, 0.2f, threads, piece -> {
            synchronized (callerDuring) { callerDuring.add(callerAffinity()); }
            sb.append(piece);
            return cb == null ? null : cb.onToken(piece);
        });
        String after = callerAffinity();
        if (!after.equals(before)) { callerNotRestored++; System.out.println("CALLER NOT RESTORED: " + before + " -> " + after); }
        if (n < 0) throw new RuntimeException("generate returned " + n + " for prompt of " + prompt.length() + " chars");
        return n + ":" + sb;
    }
    interface TokenCallback { String onToken(String piece); }

    static long load(int threads) {
        long h = LlmNative.nativeLoad(MODEL, threads, CTX, CPUS, false);
        if (h == 0) throw new RuntimeException("load failed");
        return h;
    }

    static final Map<String, Integer> affinity = new TreeMap<>();
    static final Map<String, Integer> byThread = new TreeMap<>();
    static final Set<String> preexisting = new HashSet<>();
    static final Map<String, List<String>> perTid = new TreeMap<>();
    static volatile boolean sampling = true;
    static void snapshotTids() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get("/proc/self/task"))) {
            for (Path t : ds) preexisting.add(t.getFileName().toString());
        } catch (Exception ignored) { }
    }
    static void sampleAffinity() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get("/proc/self/task"))) {
            for (Path t : ds) {
                String v = readStatus(t);
                if (v.equals("?")) continue;
                String comm = "?";
                try { comm = new String(Files.readAllBytes(t.resolve("comm"))).trim(); } catch (Exception ignored) { }
                String kind = preexisting.contains(t.getFileName().toString()) ? "jvm-thread" : "new-thread";
                String tid = t.getFileName().toString();
                synchronized (affinity) {
                    affinity.merge(v, 1, Integer::sum);
                    byThread.merge(kind + " comm=" + comm + " cpus=" + v, 1, Integer::sum);
                    if (kind.equals("new-thread") && comm.equals("java")) {
                        List<String> seq = perTid.computeIfAbsent(tid, k -> new ArrayList<>());
                        if (seq.isEmpty() || !seq.get(seq.size() - 1).startsWith(v + " ")) seq.add(v + " x1");
                        else { String last = seq.remove(seq.size() - 1); seq.add(v + " x" + (Integer.parseInt(last.substring(last.indexOf('x') + 1)) + 1)); }
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    public static void main(String[] a) throws Exception {
        System.loadLibrary(System.getProperty("lib", "ultra_llm"));
        System.out.println("pin cpus=" + Arrays.toString(CPUS) + " caller before load=" + callerAffinity());
        int fails = 0;
        Thread sampler = new Thread(() -> { while (sampling) { sampleAffinity(); try { Thread.sleep(2); } catch (InterruptedException e) { return; } } });
        snapshotTids();   // every thread alive now (JVM) predates the engine; the sampler doesn't
        sampler.setDaemon(true); sampler.start();

        String A = "You are Jarvis. Answer briefly and truthfully. ";
        String B = "User: hello, what is the time? ";
        String C = "Assistant: <think> the user asked </think> It is ";
        String[] seq = { A, A, A + B, A + B + C, B, A };

        long h = load(2);
        String[] got = new String[seq.length];
        for (int i = 0; i < seq.length; i++) got[i] = gen(h, seq[i], 2, null);
        for (int i = 0; i < seq.length; i++) {
            long f = load(2);
            String want = gen(f, seq[i], 2, null);
            LlmNative.nativeFree(f);
            boolean ok = want.equals(got[i]);
            if (!ok) fails++;
            System.out.println("seq[" + i + "] " + (ok ? "OK" : "MISMATCH") + "  carried=" + got[i].length() + " fresh=" + want.length()
                + (ok ? "" : "\n   carried: " + got[i] + "\n   fresh:   " + want));
        }

        String t1 = gen(h, A + B, 1, null), t4 = gen(h, A + B, 4, null);
        System.out.println("threads 1 vs 4: " + (t1.equals(t4) ? "same output" : "DIFFERENT (allowed: float order) ") );

        final int[] count = {0};
        String inj = gen(h, A + C, 2, piece -> ++count[0] == 5 ? "</think>" : null);
        System.out.println("inject: ok, " + inj.substring(0, inj.indexOf(':')) + " tokens");

        LlmNative.nativeFree(h);
        sampling = false; sampler.join();
        synchronized (affinity) { affinity.forEach((k, v) -> System.out.println("affinity " + k + " x" + v)); }
        synchronized (affinity) { byThread.forEach((k, v) -> System.out.println("thread " + k + " x" + v)); }
        synchronized (affinity) { perTid.forEach((k, v) -> System.out.println("native tid " + k + " masks over time: " + v)); }
        System.out.println("caller affinity during generation (from the token callback): " + callerDuring);
        System.out.println("caller affinity after everything: " + callerAffinity() + ", not-restored events: " + callerNotRestored);
        fails += callerNotRestored;
        System.out.println(fails == 0 ? "HARNESS PASS" : "HARNESS FAIL (" + fails + " mismatches)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
