package com.the3Cgrp.zupptrade.core.upstox.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.the3Cgrp.zupptrade.core.upstox.config.UpstoxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Upstox OAuth 2.0 Authorization Code flow — fully automated in the JVM.
 *
 * Flow:
 *   1. Opens the Upstox login page in the system browser.
 *   2. Starts a lightweight JDK HttpServer on localhost:8080/callback.
 *   3. Captures the ?code= parameter when Upstox redirects after login.
 *   4. POSTs to /v2/login/authorization/token to exchange the code for a token.
 *   5. Returns the access token (callers decide where to store it).
 *
 * === STANDALONE (main method) ===
 *   java -Dupstox.api-key=XXX -Dupstox.api-secret=YYY \
 *        -cp <classpath> com.the3Cgrp.zupptrade.core.upstox.auth.UpstoxTokenFetcher \
 *        path/to/application-local.yml [another.yml ...]
 *
 *   The token is printed to stdout. Each yml path passed as a CLI argument
 *   has its `access-token:` line updated automatically.
 *
 * === SPRING INTEGRATION ===
 *   Injected as a bean by UpstoxAutoConfiguration.
 *   UpstoxTokenStartupRunner calls fetchToken() on startup when the token is absent.
 */
public class UpstoxTokenFetcher {

    private static final Logger log = LoggerFactory.getLogger(UpstoxTokenFetcher.class);

    private static final int CALLBACK_PORT   = 8080;
    private static final int TIMEOUT_MINUTES = 5;

    private final String apiKey;
    private final String apiSecret;
    private final String redirectUri;
    private final String baseUrl;

    /** Spring constructor — invoked by UpstoxAutoConfiguration. */
    public UpstoxTokenFetcher(UpstoxProperties props) {
        this(props.getApiKey(), props.getApiSecret(), props.getRedirectUri(), props.getBaseUrl());
    }

    /** Standalone constructor — invoked by main(). */
    public UpstoxTokenFetcher(String apiKey, String apiSecret, String redirectUri, String baseUrl) {
        this.apiKey      = apiKey;
        this.apiSecret   = apiSecret;
        this.redirectUri = redirectUri;
        this.baseUrl     = baseUrl;
    }

    /**
     * Runs the full OAuth flow. Blocks until the browser callback is received
     * (max 5 minutes). Returns the access token string.
     */
    public String fetchToken() throws Exception {
        String authUrl = buildAuthUrl();
        System.out.println("\n[Upstox] Opening browser for login...");
        System.out.println("[Upstox] If the browser does not open automatically, visit:\n  " + authUrl + "\n");
        log.info("upstox.oauth.start url={}", authUrl);

        openBrowser(authUrl);
        String code  = waitForAuthCode();
        String token = exchangeCode(code);

        log.info("upstox.oauth.success token_length={}", token.length());
        return token;
    }

    // -----------------------------------------------------------------------
    // Private — OAuth flow steps
    // -----------------------------------------------------------------------

    private String buildAuthUrl() {
        return baseUrl + "/v2/login/authorization/dialog"
                + "?response_type=code"
                + "&client_id="    + urlEncode(apiKey)
                + "&redirect_uri=" + urlEncode(redirectUri);
    }

