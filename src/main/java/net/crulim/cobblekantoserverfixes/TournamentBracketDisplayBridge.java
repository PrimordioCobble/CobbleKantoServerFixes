package net.crulim.cobblekantoserverfixes;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.FloatSize;
import com.github.weisj.jsvg.view.ViewBox;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Optional visual bracket pipeline.
 *
 * The visual source is Challonge's own printer-friendly/live SVG, not a locally reconstructed
 * bracket. The server downloads that official SVG, rasterizes it to a normal PNG, uploads the
 * PNG to ImgBB, and returns the direct PNG URL. WaterFrames interaction remains deliberately
 * outside this class so any image/media failure can never affect match reporting.
 */
public final class TournamentBracketDisplayBridge {
    static {
        // This class is server-only and renders into BufferedImage; it must never require X11.
        System.setProperty("java.awt.headless", "true");
    }

    private static final URI IMGBB_UPLOAD = URI.create("https://api.imgbb.com/1/upload");
    private static final int MAX_SVG_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PNG_DIMENSION = 4096;
    private static final long MAX_PNG_PIXELS = 16_000_000L;
    private static final String ROUND_LABEL_CLASS = "round-label";
    private static final String ROUND_LABEL_BACKGROUND = "#dddddd";
    private static final String ROUND_LABEL_TEXT = "#333333";

    private TournamentBracketDisplayBridge() {
    }

    public static PublishResult renderAndUpload(
            ChallongeTournamentClient client,
            TournamentConfig.Snapshot config
    ) throws IOException, InterruptedException {
        return renderAndUpload(client, config, IMGBB_UPLOAD);
    }

    // Package-private endpoint override exists only for deterministic local integration tests.
    static PublishResult renderAndUpload(
            ChallongeTournamentClient client,
            TournamentConfig.Snapshot config,
            URI uploadEndpoint
    ) throws IOException, InterruptedException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(uploadEndpoint, "uploadEndpoint");

        if (!config.hasBracketDisplayConfig()) {
            throw new IllegalStateException(
                    "Bracket display is not fully configured (bracketDisplayEnabled/imgbbApiKey/waterframesUpdateCommand)."
            );
        }

