package gator.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.owasp.html.HtmlSanitizer;
import org.owasp.html.HtmlStreamEventReceiver;
import org.owasp.html.HtmlStreamEventReceiverWrapper;
import org.owasp.html.HtmlStreamRenderer;

/** Presentation transform for the CSP-sandboxed message document, never for application HTML. */
final class MailHtml {
    private static final Set<String> ELEMENTS = Set.of(
            "html", "head", "body", "title", "style", "a", "abbr", "address", "article", "aside", "b", "bdi", "bdo",
            "big", "blockquote", "br", "caption", "center", "cite", "code", "col", "colgroup", "dd", "del", "dfn",
            "div", "dl", "dt", "em", "figcaption", "figure", "font", "footer", "h1", "h2", "h3", "h4", "h5", "h6",
            "header", "hr", "i", "img", "ins", "kbd", "li", "main", "mark", "nav", "ol", "p", "pre", "q", "rp", "rt",
            "ruby", "s", "samp", "section", "small", "span", "strike", "strong", "sub", "sup", "table", "tbody", "td",
            "tfoot", "th", "thead", "time", "tr", "tt", "u", "ul", "var", "wbr");
    private static final Set<String> ATTRIBUTES = Set.of(
            "id", "class", "title", "lang", "dir", "role", "align", "valign", "width", "height", "border", "cellpadding",
            "cellspacing", "colspan", "rowspan", "bgcolor", "color", "face", "size", "alt", "hspace", "vspace", "nowrap",
            "span", "start", "reversed", "type", "value", "scope", "headers", "summary", "frame", "rules", "clear",
            "text", "link", "vlink", "alink", "topmargin", "leftmargin", "marginwidth", "marginheight");
    private static final Set<String> DISCARD_CONTENT = Set.of("script", "iframe", "object", "applet", "svg", "math", "template");
    private static final Pattern CSS_URL = Pattern.compile("(?i)url\\(\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s'\"()]*))\\s*\\)");
    private static final Pattern CSS_IMPORT = Pattern.compile("(?is)@import\\b[^;]*(?:;|$)");
    private static final Pattern CSS_FONT = Pattern.compile("(?is)@font-face\\s*\\{[^}]*}");
    private static final Pattern CSS_UNSUPPORTED = Pattern.compile("(?is)image(?:-set)?\\s*\\(|expression\\s*\\(|-moz-binding\\s*:|behavior\\s*:");

    static String render(String html, UnaryOperator<String> imageUrl) {
        StringBuilder result = new StringBuilder("<!doctype html>");
        class Policy extends HtmlStreamEventReceiverWrapper implements HtmlSanitizer.Policy {
            private String discarded;
            private int depth;
            private boolean style;
            private final StringBuilder css = new StringBuilder();

            Policy(HtmlStreamEventReceiver receiver) { super(receiver); }

            @Override public void openTag(String name, List<String> attributes) {
                if (discarded != null) {
                    if (discarded.equals(name)) depth++;
                    return;
                }
                if (DISCARD_CONTENT.contains(name)) { discarded = name; depth = 1; return; }
                if (!ELEMENTS.contains(name)) return;
                List<String> safe = new ArrayList<>();
                for (int i = 0; i + 1 < attributes.size(); i += 2) {
                    String key = attributes.get(i);
                    String value = attributes.get(i + 1);
                    if ("style".equals(key)) value = css(value, imageUrl);
                    else if ("background".equals(key) || ("src".equals(key) && "img".equals(name)))
                        value = imageUrl.apply(value.trim());
                    else if ("href".equals(key) && "a".equals(name)) {
                        value = value.trim();
                        if (!value.matches("(?is)(https?://|mailto:)[^\\x00-\\x20]*")) continue;
                    } else if (!ATTRIBUTES.contains(key) && !key.startsWith("aria-")) continue;
                    // ponytail: srcset is omitted; the email's src remains the image fallback.
                    if (value == null || value.isEmpty()) continue;
                    safe.add(key);
                    safe.add(value);
                }
                if ("a".equals(name)) safe.addAll(List.of("target", "_blank", "rel", "noreferrer noopener"));
                super.openTag(name, safe);
                if ("style".equals(name)) { style = true; css.setLength(0); }
            }

            @Override public void closeTag(String name) {
                if (discarded != null) {
                    if (discarded.equals(name) && --depth == 0) discarded = null;
                    return;
                }
                if (!ELEMENTS.contains(name)) return;
                if ("style".equals(name) && style) { super.text(css(css.toString(), imageUrl)); style = false; }
                super.closeTag(name);
            }

            @Override public void text(String text) {
                if (discarded != null) return;
                if (style) css.append(text);
                else super.text(text);
            }
        }
        HtmlSanitizer.sanitize(html == null ? "" : html, new Policy(HtmlStreamRenderer.create(result, ignored -> { })));
        return result.toString();
    }

    private static String css(String value, UnaryOperator<String> imageUrl) {
        // Reject escaped or non-url image syntax instead of trying to implement a second CSS lexer.
        // CSP is required as the backstop for browser-specific syntax and all non-image resources.
        value = value.replaceAll("(?s)/\\*.*?\\*/", "");
        if (value.indexOf('\\') >= 0 || CSS_UNSUPPORTED.matcher(value).find()) return "";
        value = CSS_IMPORT.matcher(value).replaceAll("");
        value = CSS_FONT.matcher(value).replaceAll("");
        Matcher matcher = CSS_URL.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String source = matcher.group(1) != null ? matcher.group(1)
                    : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            String target = imageUrl.apply(source.trim());
            if (target == null) target = "";
            target = target.replace("\\", "%5C").replace("\"", "%22").replace("\n", "%0A").replace("\r", "%0D")
                    .replace("<", "%3C");
            matcher.appendReplacement(result, Matcher.quoteReplacement("url(\"" + target + "\")"));
        }
        matcher.appendTail(result);
        // An unparsed url() must never retain its original fetch destination.
        if (CSS_URL.matcher(value).replaceAll("").toLowerCase(Locale.ROOT).matches("(?s).*url\\s*\\(.*")) return "";
        return result.toString();
    }
}
