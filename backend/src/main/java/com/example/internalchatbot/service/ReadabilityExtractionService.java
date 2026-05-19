package com.example.internalchatbot.service;

import de.l3s.boilerpipe.extractors.ArticleExtractor;
import net.dankito.readability4j.Article;
import net.dankito.readability4j.Readability4J;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xml.sax.ContentHandler;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ReadabilityExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ReadabilityExtractionService.class);

    private final TextNormalizer textNormalizer;
    private final AutoDetectParser tikaParser = new AutoDetectParser();

    public ReadabilityExtractionService(TextNormalizer textNormalizer) {
        this.textNormalizer = textNormalizer;
    }

    public String extract(URI uri, String html, Document fallbackDocument) {
        String readabilityText = extractWithReadability(uri, html);
        String boilerpipeText = extractWithBoilerpipe(uri, html);
        String tikaText = extractWithTika(uri, html);
        String structuredText = extractStructured(fallbackDocument);
        return chooseBest(List.of(readabilityText, boilerpipeText, tikaText, structuredText));
    }

    private String extractWithReadability(URI uri, String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        try {
            Readability4J readability = new Readability4J(uri.toString(), html);
            Article article = readability.parse();
            String articleHtml = article.getContent();
            String title = article.getTitle();
            String text = Jsoup.parse(articleHtml == null ? "" : articleHtml).text();
            return textNormalizer.normalize((title == null ? "" : title + "\n") + text);
        } catch (RuntimeException ex) {
            log.debug("Readability extraction failed url={} reason={}", uri, ex.getMessage());
            return "";
        }
    }

    private String extractWithBoilerpipe(URI uri, String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        try {
            return textNormalizer.normalize(ArticleExtractor.INSTANCE.getText(html));
        } catch (Exception ex) {
            log.debug("Boilerpipe extraction failed url={} reason={}", uri, ex.getMessage());
            return "";
        } catch (LinkageError ex) {
            log.debug("Boilerpipe runtime dependency unavailable url={} reason={}", uri, ex.getMessage());
            return "";
        }
    }

    private String extractWithTika(URI uri, String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        try {
            ContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, uri.toString());
            tikaParser.parse(
                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)),
                    handler,
                    metadata,
                    new ParseContext()
            );
            return textNormalizer.normalize(handler.toString());
        } catch (Exception ex) {
            log.debug("Tika HTML extraction failed url={} reason={}", uri, ex.getMessage());
            return "";
        }
    }

    private String extractStructured(Document document) {
        if (document == null) {
            return "";
        }

        Document clone = document.clone();
        clone.select("script,style,noscript,svg,canvas,iframe,header,footer,nav,aside,form,button,"
                + "[aria-hidden=true],.cookie,.cookies,.breadcrumb,.breadcrumbs,.pagination,.advertisement,.ads,"
                + ".sidebar,.toc,.table-of-contents,.site-footer,.site-header").remove();
        Element content = clone.selectFirst("main,article,[role=main],.content,#content,.documentation,.docs-content,"
                + ".markdown-body,.post,.entry-content,.kb-article");
        Element root = content == null ? clone.body() : content;
        if (root == null) {
            return "";
        }

        Set<String> lines = new LinkedHashSet<>();
        addLine(lines, clone.title());
        for (Element element : root.select("h1,h2,h3,h4,p,li,pre,code,blockquote,td,th")) {
            addLine(lines, element.text());
        }
        if (lines.size() <= 1) {
            addLine(lines, root.text());
        }
        return textNormalizer.normalize(String.join("\n", lines));
    }

    private String chooseBest(List<String> candidates) {
        return candidates.stream()
                .map(textNormalizer::normalize)
                .filter(text -> !text.isBlank())
                .max((left, right) -> Integer.compare(score(left), score(right)))
                .orElse("");
    }

    private int score(String text) {
        long sentenceCount = text.chars().filter(ch -> ch == '.' || ch == '?' || ch == '!').count();
        int headings = text.contains("\n") ? 50 : 0;
        return text.length() + (int) Math.min(500, sentenceCount * 20) + headings;
    }

    private void addLine(Set<String> lines, String value) {
        String normalized = textNormalizer.normalize(value);
        if (normalized.length() >= 3) {
            lines.add(normalized);
        }
    }
}
