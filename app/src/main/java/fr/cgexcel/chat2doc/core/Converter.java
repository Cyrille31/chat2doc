/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Chef d'orchestre de la conversion, indépendant d'Android :
 * dossier contenant l'export décompressé → dossier « Titre » (document Word + médias rangés) → archive .zip.
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
        /** Produire l'archive .zip finale. */
        public boolean makeZip = true;
    }

    /** Bilan de la conversion. */
    public static final class Result {
        public String title;
        public File folder;
        public File docx;
        public File zip;
        public DocxWriter.Stats stats;
        public int embeddedPictures;
        public int mediaFiles;
    }

    private static final Pattern TITLE_PREFIX = Pattern.compile(
            "^(?:discussion whatsapp avec |whatsapp chat with |whatsapp chat - |whatsapp chat mit |whatsapp-chat mit "
                    + "|chat de whatsapp con |chat whatsapp con |conversa do whatsapp com |whatsapp-chat met |whatsapp )",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private Converter() { }

    /**
     * @param inputDir  dossier où l'export a été décompressé (fichier .txt + médias)
     * @param outputDir dossier où créer le dossier de la discussion et l'archive
     * @param workDir   dossier de fichiers temporaires
     */
    public static Result convert(File inputDir, File outputDir, File workDir, Options opt,
                                 ImageProcessor images, ProgressListener progress) throws IOException {
        progress.onProgress("Lecture de la discussion", 0, 0);

        List<File> all = new ArrayList<>();
        listFiles(inputDir, all);
        File chat = findChatFile(all);
        if (chat == null) {
            throw new IOException("Aucune discussion WhatsApp trouvée : le partage doit contenir le fichier .txt de l’export.");
        }

        String title = guessTitle(chat.getName(), opt.titleHint);
        String safe = safeFileName(title);

        // Dossier de sortie : « Titre », vidé s'il existait déjà
        File folder = new File(outputDir, safe);
        Zips.deleteRecursively(folder);
        if (!folder.mkdirs()) throw new IOException("Impossible de créer le dossier " + folder);

        // Rangement des médias dans leurs sous-dossiers
        Map<String, MediaFile> media = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        int n = 0;
        for (File f : all) {
            if (f.equals(chat)) continue;
            if (progress.isCancelled()) throw new ProgressListener.CancelledException();
            MediaKind kind = MediaKind.of(f.getName());
            File dir = new File(folder, kind.folder);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File dest = uniqueFile(dir, f.getName());
            if (!f.renameTo(dest)) copyFile(f, dest);
            String key = f.getName().toLowerCase(Locale.ROOT);
            if (!media.containsKey(key)) {
                media.put(key, new MediaFile(f.getName(), kind.folder + "/" + dest.getName(), dest, kind));
                names.add(f.getName());
            }
            progress.onProgress("Rangement des médias", ++n, all.size() - 1);
        }

        // Analyse de la discussion
        String text = readUtf8(chat);
        ChatParser parser = new ChatParser(names, opt.dayFirstByDefault, title);
        List<Message> messages = parser.parse(text);
        if (messages.isEmpty()) {
            throw new IOException("Le fichier « " + chat.getName() + " » ne contient aucun message reconnu.");
        }

        // Le texte brut est conservé, par précaution
        File source = new File(folder, "Texte original");
        //noinspection ResultOfMethodCallIgnored
        source.mkdirs();
        copyFile(chat, new File(source, chat.getName()));

        // Document Word
        File docx = new File(folder, safe + ".docx");
        File tmp = new File(workDir, "docx-tmp");
        Zips.deleteRecursively(tmp);
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        DocxWriter writer = new DocxWriter(tmp, images, opt.imageMaxPx);
        writer.write(docx, title, messages, media, progress);
        Zips.deleteRecursively(tmp);

        Result r = new Result();
        r.title = title;
        r.folder = folder;
        r.docx = docx;
        r.stats = DocxWriter.computeStats(messages, media);
        r.embeddedPictures = writer.getEmbeddedPictures();
        r.mediaFiles = media.size();

        if (opt.makeZip) {
            File zip = new File(outputDir, safe + " - Chat2Doc " + LocalDate.now() + ".zip");
            Zips.zipFolder(folder, zip, progress);
            r.zip = zip;
        }
        return r;
    }

    // ------------------------------------------------------------------------------------------------

    private static void listFiles(File dir, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) return;
        java.util.Arrays.sort(list);
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
    static String safeFileName(String s) {
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
