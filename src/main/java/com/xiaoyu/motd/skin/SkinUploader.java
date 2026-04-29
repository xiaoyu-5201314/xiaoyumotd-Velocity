package com.xiaoyu.motd.skin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SkinUploader {
    private static final String QUEUE_URL = "https://api.mineskin.org/v2/queue";
    private static final String VARIANT = "classic";
    private static final String VISIBILITY = "public";
    private static final int MAX_POLL_MINUTES = 30;
    private static final String USER_AGENT = "motd-maker/1.0 (+java)";
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<String>(Arrays.asList(".png", ".jpg", ".jpeg", ".webp"));
    private static final Set<String> FINAL_FAILURE_STATUSES = new HashSet<String>(Arrays.asList("failed", "error", "cancelled", "canceled"));

    public static UploadSkinsResult uploadSkins(UploadSkinsOptions options, String apiKey, ProgressCallback callback, boolean[] cancelFlag) throws IOException, InterruptedException {
        String tmpDir = options.tmpDir() != null ? options.tmpDir() : "tmp";
        String outputPath = options.outputPath() != null ? options.outputPath() : "uploaded_skins.txt";
        List<File> files = getSkinFiles(tmpDir);
        if (files.isEmpty()) {
            throw new IOException("在 " + tmpDir + " 中未找到图片文件。");
        }
        Path path = Paths.get(outputPath);
        Files.write(path, new byte[0]);
        ArrayList<FileResult> results = new ArrayList<FileResult>();
        for (int i = 0; i < files.size(); ++i) {
            results.add(new FileResult("pending", null));
        }
        ConcurrentHashMap<String, SubmittedJob> pendingJobs = new ConcurrentHashMap<String, SubmittedJob>();
        RateLimiter rateLimiter = new RateLimiter();
        Object syncLock = new Object();
        boolean[] submissionComplete = new boolean[]{false};
        Thread pollerThread = new Thread(() -> {
            try {
                pollQueuedJobs(pendingJobs, results, apiKey, rateLimiter, callback, submissionComplete, syncLock, cancelFlag);
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        pollerThread.start();
        for (int index = 0; index < files.size() && !cancelFlag[0]; ++index) {
            File file = files.get(index);
            String fileName = file.getName();
            if (callback != null) {
                callback.onProgress("上传进度: " + (index + 1) + "/" + files.size());
            }
            try {
                SubmitResult submit = submitQueueJob(file, apiKey, rateLimiter);
                if (submit.skinUrl != null) {
                    results.set(index, new FileResult("success", submit.skinUrl));
                    continue;
                }
                if (FINAL_FAILURE_STATUSES.contains(submit.status)) {
                    results.set(index, new FileResult("failed", null));
                    continue;
                }
                pendingJobs.put(submit.jobId, new SubmittedJob(index, fileName, System.currentTimeMillis()));
                synchronized (syncLock) {
                    syncLock.notify();
                    continue;
                }
            }
            catch (Exception e) {
                results.set(index, new FileResult("failed", null));
                if (callback == null) continue;
                callback.onProgress("发生错误: " + fileName + " - " + e.getMessage());
            }
        }
        synchronized (syncLock) {
            submissionComplete[0] = true;
            syncLock.notify();
        }
        pollerThread.join();
        ArrayList<String> orderedUrls = new ArrayList<String>();
        int successfulCount = 0;
        for (FileResult result : results) {
            if (!"success".equals(result.status) || result.skinUrl == null) continue;
            orderedUrls.add(result.skinUrl);
            ++successfulCount;
        }
        if (!orderedUrls.isEmpty()) {
            Files.writeString(path, String.join(System.lineSeparator(), orderedUrls), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        if (callback != null) {
            callback.onProgress("上传完成: " + successfulCount + "/" + files.size());
        }
        return new UploadSkinsResult(outputPath, files.size(), successfulCount, cancelFlag[0]);
    }

    private static List<File> getSkinFiles(String dirPath) throws IOException {
        try (Stream<Path> stream = Files.walk(Paths.get(dirPath))) {
            return stream.filter(Files::isRegularFile).map(Path::toFile).filter(file -> {
                String name = file.getName().toLowerCase();
                return IMAGE_EXTENSIONS.stream().anyMatch(name::endsWith);
            }).sorted((f1, f2) -> {
                String name1 = f1.getName();
                String name2 = f2.getName();
                try {
                    int num1 = extractNumber(name1);
                    int num2 = extractNumber(name2);
                    return Integer.compare(num1, num2);
                }
                catch (NumberFormatException e) {
                    return name1.compareTo(name2);
                }
            }).collect(Collectors.toList());
        }
    }

    private static int extractNumber(String fileName) {
        String nameWithoutExt = fileName.substring(0, fileName.lastIndexOf('.'));
        String numberPart = nameWithoutExt.substring(nameWithoutExt.lastIndexOf('_') + 1);
        return Integer.parseInt(numberPart);
    }

    private static SubmitResult submitQueueJob(File file, String apiKey, RateLimiter rateLimiter) throws IOException, InterruptedException {
        int responseCode;
        rateLimiter.waitForTurn();
        String boundary = "----Boundary" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection)URI.create(QUEUE_URL).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("MineSkin-User-Agent", USER_AGENT);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream os = conn.getOutputStream();
             PrintWriter writer = new PrintWriter((Writer)new OutputStreamWriter(os, StandardCharsets.UTF_8), true);){
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"variant\"\r\n\r\n");
            writer.append(VARIANT).append("\r\n");
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"visibility\"\r\n\r\n");
            writer.append(VISIBILITY).append("\r\n");
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"name\"\r\n\r\n");
            writer.append(file.getName().substring(0, Math.min(20, file.getName().length()))).append("\r\n");
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(file.getName()).append("\"\r\n");
            writer.append("Content-Type: image/png\r\n\r\n");
            writer.flush();
            Files.copy(file.toPath(), os);
            os.flush();
            writer.append("\r\n");
            writer.append("--").append(boundary).append("--").append("\r\n");
            writer.flush();
        }
        String response = readResponse(conn);
        JsonObject parsed = JsonParser.parseString(response).getAsJsonObject();
        if (parsed.has("rateLimit")) {
            rateLimiter.updateFromRateLimit(parsed.getAsJsonObject("rateLimit"));
        }
        if ((responseCode = conn.getResponseCode()) != 200 && responseCode != 202) {
            throw new IOException("提交队列失败: " + file.getName() + " 状态码: " + conn.getResponseCode());
        }
        String jobId = null;
        if (parsed.has("job") && parsed.getAsJsonObject("job").has("id")) {
            jobId = parsed.getAsJsonObject("job").get("id").getAsString();
        }
        if (jobId == null) {
            throw new IOException("提交成功但未返回任务编号: " + file.getName());
        }
        String status = parsed.has("job") && parsed.getAsJsonObject("job").has("status") ? parsed.getAsJsonObject("job").get("status").getAsString().toLowerCase() : "unknown";
        String skinUrl = extractSkinUrl(parsed);
        return new SubmitResult(jobId, status, skinUrl);
    }

    private static void pollQueuedJobs(Map<String, SubmittedJob> pendingJobs, List<FileResult> results, String apiKey, RateLimiter rateLimiter, ProgressCallback callback, boolean[] submissionComplete, Object lockObject, boolean[] cancelFlag) throws InterruptedException {
        long lastProgressTime = 0L;
        int totalJobs = results.size();
        while (!cancelFlag[0]) {
            if (pendingJobs.isEmpty()) {
                synchronized (lockObject) {
                    if (submissionComplete[0]) {
                        break;
                    }
                    lockObject.wait(1000L);
                    continue;
                }
            }
            ArrayList<String> jobIds = new ArrayList<String>(pendingJobs.keySet());
            for (String jobId : jobIds) {
                SubmittedJob submitted = pendingJobs.get(jobId);
                if (submitted == null) continue;
                if (System.currentTimeMillis() - submitted.submittedAt > 1800000L) {
                    pendingJobs.remove(jobId);
                    results.set(submitted.index, new FileResult("failed", null));
                    if (callback == null) continue;
                    callback.onProgress("上传超时: " + submitted.fileName);
                    continue;
                }
                try {
                    JsonObject jobResponse = fetchQueueJob(apiKey, jobId, rateLimiter);
                    String status = jobResponse.has("job") && jobResponse.getAsJsonObject("job").has("status") ? jobResponse.getAsJsonObject("job").get("status").getAsString().toLowerCase() : "unknown";
                    String skinUrl = extractSkinUrl(jobResponse);
                    if (skinUrl != null) {
                        pendingJobs.remove(jobId);
                        results.set(submitted.index, new FileResult("success", skinUrl));
                        continue;
                    }
                    if (!FINAL_FAILURE_STATUSES.contains(status)) continue;
                    pendingJobs.remove(jobId);
                    results.set(submitted.index, new FileResult("failed", null));
                    if (callback == null) continue;
                    callback.onProgress("上传超时: " + submitted.fileName);
                }
                catch (Exception e) {
                    if (callback == null) continue;
                    callback.onProgress("上传超时: " + submitted.fileName);
                }
            }
            if (pendingJobs.isEmpty()) continue;
            long completedCount = results.stream().filter(r -> !"pending".equals(r.status)).count();
            long currentTime = System.currentTimeMillis();
            if (callback != null && currentTime - lastProgressTime > 5000L) {
                callback.onProgress("上传队列: " + completedCount + "/" + totalJobs);
                lastProgressTime = currentTime;
            }
            synchronized (lockObject) {
                lockObject.wait(5000L);
            }
        }
    }

    private static JsonObject fetchQueueJob(String apiKey, String jobId, RateLimiter rateLimiter) throws IOException, InterruptedException {
        rateLimiter.waitForTurn();
        HttpURLConnection conn = (HttpURLConnection)URI.create("https://api.mineskin.org/v2/queue/" + jobId).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("MineSkin-User-Agent", USER_AGENT);
        String response = readResponse(conn);
        JsonObject parsed = JsonParser.parseString(response).getAsJsonObject();
        if (parsed.has("rateLimit")) {
            rateLimiter.updateFromRateLimit(parsed.getAsJsonObject("rateLimit"));
        }
        if (conn.getResponseCode() != 200) {
            throw new IOException("获取任务状态失败: " + jobId + " 状态码: " + conn.getResponseCode());
        }
        return parsed;
    }

    private static String extractSkinUrl(JsonObject json) {
        // Try to get from root "skin" object
        if (json.has("skin") && !json.get("skin").isJsonNull()) {
            JsonObject skin = json.getAsJsonObject("skin");
            String url = tryGetUrl(skin);
            if (url != null) return url;
        }
        
        // Try to get from "job" -> "skin" object
        if (json.has("job") && !json.get("job").isJsonNull()) {
            JsonObject job = json.getAsJsonObject("job");
            if (job.has("skin") && !job.get("skin").isJsonNull()) {
                return tryGetUrl(job.getAsJsonObject("skin"));
            }
        }
        return null;
    }

    private static String tryGetUrl(JsonObject skinObj) {
        // Direct url field
        if (skinObj.has("url") && !skinObj.get("url").isJsonNull()) {
            return skinObj.get("url").getAsString();
        }
        // Nested in data -> texture -> url
        if (skinObj.has("data") && !skinObj.get("data").isJsonNull()) {
            JsonObject data = skinObj.getAsJsonObject("data");
            if (data.has("texture") && !data.get("texture").isJsonNull()) {
                JsonObject texture = data.getAsJsonObject("texture");
                if (texture.has("url") && !texture.get("url").isJsonNull()) {
                    return texture.get("url").getAsString();
                }
            }
        }
        // Nested in texture -> url -> skin
        if (skinObj.has("texture") && !skinObj.get("texture").isJsonNull()) {
            JsonObject texture = skinObj.getAsJsonObject("texture");
            if (texture.has("url") && !texture.get("url").isJsonNull()) {
                JsonObject urlObj = texture.getAsJsonObject("url");
                if (urlObj.has("skin") && !urlObj.get("skin").isJsonNull()) {
                    return urlObj.get("skin").getAsString();
                }
            }
        }
        return null;
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream(), 
                StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    public record UploadSkinsOptions(String tmpDir, String outputPath) {
    }

    private static class FileResult {
        String status;
        String skinUrl;

        FileResult(String status, String skinUrl) {
            this.status = status;
            this.skinUrl = skinUrl;
        }
    }

    private static class RateLimiter {
        private long nextRequestAt = 0L;

        private RateLimiter() {
        }

        synchronized void waitForTurn() throws InterruptedException {
            long delayMs = Math.max(0L, this.nextRequestAt - System.currentTimeMillis());
            if (delayMs > 0L) {
                Thread.sleep(delayMs);
            }
        }

        synchronized void updateFromRateLimit(JsonObject rateLimit) {
            if (rateLimit == null) {
                return;
            }
            long absoluteTarget = 0L;
            long relativeTarget = 0L;
            long millisTarget = 0L;
            long secondsTarget = 0L;
            if (rateLimit.has("next") && rateLimit.get("next").isJsonObject()) {
                JsonObject next = rateLimit.getAsJsonObject("next");
                if (next.has("absolute")) {
                    absoluteTarget = next.get("absolute").getAsLong();
                }
                if (next.has("relative")) {
                    relativeTarget = System.currentTimeMillis() + next.get("relative").getAsLong();
                }
            }
            if (rateLimit.has("delay") && rateLimit.get("delay").isJsonObject()) {
                JsonObject delay = rateLimit.getAsJsonObject("delay");
                if (delay.has("millis")) {
                    millisTarget = System.currentTimeMillis() + delay.get("millis").getAsLong();
                }
                if (delay.has("seconds")) {
                    secondsTarget = System.currentTimeMillis() + delay.get("seconds").getAsLong() * 1000L;
                }
            }
            this.nextRequestAt = Math.max(this.nextRequestAt, Math.max(absoluteTarget, Math.max(relativeTarget, Math.max(millisTarget, secondsTarget))));
        }
    }

    @FunctionalInterface
    public static interface ProgressCallback {
        public void onProgress(String var1);
    }

    private static class SubmitResult {
        String jobId;
        String status;
        String skinUrl;

        SubmitResult(String jobId, String status, String skinUrl) {
            this.jobId = jobId;
            this.status = status;
            this.skinUrl = skinUrl;
        }
    }

    private static class SubmittedJob {
        int index;
        String fileName;
        long submittedAt;

        SubmittedJob(int index, String fileName, long submittedAt) {
            this.index = index;
            this.fileName = fileName;
            this.submittedAt = submittedAt;
        }
    }

    public record UploadSkinsResult(String outputPath, int submittedCount, int successfulCount, boolean cancelled) {
    }
}

