export const GENOME_TEMPLATES: Record<string, string> = {

'GenomeManager.java': `package {{packageName}};

import android.content.Context;
import android.content.res.AssetManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.MessageDigest;

public class GenomeManager {
    private final Context context;
    private final AgentDatabase db;
    private JSONObject currentGenome;

    public GenomeManager(Context context, AgentDatabase db) {
        this.context = context;
        this.db = db;
    }

    public JSONObject loadGenome() throws Exception {
        String dbGenome = db.loadLatestGenome();
        if (dbGenome != null) {
            currentGenome = new JSONObject(dbGenome);
            return currentGenome;
        }
        AssetManager am = context.getAssets();
        BufferedReader br = new BufferedReader(new InputStreamReader(am.open("genome.json")));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        currentGenome = new JSONObject(sb.toString());
        db.saveGenome(
            currentGenome.getString("id"),
            currentGenome.toString(),
            computeHash(currentGenome.toString())
        );
        return currentGenome;
    }

    public JSONObject getCurrentGenome() { return currentGenome; }

    public void saveGenome(JSONObject genome) throws Exception {
        String json = genome.toString();
        db.saveGenome(genome.getString("id"), json, computeHash(json));
        currentGenome = genome;
    }

    public String serializeForOffspring(JSONObject genome) throws Exception {
        JSONObject offspring = new JSONObject(genome.toString());
        offspring.put("generation", genome.getInt("generation") + 1);
        offspring.put("parentId", genome.getString("id"));
        offspring.put("id", "genome_" + System.currentTimeMillis());
        offspring.put("createdAt", System.currentTimeMillis());

        String parentHash = genome.getString("lineageHash");
        offspring.put("lineageHash", computeHash(parentHash + offspring.toString()));

        return offspring.toString();
    }

    public static String computeHash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "hash_error_" + content.hashCode();
        }
    }
}`,

'GenomeCompilerNative.java': `package {{packageName}};

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.*;

public class GenomeCompilerNative {
    private final Context context;
    private final FileManager fileManager;

    public GenomeCompilerNative(Context context, FileManager fileManager) {
        this.context = context;
        this.fileManager = fileManager;
    }

    public File compile(JSONObject genome, File outputDir) throws Exception {
        outputDir.mkdirs();
        File srcDir = new File(outputDir, "src");
        srcDir.mkdirs();
        File assetsDir = new File(outputDir, "assets");
        assetsDir.mkdirs();

        String packageName = genome.getJSONObject("identity").getString("packageName");
        String pkgPath = packageName.replace('.', '/');
        File pkgDir = new File(srcDir, pkgPath);
        pkgDir.mkdirs();

        JSONArray capabilities = genome.getJSONArray("capabilities");
        for (int i = 0; i < capabilities.length(); i++) {
            JSONObject cap = capabilities.getJSONObject(i);
            JSONArray sources = cap.getJSONArray("sources");
            for (int j = 0; j < sources.length(); j++) {
                JSONObject src = sources.getJSONObject(j);
                String generatedBy = src.getString("generatedBy");
                if ("template".equals(generatedBy)) {
                    String content = src.getString("content");
                    if (content != null && !content.isEmpty()) {
                        String resolved = resolveTemplate(content, genome);
                        String path = src.getString("path");
                        String fileName = path.substring(path.lastIndexOf('/') + 1);
                        fileManager.writeFile(new File(pkgDir, fileName).getAbsolutePath(), resolved);
                    }
                } else if ("fixed".equals(generatedBy)) {
                    String content = src.getString("content");
                    if (content != null && !content.startsWith("FIXED:") && !content.startsWith("LOAD_FROM:")) {
                        String path = src.getString("path");
                        String fileName = path.substring(path.lastIndexOf('/') + 1);
                        String rewritten = rewritePackage(content, packageName);
                        fileManager.writeFile(new File(pkgDir, fileName).getAbsolutePath(), rewritten);
                    }
                }
            }
        }

        JSONArray fixedSources = genome.getJSONArray("fixedSources");
        for (int i = 0; i < fixedSources.length(); i++) {
            JSONObject fs = fixedSources.getJSONObject(i);
            String content = fs.getString("content");
            if (content != null && !content.startsWith("FIXED:") && !content.startsWith("LOAD_FROM:")) {
                String path = fs.getString("path");
                String fileName = path.substring(path.lastIndexOf('/') + 1);
                String rewritten = rewritePackage(content, packageName);
                fileManager.writeFile(new File(pkgDir, fileName).getAbsolutePath(), rewritten);
            }
        }

        JSONObject offspringGenome = new JSONObject(genome.toString());
        offspringGenome.put("generation", genome.getInt("generation") + 1);
        offspringGenome.put("parentId", genome.getString("id"));
        offspringGenome.put("id", "genome_" + System.currentTimeMillis());
        offspringGenome.put("createdAt", System.currentTimeMillis());
        fileManager.writeFile(
            new File(assetsDir, "genome.json").getAbsolutePath(),
            offspringGenome.toString(2)
        );

        return outputDir;
    }

    private String resolveTemplate(String template, JSONObject genome) throws Exception {
        String result = template;
        JSONObject identity = genome.getJSONObject("identity");
        JSONObject ai = genome.getJSONObject("ai");
        JSONObject safety = genome.getJSONObject("safety");

        Pattern eachPattern = Pattern.compile(
            "\\\\{\\\\{#each (\\\\w+)\\\\}\\\\}([\\\\s\\\\S]*?)\\\\{\\\\{/each\\\\}\\\\}", Pattern.DOTALL);
        Matcher eachMatcher = eachPattern.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (eachMatcher.find()) {
            String key = eachMatcher.group(1);
            String body = eachMatcher.group(2);
            JSONArray arr = resolveJsonArray(key, safety);
            StringBuilder expanded = new StringBuilder();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String item = arr.getString(i);
                    String line = body.replace("{{this}}", item);
                    if (i == arr.length() - 1) {
                        line = line.replaceAll(
                            "\\\\{\\\\{#unless @last\\\\}\\\\}[\\\\s\\\\S]*?\\\\{\\\\{/unless\\\\}\\\\}", "");
                    } else {
                        line = line.replaceAll(
                            "\\\\{\\\\{#unless @last\\\\}\\\\}([\\\\s\\\\S]*?)\\\\{\\\\{/unless\\\\}\\\\}", "$1");
                    }
                    expanded.append(line);
                }
            }
            eachMatcher.appendReplacement(sb, Matcher.quoteReplacement(expanded.toString()));
        }
        eachMatcher.appendTail(sb);
        result = sb.toString();

        result = result.replace("{{packageName}}", identity.getString("packageName"));
        result = result.replace("{{agentName}}", identity.getString("name"));
        result = result.replace("{{greeting}}", identity.getString("greeting"));
        result = result.replace("{{apiBaseUrl}}", ai.getString("apiBaseUrl"));
        result = result.replace("{{defaultModel}}", ai.getString("defaultModel"));

        if (ai.getJSONArray("models").length() > 0) {
            result = result.replace("{{defaultMaxTokens}}",
                String.valueOf(ai.getJSONArray("models").getJSONObject(0).getInt("maxTokens")));
        }
        result = result.replace("{{maxRetries}}", String.valueOf(ai.getInt("maxRetries")));
        result = result.replace("{{temperature}}", String.valueOf(ai.getDouble("temperature")));

        result = result.replace("{{backgroundColor}}", "#0F0F1A");
        result = result.replace("{{userBubbleColor}}", "#2A2A3E");
        result = result.replace("{{agentBubbleColor}}", "#1A1A2E");
        result = result.replace("{{textColor}}", "#E0E0E0");
        result = result.replace("{{accentColor}}", "#6C63FF");
        result = result.replace("{{dbName}}", "ultra_agent.db");
        result = result.replace("{{dbVersion}}", "1");

        return result;
    }

    private JSONArray resolveJsonArray(String key, JSONObject safety) {
        try {
            if (safety.has(key)) return safety.getJSONArray(key);
        } catch (Exception ignored) {}
        return null;
    }

    private String rewritePackage(String content, String targetPackage) {
        return content.replaceFirst("^package\\\\s+[\\\\w.]+;", "package " + targetPackage + ";");
    }
}`,

};
