package com.example.internalchatbot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

@Service
public class UrlValidationService {

    private final boolean blockPrivateHosts;

    public UrlValidationService(@Value("${url.block-private-hosts:true}") boolean blockPrivateHosts) {
        this.blockPrivateHosts = blockPrivateHosts;
    }

    public URI validate(String url) {
        URI uri = parse(url);
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only public http and https URLs are supported");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL host is required");
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            throw new IllegalArgumentException("URLs with embedded credentials are not supported");
        }

        URI normalized = normalizeHost(uri);
        if (blockPrivateHosts) {
            ensurePublicHost(normalized.getHost());
        }
        return normalized;
    }

    public URI normalizeHttpUri(URI uri) {
        URI normalized = normalizeHost(uri);
        if (!"http".equalsIgnoreCase(normalized.getScheme()) && !"https".equalsIgnoreCase(normalized.getScheme())) {
            throw new IllegalArgumentException("Only http and https links can be crawled");
        }
        return withoutFragment(normalized).normalize();
    }

    private URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL is required");
        }
        try {
            return new URI(url.trim());
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid URL syntax", ex);
        }
    }

    private URI normalizeHost(URI uri) {
        try {
            String host = uri.getHost() == null ? null : IDN.toASCII(uri.getHost().toLowerCase(Locale.ROOT));
            return new URI(
                    uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT),
                    null,
                    host,
                    uri.getPort(),
                    blankToSlash(uri.getRawPath()),
                    uri.getRawQuery(),
                    null
            ).normalize();
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid URL after normalization", ex);
        }
    }

    private URI withoutFragment(URI uri) {
        try {
            return new URI(
                    uri.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    blankToSlash(uri.getRawPath()),
                    uri.getRawQuery(),
                    null
            );
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid URL after removing fragment", ex);
        }
    }

    private String blankToSlash(String path) {
        return path == null || path.isBlank() ? "/" : path;
    }

    private void ensurePublicHost(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new IllegalArgumentException("URL host could not be resolved: " + host);
            }
            for (InetAddress address : addresses) {
                if (!isPublicAddress(address)) {
                    throw new IllegalArgumentException("URL host resolves to a non-public address: " + host);
                }
            }
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("URL host could not be resolved: " + host, ex);
        }
    }

    private boolean isPublicAddress(InetAddress address) {
        return !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress()
                && !address.isMulticastAddress();
    }
}
