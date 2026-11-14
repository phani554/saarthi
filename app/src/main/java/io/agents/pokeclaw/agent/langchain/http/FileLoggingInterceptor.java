// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.langchain.http;

import io.agents.pokeclaw.utils.XLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * OkHttp interceptor: writes raw request data, curl command, and raw response to individual files
 * in the sandbox cache directory.
 * <p>
 * File path: {cacheDir}/http_logs/yyyyMMdd_HHmmssSSS_{method}.txt
 */
public class FileLoggingInterceptor implements Interceptor {

    private static final String TAG = "FileLoggingInterceptor";

    private static final int MAX_LOG_FILES = 50;

    private final File logDir;

    /** Headers that carry API keys (OpenAI/OpenRouter Bearer, Anthropic, Sarvam, Gemini). */
    private static boolean isSecretHeader(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.equals("authorization") || n.equals("proxy-authorization") || n.equals("x-api-key")
                || n.equals("api-key") || n.equals("api-subscription-key") || n.equals("x-goog-api-key")
                || n.contains("api-key") || n.contains("token");
    }

    private static String maskHeader(String name, String value) {
        if (!isSecretHeader(name) || value == null) return value;
        String v = value;
        String prefix = "";
        if (v.regionMatches(true, 0, "Bearer ", 0, 7)) {
            prefix = v.substring(0, 7);
            v = v.substring(7);
        }
        return prefix + (v.length() > 8 ? v.substring(0, 4) + "****" : "****");
    }

    /** Redacts key=/api_key= style query parameters (Gemini uses ?key=). */
    static String maskUrl(String url) {
        return url.replaceAll("(?i)([?&](?:key|api_key|apikey|access_token|token)=)[^&]*", "$1****");
    }

    public FileLoggingInterceptor(File cacheDir) {
        this.logDir = new File(cacheDir, "http_logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        // Read request body
        String requestBodyStr = "";
        if (request.body() != null) {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            requestBodyStr = buffer.readUtf8();
        }

        // Build curl command
        String curl = buildCurl(request, requestBodyStr);

        // Execute request
        long startMs = System.nanoTime();
        Response response;
        try {
            response = chain.proceed(request);
        } catch (IOException e) {
            // Log even on request failure
            writeToFile(request, requestBodyStr, curl, null, -1, e.toString(), 0);
            throw e;
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startMs);

        // Read response body (needs to be re-wrapped for subsequent consumption)
        String responseBodyStr = "";
        ResponseBody responseBody = response.body();
        MediaType bodyType = responseBody != null ? responseBody.contentType() : null;
        if (bodyType != null && "event-stream".equalsIgnoreCase(bodyType.subtype())) {
            // Buffering an SSE stream would make streaming wait for the whole reply.
            responseBodyStr = "(streaming body not logged)";
        } else if (responseBody != null) {
            MediaType contentType = responseBody.contentType();
            responseBodyStr = responseBody.string();
            // Re-wrap ResponseBody because string() can only be consumed once
            response = response.newBuilder()
                    .body(ResponseBody.create(contentType, responseBodyStr))
                    .build();
        }

        writeToFile(request, requestBodyStr, curl, response, response.code(), responseBodyStr, durationMs);

        return response;
    }

    private String buildCurl(Request request, String body) {
        StringBuilder sb = new StringBuilder("curl -X ").append(request.method());

        // Headers
        for (int i = 0; i < request.headers().size(); i++) {
            String name = request.headers().name(i);
            String value = request.headers().value(i);
            // Mask every header that carries a key
            value = maskHeader(name, value);
            sb.append(" \\\n  -H '").append(name).append(": ").append(value).append("'");
        }

        // Body
        if (body != null && !body.isEmpty()) {
            // Truncate overly long body; keep only the first 2000 characters in curl
            String truncated = body.length() > 2000 ? body.substring(0, 2000) + "...[TRUNCATED]" : body;
            sb.append(" \\\n  -d '").append(truncated.replace("'", "'\\''")).append("'");
        }

        sb.append(" \\\n  '").append(maskUrl(request.url().toString())).append("'");
        return sb.toString();
    }

    /** Keep only the newest MAX_LOG_FILES logs (names start with a sortable timestamp). */
    private void pruneOldLogs() {
        File[] files = logDir.listFiles();
        if (files == null || files.length <= MAX_LOG_FILES) return;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (int i = 0; i < files.length - MAX_LOG_FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    private void writeToFile(Request request, String requestBody, String curl,
                             Response response, int statusCode, String responseBody,
                             long durationMs) {
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(new Date());
            String fileName = timestamp + "_" + request.method() + ".txt";
            File file = new File(logDir, fileName);

            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                writer.write("==================== REQUEST ====================\n");
                writer.write("URL: " + maskUrl(request.url().toString()) + "\n");
                writer.write("Method: " + request.method() + "\n");
                writer.write("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + "\n");
                writer.write("\n--- Headers ---\n");
                for (int i = 0; i < request.headers().size(); i++) {
                    String hName = request.headers().name(i);
                    writer.write(hName + ": " + maskHeader(hName, request.headers().value(i)) + "\n");
                }
                writer.write("\n--- Body ---\n");
                writer.write(requestBody.isEmpty() ? "(empty)\n" : requestBody + "\n");

                writer.write("\n==================== CURL ====================\n");
                writer.write(curl + "\n");

                writer.write("\n==================== RESPONSE ====================\n");
                writer.write("Status: " + statusCode + "\n");
                writer.write("Duration: " + durationMs + "ms\n");
                if (response != null) {
                    writer.write("\n--- Headers ---\n");
                    for (int i = 0; i < response.headers().size(); i++) {
                        writer.write(response.headers().name(i) + ": " + response.headers().value(i) + "\n");
                    }
                }
                writer.write("\n--- Body ---\n");
                writer.write(responseBody.isEmpty() ? "(empty)\n" : responseBody + "\n");
            }

            XLog.d(TAG, "Log written to: " + file.getAbsolutePath());
            pruneOldLogs();
        } catch (Exception e) {
            XLog.e(TAG, "Failed to write log file", e);
        }
    }
}