        URI svgUri = officialBracketSvgUri(config.challongeTournament(), System.currentTimeMillis());
        byte[] svg = downloadOfficialBracketSvg(config, svgUri);
        RenderedPng rendered = rasterizeSvg(svg, svgUri);
        String url = uploadPng(rendered.bytes(), config, uploadEndpoint);
        return new PublishResult(
                url,
                svg.length,
                rendered.bytes().length,
                rendered.width(),
                rendered.height(),
                svgUri.toString()
        );
    }

    static URI officialBracketSvgUri(String tournamentSlug, long cacheBuster) throws IOException {
        String slug = tournamentSlug == null ? "" : tournamentSlug.trim();
        if (slug.isBlank()) {
            throw new IOException("Challonge tournament slug is blank.");
        }
        // The visual fetch is intentionally pinned to Challonge's host. Reject path/query syntax
        // instead of allowing config values to turn this into an arbitrary URL fetcher.
        if (!slug.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IOException(
                    "Challonge tournament slug contains unsupported characters for the printer-friendly SVG endpoint."
            );
        }
        try {
            return new URI(
                    "https",
                    "challonge.com",
                    "/" + slug + ".svg",
                    "ckt=" + Math.max(0L, cacheBuster),
                    null
            );
        } catch (URISyntaxException exception) {
            throw new IOException("Could not build Challonge printer-friendly SVG URL.", exception);
        }
    }

    private static byte[] downloadOfficialBracketSvg(TournamentConfig.Snapshot config, URI svgUri)
            throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.httpConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(svgUri)
                .timeout(Duration.ofSeconds(Math.max(config.httpRequestTimeoutSeconds(), 12)))
                .header("Accept", "image/svg+xml,application/svg+xml;q=0.9,*/*;q=0.1")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("User-Agent", "CobbleKantoServerFixes/0.6.11")
                .GET()
                .build();

        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                byte[] errorBody = input.readNBytes(1024);
                throw new IOException(
                        "Challonge bracket SVG HTTP " + response.statusCode() + ": "
                                + compact(new String(errorBody, StandardCharsets.UTF_8))
                );
            }

            byte[] svg = input.readNBytes(MAX_SVG_BYTES + 1);
            if (svg.length == 0) {
                throw new IOException("Challonge returned an empty printer-friendly SVG.");
            }
            if (svg.length > MAX_SVG_BYTES) {
                throw new IOException("Challonge printer-friendly SVG exceeded the 8 MiB safety limit.");
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase().contains("svg") && !looksLikeSvg(svg)) {
                throw new IOException(
                        "Challonge did not return an SVG image (Content-Type='" + contentType + "')."
                );
            }
            if (!looksLikeSvg(svg)) {
                throw new IOException("Challonge response did not contain a valid-looking SVG document.");
            }
            return svg;
        }
    }

    /**
     * Rasterizes the exact Challonge SVG into PNG. No bracket layout/data is reconstructed here.
     */
    static RenderedPng rasterizeSvg(byte[] svg, URI sourceUri) throws IOException {
        if (svg == null || svg.length == 0) {
            throw new IOException("Cannot rasterize an empty SVG.");
        }
        if (sourceUri == null || !"challonge.com".equalsIgnoreCase(sourceUri.getHost())) {
            throw new IOException("SVG source URI must be on challonge.com.");
        }

        byte[] renderSvg = patchRoundLabelsForJsvg(svg);

        SVGDocument document;
        SVGLoader loader = new SVGLoader();
        try (ByteArrayInputStream input = new ByteArrayInputStream(renderSvg)) {
            document = loader.load(input, sourceUri, LoaderContext.createDefault());
        }
        if (document == null) {
            throw new IOException("JSVG could not parse Challonge's printer-friendly SVG.");
        }

        FloatSize nativeSize = document.size();
        double nativeWidth = nativeSize.width;
        double nativeHeight = nativeSize.height;
        if (!Double.isFinite(nativeWidth) || !Double.isFinite(nativeHeight)
                || nativeWidth <= 0.0 || nativeHeight <= 0.0) {
            throw new IOException(
                    "Challonge SVG reported invalid dimensions: " + nativeWidth + "x" + nativeHeight + "."
            );
        }

        double scale = Math.min(
                1.0,
                Math.min(
                        (double) MAX_PNG_DIMENSION / nativeWidth,
                        (double) MAX_PNG_DIMENSION / nativeHeight
                )
        );
        double nativePixels = nativeWidth * nativeHeight;
        if (nativePixels * scale * scale > MAX_PNG_PIXELS) {
            scale = Math.min(scale, Math.sqrt(MAX_PNG_PIXELS / nativePixels));
        }

        int width = Math.max(1, (int) Math.ceil(nativeWidth * scale));
        int height = Math.max(1, (int) Math.ceil(nativeHeight * scale));

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            // Challonge's printer-friendly presentation is white. Filling first also prevents
            // transparent pixels from becoming black/undefined in image consumers.
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            document.render(null, graphics, new ViewBox(0, 0, width, height));
        } catch (RuntimeException renderFailure) {
            throw new IOException("JSVG failed while rendering Challonge's printer-friendly SVG.", renderFailure);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("No PNG ImageIO writer is available in this Java runtime.");
        }
        byte[] png = output.toByteArray();
        if (!looksLikePng(png)) {
            throw new IOException("Generated bracket output did not have a valid PNG signature.");
        }
        return new RenderedPng(png, width, height);
    }

    /**
     * Challonge's printer SVG relies on stylesheet rules for the round headers. JSVG renders the
     * geometry correctly but can miss those external CSS rules, leaving the SVG defaults in place:
     * black rect + black text. That is the black-bar artifact visible in WaterFrames.
     *
     * We keep Challonge's exact bracket/layout and only inline the two paint properties that the
     * round-label group needs. If Challonge changes the markup and no round-label group is found,
     * this is a strict no-op rather than rewriting unrelated SVG elements.
     */
    static byte[] patchRoundLabelsForJsvg(byte[] svg) {
        if (svg == null || svg.length == 0) {
            return svg;
        }

        String source = new String(svg, StandardCharsets.UTF_8);
        StringBuilder output = new StringBuilder(source.length() + 512);
        int cursor = 0;
        boolean changed = false;

        while (cursor < source.length()) {
            int groupStart = indexOfIgnoreCase(source, "<g", cursor);
            if (groupStart < 0) {
                output.append(source, cursor, source.length());
                break;
            }

            int openEnd = source.indexOf('>', groupStart);
            if (openEnd < 0) {
                output.append(source, cursor, source.length());
                break;
            }

            String openTag = source.substring(groupStart, openEnd + 1);
            if (!hasCssClass(openTag, ROUND_LABEL_CLASS)) {
                output.append(source, cursor, openEnd + 1);
                cursor = openEnd + 1;
                continue;
            }

            int groupEnd = matchingGroupEnd(source, openEnd + 1);
            if (groupEnd < 0) {
                output.append(source, cursor, source.length());
                break;
            }

            output.append(source, cursor, groupStart);
            String group = source.substring(groupStart, groupEnd);
            String patched = patchRoundLabelGroup(group);
            output.append(patched);
            changed |= !patched.equals(group);
            cursor = groupEnd;
        }

        if (!changed) {
            return svg;
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String patchRoundLabelGroup(String group) {
        String patched = patchOpeningTags(group, "rect", ROUND_LABEL_BACKGROUND, false);
        return patchOpeningTags(patched, "text", ROUND_LABEL_TEXT, true);
    }

    private static String patchOpeningTags(String source, String tagName, String fill, boolean text) {
        StringBuilder result = new StringBuilder(source.length() + 128);
        int cursor = 0;
        String needle = "<" + tagName;

        while (cursor < source.length()) {
            int start = indexOfIgnoreCase(source, needle, cursor);
            if (start < 0) {
                result.append(source, cursor, source.length());
                break;
            }
            int end = source.indexOf('>', start);
            if (end < 0) {
                result.append(source, cursor, source.length());
                break;
            }

            result.append(source, cursor, start);
            String tag = source.substring(start, end + 1);
            result.append(forceInlinePaint(tag, fill, text));
            cursor = end + 1;
        }
        return result.toString();
    }

    private static String forceInlinePaint(String tag, String fill, boolean text) {
        String styleSuffix = "fill:" + fill + " !important;stroke:none !important;";
        if (text) {
            styleSuffix += "font-family:Arial,Helvetica,sans-serif !important;";
        }

        int stylePos = indexOfIgnoreCase(tag, "style=", 0);
        if (stylePos >= 0) {
            int quotePos = stylePos + "style=".length();
            while (quotePos < tag.length() && Character.isWhitespace(tag.charAt(quotePos))) {
                quotePos++;
            }
            if (quotePos < tag.length() && (tag.charAt(quotePos) == '"' || tag.charAt(quotePos) == '\'')) {
                char quote = tag.charAt(quotePos);
                int close = tag.indexOf(quote, quotePos + 1);
                if (close > quotePos) {
                    String existing = tag.substring(quotePos + 1, close);
                    String separator = existing.isBlank() || existing.endsWith(";") ? "" : ";";
                    return tag.substring(0, quotePos + 1)
                            + existing + separator + styleSuffix
                            + tag.substring(close);
                }
            }
        }

        int insertAt = tag.endsWith("/>") ? tag.length() - 2 : tag.length() - 1;
        return tag.substring(0, insertAt)
                + " style=\"" + styleSuffix + "\""
                + tag.substring(insertAt);
    }

    private static boolean hasCssClass(String tag, String className) {
        int search = 0;
        while (search < tag.length()) {
            int classPos = indexOfIgnoreCase(tag, "class=", search);
            if (classPos < 0) {
                return false;
            }
            int quotePos = classPos + "class=".length();
            while (quotePos < tag.length() && Character.isWhitespace(tag.charAt(quotePos))) {
                quotePos++;
            }
            if (quotePos >= tag.length() || (tag.charAt(quotePos) != '"' && tag.charAt(quotePos) != '\'')) {
                search = quotePos + 1;
                continue;
            }
            char quote = tag.charAt(quotePos);
            int close = tag.indexOf(quote, quotePos + 1);
            if (close < 0) {
                return false;
            }
            String classes = tag.substring(quotePos + 1, close);
            for (String token : classes.trim().split("\\s+")) {
                if (className.equals(token)) {
                    return true;
                }
            }
            search = close + 1;
        }
        return false;
    }

    private static int matchingGroupEnd(String source, int contentStart) {
        int depth = 1;
        int cursor = contentStart;
        while (cursor < source.length()) {
            int nextOpen = indexOfIgnoreCase(source, "<g", cursor);
            int nextClose = indexOfIgnoreCase(source, "</g", cursor);
            if (nextClose < 0) {
                return -1;
            }
            if (nextOpen >= 0 && nextOpen < nextClose) {
                int openEnd = source.indexOf('>', nextOpen);
                if (openEnd < 0) {
                    return -1;
                }
                depth++;
                cursor = openEnd + 1;
                continue;
            }
            int closeEnd = source.indexOf('>', nextClose);
            if (closeEnd < 0) {
                return -1;
            }
            depth--;
            cursor = closeEnd + 1;
            if (depth == 0) {
                return cursor;
            }
        }
        return -1;
    }

    private static int indexOfIgnoreCase(String source, String needle, int fromIndex) {
        int max = source.length() - needle.length();
        for (int i = Math.max(0, fromIndex); i <= max; i++) {
            if (source.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean looksLikeSvg(byte[] data) {
        int length = Math.min(data.length, 4096);
        String prefix = new String(data, 0, length, StandardCharsets.UTF_8)
                .replace("\uFEFF", "")
                .stripLeading()
                .toLowerCase();
        if (prefix.startsWith("<svg") || prefix.startsWith("<?xml")) {
            return prefix.contains("<svg");
        }
        return false;
    }

    private static boolean looksLikePng(byte[] data) {
        return data != null
                && data.length >= 8
                && (data[0] & 0xFF) == 0x89
                && data[1] == 'P'
                && data[2] == 'N'
                && data[3] == 'G'
                && (data[4] & 0xFF) == 0x0D
                && (data[5] & 0xFF) == 0x0A
                && (data[6] & 0xFF) == 0x1A
                && (data[7] & 0xFF) == 0x0A;
    }

    private static String uploadPng(byte[] png, TournamentConfig.Snapshot config, URI uploadEndpoint)
            throws IOException, InterruptedException {
        if (!looksLikePng(png)) {
            throw new IOException("Refusing to upload data that is not a PNG.");
        }

        String boundary = "----CobbleKantoTournamentBoundary7MA4YWxk";
        ByteArrayOutputStream body = new ByteArrayOutputStream(png.length + 512);
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write("Content-Disposition: form-data; name=\"image\"; filename=\"cobblekanto-bracket.png\"\r\n"
                .getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: image/png\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(png);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        String key = URLEncoder.encode(config.imgbbApiKey(), StandardCharsets.UTF_8);
        String separator = uploadEndpoint.toString().contains("?") ? "&" : "?";
        URI uri = URI.create(uploadEndpoint + separator + "key=" + key);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.httpConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(config.httpRequestTimeoutSeconds(), 12)))
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("ImgBB HTTP " + response.statusCode() + ": " + compact(response.body()));
        }

        Map<String, Object> root = asObject(ChallongeTournamentClient.parseJson(response.body()));
        if (!Boolean.TRUE.equals(root.get("success"))) {
            throw new IOException("ImgBB did not report success: " + compact(response.body()));
        }
        Map<String, Object> data = asObject(root.get("data"));
        String url = stringValue(data.get("url"));
        if (url.isBlank()) {
            url = stringValue(data.get("display_url"));
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new IOException("ImgBB returned no usable direct image URL.");
        }
        return url;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) throws IOException {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (value == null) {
            return Map.of();
        }
        throw new IOException("Expected JSON object but received " + value.getClass().getSimpleName() + ".");
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String compact(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() > 280 ? compact.substring(0, 280) + "..." : compact;
    }

    record RenderedPng(byte[] bytes, int width, int height) {
    }

    public record PublishResult(
            String imageUrl,
            int sourceSvgBytes,
            int pngBytes,
            int width,
            int height,
            String sourceSvgUrl
    ) {
    }
}
