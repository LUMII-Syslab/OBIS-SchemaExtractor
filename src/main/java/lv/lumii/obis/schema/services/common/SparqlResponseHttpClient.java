package lv.lumii.obis.schema.services.common;

import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * OVERRIDE
 * Works around SPARQL endpoints that return a successful response without a Content-Type header.
 * <p>
 * Jena 4.9 ({@code QueryExecHTTP#execRowSet}) treats a missing Content-Type as {@code application/sparql-results+xml},
 * while its default Accept header prefers JSON. An endpoint that honours Accept but omits Content-Type therefore returns JSON
 * that Jena tries to parse as XML. When the Content-Type is absent, this client peeks at a bounded prefix of the body and,
 * if it starts with '{', supplies {@code application/sparql-results+json}. In every other case the missing header is left
 * alone, so Jena falls back to XML exactly as before. Jena still validates and parses the complete results.
 * <p>
 * Only SELECT (and ASK) results are supported: the sniffed type is a SPARQL results type, which would be wrong for
 * CONSTRUCT/DESCRIBE responses (JSON-LD also starts with '{'). Use this client only for {@code execSelect()} for now.
 * <p>
 * The delegate is normally Jena's shared default client, so this wrapper never shuts it down.
 */
@Slf4j
final class SparqlResponseHttpClient extends HttpClient {

    private static final String SPARQL_RESULTS_JSON = "application/sparql-results+json";

    private static final int PREFIX_LIMIT = 4096;

    /** Endpoints already reported at WARN level; later occurrences are logged at DEBUG to avoid flooding the log. */
    private static final Set<String> REPORTED_ENDPOINTS = ConcurrentHashMap.newKeySet();

    /** Runs the blocking body peek of async requests; one virtual thread per task, nothing to shut down. */
    private static final Executor PEEK_EXECUTOR = task -> Thread.ofVirtual().name("sparql-content-type-peek").start(task);

    private final HttpClient delegate;

    SparqlResponseHttpClient(HttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        return detectContentType(delegate.send(request, handler));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return detectContentType(delegate.sendAsync(request, handler));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                            HttpResponse.PushPromiseHandler<T> pushHandler) {
        return detectContentType(delegate.sendAsync(request, handler, pushHandler));
    }

    private <T> CompletableFuture<HttpResponse<T>> detectContentType(CompletableFuture<HttpResponse<T>> response) {
        // Peeking blocks on the body, so keep it off the common ForkJoinPool. A plain thenApply is not enough: a JDK client
        // without its own executor completes the future on the common pool, so run the peek on a virtual thread instead.
        return response.thenApplyAsync(value -> {
            try {
                return detectContentType(value);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, PEEK_EXECUTOR);
    }

    @SuppressWarnings("unchecked")
    private <T> HttpResponse<T> detectContentType(HttpResponse<T> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300
                || !response.headers().firstValue("Content-Type").orElse("").isBlank()
                || !(response.body() instanceof InputStream)) return response;

        BufferedInputStream input = new BufferedInputStream((InputStream) response.body(), PREFIX_LIMIT);
        boolean json;
        try {
            input.mark(PREFIX_LIMIT);
            int first = input.read();
            int examined = 1;
            if (first == 0xef) {
                if (input.read() == 0xbb && input.read() == 0xbf) {
                    first = input.read();
                    examined = 4;
                }
            }
            while (examined < PREFIX_LIMIT && (first == ' ' || first == '\t' || first == '\r' || first == '\n')) {
                first = input.read();
                examined++;
            }
            input.reset();
            // Only JSON needs a supplied type: for anything else (including XML) Jena already assumes XML results.
            json = first == '{';
        } catch (IOException failure) {
            try {
                input.close();
            } catch (IOException closingFailure) {
                failure.addSuppressed(closingFailure);
            }
            throw failure;
        }

        String endpoint = endpointOf(response.uri());
        HttpHeaders headers = response.headers();
        if (json) {
            logDetected(endpoint);
            Map<String, List<String>> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            values.putAll(headers.map());
            values.put("Content-Type", List.of(SPARQL_RESULTS_JSON));
            headers = HttpHeaders.of(values, (name, value) -> true);
        } else {
            log.debug("SPARQL endpoint {} returned no Content-Type and the body is not JSON; Jena will assume SPARQL XML results", endpoint);
        }
        return new Response<>(response, (T) input, headers);
    }

    private static void logDetected(String endpoint) {
        if (REPORTED_ENDPOINTS.add(endpoint)) {
            log.warn("SPARQL endpoint {} returned no Content-Type; detected JSON body, using {} (further occurrences are logged at DEBUG)",
                    endpoint, SPARQL_RESULTS_JSON);
        } else {
            log.debug("SPARQL endpoint {} returned no Content-Type; detected JSON body, using {}", endpoint, SPARQL_RESULTS_JSON);
        }
    }

    /** The endpoint without the query string, which for GET requests carries the whole SPARQL query. */
    private static String endpointOf(URI uri) {
        if (uri == null) return "<unknown>";
        return uri.getScheme() + "://" + uri.getRawAuthority() + (uri.getRawPath() == null ? "" : uri.getRawPath());
    }

    /** The original response with the replayable body and (possibly) supplied Content-Type; everything else is delegated. */
    private record Response<T>(HttpResponse<T> original, T body, HttpHeaders headers) implements HttpResponse<T> {
        @Override public int statusCode() { return original.statusCode(); }
        @Override public HttpRequest request() { return original.request(); }
        @Override public Optional<HttpResponse<T>> previousResponse() { return original.previousResponse(); }
        @Override public Optional<SSLSession> sslSession() { return original.sslSession(); }
        @Override public URI uri() { return original.uri(); }
        @Override public Version version() { return original.version(); }
        @Override public Optional<String> connectionLabel() { return original.connectionLabel(); }
    }

    // Pure delegation to the wrapped client
    @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
    @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
    @Override public Redirect followRedirects() { return delegate.followRedirects(); }
    @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
    @Override public SSLContext sslContext() { return delegate.sslContext(); }
    @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
    @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
    @Override public Version version() { return delegate.version(); }
    @Override public Optional<Executor> executor() { return delegate.executor(); }

    // Lifecycle methods (close, shutdown, shutdownNow, awaitTermination, isTerminated) are intentionally NOT overridden:
    // the inherited JDK defaults are no-ops. Do not delegate them - the delegate is Jena's shared default client (HttpEnv),
    // and shutting it down would break every later SPARQL query in the JVM.
}
