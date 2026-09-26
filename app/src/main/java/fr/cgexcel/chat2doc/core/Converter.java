/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chef d'orchestre de la conversion, indépendant d'Android.
 *
 * <ol>
 *   <li>{@link #inspect} : repère le fichier de la discussion dans l'export et son nom ;</li>
 *   <li>{@link #prepare} : range les nouveaux médias dans l'archive, conserve le texte de l'export,
 *       fusionne tous les exports connus de la discussion, repère les liens ;</li>
 *   <li>{@link #finish} : écrit le document Word (et l'archive .zip en mode sans dossier).</li>
 * </ol>
 */
public final class Converter {

    /** Réglages de la conversion. */
    public static final class Options {
        /** Plus grand côté des photos insérées dans le Word, en pixels (0 = taille d'origine). */
        public int imageMaxPx = 1280;
        /** Ordre des dates retenu quand le fichier ne permet pas de trancher (jour/mois à la française). */
        public boolean dayFirstByDefault = true;
        /** Nom de la discussion transmis par WhatsApp (objet du partage, nom du .zip...), s'il est connu. */
        public String titleHint;
        /** Produire l'archive .zip finale (mode sans dossier Chat2Doc). */
        public boolean makeZip = true;
        /** Un document Word par année (sinon un seul document). */
        public boolean splitByYear = true;
    }

    /** Export reçu : fichier de la discussion et nom. */
    public static final class Inspection {
        public String title;
        /** Nom de dossier correspondant au titre. */
        public String safe;
        File chat;
        List<File> files;
    }

    /** Discussion analysée et médias rangés, en attente de la mise en page. */
    public static final class Prepared {
        public String title;
        /** L'archive contenait déjà cette discussion. */
        public boolean update;
        /** Nombre d'exports distincts réunis, messages avant et après ce nouvel export. */
        public int exports, previousMessages, totalMessages;
        /** Avertissement sur un export probablement tronqué par WhatsApp, ou {@code null}. */
        public String warning;
        /** Tous les liens de la discussion ; ceux dont l'aperçu reste à chercher ; parmi eux, vidéos YouTube. */
        public List<String> links, linksToFetch;
        public int youtubeLinks;
        public Archive archive;
        String safe;
        List<Message> messages;
        Map<String, MediaFile> media;
        Map<String, LinkPreviewFetcher.Preview> cachedPreviews;
        Set<String> failedPreviews;
        Options opt;
        File workDir;
    }

    /** Bilan de la conversion. */
    public static final class Result {
        public String title;
        public File folder;
        public File docx;
        /** Nom du document Word le plus récent dans le dossier de la discussion. */
        public String docxName;
        /** Tous les documents Word de la discussion, et ceux qui ont été (re)créés. */
        public List<String> volumes = new ArrayList<>(), rewritten = new ArrayList<>();
        public File zip;
        public DocxWriter.Stats stats;
        public int embeddedPictures;
        public int mediaFiles;
        /** Liens distincts dans la discussion, et aperçus disponibles. */
        public int links, previews;
        public boolean update;
        public int exports, newMessages;
        public String warning;
        public Archive archive;
    }

    private static final String MEDIA_INDEX = Archive.STATE + "/medias.tsv";
    private static final String PREVIEW_INDEX = Archive.STATE + "/apercus.tsv";
    private static final String PREVIEW_DIR = Archive.STATE + "/apercus";
    /** Résumé de l'archive (titre, période, nombre de messages, date de mise à jour, nom du document). */
    public static final String SUMMARY = Archive.STATE + "/resume.tsv";
    private static final String VOLUMES = Archive.STATE + "/volumes.tsv";
    /** À augmenter quand la mise en page change, pour que les documents existants soient réécrits. */
    private static final String FORMAT_VERSION = "1.4";

    private static final Pattern TITLE_PREFIX = Pattern.compile(
            "^(?:discussion whatsapp avec |whatsapp chat with |whatsapp chat - |whatsapp chat mit |whatsapp-chat mit "
                    + "|chat de whatsapp con |chat whatsapp con |conversa do whatsapp com |whatsapp-chat met |whatsapp )",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private Converter() { }

    // ================================================================================================

    /** Conversion complète sans dossier Chat2Doc ni aperçus (utilisée par les tests). */
    public static Result convert(File inputDir, File outputDir, File workDir, Options opt,
                                 ImageProcessor images, ProgressListener progress) throws IOException {
        Inspection ins = inspect(inputDir, opt);
        Archive archive = new Archive(new File(outputDir, ins.safe), null);
        return finish(prepare(ins, archive, workDir, opt, progress), null, null, images, progress);
    }

    /** Repère le fichier de la discussion et son nom. */
    public static Inspection inspect(File inputDir, Options opt) throws IOException {
        List<File> all = new ArrayList<>();
        listFiles(inputDir, all);
        File chat = findChatFile(all);
        if (chat == null) {
            throw new IOException("Aucune discussion WhatsApp trouvée : le partage doit contenir le fichier .txt de l’export.");
        }
        Inspection ins = new Inspection();
        ins.chat = chat;
        ins.files = all;
        ins.title = guessTitle(chat.getName(), opt.titleHint);
        ins.safe = safeFileName(ins.title);
        return ins;
    }

    /**
     * Range les médias du nouvel export dans l'archive, y conserve le texte de l'export, fusionne
     * tous les exports de la discussion et repère les liens.
     *
     * @param archive archive de la discussion (vide pour une première conversion)
     */
    public static Prepared prepare(Inspection ins, Archive archive, File workDir, Options opt,
                                   ProgressListener progress) throws IOException {
        progress.onProgress("Lecture de la discussion", 0, 0);
        //noinspection ResultOfMethodCallIgnored
        archive.dir.mkdirs();

        // Médias déjà présents dans l'archive
        Map<String, MediaFile> media = new LinkedHashMap<>();
        for (String[] r : archive.readTable(MEDIA_INDEX)) {
            if (r.length < 4) continue;
            MediaKind kind;
            try {
                kind = MediaKind.valueOf(r[2]);
            } catch (IllegalArgumentException e) {
                kind = MediaKind.of(r[0]);
            }
            long size;
            try {
                size = Long.parseLong(r[3]);
            } catch (NumberFormatException e) {
                size = 0;
            }
            media.put(r[0].toLowerCase(Locale.ROOT), new MediaFile(r[0], r[1], archive.file(r[1]), kind, size));
        }

        // Rangement des nouveaux médias
        int n = 0, total = ins.files.size() - 1;
        for (File f : ins.files) {
            if (f.equals(ins.chat)) continue;
            if (progress.isCancelled()) throw new ProgressListener.CancelledException();
            progress.onProgress("Rangement des médias", ++n, total);
            String key = f.getName().toLowerCase(Locale.ROOT);
            if (media.containsKey(key)) continue; // déjà dans l'archive
            if (f.getName().toLowerCase(Locale.ROOT).endsWith(".txt") && ChatParser.looksLikeChat(readHead(f))) continue;
            MediaKind kind = MediaKind.of(f.getName());
            File dir = archive.file(kind.folder);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File dest = uniqueFile(dir, f.getName());
            long size = f.length();
            if (!f.renameTo(dest)) copyFile(f, dest);
            String rel = kind.folder + "/" + dest.getName();
            archive.markChanged(rel);
            media.put(key, new MediaFile(f.getName(), rel, dest, kind, size));
        }

        // Texte de l'export, conservé à côté des précédents (sauf s'il est identique à l'un d'eux)
        String newText = readUtf8(ins.chat);
        File textsDir = archive.file(Archive.TEXTS);
        //noinspection ResultOfMethodCallIgnored
        textsDir.mkdirs();
        File[] previous = textsDir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".txt"));
        List<File> texts = new ArrayList<>(previous == null ? new ArrayList<>() : Arrays.asList(previous));
        texts.sort((a, b) -> a.getName().compareTo(b.getName()));
        boolean duplicate = false;
        for (File t : texts) {
            if (t.length() == ins.chat.length() && readUtf8(t).equals(newText)) duplicate = true;
        }

        List<String> names = new ArrayList<>();
        for (MediaFile mf : media.values()) names.add(mf.name);
        ChatParser parser = new ChatParser(names, opt.dayFirstByDefault, ins.title);

        List<List<Message>> older = new ArrayList<>();
        for (File t : texts) older.add(parser.parse(readUtf8(t)));
        List<Message> fresh = parser.parse(newText);
        if (fresh.isEmpty()) {
            throw new IOException("Le fichier « " + ins.chat.getName() + " » ne contient aucun message reconnu.");
        }

        Prepared p = new Prepared();
        p.title = ins.title;
        p.safe = archive.dir.getName();
        p.archive = archive;
        p.opt = opt;
        p.workDir = workDir;
        p.media = media;
        p.update = !older.isEmpty();
        p.previousMessages = p.update ? countUser(ChatMerge.merge(older)) : 0;

        List<List<Message>> all = new ArrayList<>(older);
        if (!duplicate) {
            all.add(fresh);
            String base = ins.chat.getName().replaceAll("(?i)\\.txt$", "");
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH'h'mm"));
            String name = safeFileName(base + " - export du " + stamp) + ".txt";
            File dest = uniqueFile(textsDir, name);
            try (OutputStream os = new FileOutputStream(dest)) {
                os.write(newText.getBytes(StandardCharsets.UTF_8));
            }
            archive.markChanged(Archive.TEXTS + "/" + dest.getName());
        }
        p.exports = all.size();
        p.messages = ChatMerge.merge(all);
        p.totalMessages = countUser(p.messages);
        p.warning = truncationWarning(fresh, total > 0, p.messages);

        // Liens, et aperçus déjà connus
        p.cachedPreviews = new HashMap<>();
        p.failedPreviews = new HashSet<>();
        for (String[] r : archive.readTable(PREVIEW_INDEX)) {
            if (r.length < 5) continue;
            if ("x".equals(r[1])) {
                p.failedPreviews.add(r[0]);
            } else {
                File img = r[4].isEmpty() ? null : archive.file(PREVIEW_DIR + "/" + r[4]);
                p.cachedPreviews.put(r[0], new LinkPreviewFetcher.Preview(r[0], blank(r[2]), blank(r[3]), img));
            }
        }
        p.links = Links.collect(p.messages);
        p.linksToFetch = new ArrayList<>();
        for (String l : p.links) {
            if (p.cachedPreviews.containsKey(l) || p.failedPreviews.contains(l)) continue;
            p.linksToFetch.add(l);
            if (Links.youtubeId(l) != null) p.youtubeLinks++;
        }
        return p;
    }

    /**
     * Écrit le document Word, les index de l'archive et, en mode sans dossier, l'archive .zip.
     *
     * @param fetched aperçus obtenus pour {@link Prepared#linksToFetch} (peut être {@code null})
     * @param failed  liens pour lesquels aucun aperçu n'a pu être obtenu (peut être {@code null})
     */
    public static Result finish(Prepared p, Map<String, LinkPreviewFetcher.Preview> fetched, Collection<String> failed,
                                ImageProcessor images, ProgressListener progress) throws IOException {
        Archive archive = p.archive;

        // Aperçus : ceux déjà connus, plus les nouveaux (images rangées dans l'archive)
        Map<String, LinkPreviewFetcher.Preview> previews = new HashMap<>(p.cachedPreviews);
        if (fetched != null) {
            int k = 0;
            File dir = archive.file(PREVIEW_DIR);
            for (Map.Entry<String, LinkPreviewFetcher.Preview> e : fetched.entrySet()) {
                LinkPreviewFetcher.Preview pv = e.getValue();
                File img = null;
                if (pv.image != null && pv.image.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    String ext = MediaKind.extension(pv.image.getName());
                    File dest;
                    do {
                        dest = new File(dir, "a" + System.currentTimeMillis() + "-" + (++k) + "." + ext);
                    } while (dest.exists());
                    if (!pv.image.renameTo(dest)) copyFile(pv.image, dest);
                    archive.markChanged(PREVIEW_DIR + "/" + dest.getName());
                    img = dest;
                }
                previews.put(e.getKey(), new LinkPreviewFetcher.Preview(pv.url, pv.title, pv.site, img));
            }
        }
        Set<String> failedAll = new HashSet<>(p.failedPreviews);
        if (failed != null) failedAll.addAll(failed);
        failedAll.removeAll(previews.keySet());
        if (fetched != null || failed != null) {
            List<String[]> rows = new ArrayList<>();
            for (LinkPreviewFetcher.Preview pv : previews.values()) {
                rows.add(new String[]{pv.url, "ok", nz(pv.title), nz(pv.site), pv.image == null ? "" : pv.image.getName()});
            }
            for (String f : failedAll) rows.add(new String[]{f, "x", "", "", ""});
            rows.sort((a, b) -> a[0].compareTo(b[0]));
            archive.writeTable(PREVIEW_INDEX, "adresse\tétat\ttitre\tsite\timage", rows);
        }

        // Documents Word : un par année (ou un seul) ; seuls ceux dont le contenu a changé sont réécrits
        Map<String, List<Message>> byVolume = new LinkedHashMap<>();
        for (Message m : p.messages) {
            String v = p.opt.splitByYear ? String.valueOf(m.time.getYear()) : "";
            byVolume.computeIfAbsent(v, x -> new ArrayList<>()).add(m);
        }
        Map<String, String> oldPrints = new HashMap<>();
        Set<String> oldDocs = new HashSet<>();
        for (String[] row : archive.readTable(VOLUMES)) {
            if (row.length >= 3) {
                oldPrints.put(row[1], row[2]);
                oldDocs.add(row[1]);
            }
        }
        for (String[] row : archive.readTable(SUMMARY)) {
            if (row.length >= 7) oldDocs.addAll(Arrays.asList(row[6].split("\\|"))); // version 1.3 : document unique
        }

        List<String> names = new ArrayList<>(), labels = new ArrayList<>();
        for (String v : byVolume.keySet()) {
            names.add(v.isEmpty() ? p.safe + ".docx" : v + " - " + p.safe + ".docx"); // l'année d'abord : lisible même tronqué
            labels.add(v);
        }
        Map<String, String> colors = DocxWriter.colorsFor(p.messages);
        File tmp = new File(p.workDir, "docx-tmp");
        ThumbCache cache = new ThumbCache(archive);
        List<String> volumeRows = new ArrayList<>();
        List<String[]> rowsVol = new ArrayList<>();
        int embedded = 0, k = 0;
        List<String> rewritten = new ArrayList<>();
        for (Map.Entry<String, List<Message>> e : byVolume.entrySet()) {
            String name = names.get(k), label = labels.get(k);
            k++;
            String print = fingerprint(e.getValue(), previews, p.opt.imageMaxPx, label, names);
            rowsVol.add(new String[]{label, name, print, String.valueOf(countUser(e.getValue()))});
            volumeRows.add(name);
            boolean present = archive.isRemote() || archive.file(name).exists();
            if (print.equals(oldPrints.get(name)) && present && oldDocs.contains(name)) {
                embedded += countPictures(e.getValue(), p.media);
                continue; // inchangé
            }
            if (progress.isCancelled()) throw new ProgressListener.CancelledException();
            Zips.deleteRecursively(tmp);
            //noinspection ResultOfMethodCallIgnored
            tmp.mkdirs();
            DocxWriter writer = new DocxWriter(tmp, images, p.opt.imageMaxPx, archive, cache);
            writer.setColors(colors);
            if (!label.isEmpty()) writer.setVolume(label, otherLabels(labels, label), p.safe);
            writer.write(archive.file(name), p.title, e.getValue(), p.media, previews, progress);
            embedded += writer.getEmbeddedPictures();
            archive.markChanged(name);
            rewritten.add(name);
        }
        cache.save();
        Zips.deleteRecursively(tmp);
        for (String old : oldDocs) if (!volumeRows.contains(old)) archive.remove(old); // découpage modifié
        archive.writeTable(VOLUMES, "volume\tdocument\tempreinte\tmessages", rowsVol);
        String docxName = names.get(names.size() - 1);
        File docx = archive.file(docxName);

        // Index des médias
        List<String[]> rows = new ArrayList<>();
        for (MediaFile mf : p.media.values()) {
            rows.add(new String[]{mf.name, mf.relativePath, mf.kind.name(), String.valueOf(mf.size)});
        }
        archive.writeTable(MEDIA_INDEX, "nom\tchemin\ttype\ttaille", rows);

        // Résumé, pour la liste des discussions enregistrées
        DocxWriter.Stats st = DocxWriter.computeStats(p.messages, p.media);
        List<String[]> summary = new ArrayList<>();
        summary.add(new String[]{p.title, st.first == null ? "" : st.first.toString(), st.last == null ? "" : st.last.toString(),
                String.valueOf(st.messages), String.valueOf(p.exports), LocalDateTime.now().withNano(0).toString(),
                String.join("|", volumeRows)});
        archive.writeTable(SUMMARY, "titre\tpremier message\tdernier message\tmessages\texports\tmise à jour\tdocument", summary);

        Result r = new Result();
        r.title = p.title;
        r.folder = archive.dir;
        r.docx = docx;
        r.docxName = docxName;
        r.stats = DocxWriter.computeStats(p.messages, p.media);
        r.embeddedPictures = embedded;
        r.volumes = volumeRows;
        r.rewritten = rewritten;
        r.mediaFiles = p.media.size();
        r.links = p.links.size();
        r.previews = 0;
        for (String l : p.links) if (previews.containsKey(l)) r.previews++;
        r.update = p.update;
        r.exports = p.exports;
        r.newMessages = p.totalMessages - p.previousMessages;
        r.warning = p.warning;
        r.archive = archive;

        if (p.opt.makeZip) {
            File zip = new File(archive.dir.getParentFile(), p.safe + " - Chat2Doc " + LocalDate.now() + ".zip");
            Zips.zipFolder(archive.dir, zip, progress);
            r.zip = zip;
        }
        return r;
    }

    // ------------------------------------------------------------------------------------------------

    /** Autres volumes, pour la page de garde (« Les autres années : 2024, 2026 »). */
    private static List<String> otherLabels(List<String> labels, String current) {
        List<String> out = new ArrayList<>(labels);
        out.remove(current);
        return out;
    }

    private static int countPictures(List<Message> msgs, Map<String, MediaFile> media) {
        int n = 0;
        for (Message m : msgs) {
            for (String a : m.attachments) {
                MediaFile mf = media.get(a.toLowerCase(Locale.ROOT));
                if (mf != null && mf.kind.isImage()) n++;
            }
        }
        return n;
    }

    /** Empreinte du contenu d'un volume : s'il n'a pas changé, inutile de réécrire le document. */
    private static String fingerprint(List<Message> msgs, Map<String, LinkPreviewFetcher.Preview> previews,
                                      int maxPx, String label, List<String> allNames) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            StringBuilder sb = new StringBuilder();
            sb.append(FORMAT_VERSION).append('|').append(maxPx).append('|').append(label).append('|')
                    .append(String.join(",", allNames)).append('\n');
            for (Message m : msgs) {
                sb.append(ChatMerge.key(m)).append('|').append(m.attachments).append('|')
                        .append(m.mediaOmitted).append(m.deleted).append(m.edited).append('\n');
                for (Links.Found f : Links.find(m.text)) {
                    LinkPreviewFetcher.Preview pv = previews.get(f.url);
                    if (pv != null) sb.append("L").append(f.url).append(pv.title).append(pv.site).append(pv.image != null).append('\n');
                }
                if (sb.length() > 1 << 16) {
                    md.update(sb.toString().getBytes(StandardCharsets.UTF_8));
                    sb.setLength(0);
                }
            }
            md.update(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return String.valueOf(System.nanoTime());
        }
    }

    private static int countUser(List<Message> msgs) {
        int n = 0;
        for (Message m : msgs) if (!m.isSystem()) n++;
        return n;
    }

    /** WhatsApp limite l'export à environ 10 000 messages avec médias et 40 000 sans. */
    private static String truncationWarning(List<Message> fresh, boolean withMedia, List<Message> merged) {
        int n = countUser(fresh);
        boolean olderKnown = !merged.isEmpty() && !fresh.isEmpty() && merged.get(0).time.isBefore(fresh.get(0).time);
        if (withMedia && n >= 9500 && !olderKnown) {
            return "Cet export compte près de 10 000 messages : WhatsApp en a probablement omis les plus anciens. "
                    + "Pour les récupérer (sans les photos), exportez aussi la discussion « sans les médias » et "
                    + "partagez-la avec Chat2Doc : les deux exports seront fusionnés.";
        }
        if (!withMedia && n >= 39000 && !olderKnown) {
            return "Cet export compte près de 40 000 messages, la limite de WhatsApp : les plus anciens manquent "
                    + "probablement. Avec un dossier Chat2Doc et des exports réguliers, l’historique se complète au fil du temps.";
        }
        return null;
    }

    private static String blank(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void listFiles(File dir, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) return;
        Arrays.sort(list);
        for (File f : list) {
            String name = f.getName();
            if (name.startsWith(".") || name.equals("__MACOSX")) continue;
            if (f.isDirectory()) listFiles(f, out);
            else out.add(f);
        }
    }

    /** Le fichier de la discussion : « _chat.txt » (iPhone), sinon le plus gros .txt qui ressemble à un export. */
    static File findChatFile(List<File> files) throws IOException {
        File best = null;
        for (File f : files) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".txt")) continue;
            if (name.equals("_chat.txt")) return f;
            if (!ChatParser.looksLikeChat(readHead(f))) continue;
            if (best == null || f.length() > best.length()) best = f;
        }
        return best;
    }

    /** « Discussion WhatsApp avec Famille.txt » → « Famille ». */
    static String guessTitle(String chatFileName, String hint) {
        String t = stripTitle(chatFileName);
        if (t.isEmpty() || t.equalsIgnoreCase("_chat")) t = hint == null ? "" : stripTitle(hint);
        if (t.isEmpty() || t.equalsIgnoreCase("_chat")) t = "Discussion WhatsApp";
        return t;
    }

    private static String stripTitle(String s) {
        s = ChatParser.clean(s).trim();
        s = s.replaceAll("(?i)\\.(txt|zip)$", "").trim();
        s = TITLE_PREFIX.matcher(s).replaceFirst("").trim();
        return s;
    }

    /** Nom utilisable comme nom de fichier sous Windows, Android et macOS. */
    public static String safeFileName(String s) {
        String r = s.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]", "_").trim();
        while (r.endsWith(".")) r = r.substring(0, r.length() - 1);
        if (r.length() > 80) r = r.substring(0, 80).trim();
        return r.isEmpty() ? "Discussion WhatsApp" : r;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        java.nio.file.Files.copy(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    static String readUtf8(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(f.length(), Integer.MAX_VALUE));
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String readHead(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n = is.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.UTF_8);
        }
    }
}
