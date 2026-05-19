package com.example.internalchatbot.service;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class UrlReaderService {

    private static final Logger log = LoggerFactory.getLogger(UrlReaderService.class);
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36 InternalChatbot/3.0";

    private final UrlValidationService urlValidationService;
    private final AuthenticatedUrlReaderService authenticatedUrlReaderService;
    private final TextNormalizer textNormalizer;
    private final int maxPages;
    private final int maxDepth;
    private final Duration timeout;
    private final int maxExtractedChars;
    private final int retryAttempts;
    private final Duration retryBackoff;

    public UrlReaderService(
            UrlValidationService urlValidationService,
            AuthenticatedUrlReaderService authenticatedUrlReaderService,
            TextNormalizer textNormalizer,
            @Value("${url.max-pages:20}") int maxPages,
            @Value("${url.max-depth:2}") int maxDepth,
            @Value("${url.timeout:15s}") Duration timeout,
            @Value("${url.max-extracted-chars:180000}") int maxExtractedChars,
            @Value("${url.retry-attempts:3}") int retryAttempts,
            @Value("${url.retry-backoff:500ms}") Duration retryBackoff
    ) {
        this.urlValidationService = urlValidationService;
        this.authenticatedUrlReaderService = authenticatedUrlReaderService;
        this.textNormalizer = textNormalizer;
        this.maxPages = Math.max(1, maxPages);
        this.maxDepth = Math.max(0, maxDepth);
        this.timeout = timeout;
        this.maxExtractedChars = Math.max(10_000, maxExtractedChars);
        this.retryAttempts = Math.max(1, retryAttempts);
        this.retryBackoff = retryBackoff == null ? Duration.ofMillis(500) : retryBackoff;
    }

    public ExtractedDocument read(String url, boolean loginRequired) {
        long startedAt = System.nanoTime();
        URI rootUri = urlValidationService.validate(url);
        if (loginRequired) {
            String text = textNormalizer.normalize(authenticatedUrlReaderService.readAfterLogin(rootUri));
            return toDocument(rootUri, List.of(new ExtractedPage(
                    1,
                    rootUri.toString(),
                    text,
                    Map.of("url", rootUri.toString(), "parser", "selenium")
            )));
        }

        ExtractedDocument document = crawl(rootUri);
        log.info(
                "URL ingestion extracted rootUrl={} pages={} chars={} totalMs={}",
                rootUri,
                document.pageCount(),
                document.combinedText().length(),
                elapsedMillis(startedAt)
        );
        return document;
    }

    private ExtractedDocument crawl(URI rootUri) {
        List<ExtractedPage> pages = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> queued = new LinkedHashSet<>();
        Set<String> contentHashes = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        Deque<CrawlTarget> queue = new ArrayDeque<>();
        addTarget(queue, queued, rootUri, 0);
        sitemapUrls(rootUri).forEach(uri -> addTarget(queue, queued, uri, 1));

        int totalChars = 0;
        int maxQueueSize = Math.max(maxPages * 25, 50);
        while (!queue.isEmpty() && pages.size() < maxPages && totalChars < maxExtractedChars) {
            CrawlTarget target = queue.removeFirst();
            URI uri = normalizeUri(target.uri());
            if (!visited.add(uri.toString()) || target.depth() > maxDepth) {
                continue;
            }

            try {
                FetchedPage fetched = fetchPage(uri);
                Document document = fetched.document();
                String text = readableText(document);
                if (text.isBlank()) {
                    failures.add(uri + " returned no readable text");
                    continue;
                }

                String hash = ContentHash.sha256(textNormalizer.compact(text));
                if (!contentHashes.add(hash)) {
                    continue;
                }

                String title = document.title().isBlank() ? fetched.finalUri().toString() : document.title();
                pages.add(new ExtractedPage(
                        pages.size() + 1,
                        title,
                        text,
                        Map.of(
                                "url", fetched.finalUri().toString(),
                                "requestedUrl", uri.toString(),
                                "title", title,
                                "statusCode", String.valueOf(fetched.statusCode()),
                                "contentType", fetched.contentType(),
                                "parser", "jsoup-crawler"
                        )
                ));
                totalChars += text.length();

                if (target.depth() < maxDepth && queued.size() < maxQueueSize) {
                    for (URI link : pageLinks(document, fetched.finalUri(), rootUri)) {
                        if (queued.size() >= maxQueueSize) {
                            break;
                        }
                        addTarget(queue, queued, link, target.depth() + 1);
                    }
                }
            } catch (IOException | IllegalArgumentException ex) {
                String message = uri + " failed: " + ex.getMessage();
                failures.add(message);
                log.debug("URL crawl page skipped {}", message);
            }
        }

        if (pages.isEmpty()) {
            String detail = failures.isEmpty() ? "No pages were fetched" : String.join("; ", failures.stream().limit(3).toList());
            throw new IllegalArgumentException("No readable public content found at the supplied URL. " + detail);
        }
        return toDocument(rootUri, pages);
    }

    private FetchedPage fetchPage(URI uri) throws IOException {
        IOException lastException = null;
        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            try {
                Connection.Response response = browserConnection(uri)
                        .ignoreContentType(true)
                        .ignoreHttpErrors(true)
                        .execute();
                int statusCode = response.statusCode();
                if (statusCode >= 400) {
                    throw new IOException("HTTP " + statusCode + " " + response.statusMessage());
                }

                URI finalUri = urlValidationService.validate(response.url().toString());
                Document document = response.parse();
                return new FetchedPage(finalUri, document, statusCode, defaultIfBlank(response.contentType(), "unknown"));
            } catch (IOException ex) {
                lastException = ex;
                if (attempt < retryAttempts) {
                    sleepBeforeRetry(uri, attempt, ex);
                }
            }
        }
        throw lastException == null ? new IOException("Unable to fetch URL") : lastException;
    }

    private Connection browserConnection(URI uri) {
        return Jsoup.connect(uri.toString())
                .timeout((int) timeout.toMillis())
                .maxBodySize(0)
                .userAgent(USER_AGENT)
                .followRedirects(true)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .referrer(uri.resolve("/").toString());
    }

    private String readableText(Document document) {
        Document clone = document.clone();
        clone.select("script,style,noscript,svg,canvas,iframe,header,footer,nav,aside,form,button,"
                + "[aria-hidden=true],.cookie,.cookies,.breadcrumb,.breadcrumbs,.pagination,.advertisement,.ads").remove();
        Element content = clone.selectFirst("main,article,[role=main],.content,#content,.documentation,.docs-content");
        Element root = content == null ? clone.body() : content;
        if (root == null) {
            return "";
        }

        LinkedHashSet<String> lines = new LinkedHashSet<>();
        addLine(lines, clone.title());
        for (Element element : root.select("h1,h2,h3,h4,p,li,pre,code,blockquote,td,th")) {
            addLine(lines, element.text());
        }
        if (lines.size() <= 1) {
            addLine(lines, root.text());
        }
        return textNormalizer.normalize(String.join("\n", lines));
    }

    private List<URI> sitemapUrls(URI rootUri) {
        LinkedHashSet<URI> urls = new LinkedHashSet<>();
        Deque<URI> sitemaps = new ArrayDeque<>();
        sitemaps.add(rootUri.resolve("/sitemap.xml"));
        int sitemapReads = 0;

        while (!sitemaps.isEmpty() && urls.size() < maxPages && sitemapReads < 5) {
            URI sitemapUri = sitemaps.removeFirst();
            sitemapReads++;
            try {
                Document sitemap = browserConnection(sitemapUri)
                        .ignoreContentType(true)
                        .parser(Parser.xmlParser())
                        .get();
                for (Element loc : sitemap.select("loc")) {
                    URI uri = safeUri(loc.text());
                    if (uri == null || !sameHost(uri, rootUri)) {
                        continue;
                    }
                    if (uri.getPath() != null && uri.getPath().toLowerCase().endsWith(".xml")) {
                        sitemaps.addLast(uri);
                    } else if (!hasUnsupportedSuffix(uri)) {
                        urls.add(uri);
                    }
                    if (urls.size() >= maxPages) {
                        break;
                    }
                }
            } catch (IOException ex) {
                log.debug("Sitemap read skipped url={} reason={}", sitemapUri, ex.getMessage());
            }
        }
        return List.copyOf(urls);
    }

    private List<URI> pageLinks(Document document, URI pageUri, URI rootUri) {
        List<URI> links = new ArrayList<>();
        for (Element link : document.select("a[href]")) {
            String href = link.attr("href");
            if (href == null || href.isBlank() || href.startsWith("mailto:") || href.startsWith("tel:")
                    || href.startsWith("javascript:")) {
                continue;
            }
            URI uri = safeUri(pageUri.resolve(href).toString());
            if (uri == null || !sameHost(uri, rootUri) || hasUnsupportedSuffix(uri)) {
                continue;
            }
            links.add(uri);
        }
        return links;
    }

    private ExtractedDocument toDocument(URI rootUri, List<ExtractedPage> pages) {
        String combinedText = pages.stream()
                .map(ExtractedPage::text)
                .reduce("", (left, right) -> left + "\n\n" + right);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("rootUrl", rootUri.toString());
        metadata.put("pageCount", String.valueOf(pages.size()));
        metadata.put("crawler", "jsoup-recursive");
        metadata.put("allowList", "disabled");
        return new ExtractedDocument(
                rootUri.toString(),
                "url",
                rootUri.toString(),
                "text/html",
                "jsoup-recursive-crawler",
                ContentHash.sha256(textNormalizer.compact(combinedText)),
                metadata,
                pages
        );
    }

    private void addTarget(Deque<CrawlTarget> queue, Set<String> queued, URI uri, int depth) {
        URI normalized = normalizeUri(uri);
        if (queued.add(normalized.toString())) {
            queue.addLast(new CrawlTarget(normalized, depth));
        }
    }

    private URI safeUri(String url) {
        try {
            return normalizeUri(URI.create(url));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private URI normalizeUri(URI uri) {
        return urlValidationService.normalizeHttpUri(uri);
    }

    private boolean sameHost(URI left, URI right) {
        return left.getHost() != null && right.getHost() != null && left.getHost().equalsIgnoreCase(right.getHost());
    }

    private boolean hasUnsupportedSuffix(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
        return path.endsWith(".zip") || path.endsWith(".exe") || path.endsWith(".dmg") || path.endsWith(".jpg")
                || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".gif") || path.endsWith(".mp4")
                || path.endsWith(".mp3") || path.endsWith(".avi") || path.endsWith(".mov") || path.endsWith(".webp")
                || path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf");
    }

    private void addLine(Set<String> lines, String value) {
        String normalized = textNormalizer.normalize(value);
        if (normalized.length() >= 3) {
            lines.add(normalized);
        }
    }

    private void sleepBeforeRetry(URI uri, int attempt, IOException ex) {
        long sleepMillis = retryBackoff.toMillis() * attempt;
        log.debug("URL fetch retry url={} attempt={} backoffMs={} reason={}", uri, attempt, sleepMillis, ex.getMessage());
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private record FetchedPage(URI finalUri, Document document, int statusCode, String contentType) {
    }

    private record CrawlTarget(URI uri, int depth) {
    }
}
