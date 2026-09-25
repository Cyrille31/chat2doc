/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyse le fichier texte d'un export WhatsApp.
 *
 * <p>Formats reconnus :
 * <ul>
 *   <li>Android : {@code 24/09/2026 21:15 - Marie: Bonjour}</li>
 *   <li>iPhone : {@code [24/09/2026 21:15:32] Marie: Bonjour}</li>
 *   <li>variantes anglaises : {@code 9/24/26, 9:15 PM - Marie: Hello}</li>
 * </ul>
 * L'ordre jour/mois ou mois/jour est déduit de l'ensemble du fichier (un premier nombre supérieur à 12
 * impose jour/mois, un second supérieur à 12 impose mois/jour) ; s'il reste ambigu, on retient
 * l'ordre préféré passé au constructeur.
 */
public final class ChatParser {

    /** En-tête d'un message : date, heure, séparateur, puis « Expéditeur: texte » ou message système. */
    static final Pattern HEADER = Pattern.compile(
            "^\\[?(\\d{1,4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,4}),?\\s+"
                    + "(\\d{1,2})[:.](\\d{2})(?:[:.](\\d{2}))?"
                    + "(?:\\s*([AaPp])\\.?\\s?[Mm]\\.?)?"
                    + "(?:\\]\\s?|\\s[-\u2013]\\s)(.*)$");

    private static final Pattern INVISIBLE = Pattern.compile("[\u200e\u200f\u202a-\u202e\u2066-\u2069\ufeff]");

