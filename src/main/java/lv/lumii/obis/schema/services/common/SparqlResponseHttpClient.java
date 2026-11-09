package lv.lumii.obis.schema.services.common;

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
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

// Supply only absent response types from a bounded stream prefix; Jena still validates and parses the complete results.
final class SparqlResponseHttpClient extends HttpClient {
    private static final int PREFIX_LIMIT = 4096;
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
        return response.thenApplyAsync(value -> {
            try { return detectContentType(value); }
            catch (IOException failure) { throw new CompletionException(failure); }
        });
    }

    @SuppressWarnings("unchecked")
    private <T> HttpResponse<T> detectContentType(HttpResponse<T> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300
                || !response.headers().firstValue("Content-Type").orElse("").isBlank()
                || !(response.body() instanceof InputStream)) return response;

        BufferedInputStream input = new BufferedInputStream((InputStream) response.body(), PREFIX_LIMIT);
        String type;
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
            type = first == '{' ? "application/sparql-results+json"
                    : first == '<' ? "application/sparql-results+xml" : null;
        } catch (IOException failure) {
            try { input.close(); }
            catch (IOException closingFailure) { failure.addSuppressed(closingFailure); }
            throw failure;
        }

        HttpHeaders headers = response.headers();
        if (type != null) {
            Map<String, List<String>> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            values.putAll(headers.map());
            values.put("Content-Type", List.of(type));
            headers = HttpHeaders.of(values, (name, value) -> true);
        }
        return new Response<>(response, (T) input, headers);
    }

    private record Response<T>(HttpResponse<T> original, T body, HttpHeaders headers) implements HttpResponse<T> {
        @Override public int statusCode() { return original.statusCode(); }
        @Override public HttpRequest request() { return original.request(); }
        @Override public Optional<HttpResponse<T>> previousResponse() { return original.previousResponse(); }
        @Override public Optional<SSLSession> sslSession() { return original.sslSession(); }
        @Override public URI uri() { return original.uri(); }
        @Override public Version version() { return original.version(); }
    }

    @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
    @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
    @Override public Redirect followRedirects() { return delegate.followRedirects(); }
    @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
    @Override public SSLContext sslContext() { return delegate.sslContext(); }
    @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
    @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
    @Override public Version version() { return delegate.version(); }
    @Override public Optional<Executor> executor() { return delegate.executor(); }
}
