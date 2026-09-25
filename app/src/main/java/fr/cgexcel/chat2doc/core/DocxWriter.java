/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Écrit la discussion dans un document Word (.docx, format Office Open XML), sans bibliothèque externe.
 *
 * <p>Mise en page : page de garde (titre, période, statistiques, participants), puis la discussion
 * avec un titre par mois (niveau 1) et par jour (niveau 2) — ce qui donne un volet de navigation
 * Word utilisable même pour des années d'échanges. Chaque expéditeur a sa couleur ; ses messages
 * sont marqués d'un filet vertical de cette couleur ; l'heure figure en petit en fin de message,
 * comme dans WhatsApp. Les photos sont insérées dans le fil (cliquer dessus ouvre l'original) ;
 * les autres médias sont des liens relatifs vers les sous-dossiers de l'archive.
 */
public final class DocxWriter {

    private static final Locale FR = Locale.FRENCH;
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEEE", FR);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", FR);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", FR);

    private static final String[] PALETTE = {
            "1F5FAD", "B03A2E", "1E8449", "7D3C98", "CA6F1E", "117A65",
            "A93A7A", "2E4053", "8D6E0A", "5B6BBF", "A04000", "3D7A8C"
    };
    private static final String GREY = "8C8C8C";
    private static final String BRAND_BLUE = "1F3F8F";

    /** Cadre maximal des photos et des autocollants, en centimètres. */
    private static final double PHOTO_MAX_W = 7.0, PHOTO_MAX_H = 8.0, STICKER_MAX = 3.2;
    private static final long EMU_PER_CM = 360000L;

    /** Cadre de la vignette d'un aperçu de lien, en centimètres, et sa définition en pixels. */
    private static final double PREVIEW_MAX_W = 5.0, PREVIEW_MAX_H = 3.2;
    private static final int PREVIEW_PX = 480;
    private static final String PREVIEW_FILL = "F1F4FA";

    private final File tmpDir;
    private final ImageProcessor images;
    private final int imageMaxPx;

    // État de l'écriture
    private final StringBuilder rels = new StringBuilder();
    private final List<String[]> mediaEntries = new ArrayList<>(); // {nom dans le zip, fichier, "1" si temporaire}
    private final Archive archive;
    private final ThumbCache cache;
    private final Map<String, String> linkRelIds = new HashMap<>();
    private final Map<String, Picture> pictures = new HashMap<>();
    private final Map<String, String> senderColors = new HashMap<>();
    private int nextRelId = 10;
    private int nextPictureId = 1;
    private int embeddedPictures;
    private Map<String, LinkPreviewFetcher.Preview> previews = new HashMap<>();
    private Map<String, String> presetColors;
    private String volume, baseName;
    private List<String> otherVolumes = new ArrayList<>();

    /** Couleurs des participants, communes à tous les volumes d'une discussion. */
    void setColors(Map<String, String> colors) {
        this.presetColors = colors;
    }

    /** Ce document est le volume {@code label} (une année) ; les autres volumes sont cités en page de garde. */
    void setVolume(String label, List<String> others, String baseName) {
        this.volume = label;
        this.otherVolumes = others;
        this.baseName = baseName;
    }

    /** Une couleur par participant, dans l'ordre de première apparition dans toute la discussion. */
    static Map<String, String> colorsFor(List<Message> msgs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Message x : msgs) {
            if (x.sender != null && !m.containsKey(x.sender)) m.put(x.sender, PALETTE[m.size() % PALETTE.length]);
        }
        return m;
    }

    private static final class Picture {
        final String relId;
        final long cx, cy;

        Picture(String relId, long cx, long cy) {
            this.relId = relId;
            this.cx = cx;
            this.cy = cy;
        }
    }

    /** Statistiques de la discussion, affichées en page de garde. */
    public static final class Stats {
        public int messages;
        public final LinkedHashMap<String, Integer> perSender = new LinkedHashMap<>();
        public final EnumMap<MediaKind, Integer> perKind = new EnumMap<>(MediaKind.class);
        public final List<String> missing = new ArrayList<>();
        public int omitted;
        public LocalDateTime first, last;
        public int links;
    }

    /**
     * @param tmpDir     dossier de travail (fichiers temporaires)
     * @param images     préparation des images
     * @param imageMaxPx plus grand côté des photos insérées (0 = taille d'origine)
     */
    DocxWriter(File tmpDir, ImageProcessor images, int imageMaxPx, Archive archive, ThumbCache cache) {
        this.archive = archive;
        this.cache = cache;
        this.tmpDir = tmpDir;
        this.images = images;
        this.imageMaxPx = imageMaxPx;
    }

    public int getEmbeddedPictures() {
        return embeddedPictures;
    }

    // ================================================================================================
    // Statistiques
    // ================================================================================================

    public static Stats computeStats(List<Message> msgs, Map<String, MediaFile> media) {
        Stats s = new Stats();
        for (Message m : msgs) {
            if (m.isSystem()) continue;
            s.messages++;
            s.perSender.merge(m.sender, 1, Integer::sum);
            if (m.mediaOmitted) s.omitted++;
            for (String a : m.attachments) {
                MediaFile mf = media.get(a.toLowerCase(Locale.ROOT));
                if (mf == null) s.missing.add(a);
                else s.perKind.merge(mf.kind, 1, Integer::sum);
            }
        }
        if (!msgs.isEmpty()) {
            s.first = msgs.get(0).time;
            s.last = msgs.get(msgs.size() - 1).time;
        }
        return s;
    }

    // ================================================================================================
    // Écriture
    // ================================================================================================

    public void write(File docx, String title, List<Message> msgs, Map<String, MediaFile> media,
                      Map<String, LinkPreviewFetcher.Preview> previews, ProgressListener progress) throws IOException {
        this.previews = previews == null ? new HashMap<>() : previews;
        Stats stats = computeStats(msgs, media);
        stats.links = Links.collect(msgs).size();
        int i = 0;
        for (String sender : stats.perSender.keySet()) {
            String c = presetColors != null ? presetColors.get(sender) : null;
            senderColors.put(sender, c != null ? c : PALETTE[i % PALETTE.length]);
            i++;
        }

        File body = new File(tmpDir, "document.xml");
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(body), StandardCharsets.UTF_8), 1 << 16)) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
                    + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
                    + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
                    + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                    + " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>");
            writeCover(w, title, stats);
            writeConversation(w, msgs, media, progress);
            w.write("<w:sectPr><w:footerReference w:type=\"default\" r:id=\"rId3\"/>"
                    + "<w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                    + "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/>"
                    + "</w:sectPr></w:body></w:document>");
        }

        progress.onProgress("Assemblage du document Word", 0, 0);
        assemble(docx, body, volume != null ? title + " " + volume : title);
    }

    // ------------------------------------------------------------------------------------------------

    private void writeCover(Writer w, String title, Stats s) throws IOException {
        para(w, "Title", "", run(volume != null ? title + " \u2014 " + volume : title, ""));
        if (s.first != null) {
            String period = s.first.toLocalDate().equals(s.last.toLocalDate())
                    ? "le " + dateFr(s.first.toLocalDate(), false)
                    : "du " + dateFr(s.first.toLocalDate(), false) + " au " + dateFr(s.last.toLocalDate(), false);
            para(w, "C2DSubtitle", "", run((volume != null ? "Année " + volume + " \u00b7 " : "")
                    + "Discussion WhatsApp " + period, ""));
        }

        List<String> parts = new ArrayList<>();
        parts.add(plural(s.messages, "message", "messages"));
        parts.add(plural(s.perSender.size(), "participant", "participants"));
        int photos = count(s, MediaKind.PHOTO);
        if (photos > 0) parts.add(plural(photos, "photo", "photos"));
        int videos = count(s, MediaKind.VIDEO);
        if (videos > 0) parts.add(plural(videos, "vidéo", "vidéos"));
        int voice = count(s, MediaKind.VOICE);
        if (voice > 0) parts.add(plural(voice, "message vocal", "messages vocaux"));
        int audio = count(s, MediaKind.AUDIO);
        if (audio > 0) parts.add(plural(audio, "fichier audio", "fichiers audio"));
        int docs = count(s, MediaKind.DOCUMENT);
        if (docs > 0) parts.add(plural(docs, "document", "documents"));
        int stickers = count(s, MediaKind.STICKER);
        if (stickers > 0) parts.add(plural(stickers, "autocollant", "autocollants"));
        if (s.links > 0) parts.add(plural(s.links, "lien", "liens"));
        para(w, "C2DStats", "", run(String.join(" · ", parts), ""));

        if (!s.perSender.isEmpty()) {
            para(w, "C2DCoverHeading", "", run("Participants", ""));
            List<Map.Entry<String, Integer>> list = new ArrayList<>(s.perSender.entrySet());
            list.sort((x, y) -> y.getValue() - x.getValue());
            for (Map.Entry<String, Integer> e : list) {
                String color = senderColors.get(e.getKey());
                para(w, "C2DParticipant", "",
                        run(e.getKey(), "<w:b/><w:color w:val=\"" + color + "\"/>")
                                + "<w:r><w:tab/></w:r>"
                                + run(plural(e.getValue(), "message", "messages"), ""));
            }
        }

        if (!s.missing.isEmpty() || s.omitted > 0) {
            para(w, "C2DCoverHeading", "", run("À savoir", ""));
            if (s.omitted > 0) {
                para(w, "C2DNote", "", run(plural(s.omitted, "média n’a", "médias n’ont")
                        + " pas été inclus par WhatsApp dans l’export (export « sans les médias », ou limite de taille).", ""));
            }
            if (!s.missing.isEmpty()) {
                para(w, "C2DNote", "", run(plural(s.missing.size(), "fichier cité", "fichiers cités")
                        + " dans la discussion ne figurai" + (s.missing.size() > 1 ? "ent" : "t")
                        + " pas dans l’export (média supprimé ou jamais téléchargé sur le téléphone).", ""));
            }
        }

        if (volume != null && !otherVolumes.isEmpty()) {
            List<String> volumes = new ArrayList<>();
            for (String v : otherVolumes) volumes.add(baseName + " - " + v + ".docx");
            para(w, "C2DCoverHeading", "", run("Autres années", ""));
            para(w, "C2DNote", "", run("Chaque année a son propre document, dans le même dossier : "
                    + String.join(", ", volumes) + ".", ""));
        }

        para(w, "C2DNote", "<w:spacing w:before=\"480\"/>",
                run("Document créé le " + dateFr(LocalDate.now(), false) + " avec Chat2Doc — CGExcel. "
                        + "Les photos, vidéos, messages vocaux et documents se trouvent dans les sous-dossiers "
                        + "placés à côté de ce fichier : gardez-les ensemble pour que les liens fonctionnent.", ""));
    }

    private static int count(Stats s, MediaKind k) {
        Integer n = s.perKind.get(k);
        return n == null ? 0 : n;
    }

    private static String plural(int n, String one, String many) {
        return String.format(FR, "%,d", n).replace(' ', ' ') + " " + (n > 1 ? many : one);
    }

    // ------------------------------------------------------------------------------------------------

    private void writeConversation(Writer w, List<Message> msgs, Map<String, MediaFile> media,
                                   ProgressListener progress) throws IOException {
        LocalDate lastDay = null;
        String lastMonth = null;
        String lastSender = null;
        LocalDateTime lastTime = null;
        boolean first = true;
        int n = 0, total = msgs.size();

        for (Message m : msgs) {
            if (progress.isCancelled()) throw new ProgressListener.CancelledException();
            if (n++ % 25 == 0) progress.onProgress("Mise en page de la discussion", n, total);

            LocalDate day = m.time.toLocalDate();
            String month = capitalize(MONTH.format(day));
            if (!month.equals(lastMonth)) {
                String ppr = first ? "<w:pageBreakBefore/>" : "";
                para(w, "Heading1", ppr, run(month, ""));
                lastMonth = month;
                first = false;
            }
            if (!day.equals(lastDay)) {
                para(w, "Heading2", "", run(capitalize(dateFr(day, true)), ""));
                lastDay = day;
                lastSender = null;
            }

            String time = HOUR.format(m.time);

            if (m.isSystem()) {
                para(w, "C2DSystem", "", run(time + "  ·  " + m.text, ""));
                lastSender = null;
                continue;
            }

            String color = senderColors.get(m.sender);
            boolean newBlock = !m.sender.equals(lastSender)
                    || (lastTime != null && ChronoUnit.MINUTES.between(lastTime, m.time) >= 60);
            if (newBlock) {
                para(w, "C2DSender", "", run(m.sender, "<w:color w:val=\"" + color + "\"/>"));
            }
            lastSender = m.sender;
            lastTime = m.time;

            // Contenu : pièces jointes, texte, aperçus des liens ; l'heure suit le texte (ou le dernier élément)
            String border = "<w:pBdr><w:left w:val=\"single\" w:sz=\"8\" w:space=\"7\" w:color=\"" + color + "\"/></w:pBdr>";
            String card = "<w:pBdr><w:left w:val=\"single\" w:sz=\"8\" w:space=\"7\" w:color=\"" + color + "\"/></w:pBdr>"
                    + "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"" + PREVIEW_FILL + "\"/>";
            List<String> bodies = new ArrayList<>();
            List<String> pprs = new ArrayList<>();
            for (String att : m.attachments) {
                MediaFile mf = media.get(att.toLowerCase(Locale.ROOT));
                if (mf == null) {
                    bodies.add(run("Fichier absent de l’export : " + att, "<w:i/><w:color w:val=\"" + GREY + "\"/>"));
                    pprs.add(border);
                    continue;
                } else if (mf.kind.isImage()) {
                    String drawing = picture(mf);
                    bodies.add(drawing != null ? drawing : mediaLink(mf));
                } else {
                    bodies.add(mediaLink(mf));
                }
                pprs.add(border);
            }
            if (m.mediaOmitted) {
                bodies.add(run("Média non inclus dans l’export", "<w:i/><w:color w:val=\"" + GREY + "\"/>"));
                pprs.add(border);
            }
            int stampAt = -1;
            if (!m.text.isEmpty()) {
                String rpr = m.deleted ? "<w:i/><w:color w:val=\"" + GREY + "\"/>" : "";
                bodies.add(textWithLinks(m.text, rpr));
                pprs.add(border);
                stampAt = bodies.size() - 1;
                addPreviews(m.text, bodies, pprs, card);
            }
            String stamp = (m.edited ? "  modifié " : "  ") + time;
            if (bodies.isEmpty()) {
                bodies.add("");
                pprs.add(border);
            }
            if (stampAt < 0) stampAt = bodies.size() - 1;
            bodies.set(stampAt, bodies.get(stampAt) + run(" " + stamp.trim(),
                    "<w:color w:val=\"" + GREY + "\"/><w:sz w:val=\"15\"/>"));

            for (int b = 0; b < bodies.size(); b++) para(w, "C2DText", pprs.get(b), bodies.get(b));
        }
        progress.onProgress("Mise en page de la discussion", total, total);
    }

    // ------------------------------------------------------------------------------------------------
    // Éléments

    private static void para(Writer w, String style, String extraPPr, String content) throws IOException {
        w.write("<w:p><w:pPr><w:pStyle w:val=\"");
        w.write(style);
        w.write("\"/>");
        w.write(extraPPr);
        w.write("</w:pPr>");
        w.write(content);
        w.write("</w:p>");
    }

    /** Un « run » de texte ; les sauts de ligne deviennent des retours à la ligne Word. */
    private static String run(String text, String rPr) {
        StringBuilder sb = new StringBuilder();
        sb.append("<w:r>");
        if (!rPr.isEmpty()) sb.append("<w:rPr>").append(rPr).append("</w:rPr>");
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("<w:br/>");
            String[] tabs = lines[i].split("\t", -1);
            for (int j = 0; j < tabs.length; j++) {
                if (j > 0) sb.append("<w:tab/>");
                if (!tabs[j].isEmpty()) sb.append("<w:t xml:space=\"preserve\">").append(xml(tabs[j])).append("</w:t>");
            }
        }
        return sb.append("</w:r>").toString();
    }

    /** Texte avec les adresses web transformées en liens cliquables. */
    private String textWithLinks(String text, String rPr) {
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        for (Links.Found f : Links.find(text)) {
            if (f.start > pos) sb.append(run(text.substring(pos, f.start), rPr));
            sb.append("<w:hyperlink r:id=\"").append(linkRel(f.url)).append("\" w:history=\"1\">")
                    .append(run(f.shown, "<w:rStyle w:val=\"Hyperlink\"/>")).append("</w:hyperlink>");
            pos = f.end;
        }
        if (pos < text.length()) sb.append(run(text.substring(pos), rPr));
        return sb.toString();
    }

    /** Aperçus des liens du message (vignette, titre, site), comme dans WhatsApp. */
    private void addPreviews(String text, List<String> bodies, List<String> pprs, String card) throws IOException {
        List<String> seen = new ArrayList<>();
        for (Links.Found f : Links.find(text)) {
            LinkPreviewFetcher.Preview p = previews.get(f.url);
            if (p == null || seen.contains(f.url)) continue;
            seen.add(f.url);
            boolean hasImage = false;
            if (p.image != null) {
                String img = pictureOf(Archive.STATE + "/apercus/" + p.image.getName(), p.site != null ? p.site : "Aperçu du lien",
                        PREVIEW_MAX_W, PREVIEW_MAX_H, p.url, PREVIEW_PX);
                if (img != null) {
                    bodies.add(img);
                    pprs.add(card + "<w:spacing w:before=\"60\" w:after=\"0\"/>");
                    hasImage = true;
                }
            }
            StringBuilder t = new StringBuilder();
            if (p.title != null) {
                t.append("<w:hyperlink r:id=\"").append(linkRel(p.url)).append("\" w:history=\"1\">")
                        .append(run(p.title, "<w:b/><w:color w:val=\"" + BRAND_BLUE + "\"/><w:sz w:val=\"20\"/>"))
                        .append("</w:hyperlink>");
            }
            if (p.site != null) {
                if (t.length() > 0) t.append("<w:r><w:br/></w:r>");
                t.append(run(p.site, "<w:color w:val=\"" + GREY + "\"/><w:sz w:val=\"16\"/>"));
            }
            if (t.length() > 0) {
                bodies.add(t.toString());
                pprs.add(card + "<w:spacing w:before=\"" + (hasImage ? 0 : 60) + "\" w:after=\"100\"/>");
            }
        }
    }

    /** Lien vers un média rangé dans l'archive (vidéo, message vocal, document...). */
    private String mediaLink(MediaFile mf) {
        String symbol;
        switch (mf.kind) {
            case VIDEO: symbol = "▶ "; break;
            case VOICE: case AUDIO: symbol = "♪ "; break;
            default: symbol = "■ "; break;
        }
        String label = mf.kind.label + " : " + mf.name + " (" + size(mf.size) + ")";
        return run(symbol, "<w:color w:val=\"" + GREY + "\"/>")
                + "<w:hyperlink r:id=\"" + linkRel(relativeUrl(mf.relativePath)) + "\" w:history=\"1\""
                + " w:tooltip=\"" + xml("Ouvrir " + mf.relativePath) + "\">"
                + run(label, "<w:rStyle w:val=\"Hyperlink\"/>") + "</w:hyperlink>";
    }

    /** Photo insérée dans le fil ; un clic ouvre l'original. Renvoie null si l'image n'est pas lisible. */
    private String picture(MediaFile mf) throws IOException {
        boolean sticker = mf.kind == MediaKind.STICKER;
        return pictureOf(mf.relativePath, mf.name,
                sticker ? STICKER_MAX : PHOTO_MAX_W, sticker ? STICKER_MAX : PHOTO_MAX_H,
                relativeUrl(mf.relativePath), imageMaxPx);
    }

    /**
     * Image de l'archive insérée dans le fil, cliquable vers {@code linkTarget}. Les images réduites sont
     * conservées dans l'archive pour les mises à jour suivantes. Renvoie null si l'image n'est pas lisible.
     */
    private String pictureOf(String sourceRel, String label, double maxW, double maxH,
                             String linkTarget, int maxPx) throws IOException {
        String key = sourceRel + "|" + maxPx;
        Picture pic = pictures.get(key);
        if (pic == null) {
            File embed;
            String ext;
            int width, height;
            boolean temporary = false;
            ThumbCache.Entry cached = maxPx > 0 ? cache.get(key) : null;
            File cachedFile = cached != null ? cache.file(cached) : null;
            if (cachedFile != null) {
                embed = cachedFile;
                ext = cached.extension;
                width = cached.width;
                height = cached.height;
            } else {
                File src = archive.fetch(sourceRel);
                if (src == null) return null;
                ImageProcessor.Result r;
                try {
                    r = images.process(src, maxPx);
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    r = null;
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    if (archive.isTransient(src, sourceRel)) src.delete();
                }
                if (r == null || r.width <= 0 || r.height <= 0) return null;
                ext = r.extension;
                width = r.width;
                height = r.height;
                if (maxPx > 0) {
                    embed = cache.file(cache.put(key, r));
                    if (embed == null) return null;
                } else {
                    // Taille d'origine : pas de copie en cache (ce serait un doublon des originaux)
                    embed = new File(tmpDir, "img" + (mediaEntries.size() + 1) + "." + ext);
                    try (OutputStream os = new FileOutputStream(embed)) {
                        os.write(r.data);
                    }
                    temporary = true;
                }
            }

            String entry = "media/image" + (mediaEntries.size() + 1) + "." + ext;
            mediaEntries.add(new String[]{"word/" + entry, embed.getPath(), temporary ? "1" : ""});
            String relId = "rId" + (nextRelId++);
            rels.append("<Relationship Id=\"").append(relId)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"")
                    .append(entry).append("\"/>");

            double naturalW = width / 96.0 * 2.54, naturalH = height / 96.0 * 2.54;
            double k = Math.min(1.0, Math.min(maxW / naturalW, maxH / naturalH));
            pic = new Picture(relId, Math.round(naturalW * k * EMU_PER_CM), Math.round(naturalH * k * EMU_PER_CM));
            pictures.put(key, pic);
            if (!sourceRel.startsWith(Archive.STATE)) embeddedPictures++;
        }

        int id = nextPictureId++;
        String link = linkRel(linkTarget);
        String name = xml(label);
        return "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
                + "<wp:extent cx=\"" + pic.cx + "\" cy=\"" + pic.cy + "\"/>"
                + "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>"
                + "<wp:docPr id=\"" + id + "\" name=\"Image " + id + "\" descr=\"" + name + "\">"
                + "<a:hlinkClick r:id=\"" + link + "\"/></wp:docPr>"
                + "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"" + id + "\" name=\"" + name + "\"/><pic:cNvPicPr/></pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed=\"" + pic.relId + "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + pic.cx + "\" cy=\"" + pic.cy + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>"
                + "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r>";
    }

    private String linkRel(String target) {
        String id = linkRelIds.get(target);
        if (id == null) {
            id = "rId" + (nextRelId++);
            linkRelIds.put(target, id);
            rels.append("<Relationship Id=\"").append(id)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"")
                    .append(xml(target)).append("\" TargetMode=\"External\"/>");
        }
        return id;
    }

    // ================================================================================================
    // Assemblage du paquet .docx
    // ================================================================================================

    private void assemble(File docx, File body, String title) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(docx), 1 << 16))) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>"
                    + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                    + "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>"
                    + "<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>"
                    + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                    + "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>"
                    + "</Types>");
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                    + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
                    + "</Relationships>");
            String now = LocalDateTime.now(ZoneOffset.UTC).withNano(0) + "Z";
            put(zip, "docProps/core.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                    + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\""
                    + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                    + "<dc:title>" + xml(title) + "</dc:title><dc:subject>Discussion WhatsApp</dc:subject>"
                    + "<dc:creator>Chat2Doc</dc:creator><cp:lastModifiedBy>Chat2Doc</cp:lastModifiedBy>"
                    + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:created>"
                    + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:modified>"
                    + "</cp:coreProperties>");
            put(zip, "docProps/app.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">"
                    + "<Application>Chat2Doc (CGExcel)</Application></Properties>");

            zip.putNextEntry(new ZipEntry("word/document.xml"));
            copy(body, zip);
            zip.closeEntry();

            put(zip, "word/styles.xml", styles());
            put(zip, "word/settings.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<w:settings xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:zoom w:percent=\"100\"/><w:defaultTabStop w:val=\"709\"/>"
                    + "<w:hyphenationZone w:val=\"425\"/><w:characterSpacingControl w:val=\"doNotCompress\"/>"
                    + "<w:compat><w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat>"
                    + "<w:themeFontLang w:val=\"fr-FR\"/></w:settings>");
            put(zip, "word/footer1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<w:ftr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:p><w:pPr><w:pStyle w:val=\"Footer\"/></w:pPr>"
                    + run(title + "  ·  page ", "")
                    + "<w:fldSimple w:instr=\" PAGE \"><w:r><w:t>1</w:t></w:r></w:fldSimple>"
                    + run(" / ", "")
                    + "<w:fldSimple w:instr=\" NUMPAGES \"><w:r><w:t>1</w:t></w:r></w:fldSimple>"
                    + "</w:p></w:ftr>");
            put(zip, "word/_rels/document.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings\" Target=\"settings.xml\"/>"
                    + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\"footer1.xml\"/>"
                    + rels + "</Relationships>");

            // Les images sont déjà compressées : on les range sans recompression (plus rapide sur téléphone)
            zip.setLevel(0);
            for (String[] e : mediaEntries) {
                zip.putNextEntry(new ZipEntry(e[0]));
                File f = new File(e[1]);
                copy(f, zip);
                zip.closeEntry();
                //noinspection ResultOfMethodCallIgnored
                if ("1".equals(e[2])) f.delete();
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            body.delete();
        }
    }

    private static String styles() {
        StringBuilder s = new StringBuilder();
        s.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
                .append("<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
                .append("<w:docDefaults><w:rPrDefault><w:rPr>")
                .append("<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>")
                .append("<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"fr-FR\" w:eastAsia=\"en-US\" w:bidi=\"ar-SA\"/>")
                .append("</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"0\" w:line=\"259\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>")
                .append("</w:docDefaults>");
        s.append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>");
        s.append("<w:style w:type=\"character\" w:default=\"1\" w:styleId=\"DefaultParagraphFont\"><w:name w:val=\"Default Paragraph Font\"/><w:uiPriority w:val=\"1\"/><w:semiHidden/></w:style>");

        pStyle(s, "Title", "Title", "<w:spacing w:before=\"1200\" w:after=\"160\"/>",
                "<w:rFonts w:ascii=\"Calibri Light\" w:hAnsi=\"Calibri Light\"/><w:color w:val=\"" + BRAND_BLUE + "\"/><w:sz w:val=\"56\"/><w:szCs w:val=\"56\"/>");
        pStyle(s, "C2DSubtitle", "C2D Sous-titre", "<w:spacing w:after=\"80\"/>",
                "<w:color w:val=\"404040\"/><w:sz w:val=\"28\"/>");
        pStyle(s, "C2DStats", "C2D Statistiques",
                "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"12\" w:color=\"BFBFBF\"/></w:pBdr><w:spacing w:after=\"360\"/>",
                "<w:color w:val=\"595959\"/><w:sz w:val=\"21\"/>");
        pStyle(s, "C2DCoverHeading", "C2D Rubrique", "<w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/>",
                "<w:b/><w:caps/><w:color w:val=\"" + BRAND_BLUE + "\"/><w:spacing w:val=\"10\"/><w:sz w:val=\"20\"/>");
        pStyle(s, "C2DParticipant", "C2D Participant",
                "<w:tabs><w:tab w:val=\"right\" w:leader=\"dot\" w:pos=\"7371\"/></w:tabs><w:spacing w:after=\"40\"/>", "");
        pStyle(s, "C2DNote", "C2D Note", "<w:spacing w:after=\"80\"/>",
                "<w:color w:val=\"595959\"/><w:sz w:val=\"19\"/>");

        s.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>")
                .append("<w:pPr><w:keepNext/><w:pBdr><w:bottom w:val=\"single\" w:sz=\"8\" w:space=\"4\" w:color=\"" + BRAND_BLUE + "\"/></w:pBdr>")
                .append("<w:spacing w:before=\"480\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr>")
                .append("<w:rPr><w:rFonts w:ascii=\"Calibri Light\" w:hAnsi=\"Calibri Light\"/><w:color w:val=\"" + BRAND_BLUE + "\"/><w:sz w:val=\"34\"/><w:szCs w:val=\"34\"/></w:rPr></w:style>");
        s.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>")
                .append("<w:pPr><w:keepNext/><w:spacing w:before=\"280\" w:after=\"80\"/><w:jc w:val=\"center\"/><w:outlineLvl w:val=\"1\"/></w:pPr>")
                .append("<w:rPr><w:b/><w:color w:val=\"737373\"/><w:sz w:val=\"19\"/><w:szCs w:val=\"19\"/></w:rPr></w:style>");

        pStyle(s, "C2DSender", "C2D Expéditeur", "<w:keepNext/><w:spacing w:before=\"160\" w:after=\"40\"/>",
                "<w:b/><w:sz w:val=\"20\"/>");
        pStyle(s, "C2DText", "C2D Message", "<w:spacing w:after=\"60\"/><w:ind w:left=\"284\"/>", "");
        pStyle(s, "C2DSystem", "C2D Message système", "<w:spacing w:before=\"80\" w:after=\"80\"/><w:jc w:val=\"center\"/>",
                "<w:i/><w:color w:val=\"" + GREY + "\"/><w:sz w:val=\"17\"/>");
        pStyle(s, "Footer", "footer", "<w:jc w:val=\"center\"/>", "<w:color w:val=\"" + GREY + "\"/><w:sz w:val=\"16\"/>");

        s.append("<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/><w:basedOn w:val=\"DefaultParagraphFont\"/>")
                .append("<w:rPr><w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/></w:rPr></w:style>");
        s.append("</w:styles>");
        return s.toString();
    }

    private static void pStyle(StringBuilder s, String id, String name, String pPr, String rPr) {
        boolean builtIn = id.equals("Title") || id.equals("Footer");
        s.append("<w:style w:type=\"paragraph\"").append(builtIn ? "" : " w:customStyle=\"1\"")
                .append(" w:styleId=\"").append(id).append("\">")
                .append("<w:name w:val=\"").append(xml(name)).append("\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>");
        if (!pPr.isEmpty()) s.append("<w:pPr>").append(pPr).append("</w:pPr>");
        if (!rPr.isEmpty()) s.append("<w:rPr>").append(rPr).append("</w:rPr>");
        s.append("</w:style>");
    }

    // ================================================================================================
    // Utilitaires

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void copy(File f, OutputStream os) throws IOException {
        byte[] buf = new byte[1 << 16];
        try (InputStream is = new FileInputStream(f)) {
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    /** Échappement XML, avec suppression des caractères interdits en XML 1.0 (et des demi-paires isolées). */
    static String xml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default:
                    if (Character.isHighSurrogate(c)) {
                        if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                            sb.append(c).append(s.charAt(++i));
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        // demi-paire isolée : ignorée
                    } else if (c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c != 0xFFFE && c != 0xFFFF)) {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** Chemin relatif encodé pour un lien (espaces, accents...). */
    static String relativeUrl(String path) {
        StringBuilder sb = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || c == '/') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " o";
        if (bytes < 1024 * 1024) return Math.max(1, Math.round(bytes / 1024.0)) + " Ko";
        return String.format(FR, "%.1f Mo", bytes / (1024.0 * 1024.0));
    }

    /** « 1er septembre 2026 », « mardi 24 septembre 2026 ». */
    public static String dateFr(LocalDate d, boolean weekday) {
        String s = (d.getDayOfMonth() == 1 ? "1er" : String.valueOf(d.getDayOfMonth())) + " "
                + MONTH.format(d);
        return weekday ? WEEKDAY.format(d) + " " + s : s;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(FR) + s.substring(1);
    }
}
