package top.outlands.gradle.helpers

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal HTTP helper for the release tasks: just enough to talk to the maven index and the GitHub API, with no
 * dependency beyond the JDK. Responses come back as {@code [status:int, body:String, headers:Map]}.
 */
class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            // HTTP/1.1 on purpose: the JDK's HTTP/2 stack fails behind proxies and some TLS inspection setups
            // ('EOF reached while reading' while curl and HTTP/1.1 work), and neither the maven index nor the
            // GitHub API needs HTTP/2.
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    static Map<String, Object> send(String method, String url, Map<String, String> headers = [:], String body = null) {
        // Only the idempotent methods are retried: a dispatch must never be sent twice.
        int attempts = method == 'POST' ? 1 : 3
        Exception last = null
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return sendOnce(method, url, headers, body)
            } catch (Exception e) {
                last = e
                if (attempt < attempts) {
                    Thread.sleep(1000L * attempt)
                }
            }
        }
        throw new IllegalStateException("${method} ${url} failed after ${attempts} attempt(s): ${last}".toString(), last)
    }

    private static Map<String, Object> sendOnce(String method, String url, Map<String, String> headers, String body) {
        def builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
        headers.each { name, value -> builder.header(name, value) }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body))
        }
        def response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return [
                status : response.statusCode(),
                body   : response.body(),
                headers: response.headers().map(),
        ]
    }

    static Map<String, Object> get(String url, Map<String, String> headers = [:]) {
        return send('GET', url, headers)
    }

    static Map<String, Object> head(String url, Map<String, String> headers = [:]) {
        return send('HEAD', url, headers)
    }

    static Map<String, Object> post(String url, Map<String, String> headers, String body) {
        return send('POST', url, headers, body)
    }

    /** {@code Authorization: Bearer ...} plus the headers the GitHub API asks for. */
    static Map<String, String> github(String token) {
        return [
                'Authorization'         : "Bearer ${token}".toString(),
                'Accept'                : 'application/vnd.github+json',
                'X-GitHub-Api-Version'  : '2026-03-10',
                'User-Agent'            : 'outlands-release',
        ] as Map<String, String>
    }
}
