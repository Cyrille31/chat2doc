/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Repérage des adresses web dans le texte des messages. */
public final class Links {

    private Links() { }

    static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>\"]+");

    private static final String TRAILING = ".,;:!?)]}»’'";

    private static final Pattern YOUTUBE = Pattern.compile(
            "(?i)^https?://(?:www\\.|m\\.|music\\.)?(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/)|youtu\\.be/)"
                    + "([A-Za-z0-9_-]{11})");

    /** Une adresse trouvée dans un texte : position et forme affichée. */
    public static final class Found {
        public final int start, end;
        /** Texte tel qu'écrit dans le message. */
        public final String shown;
        /** Adresse complète (« www. » complété en « http:// »). */
        public final String url;

        Found(int start, int end, String shown) {
            this.start = start;
            this.end = end;
            this.shown = shown;
            this.url = shown.toLowerCase(Locale.ROOT).startsWith("www.") ? "http://" + shown : shown;
        }
    }

    /** Adresses présentes dans un texte, sans la ponctuation finale. */
    public static List<Found> find(String text) {
        List<Found> out = new ArrayList<>();
        Matcher m = URL.matcher(text);
        while (m.find()) {
            int end = m.end();
            while (end > m.start() && TRAILING.indexOf(text.charAt(end - 1)) >= 0) end--;
            if (end - m.start() > 4) out.add(new Found(m.start(), end, text.substring(m.start(), end)));
        }
        return out;
    }

    /** Toutes les adresses distinctes de la discussion, dans l'ordre d'apparition. */
    public static List<String> collect(List<Message> messages) {
        Set<String> set = new LinkedHashSet<>();
        for (Message m : messages) {
            if (m.text == null || m.text.isEmpty()) continue;
            for (Found f : find(m.text)) set.add(f.url);
        }
        return new ArrayList<>(set);
    }

    /** Identifiant d'une vidéo YouTube, ou {@code null}. */
    public static String youtubeId(String url) {
        Matcher m = YOUTUBE.matcher(url);
        return m.find() ? m.group(1) : null;
    }
}
