package lv.lumii.obis.schema.services.common;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.WebContent;
import org.apache.jena.riot.resultset.ResultSetReaderRegistry;
import org.slf4j.MDC;

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
 * Works around SPARQL endpoints that return a successful response with a missing or wrong Content-Type header.
 * <ul>
 *     <li>Missing (e.g. https://vocab.nerc.ac.uk/sparql): Jena 4.9 ({@code QueryExecHTTP#execRowSet}) then assumes
 *     {@code application/sparql-results+xml}, while its default Accept header prefers JSON, so JSON results are parsed as XML.</li>
 *     <li>Unusable (e.g. https://data.carnegiehall.org/sparql/ labels JSON results as {@code text/html}): Jena rejects any type it
 *     cannot read SELECT results from.</li>
 * </ul>
 * In both cases this client peeks at a bounded prefix of the body and, if it starts with '{', supplies
 * {@code application/sparql-results+json}. In every other case the response is left unchanged, so Jena behaves exactly as
 * before (XML for a missing type, an error for an unusable one, e.g. an HTML error page). Responses with a Content-Type that
 * Jena can read are never touched. Jena still validates and parses the complete results.
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
        // carry the logging context (e.g. the run-specific log file of the schema extraction) over to the peek thread
        Map<String, String> loggingContext = MDC.getCopyOfContextMap();
        return response.thenApplyAsync(value -> {
            if (loggingContext != null) {
                MDC.setContextMap(loggingContext);
            }
            try {
                return detectContentType(value);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            } finally {
                MDC.clear();
            }
        }, PEEK_EXECUTOR);
    }

    @SuppressWarnings("unchecked")
    private <T> HttpResponse<T> detectContentType(HttpResponse<T> response) throws IOException {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (response.statusCode() < 200 || response.statusCode() >= 300
                || isReadableResultsType(contentType)
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
            // Only JSON is supplied: XML is what Jena already assumes for a missing type, and '<' would also match HTML error pages.
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
            logDetected(endpoint, contentType);
            Map<String, List<String>> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            values.putAll(headers.map());
            values.put("Content-Type", List.of(SPARQL_RESULTS_JSON));
            headers = HttpHeaders.of(values, (name, value) -> true);
        } else {
            log.debug("SPARQL endpoint {} returned {} and the body is not JSON; response left unchanged", endpoint, describe(contentType));
        }
        return new Response<>(response, (T) input, headers);
    }

    /**
     * The same check Jena's {@code QueryExecHTTP} applies to SELECT responses. Evaluated per response, never cached:
     * Jena registers its languages lazily, so the answer is only reliable once Jena is initialised.
     */
    private static boolean isReadableResultsType(String contentType) {
        String mediaType = contentType.split(";", 2)[0].trim(); // drop parameters such as charset, as Jena does
        if (mediaType.isEmpty()) return false;
        Lang lang = WebContent.contentTypeToLangResultSet(mediaType);
        return lang != null && ResultSetReaderRegistry.isRegistered(lang);
    }

    private static void logDetected(String endpoint, String contentType) {
        if (REPORTED_ENDPOINTS.add(endpoint)) {
            log.warn("SPARQL endpoint {} returned {}; detected JSON body, using {} (further occurrences are logged at DEBUG)",
                    endpoint, describe(contentType), SPARQL_RESULTS_JSON);
        } else {
            log.debug("SPARQL endpoint {} returned {}; detected JSON body, using {}", endpoint, describe(contentType), SPARQL_RESULTS_JSON);
        }
    }

    private static String describe(String contentType) {
        return contentType.isBlank() ? "no Content-Type" : "unusable Content-Type '" + contentType + "'";
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
