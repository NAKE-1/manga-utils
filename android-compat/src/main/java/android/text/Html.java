package android.text;


import android.graphics.drawable.Drawable;
import org.jetbrains.annotations.NotNull;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.xml.sax.XMLReader;

/**
 * Project: TachiServer
 * Author: nulldev
 * Creation Date: 16/08/16
 *
 * Android compat class for processing HTML
 */

public class Html {

    // API 24+ fromHtml flag constants (see android.text.Html). We ignore the flags — this shim strips to
    // plain text — but sources reference these fields, so declare them to avoid NoSuchFieldError.
    public static final int FROM_HTML_MODE_LEGACY = 0x00000000;
    public static final int FROM_HTML_MODE_COMPACT = 0x0000003f;
    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_HEADING = 0x00000002;
    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_LIST_ITEM = 0x00000004;
    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_LIST = 0x00000008;
    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH = 0x00000010;
    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_BLOCKQUOTE = 0x00000020;
    public static final int FROM_HTML_OPTION_USE_CSS_COLORS = 0x00000100;

    public static Spanned fromHtml(String source) {
        return new FakeSpanned(Jsoup.clean(source, Safelist.none()));
    }

    // API 24+ overloads. The flags only control paragraph spacing/separators, which don't matter here
    // (we strip to plain text) — so delegate to the single-arg version. (Tapas & co. call fromHtml(s, flags).)
    public static Spanned fromHtml(String source, int flags) {
        return fromHtml(source);
    }

    public static Spanned fromHtml(String source, int flags, Html.ImageGetter imageGetter, Html.TagHandler tagHandler) {
        return fromHtml(source);
    }

    public static Spanned fromHtml(String source, Html.ImageGetter imageGetter, Html.TagHandler tagHandler) {
        throw new RuntimeException("Stub!");
    }

    public static String toHtml(Spanned text) {
        return text.toString();
    }

    /** From: http://stackoverflow.com/a/25228492/5054192 **/
    public static String escapeHtml(CharSequence s) {
        StringBuilder out = new StringBuilder(Math.max(16, s.length()));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 127 || c == '"' || c == '<' || c == '>' || c == '&') {
                out.append("&#");
                out.append((int) c);
                out.append(';');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    public interface TagHandler {
        void handleTag(boolean var1, String var2, Editable var3, XMLReader var4);
    }

    public interface ImageGetter {
        Drawable getDrawable(String var1);
    }

    private static class FakeSpanned implements Spanned {

        String string;

        public FakeSpanned(String string) {
            this.string = string;
        }

        @Override
        public <T> T[] getSpans(int i, int i1, Class<T> aClass) {
            return null;
        }

        @Override
        public int getSpanStart(Object o) {
            return 0;
        }

        @Override
        public int getSpanEnd(Object o) {
            return 0;
        }

        @Override
        public int getSpanFlags(Object o) {
            return 0;
        }

        @Override
        public int nextSpanTransition(int i, int i1, Class aClass) {
            return 0;
        }

        @Override
        public int length() {
            return 0;
        }

        @Override
        public char charAt(int index) {
            return 0;
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return null;
        }

        @NotNull
        @Override
        public String toString() {
            return string;
        }
    }
}
