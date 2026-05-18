package com.example.internalchatbot.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
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

    private final UrlValidationService urlValidationService;
    private final AuthenticatedUrlReaderService authenticatedUrlReaderService;
    private final TextNormalizer textNormalizer;
    private final int maxPages;
    private final int maxDepth;
    private final Duration timeout;
    private final int maxExtractedChars;

    public UrlReaderService(
            UrlValidationService urlValidationService,
            AuthenticatedUrlReaderService authenticatedUrlReaderService,
            TextNormalizer textNormalizer,
            @Value("${url.max-pages:8}") int maxPages,
            @Value("${url.max-depth:2}") int maxDepth,
            @Value("${url.timeout:15s}") Duration timeout,
            @Value("${url.max-extracted-chars:120000}") int maxExtractedChars
    ) {
        this.urlValidationService = urlValidationService;
        this.authenticatedUrlReaderService = authenticatedUrlReaderService;
        this.textNormalizer = textNormalizer;
        this.maxPages = Math.max(1, maxPages);
        this.maxDepth = Math.max(0, maxDepth);
        this.timeout = timeout;
        this.maxExtractedChars = Math.max(10_000, maxExtractedChars);
    }

    public ExtractedDocument read(String url, boolean loginRequired) {
        URI rootUri = urlValidationService.validate(url);
        if (loginRequired) {
            String text = textNormalizer.normalize(authenticatedUrlReaderService.readAfterLogin(rootUri));
            return toDocument(rootUri, List.of(new ExtractedPage(1, rootUri.toString(), text, Map.of("url", rootUri.toString(), "parser", "selenium"))));
        }
        return crawl(rootUri);
    }

    private ExtractedDocument crawl(URI rootUri) {
        List<ExtractedPage> pages = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> contentHashes = new LinkedHashSet<>();
        Deque<CrawlTarget> queue = new ArrayDeque<>();
        queue.add(new CrawlTarget(rootUri, 0));
        sitemapUrls(rootUri).forEach(uri -> queue.add(new CrawlTarget(uri, 1)));

        int totalChars = 0;
        while (!queue.isEmpty() && pages.size() < maxPages && totalChars < maxExtractedChars) {
            CrawlTarget target = queue.removeFirst();
            URI uri = normalizeUri(target.uri());
            if (!visited.add(uri.toString()) || target.depth() > maxDepth) {
                continue;
            }

            try {
                Document document = fetchHtml(uri);
                String text = readableText(document);
                if (text.isBlank()) {
                    continue;
                }
                String hash = ContentHash.sha256(textNormalizer.compact(text));
                if (!contentHashes.add(hash)) {
                    continue;
                }

                String title = document.title().isBlank() ? uri.toString() : document.title();
                pages.add(new ExtractedPage(
                        pages.size() + 1,
                        title,
                        text,
                        Map.of("url", uri.toString(), "title", title, "parser", "jsoup-crawler")
                ));
                totalChars += text.length();

                if (target.depth() < maxDepth) {
                    for (URI link : pageLinks(document, uri, rootUri)) {
                        if (!visited.contains(link.toString())) {
                            queue.addLast(new CrawlTarget(link, target.depth() + 1));
                        }
                    }
                }
            } catch (IOException | IllegalArgumentException ex) {
                // Continue crawling other pages. The controller reports failure only when no text is extracted.
            }
        }

        if (pages.isEmpty()) {
            throw new IllegalArgumentException("No readable content found at the supplied URL");
        }
        return toDocument(rootUri, pages);
    }

    private Document fetchHtml(URI uri) throws IOException {
        return Jsoup.connect(uri.toString())
                .timeout((int) timeout.toMillis())
                .userAgent("InternalChatbot/2.0")
                .followRedirects(true)
                .get();
    }

    private String readableText(Document document) {
        Document clone = document.clone();
        clone.select("script,style,noscript,svg,canvas,iframe,header,footer,nav,aside,form,button").remove();
        Element main = clone.selectFirst("main,article,[role=main],.content,#content");
        String text = main == null ? clone.body().text() : main.text();
        return textNormalizer.normalize(text);
    }

    private List<URI> sitemapUrls(URI rootUri) {
        URI sitemapUri = rootUri.resolve("/sitemap.xml");
        try {
            Document sitemap = Jsoup.connect(sitemapUri.toString())
                    .timeout((int) timeout.toMillis())
                    .ignoreContentType(true)
                    .parser(Parser.xmlParser())
                    .get();
            return sitemap.select("loc")
                    .stream()
                    .map(Element::text)
                    .map(this::safeUri)
                    .filter(uri -> uri != null && sameHost(uri, rootUri))
                    .limit(maxPages)
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    private List<URI> pageLinks(Document document, URI pageUri, URI rootUri) {
        List<URI> links = new ArrayList<>();
        for (Element link : document.select("a[href]")) {
            URI uri = safeUri(pageUri.resolve(link.attr("href")).toString());
            if (uri == null || !sameHost(uri, rootUri) || hasUnsupportedSuffix(uri)) {
                continue;
            }
            try {
                links.add(urlValidationService.validate(uri.toString()));
            } catch (IllegalArgumentException ignored) {
                // Ignore links outside the configured allow-list.
            }
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

    private URI safeUri(String url) {
        try {
            return normalizeUri(URI.create(url));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private URI normalizeUri(URI uri) {
        String withoutFragment = uri.toString().replaceAll("#.*$", "");
        return URI.create(withoutFragment).normalize();
    }

    private boolean sameHost(URI left, URI right) {
        return left.getHost() != null && right.getHost() != null && left.getHost().equalsIgnoreCase(right.getHost());
    }

    private boolean hasUnsupportedSuffix(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
        return path.endsWith(".zip") || path.endsWith(".exe") || path.endsWith(".dmg") || path.endsWith(".jpg")
                || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".gif") || path.endsWith(".mp4");
    }

    private record CrawlTarget(URI uri, int depth) {
    }
}