    private void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {}
        // Fallback for headless / Linux
        try {
            Runtime.getRuntime().exec(new String[]{"xdg-open", url});
        } catch (Exception ignored) {}
    }

    private String waitForAuthCode() throws Exception {
        CountDownLatch          latch    = new CountDownLatch(1);
        AtomicReference<String> codeRef  = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(CALLBACK_PORT), 0);
        server.createContext("/callback", exchange -> {
            try {
                String query = exchange.getRequestURI().getQuery();
                String code  = parseQueryParam(query, "code");
                String error = parseQueryParam(query, "error");
                if (code != null) {
                    codeRef.set(code);
                    sendHtml(exchange, 200, successPage());
                } else {
                    errorRef.set(error != null ? error : "no_code");
                    sendHtml(exchange, 400, errorPage(error));
                }
            } catch (Exception e) {
                errorRef.set(e.getMessage());
            } finally {
                latch.countDown();
            }
        });
        server.start();
        System.out.println("[Upstox] Waiting for login callback (up to " + TIMEOUT_MINUTES + " min)...");

        boolean received = latch.await(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        server.stop(0);

        if (!received) {
            throw new TimeoutException("Upstox OAuth callback not received within " + TIMEOUT_MINUTES + " minutes");
        }
        if (errorRef.get() != null) {
            throw new IllegalStateException("Upstox OAuth error: " + errorRef.get());
        }
        return codeRef.get();
    }

    /**
     * Exchanges the auth code for an access token using JDK's built-in HttpClient.
     * No Spring/Jackson dependency — safe for standalone main() use.
     */
    private String exchangeCode(String code) throws Exception {
        String formBody = "code="          + urlEncode(code)
                + "&client_id="     + urlEncode(apiKey)
                + "&client_secret=" + urlEncode(apiSecret)
                + "&redirect_uri="  + urlEncode(redirectUri)
                + "&grant_type=authorization_code";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v2/login/authorization/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Token exchange failed [HTTP " + response.statusCode() + "]: " + response.body());
        }
        System.out.println("[Upstox] Token exchange successful.");
        return extractJsonString(response.body(), "access_token");
    }

    // -----------------------------------------------------------------------
    // Private — HTTP server helpers
    // -----------------------------------------------------------------------

    private void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String parseQueryParam(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }

    /** Minimal JSON string field extractor — no Jackson needed for standalone mode. */
    static String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) throw new IllegalStateException("Key '" + key + "' not found in response: " + json);
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String successPage() {
        return "<html><body style='font-family:sans-serif;padding:40px;text-align:center'>"
                + "<h2 style='color:#2e7d32'>&#10003; Login successful</h2>"
                + "<p>Access token captured. You can close this browser tab.</p>"
                + "</body></html>";
    }

    private static String errorPage(String error) {
        return "<html><body style='font-family:sans-serif;padding:40px;text-align:center'>"
                + "<h2 style='color:#c62828'>&#10007; Login failed</h2>"
                + "<p>Error: " + (error != null ? error : "unknown") + "</p>"
                + "</body></html>";
    }

    // -----------------------------------------------------------------------
    // YAML persistence utility
    // -----------------------------------------------------------------------

    /**
     * Updates the `access-token:` line in a YAML file in-place.
     * Uses line-by-line regex replacement — no full YAML parse/re-serialise required.
     */
    public static void updateAccessTokenInYml(Path ymlFile, String token) throws IOException {
        if (!Files.exists(ymlFile)) {
            System.err.println("[Upstox] WARN: yml file not found, skipping update: " + ymlFile.toAbsolutePath());
            return;
        }
        String original = Files.readString(ymlFile);
        String updated  = original.replaceFirst(
                "(?m)(^\\s*access-token:\\s*).*$",
                "$1" + token
        );
        if (updated.equals(original)) {
            System.err.println("[Upstox] WARN: 'access-token:' key not found in " + ymlFile + " — file unchanged");
            return;
        }
        Files.writeString(ymlFile, updated);
        System.out.println("[Upstox] Updated access-token in: " + ymlFile.toAbsolutePath());
    }

    // -----------------------------------------------------------------------
    // Standalone entry point
    // -----------------------------------------------------------------------

    /**
     * Standalone token refresh. Reads credentials from system properties.
     *
     * Required:
     *   -Dupstox.api-key=<key>
     *   -Dupstox.api-secret=<secret>
     *
     * Optional:
     *   -Dupstox.redirect-uri=http://localhost:8080/callback   (default as shown)
     *   -Dupstox.base-url=https://api.upstox.com               (default as shown)
     *
     * CLI arguments (optional): paths to application-local.yml files to update automatically.
     *
     * Example (from repo root):
     *   java -Dupstox.api-key=XXX -Dupstox.api-secret=YYY \
     *        -cp "core-module/target/classes;..." \
     *        com.the3Cgrp.zupptrade.core.upstox.auth.UpstoxTokenFetcher \
     *        core-module/src/test/resources/application-local.yml \
     *        agent1-market_analyst/src/main/resources/application-local.yml
     */
    public static void main(String[] args) throws Exception {
        String apiKey      = System.getProperty("upstox.api-key");
        String apiSecret   = System.getProperty("upstox.api-secret");
        String redirectUri = System.getProperty("upstox.redirect-uri", "http://localhost:8080/callback");
        String baseUrl     = System.getProperty("upstox.base-url",     "https://api.upstox.com");

        if (apiKey == null || apiSecret == null) {
            System.err.println("ERROR: Provide credentials as system properties:");
            System.err.println("  -Dupstox.api-key=<key> -Dupstox.api-secret=<secret>");
            System.exit(1);
        }

        UpstoxTokenFetcher fetcher = new UpstoxTokenFetcher(apiKey, apiSecret, redirectUri, baseUrl);
        String token = fetcher.fetchToken();

        System.out.println("\n==========================================================");
        System.out.println("  Upstox Access Token:");
        System.out.println("  " + token);
        System.out.println("==========================================================\n");

        // Auto-update any yml files supplied as CLI arguments
        for (String path : args) {
            updateAccessTokenInYml(Paths.get(path), token);
        }

        if (args.length == 0) {
            System.out.println("[Upstox] Tip: pass yml file paths as CLI args to auto-update access-token.");
        }
    }
}
