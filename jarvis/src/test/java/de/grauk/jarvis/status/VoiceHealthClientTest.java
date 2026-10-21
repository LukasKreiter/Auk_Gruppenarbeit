package de.grauk.jarvis.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class VoiceHealthClientTest {

    private static final String BODY = """
            {"status":"ok","models":{"stt":"faster-whisper large-v3","tts":"piper"},
             "gpu":{"name":"RTX 4070","memory_used_mb":2048,"memory_total_mb":12288,"utilization_percent":35}}""";

    /** Veränderbare Uhr für den Cache-Test. */
    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private MockRestServiceServer server;

    private VoiceHealthClient client(String token, MutableClock clock) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new VoiceHealthClient(builder, "http://voice:8090", token, clock, Duration.ofSeconds(3));
    }

    @Test
    void sendsTokenHeaderAndParsesDetails() {
        VoiceHealthClient client = client("geheim", new MutableClock());
        server.expect(requestTo("http://voice:8090/health")).andExpect(header("X-Jarvis-Token", "geheim"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        VoiceHealth h = client.current();

        assertThat(h.up()).isTrue();
        assertThat(h.models()).containsExactly("stt: faster-whisper large-v3", "tts: piper");
        assertThat(h.gpuName()).isEqualTo("RTX 4070");
        assertThat(h.gpuMemoryUsedBytes()).isEqualTo(2048L * 1024 * 1024);
        assertThat(h.gpuMemoryTotalBytes()).isEqualTo(12288L * 1024 * 1024);
        assertThat(h.gpuUtilization()).isEqualTo(0.35);
        server.verify();
    }

    @Test
    void omitsTokenHeaderWhenEmpty() {
        VoiceHealthClient client = client("  ", new MutableClock());
        server.expect(requestTo("http://voice:8090/health")).andExpect(headerDoesNotExist("X-Jarvis-Token"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.current().up()).isTrue();
        server.verify();
    }

    @Test
    void ioErrorAndServerErrorAreDownWithoutLeakingToken() {
        VoiceHealthClient client = client("geheim", new MutableClock());
        server.expect(requestTo("http://voice:8090/health")).andRespond(withException(new IOException("Timeout geheim")));

        VoiceHealth down = client.current();

        assertThat(down.up()).isFalse();
        assertThat(down.models()).isEmpty();
        assertThat(down.message()).doesNotContain("geheim");

        MutableClock clock = new MutableClock();
        VoiceHealthClient other = client("geheim", clock);
        server.expect(requestTo("http://voice:8090/health")).andRespond(withServerError());
        assertThat(other.current().up()).isFalse();
    }

    @Test
    void resultIsCachedUntilTtlExpires() {
        MutableClock clock = new MutableClock();
        VoiceHealthClient client = client("", clock);
        server.expect(times(2), requestTo("http://voice:8090/health"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        VoiceHealth first = client.current();
        clock.now.set(clock.now.get().plusSeconds(2));
        assertThat(client.current()).isSameAs(first);
        clock.now.set(clock.now.get().plusSeconds(2));
        assertThat(client.current()).isNotSameAs(first);
        server.verify();
    }

    @Test
    void toleratesOddFormats() {
        VoiceHealth h = VoiceHealthClient.parse(
                "{\"status\":\"error\",\"models\":[\"a\",\"b\"],\"gpu\":[{\"name\":\"X\",\"utilization\":0.5,\"memory_used_bytes\":10,\"memory_total_bytes\":20}]}",
                Instant.EPOCH);
        assertThat(h.up()).isFalse();
        assertThat(h.models()).containsExactly("a", "b");
        assertThat(h.gpuUtilization()).isEqualTo(0.5);
        assertThat(h.gpuMemoryUsedBytes()).isEqualTo(10L);

        assertThat(VoiceHealthClient.parse("kein json", Instant.EPOCH).up()).isTrue();
        assertThat(VoiceHealthClient.parse("", Instant.EPOCH).gpuName()).isNull();
    }

    @Test
    void unauthorizedIsReportedAsInvalidToken() {
        VoiceHealthClient client = client("geheim", new MutableClock());
        server.expect(requestTo("http://voice:8090/health")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        VoiceHealth h = client.current();

        assertThat(h.up()).isFalse();
        assertThat(h.message()).contains("Token ungültig (HTTP 401)", "down seit 00:00:00").doesNotContain("geheim");
    }

    @Test
    void otherHttpErrorShowsStatusCode() {
        VoiceHealthClient client = client("", new MutableClock());
        server.expect(requestTo("http://voice:8090/health")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(client.current().message()).startsWith("HTTP 503");
    }

    @Test
    void connectionProblemIsReportedAsTimeout() {
        VoiceHealthClient client = client("geheim", new MutableClock());
        server.expect(requestTo("http://voice:8090/health")).andRespond(withException(new SocketTimeoutException("Read timed out")));

        VoiceHealth h = client.current();

        assertThat(h.up()).isFalse();
        assertThat(h.message()).startsWith("Timeout/Verbindung fehlgeschlagen");
    }

    @Test
    void htmlBodyCountsAsUpButUnreadable() {
        VoiceHealthClient client = client("", new MutableClock());
        server.expect(requestTo("http://voice:8090/health"))
                .andRespond(withSuccess("<html><body>Login</body></html>", MediaType.TEXT_HTML));

        VoiceHealth h = client.current();

        assertThat(h.up()).isTrue();
        assertThat(h.message()).isEqualTo("ok (Antwort nicht lesbar)");
        assertThat(h.models()).isEmpty();
    }

    @Test
    void parallelCallsShareOneRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        RestClient.Builder builder = RestClient.builder().requestFactory((uri, method) -> {
            requests.incrementAndGet();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
            MockClientHttpResponse response = new MockClientHttpResponse(BODY.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            request.setResponse(response);
            return request;
        });
        VoiceHealthClient client = new VoiceHealthClient(builder, "http://voice:8090", "", new MutableClock(), Duration.ofSeconds(3));

        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<VoiceHealth>> results = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                results.add(pool.submit(client::current));
            }
            Thread.sleep(300);
            release.countDown();
            for (Future<VoiceHealth> f : results) {
                assertThat(f.get(5, TimeUnit.SECONDS).up()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void indicatorMapsHealthWithoutSecrets() {
        VoiceHealthClient client = client("geheim", new MutableClock());
        server.expect(requestTo("http://voice:8090/health")).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        var health = new VoiceHealthIndicator(client).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKeys("models", "gpuName", "gpuMemoryUsedBytes");
        assertThat(health.getDetails().toString()).doesNotContain("geheim");
    }
}
