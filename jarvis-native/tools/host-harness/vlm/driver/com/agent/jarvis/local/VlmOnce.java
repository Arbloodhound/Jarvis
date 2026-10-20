package com.agent.jarvis.local;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * One describe of the 300x600 test pattern (same pixels as make_png.py's img300x600.png),
 * answer written as UTF-8 to -Dout. For the token-for-token comparison with llama-mtmd-cli
 * (compare_cli.sh): same image, same chat-templated prompt, same sampler chain and seed.
 */
public final class VlmOnce {
    public static void main(String[] a) throws Exception {
        System.loadLibrary(System.getProperty("lib", "jarvis_vlm"));
        VisionNative v = VisionNative.INSTANCE;
        long h = v.nativeVlmLoad(System.getProperty("model"), System.getProperty("mmproj"), 4, 4096, new int[0]);
        if (h == 0) { System.out.println("load failed"); System.exit(1); }
        String out = v.nativeVlmDescribe(h, VlmHarness.image(300, 600, 0), 300, 600,
                System.getProperty("prompt"), Integer.getInteger("max", 32), 4);
        v.nativeVlmFree(h);
        if (out == null) { System.out.println("describe failed"); System.exit(1); }
        Files.write(Paths.get(System.getProperty("out")), out.getBytes(StandardCharsets.UTF_8));
        System.out.println("chars=" + out.length());
    }
}
