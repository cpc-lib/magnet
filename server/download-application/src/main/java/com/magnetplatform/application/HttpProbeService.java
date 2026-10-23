package com.magnetplatform.application;

import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP/HTTPS 直链资源探活与元数据提取服务（规格 §4.2）。
 */
@Service
public class HttpProbeService {

    private static final Logger log = LoggerFactory.getLogger(HttpProbeService.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern FILENAME_STAR_PATTERN = Pattern.compile("filename\\*=(?:UTF-8''|utf-8'')?([^;\\r\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_PATTERN = Pattern.compile("filename=\"?([^\"\\r\\n;]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_RANGE_PATTERN = Pattern.compile("bytes\\s+\\d+-\\d+/(\\d+)", Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient;

    public HttpProbeService() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(PROBE_TIMEOUT)
                .build());
    }

    public HttpProbeService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public record ProbeResult(String filename, long contentLength) {
    }

    public ProbeResult probe(String uriString) {
        URI uri;
        try {
            uri = URI.create(uriString);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new DomainException(ErrorCodes.VALIDATION, "仅支持 HTTP 或 HTTPS 协议");
            }
            if (uri.getHost() == null || uri.getUserInfo() != null) {
                throw new DomainException(ErrorCodes.VALIDATION, "无效的 URL 主机或用户信息");
            }
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCodes.VALIDATION, "无效的 URL 格式");
        }

        // 优先使用 HEAD 请求探活
        try {
            HttpRequest headReq = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(PROBE_TIMEOUT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<Void> headResp = httpClient.send(headReq, HttpResponse.BodyHandlers.discarding());
            int code = headResp.statusCode();
            if (code >= 200 && code < 300 && contentLength(headResp) > 0) {
                return extractResult(headResp, uri);
            }

            // 若 HEAD 返回 405 Method Not Allowed 或 403，降级为 GET Range 0-0 探测
            if (code == 405 || code == 403 || (code >= 200 && code < 300)) {
                HttpRequest rangeReq = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(PROBE_TIMEOUT)
                        .header("Range", "bytes=0-0")
                        .GET()
                        .build();

                HttpResponse<InputStream> rangeResp = httpClient.send(rangeReq, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = rangeResp.body()) {
                    int rangeCode = rangeResp.statusCode();
                    if (rangeCode == 200 || rangeCode == 206) {
                        return extractResult(rangeResp, uri);
                    }
                }
            }

            throw new DomainException(ErrorCodes.RESOLVE_FAILED, "HTTP 资源探活失败，服务端响应状态码: " + code);
        } catch (IOException e) {
            log.warn("[http-probe] 网络异常 uri={}: {}", uriString, e.getMessage());
            throw new DomainException(ErrorCodes.RESOLVE_FAILED, "无法连接 HTTP 目标服务器: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException(ErrorCodes.RESOLVE_FAILED, "探活请求被中断");
        }
    }

    private ProbeResult extractResult(HttpResponse<?> response, URI uri) {
        long length = contentLength(response);
        if (length <= 0) {
            throw new DomainException(ErrorCodes.RESOLVE_FAILED, "HTTP 资源缺少有效的正整数长度");
        }

        String filename = null;
        Optional<String> disposition = response.headers().firstValue("Content-Disposition");
        if (disposition.isPresent()) {
            filename = parseFilenameFromDisposition(disposition.get());
        }

        if (filename == null || filename.isBlank()) {
            filename = parseFilenameFromUri(uri);
        }

        if (filename == null || filename.isBlank()) {
            filename = "download.bin";
        }
        if (filename.equals(".") || filename.equals("..") || filename.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")) {
            throw new DomainException(ErrorCodes.RESOLVE_FAILED, "HTTP 资源文件名不安全");
        }

        return new ProbeResult(filename, length);
    }

    private long contentLength(HttpResponse<?> response) {
        try {
            Optional<String> contentRange = response.headers().firstValue("Content-Range");
            if (response.statusCode() == 206) {
                if (contentRange.isEmpty()) return -1L;
                Matcher m = CONTENT_RANGE_PATTERN.matcher(contentRange.get());
                if (m.matches()) {
                    return Long.parseLong(m.group(1));
                }
                return -1L;
            }
            return response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private String parseFilenameFromDisposition(String disposition) {
        Matcher mStar = FILENAME_STAR_PATTERN.matcher(disposition);
        if (mStar.find()) {
            String raw = mStar.group(1).trim();
            try {
                return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return raw;
            }
        }
        Matcher m = FILENAME_PATTERN.matcher(disposition);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private String parseFilenameFromUri(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank() || path.equals("/")) {
            return null;
        }
        int idx = path.lastIndexOf('/');
        String name = (idx >= 0 && idx < path.length() - 1) ? path.substring(idx + 1) : path;
        return name.trim();
    }
}
