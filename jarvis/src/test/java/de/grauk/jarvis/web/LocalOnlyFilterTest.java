package de.grauk.jarvis.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalOnlyFilterTest {

    private final LocalOnlyFilter filter = new LocalOnlyFilter(
            new JarvisWebProperties(List.of("localhost", "127.0.0.1"), Duration.ofMinutes(10), List.of()));

    private record Outcome(int status, boolean passedThrough, String body) {}

    private Outcome run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(response.getStatus(), chain.getRequest() != null, response.getContentAsString());
    }

    private static MockHttpServletRequest request(String method, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/settings");
        request.addHeader("Host", host);
        request.setLocalPort(8080);
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "localhost:8080", "127.0.0.1:8080", "LOCALHOST:8080", "localhost.:8080"})
    void allowsLocalHostHeaderOnGet(String host) throws Exception {
        assertThat(run(request("GET", host)).passedThrough()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example.com", "evil.example.com:8080", "localhost.evil.com", "127.0.0.2:8080",
            "192.168.0.5:8080"})
    void rejectsForeignHostHeaderForAllMethods(String host) throws Exception {
        Outcome get = run(request("GET", host));
        MockHttpServletRequest post = request("POST", host);
        post.addHeader("Origin", "http://localhost:8080");

        assertThat(get.status()).isEqualTo(403);
        assertThat(get.passedThrough()).isFalse();
        assertThat(run(post).status()).isEqualTo(403);
        assertThat(get.body()).doesNotContain("evil");
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void stateChangingWithoutOriginAndRefererIs403(String method) throws Exception {
        Outcome outcome = run(request(method, "localhost:8080"));

        assertThat(outcome.status()).isEqualTo(403);
        assertThat(outcome.passedThrough()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080", "http://127.0.0.1:8080", "http://LocalHost:8080", "http://localhost:8080/"})
    void allowsLocalOrigin(String origin) throws Exception {
        MockHttpServletRequest request = request("POST", "localhost:8080");
        request.addHeader("Origin", origin);

        assertThat(run(request).passedThrough()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://evil.example.com", "http://localhost.evil.com", "null", "ftp://localhost",
            "http://127.0.0.1.evil.com:8080", "http://evil.com/http://localhost", "javascript:alert(1)", "::::"})
    void rejectsForeignOrigin(String origin) throws Exception {
        MockHttpServletRequest request = request("DELETE", "localhost:8080");
        request.addHeader("Origin", origin);

        assertThat(run(request).status()).isEqualTo(403);
    }

    @Test
    void refererIsUsedWhenOriginIsMissing() throws Exception {
        MockHttpServletRequest ok = request("PUT", "localhost:8080");
        ok.addHeader("Referer", "http://localhost:8080/settings");
        MockHttpServletRequest bad = request("PUT", "localhost:8080");
        bad.addHeader("Referer", "http://evil.example.com/settings");

        assertThat(run(ok).passedThrough()).isTrue();
        assertThat(run(bad).status()).isEqualTo(403);
    }

    @Test
    void originWinsOverRefererWhenBothPresent() throws Exception {
        MockHttpServletRequest request = request("POST", "localhost:8080");
        request.addHeader("Origin", "http://evil.example.com");
        request.addHeader("Referer", "http://localhost:8080/");

        assertThat(run(request).status()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:3000", "http://localhost:9999", "http://localhost", "https://localhost",
            "http://127.0.0.1:8081"})
    void rejectsOriginWithForeignPort(String origin) throws Exception {
        MockHttpServletRequest request = request("POST", "localhost:8080");
        request.addHeader("Origin", origin);

        assertThat(run(request).status()).isEqualTo(403);
    }

    @Test
    void defaultPortIsAllowedWhenServerListensOnIt() throws Exception {
        MockHttpServletRequest request = request("POST", "localhost");
        request.setLocalPort(80);
        request.addHeader("Origin", "http://localhost");

        assertThat(run(request).passedThrough()).isTrue();
    }

    @Test
    void refererWithForeignPortIsRejected() throws Exception {
        MockHttpServletRequest request = request("POST", "localhost:8080");
        request.addHeader("Referer", "http://localhost:3000/settings");

        assertThat(run(request).status()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cross-site", "same-site", "bogus"})
    void rejectsForeignSecFetchSite(String value) throws Exception {
        MockHttpServletRequest request = request("POST", "localhost:8080");
        request.addHeader("Origin", "http://localhost:8080");
        request.addHeader("Sec-Fetch-Site", value);

        assertThat(run(request).status()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"same-origin", "none"})
    void allowsSameOriginSecFetchSite(String value) throws Exception {
        MockHttpServletRequest request = request("POST", "localhost:8080");
        request.addHeader("Origin", "http://localhost:8080");
        request.addHeader("Sec-Fetch-Site", value);

        assertThat(run(request).passedThrough()).isTrue();
    }

    @Test
    void secFetchSiteIsIgnoredForGet() throws Exception {
        MockHttpServletRequest request = request("GET", "localhost:8080");
        request.addHeader("Sec-Fetch-Site", "cross-site");

        assertThat(run(request).passedThrough()).isTrue();
    }

    @Test
    void additionalAllowedOriginsAreAccepted() throws Exception {
        LocalOnlyFilter dev = new LocalOnlyFilter(new JarvisWebProperties(List.of("localhost"),
                Duration.ofMinutes(1), List.of("http://localhost:5173")));
        MockHttpServletRequest ok = request("POST", "localhost:8080");
        ok.addHeader("Origin", "http://localhost:5173");
        ok.addHeader("Sec-Fetch-Site", "same-site");
        MockHttpServletRequest crossSite = request("POST", "localhost:8080");
        crossSite.addHeader("Origin", "http://localhost:5173");
        crossSite.addHeader("Sec-Fetch-Site", "cross-site");
        MockHttpServletRequest other = request("POST", "localhost:8080");
        other.addHeader("Origin", "http://localhost:5174");

        MockFilterChain okChain = new MockFilterChain();
        dev.doFilter(ok, new MockHttpServletResponse(), okChain);
        MockHttpServletResponse crossResponse = new MockHttpServletResponse();
        dev.doFilter(crossSite, crossResponse, new MockFilterChain());
        MockHttpServletResponse otherResponse = new MockHttpServletResponse();
        dev.doFilter(other, otherResponse, new MockFilterChain());

        assertThat(okChain.getRequest()).isNotNull();
        assertThat(crossResponse.getStatus()).isEqualTo(403);
        assertThat(otherResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void securityHeadersArePresentOnSuccessAndOnRejection() throws Exception {
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(request("GET", "localhost:8080"), ok, new MockFilterChain());
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(request("GET", "evil.example.com"), denied, new MockFilterChain());

        for (MockHttpServletResponse response : List.of(ok, denied)) {
            assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
            assertThat(response.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'");
            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("same-origin");
        }
        assertThat(denied.getStatus()).isEqualTo(403);
    }

    @Test
    void sanitizeTruncatesAndReplacesControlCharacters() {
        String dirty = "http://evil\r\nFAKE LOG LINE\t" + "x".repeat(300);

        String clean = LocalOnlyFilter.sanitize(dirty);

        assertThat(clean).doesNotContain("\r", "\n", "\t");
        assertThat(clean.length()).isLessThanOrEqualTo(LocalOnlyFilter.MAX_LOGGED_LENGTH + 3);
        assertThat(LocalOnlyFilter.sanitize(null)).isEqualTo("null");
    }

    @Test
    void getDoesNotNeedOrigin() throws Exception {
        assertThat(run(request("GET", "localhost:8080")).passedThrough()).isTrue();
        assertThat(run(request("HEAD", "localhost:8080")).passedThrough()).isTrue();
    }

    @Test
    void allowedHostsAreConfigurable() throws Exception {
        LocalOnlyFilter custom = new LocalOnlyFilter(
                new JarvisWebProperties(List.of("jarvis.local"), Duration.ofMinutes(1), List.of()));
        MockHttpServletRequest allowed = request("GET", "jarvis.local:8080");
        MockHttpServletRequest denied = request("GET", "localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = new MockFilterChain();
        custom.doFilter(allowed, response, chain);
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        custom.doFilter(denied, deniedResponse, new MockFilterChain());

        assertThat(chain.getRequest()).isNotNull();
        assertThat(deniedResponse.getStatus()).isEqualTo(403);
    }
}
