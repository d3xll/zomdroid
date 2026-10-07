package com.zomdroid.steam.workshop;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SteamHtmlDecoder {
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9A-Fa-f]+);");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern BBCODE_MEDIA = Pattern.compile("(?is)\\[(?:img|previewyoutube|previewyoutubehd)[^\\]]*\\].*?\\[/(?:img|previewyoutube|previewyoutubehd)\\]");
    private static final Pattern BBCODE_GENERIC = Pattern.compile("\\[(?:/?[A-Za-z][A-Za-z0-9_]*|\\*)(?:=[^\\]]+)?\\]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern MULTI_NEWLINE = Pattern.compile("\n{3,}");

    private SteamHtmlDecoder() {}

    public static String stripTagsAndDecode(String value) {
        if (value == null || value.isEmpty()) return "";
        String text = HTML_TAG.matcher(value).replaceAll(" ");
        return decode(text);
    }

    public static String decode(String value) {
        if (value == null || value.isEmpty()) return "";
        return WHITESPACE.matcher(decodeEntities(value)).replaceAll(" ").trim();
    }

    public static String decodeWorkshopDescription(String value) {
        if (value == null || value.isEmpty()) return "";
        String text = value
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)<li[^>]*>", "\n• ")
                .replaceAll("(?i)</li\\s*>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n\n")
                .replaceAll("(?i)</div\\s*>", "\n")
                .replaceAll("(?i)\\[\\*\\]", "\n• ")
                .replaceAll("(?i)\\[/?(?:h[1-6]|list|olist|quote|p|center|left|right)\\]", "\n");

        text = BBCODE_MEDIA.matcher(text).replaceAll(" ");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        text = BBCODE_GENERIC.matcher(text).replaceAll(" ");
        text = decodeEntities(text);

        String[] lines = text.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            sb.append(trimmed).append("\n");
        }
        return MULTI_NEWLINE.matcher(sb.toString().trim()).replaceAll("\n\n");
    }

    public static String decodeEntities(String text) {
        if (text == null || text.isEmpty()) return "";
        String res = text
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&trade;", "™")
                .replace("&copy;", "©")
                .replace("&reg;", "®");

        Matcher m = NUMERIC_ENTITY.matcher(res);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String group = m.group(1);
            try {
                int code;
                if (group.startsWith("x") || group.startsWith("X")) {
                    code = Integer.parseInt(group.substring(1), 16);
                } else {
                    code = Integer.parseInt(group, 10);
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(code))));
            } catch (Exception e) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