    private static final Pattern ATT_ANDROID = Pattern.compile(
            "^(.+?)\\s*\\((?:fichier joint|pi\u00e8ce jointe|file attached|archivo adjunto|arquivo anexado"
                    + "|Datei angeh\u00e4ngt|file allegato|bestand bijgevoegd)\\)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern ATT_IOS = Pattern.compile(
            "<(?:joint|pi\u00e8ce jointe|attached|adjunto|anexado|Anhang|allegato|bijlage)\\s*:\\s*([^<>\\n]+?)\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern FILE_TOKEN = Pattern.compile(
            "(?<![\\w.\\-])([\\w\\-]+(?:\\.[\\w\\-]+)*\\.(?:jpe?g|png|gif|webp|heic|mp4|3gp|mov|opus|ogg|m4a|mp3|aac"
                    + "|pdf|docx?|xlsx?|pptx?|vcf))(?![\\w])",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DOC_INFO = Pattern.compile("^.+\\.\\w{2,5}\\s*\u2022\\s*\\d+\\s*pages?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern OMITTED = Pattern.compile(
            "^(?:<?(?:m\u00e9dias? omis|media omitted|multimedia omitido|medien ausgeschlossen|media omessi|m\u00eddia oculta)>?"
                    + "|(?:image|photo|vid\u00e9o|video|audio|sticker|autocollant|gif|document|carte de contact|contact card)"
                    + "\\s+(?:absente?|omise?|omitted|retir\u00e9e?))$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern DELETED = Pattern.compile(
            "^(?:ce message a \u00e9t\u00e9 supprim\u00e9|vous avez supprim\u00e9 ce message|this message was deleted"
                    + "|you deleted this message|se elimin\u00f3 este mensaje|eliminaste este mensaje"
                    + "|diese nachricht wurde gel\u00f6scht|du hast diese nachricht gel\u00f6scht)\\.?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern EDITED = Pattern.compile(
            "\\s*<(?:ce message a \u00e9t\u00e9 modifi\u00e9|this message was edited|se edit\u00f3 este mensaje"
                    + "|diese nachricht wurde bearbeitet|messaggio modificato)>\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final Map<String, String> filesByLowerName;
    private final boolean dayFirstByDefault;
    private final String chatTitle;

    /**
     * @param fileNames         noms des fichiers présents dans l'export (pour reconnaître les pièces jointes)
     * @param dayFirstByDefault ordre retenu si les dates sont ambiguës (true = jour/mois, usage français)
     * @param chatTitle         nom de la discussion, s'il est connu (sert à repérer les messages système iPhone)
     */
    public ChatParser(Iterable<String> fileNames, boolean dayFirstByDefault, String chatTitle) {
        this.filesByLowerName = new HashMap<>();
        for (String f : fileNames) filesByLowerName.put(f.toLowerCase(Locale.ROOT), f);
        this.dayFirstByDefault = dayFirstByDefault;
        this.chatTitle = chatTitle;
    }

    /** Supprime les caractères invisibles de mise en forme et normalise les espaces insécables. */
    public static String clean(String s) {
        s = INVISIBLE.matcher(s).replaceAll("");
        return s.replace('\u202f', ' ').replace('\u00a0', ' ');
    }

    /** Vrai si le texte ressemble à un export WhatsApp (au moins un en-tête de message dans le début du fichier). */
    public static boolean looksLikeChat(String text) {
        String[] lines = clean(text).split("\r\n|\r|\n", 60);
        int n = 0;
        for (String line : lines) if (HEADER.matcher(line).matches()) n++;
        return n >= Math.min(2, lines.length);
    }

    // ------------------------------------------------------------------------------------------------

    private static final class Raw {
        final int a, b, c, hour, minute;
        final String ampm, rest, line;
        final StringBuilder more = new StringBuilder();

        Raw(Matcher m, String line) {
            a = Integer.parseInt(m.group(1));
            b = Integer.parseInt(m.group(2));
            c = Integer.parseInt(m.group(3));
            hour = Integer.parseInt(m.group(4));
            minute = Integer.parseInt(m.group(5));
            ampm = m.group(7);
            rest = m.group(8);
            this.line = line;
        }
    }

    /** Analyse le texte complet de l'export. */
    public List<Message> parse(String rawText) {
        String[] lines = clean(rawText).split("\r\n|\r|\n", -1);

        // 1er passage : repérer les en-têtes et rattacher les lignes de suite
        List<Raw> raws = new ArrayList<>();
        for (String line : lines) {
            Matcher m = HEADER.matcher(line);
            if (m.matches()) {
                raws.add(new Raw(m, line));
            } else if (!raws.isEmpty()) {
                raws.get(raws.size() - 1).more.append('\n').append(line);
            }
        }

        // Ordre des dates, déduit de tout le fichier
        boolean yearFirst = false, dayFirstSeen = false, monthFirstSeen = false;
        for (Raw r : raws) {
            if (r.a > 31) yearFirst = true;
            else if (r.a > 12) dayFirstSeen = true;
            else if (r.b > 12) monthFirstSeen = true;
        }
        boolean dayFirst = dayFirstSeen || (!monthFirstSeen && dayFirstByDefault);

        // 2e passage : construire les messages
        List<Message> out = new ArrayList<>();
        for (Raw r : raws) {
            LocalDateTime t = toDateTime(r, yearFirst, dayFirst);
            if (t == null) {
                // Pas une vraie date : c'est une ligne de texte qui ressemblait à un en-tête
                if (!out.isEmpty()) {
                    Message prev = out.get(out.size() - 1);
                    prev.text = prev.text + "\n" + r.line + r.more;
                }
                continue;
            }
            String rest = r.rest + r.more;
            String sender = null, text = rest;
            int p = rest.indexOf(": ");
            if (p > 0 && p <= 80) {
                String cand = rest.substring(0, p);
                if (plausibleSender(cand)) {
                    sender = cand.trim();
                    text = rest.substring(p + 2);
                }
            }
            if (sender != null && chatTitle != null && sender.equalsIgnoreCase(chatTitle.trim())) {
                sender = null; // iPhone : messages système attribués au nom du groupe
            }
            Message msg = new Message(t, sender, text);
            postProcess(msg);
            out.add(msg);
        }

        demoteImprobableSenders(out);
        return out;
    }

    private static LocalDateTime toDateTime(Raw r, boolean yearFirst, boolean dayFirst) {
        int year, month, day;
        if (yearFirst) {
            year = r.a; month = r.b; day = r.c;
        } else if (dayFirst) {
            day = r.a; month = r.b; year = r.c;
        } else {
            month = r.a; day = r.b; year = r.c;
        }
        if (year < 100) year += 2000;
        int hour = r.hour;
        if (r.ampm != null) {
            boolean pm = r.ampm.equalsIgnoreCase("p");
            if (hour == 12) hour = pm ? 12 : 0;
            else if (pm) hour += 12;
        }
        try {
            return LocalDateTime.of(year, month, day, hour, r.minute);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static boolean plausibleSender(String s) {
        if (s.isEmpty() || s.endsWith(" ") || s.startsWith(" ")) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\u00ab' || ch == '\u00bb' || ch == '"' || ch == '\u201c' || ch == '\u201d' || ch == '\n') return false;
        }
        return true;
    }

    /** Un « expéditeur » vu une seule fois et long de plusieurs mots est en fait un message système. */
    private static void demoteImprobableSenders(List<Message> msgs) {
        Map<String, Integer> count = new HashMap<>();
        for (Message m : msgs) if (m.sender != null) count.merge(m.sender, 1, Integer::sum);
        for (int i = 0; i < msgs.size(); i++) {
            Message m = msgs.get(i);
            if (m.sender != null && count.get(m.sender) == 1 && m.sender.split("\\s+").length >= 5) {
                Message sys = new Message(m.time, null, m.sender + ": " + m.text);
                msgs.set(i, sys);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------

    private void postProcess(Message msg) {
        String text = msg.text;

        Matcher me = EDITED.matcher(text);
        if (me.find()) {
            msg.edited = true;
            text = text.substring(0, me.start());
        }

        // iPhone : <joint : 00000012-PHOTO-2026-09-24-21-15-32.jpg>
        Matcher mi = ATT_IOS.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (mi.find()) {
            msg.attachments.add(resolve(mi.group(1)));
            mi.appendReplacement(sb, "");
        }
        mi.appendTail(sb);
        text = sb.toString();

        // Android : IMG-20260924-WA0003.jpg (fichier joint)
        String[] lines = text.split("\n", -1);
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            Matcher ma = ATT_ANDROID.matcher(line.trim());
            if (ma.matches()) {
                msg.attachments.add(resolve(ma.group(1)));
            } else {
                kept.add(line);
            }
        }

        // Filet de sécurité : noms de fichiers cités tels quels et présents dans l'export
        for (int i = 0; i < kept.size(); i++) {
            Matcher mt = FILE_TOKEN.matcher(kept.get(i));
            StringBuffer lb = new StringBuffer();
            boolean changed = false;
            while (mt.find()) {
                String real = filesByLowerName.get(mt.group(1).toLowerCase(Locale.ROOT));
                if (real != null && !msg.attachments.contains(real)) {
                    msg.attachments.add(real);
                    mt.appendReplacement(lb, "");
                    changed = true;
                }
            }
            if (changed) {
                mt.appendTail(lb);
                kept.set(i, lb.toString());
            }
        }

        // Nettoyage des lignes résiduelles (« Facture.pdf • 3 pages », légende répétant le nom du fichier)
        if (!msg.attachments.isEmpty()) {
            List<String> filtered = new ArrayList<>();
            for (String line : kept) {
                String t = line.trim();
                if (DOC_INFO.matcher(t).matches() || msg.attachments.contains(t)) continue;
                filtered.add(line);
            }
            kept = filtered;
        }

        text = trimLines(String.join("\n", kept));

        if (OMITTED.matcher(text).matches()) {
            msg.mediaOmitted = true;
            text = "";
        } else if (DELETED.matcher(text).matches()) {
            msg.deleted = true;
        }
        msg.text = text;
    }

    /** Retrouve le nom exact du fichier dans l'export ; à défaut, renvoie le nom tel que cité. */
    private String resolve(String cited) {
        String name = cited.trim();
        int bullet = name.indexOf('\u2022');
        if (bullet > 0) name = name.substring(0, bullet).trim();
        String real = filesByLowerName.get(name.toLowerCase(Locale.ROOT));
        if (real != null) return real;
        // Dernier mot, au cas où du texte précède le nom du fichier
        int sp = name.lastIndexOf(' ');
        if (sp > 0) {
            real = filesByLowerName.get(name.substring(sp + 1).toLowerCase(Locale.ROOT));
            if (real != null) return real;
        }
        return name;
    }

    private static String trimLines(String s) {
        int start = 0, end = s.length();
        while (start < end && Character.isWhitespace(s.charAt(start))) start++;
        while (end > start && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }
}
