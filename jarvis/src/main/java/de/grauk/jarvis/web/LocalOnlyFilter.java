package de.grauk.jarvis.web;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Schutz des unauthentifizierten Dashboards (bindet nur an 127.0.0.1):
 * <ul>
 *   <li>Host-Header jeder Anfrage muss in {@code jarvis.web.allowed-hosts} stehen (DNS-Rebinding),</li>
 *   <li>POST/PUT/PATCH/DELETE brauchen einen {@code Origin}- oder {@code Referer}-Header (http/https) mit erlaubtem
 *       Host <b>und eigenem Port</b> ({@code request.getLocalPort()}); Ausnahme: Einträge aus
 *       {@code jarvis.web.allowed-origins}. Fehlen beide Header, wird abgelehnt (CSRF),</li>
 *   <li>{@code Sec-Fetch-Site} bei zustandsändernden Requests: {@code cross-site} wird immer abgelehnt,
 *       {@code same-site} nur, wenn die Origin explizit in {@code allowed-origins} steht; {@code same-origin}/{@code none}
 *       sind erlaubt,</li>
 *   <li>jede Antwort (auch 403) trägt Security-Header gegen Clickjacking/MIME-Sniffing.</li>
 * </ul>
 * Abgelehnte Anfragen bekommen 403 ohne Details; im Log werden Header-Werte gekürzt und von Steuerzeichen bereinigt.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LocalOnlyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalOnlyFilter.class);
    private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");
    static final int MAX_LOGGED_LENGTH = 100;

    private final Set<String> allowedHosts;
    private final Set<String> allowedOrigins;

    public LocalOnlyFilter(JarvisWebProperties properties) {
        this.allowedHosts = properties.allowedHosts().stream().map(LocalOnlyFilter::normalize)
                .collect(Collectors.toUnmodifiableSet());
        this.allowedOrigins = properties.allowedOrigins().stream().map(LocalOnlyFilter::normalizeOrigin)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        addSecurityHeaders(response);
        // getServerName() liest den Host-Header (ohne Port)
        String host = request.getServerName();
        if (host == null || !allowedHosts.contains(normalize(host))) {
            reject(request, response, "Host-Header nicht erlaubt: " + sanitize(host));
            return;
        }
        if (STATE_CHANGING.contains(request.getMethod())) {
            String origin = request.getHeader("Origin");
            String source = origin != null ? origin : request.getHeader("Referer");
            if (source == null) {
                reject(request, response, "Origin und Referer fehlen");
                return;
            }
            boolean explicitOrigin = isExplicitlyAllowedOrigin(source);
            if (!explicitOrigin && !isAllowedSource(source, request.getLocalPort())) {
                reject(request, response, "Origin/Referer nicht erlaubt: " + sanitize(source));
                return;
            }
            String fetchSite = request.getHeader("Sec-Fetch-Site");
            if (fetchSite != null && !isAllowedFetchSite(fetchSite.strip(), explicitOrigin)) {
                reject(request, response, "Sec-Fetch-Site nicht erlaubt: " + sanitize(fetchSite));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static void addSecurityHeaders(HttpServletResponse response) {
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        response.setHeader("X-Content-Type-Options", "nosniff");
        // same-origin (nicht no-referrer): der Filter braucht den Referer als Fallback für Origin
        response.setHeader("Referrer-Policy", "same-origin");
    }

    private static boolean isAllowedFetchSite(String value, boolean explicitOrigin) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "same-origin", "none" -> true;
            case "same-site" -> explicitOrigin;
            default -> false; // cross-site und unbekannte Werte
        };
    }

    private boolean isExplicitlyAllowedOrigin(String value) {
        if (allowedOrigins.isEmpty()) {
            return false;
        }
        try {
            URI uri = URI.create(value.strip());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return false;
            }
            return allowedOrigins.contains(normalizeOrigin(
                    uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "")));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isAllowedSource(String value, int localPort) {
        try {
            URI uri = URI.create(value.strip());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return false;
            }
            if (uri.getHost() == null || !allowedHosts.contains(normalize(uri.getHost()))) {
                return false;
            }
            int port = uri.getPort() >= 0 ? uri.getPort() : (scheme.equalsIgnoreCase("https") ? 443 : 80);
            return port == localPort;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String reason) throws IOException {
        log.warn("Anfrage abgelehnt ({} {}): {}", request.getMethod(), sanitize(request.getRequestURI()), reason);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Anfrage nicht erlaubt\"}");
    }

    /** Kürzt auf {@value #MAX_LOGGED_LENGTH} Zeichen und ersetzt Steuerzeichen (Log-Injection). */
    static String sanitize(String value) {
        if (value == null) {
            return "null";
        }
        String cut = value.length() > MAX_LOGGED_LENGTH ? value.substring(0, MAX_LOGGED_LENGTH) + "..." : value;
        StringBuilder sb = new StringBuilder(cut.length());
        for (int i = 0; i < cut.length(); i++) {
            char c = cut.charAt(i);
            sb.append(Character.isISOControl(c) || c == ' ' || c == ' ' ? '_' : c);
        }
        return sb.toString();
    }

    private static String normalize(String host) {
        String h = host.strip().toLowerCase(Locale.ROOT);
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    private static String normalizeOrigin(String origin) {
        String o = origin.strip().toLowerCase(Locale.ROOT);
        return o.endsWith("/") ? o.substring(0, o.length() - 1) : o;
    }
}
