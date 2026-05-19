package com.example.internalchatbot.service;

import net.dankito.readability4j.Article;
import net.dankito.readability4j.Readability4J;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class ReadabilityExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ReadabilityExtractionService.class);

    private final TextNormalizer textNormalizer;

    public ReadabilityExtractionService(TextNormalizer textNormalizer) {
        this.textNormalizer = textNormalizer;
    }

    public String extract(URI uri, String html, Document fallbackDocument) {
        String readabilityText = extractWithReadability(uri, html);
        String structuredText = extractStructured(fallbackDocument);
        return chooseBetter(readabilityText, structuredText);
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

    private String chooseBetter(String readabilityText, String structuredText) {
        String readability = textNormalizer.normalize(readabilityText);
        String structured = textNormalizer.normalize(structuredText);
        if (readability.length() >= Math.max(300, structured.length() * 0.65)) {
            return readability;
        }
        return structured.isBlank() ? readability : structured;
    }

    private void addLine(Set<String> lines, String value) {
        String normalized = textNormalizer.normalize(value);
        if (normalized.length() >= 3) {
            lines.add(normalized);
        }
    }
}
