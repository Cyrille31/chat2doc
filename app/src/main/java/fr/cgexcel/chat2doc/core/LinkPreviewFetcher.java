/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Récupère sur Internet l'aperçu d'un lien, comme le fait WhatsApp : titre, nom du site, image.
 *
 * <p>Vidéos YouTube : titre et chaîne par le service oEmbed de YouTube, vignette de la vidéo.
 * Autres sites : balises « Open Graph » (og:title, og:image, og:site_name) que les sites prévoient
 * justement pour les aperçus, à défaut le titre de la page.
 */
public final class LinkPreviewFetcher {

    /** Aperçu d'un lien. */
    public static final class Preview {
        public final String url;
        public final String title;
        public final String site;
        /** Image téléchargée, ou {@code null}. */
        public final File image;

        public Preview(String url, String title, String site, File image) {
            this.url = url;
            this.title = title;
            this.site = site;
            this.image = image;
        }
    }

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36";
    private static final int MAX_HTML = 600_000;
    private static final int MAX_IMAGE = 6_000_000;

    /** Adresses des services YouTube (modifiables pour les tests). */
    String youtubeOembed = "https://www.youtube.com/oembed?format=json&url=";
    String youtubeThumbnail = "https://i.ytimg.com/vi/%s/mqdefault.jpg";
    int connectTimeoutMs = 8000;
    int readTimeoutMs = 10000;

    private final AtomicInteger imageCounter = new AtomicInteger();

    /** Nombre de téléchargements menés en parallèle. */
    public static final int THREADS = 6;

    /** Durée estimée en secondes pour {@code n} liens (environ 2 s par lien, 6 à la fois). */
    public static int estimateSeconds(int n) {
        return (int) Math.ceil(n / (double) THREADS) * 2 + 3;
    }

    /** « moins d'une minute », « environ 3 minutes »... */
    public static String describeDuration(int seconds) {
        if (seconds < 50) return "moins d’une minute";
        int min = Math.max(1, Math.round(seconds / 60f));
        if (min < 60) return "environ " + min + (min > 1 ? " minutes" : " minute");
        int h = min / 60, r = Math.round((min % 60) / 5f) * 5;
        return "environ " + h + " h" + (r > 0 ? String.format(Locale.FRENCH, " %02d", r) : "");
    }

    /**
     * Récupère les aperçus de toutes les adresses, plusieurs à la fois.
     *
     * @param skip vrai quand l'utilisateur demande d'ignorer les aperçus restants (on garde ceux déjà obtenus)
     */
    public Map<String, Preview> fetchAll(List<String> urls, File dir, ProgressListener progress, BooleanSupplier skip) {
        Map<String, Preview> out = new ConcurrentHashMap<>();
        if (urls.isEmpty()) return out;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        final int total = urls.size();
        final AtomicInteger done = new AtomicInteger();
        final long t0 = System.currentTimeMillis();
        final String stage = "Récupération des aperçus des liens";
        progress.onProgress(stage, 0, total);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (String url : urls) {
            pool.execute(() -> {
                if (progress.isCancelled() || skip.getAsBoolean()) return;
                Preview p = fetch(url, dir);
                if (p != null) out.put(url, p);
                int d = done.incrementAndGet();
                long elapsed = System.currentTimeMillis() - t0;
                int remaining = (int) (elapsed / 1000.0 / d * (total - d));
                progress.onProgress(stage + (d < total && d >= 6 ? " (reste " + describeDuration(remaining)
                        .replace("environ ", "~ ") + ")" : ""), d, total);
            });
        }
        pool.shutdown();
        try {
            while (!pool.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                if (progress.isCancelled() || skip.getAsBoolean()) {
                    pool.shutdownNow();
                    break;
                }
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (progress.isCancelled()) throw new ProgressListener.CancelledException();
        return new HashMap<>(out);
    }

    /** Aperçu d'une adresse, ou {@code null} si le site ne répond pas ou ne fournit rien d'exploitable. */
    public Preview fetch(String url, File dir) {
        try {
            String yt = Links.youtubeId(url);
            return yt != null ? youtube(url, yt, dir) : page(url, dir);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------

    private Preview youtube(String url, String id, File dir) throws IOException {
        String title = null, author = null;
        Response r = get(youtubeOembed + URLEncoder.encode(url, "UTF-8"), "application/json", 100_000);
        if (r != null) {
            String json = new String(r.body, StandardCharsets.UTF_8);
            title = jsonString(json, "title");
            author = jsonString(json, "author_name");
        }
        File image = download(String.format(youtubeThumbnail, id), dir);
        if (title == null && image == null) return null;
        return new Preview(url, title != null ? title : "Vidéo YouTube",
                "YouTube" + (author != null ? " \u00b7 " + author : ""), image);
    }

    private Preview page(String url, File dir) throws IOException {
        Response r = get(url, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5", MAX_HTML);
        if (r == null) return null;
        String type = r.contentType == null ? "" : r.contentType.toLowerCase(Locale.ROOT);
        if (type.startsWith("image/")) {
            // Lien direct vers une image
            File f = save(r, dir);
            return new Preview(url, null, host(r.finalUrl), f);
        }
        if (!type.isEmpty() && !type.contains("html")) return null;

        String html = decode(r.body, type);
        Map<String, String> meta = metaTags(html);
        String title = first(meta, "og:title", "twitter:title");
        if (title == null) {
            Matcher t = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
            if (t.find()) title = t.group(1);
        }
        title = clean(title);
        String site = clean(first(meta, "og:site_name", "application-name"));
        if (site == null) site = host(r.finalUrl);
        String img = first(meta, "og:image:secure_url", "og:image", "og:image:url", "twitter:image", "twitter:image:src");
        File image = null;
        if (img != null) {
            try {
                image = download(new URL(new URL(r.finalUrl), img.trim()).toString(), dir);
            } catch (IOException ignored) {
                // image inaccessible : aperçu sans image
            }
        }
        if (title == null && image == null) return null;
        return new Preview(url, title, site, image);
    }

    private File download(String url, File dir) throws IOException {
        Response r = get(url, "image/*", MAX_IMAGE);
        if (r == null || r.contentType == null || !r.contentType.toLowerCase(Locale.ROOT).startsWith("image/")) return null;
        if (r.body.length >= MAX_IMAGE) return null; // image tronquée : inutilisable
        return save(r, dir);
    }

    private File save(Response r, File dir) throws IOException {
        String t = r.contentType.toLowerCase(Locale.ROOT);
        String ext = t.contains("png") ? "png" : t.contains("webp") ? "webp" : t.contains("gif") ? "gif" : "jpg";
        File f = new File(dir, "apercu-" + imageCounter.incrementAndGet() + "." + ext);
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(r.body);
        }
        return f;
    }

    // ------------------------------------------------------------------------------------------------
    // HTTP

    private static final class Response {
        final String finalUrl, contentType;
        final byte[] body;

        Response(String finalUrl, String contentType, byte[] body) {
            this.finalUrl = finalUrl;
            this.contentType = contentType;
            this.body = body;
        }
    }

    private Response get(String url, String accept, int maxBytes) throws IOException {
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(connectTimeoutMs);
                c.setReadTimeout(readTimeoutMs);
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setRequestProperty("Accept", accept);
                c.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.7");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) return null;
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                if (code != 200) return null;
                try (InputStream is = c.getInputStream()) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) > 0 && bos.size() < maxBytes) bos.write(buf, 0, n);
                    return new Response(current, c.getContentType(), bos.toByteArray());
                }
            } finally {
                c.disconnect();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------
    // Analyse HTML

    private static final Pattern META = Pattern.compile("(?is)<meta\\s+([^>]*?)/?>");
    private static final Pattern ATTR = Pattern.compile("(?is)([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");
    private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([\\w-]+)");

    static Map<String, String> metaTags(String html) {
        Map<String, String> out = new HashMap<>();
        int head = html.toLowerCase(Locale.ROOT).indexOf("</head>");
        Matcher m = META.matcher(head > 0 ? html.substring(0, head) : html);
        while (m.find()) {
            String key = null, content = null;
            Matcher a = ATTR.matcher(m.group(1));
            while (a.find()) {
                String name = a.group(1).toLowerCase(Locale.ROOT);
                String value = a.group(2) != null ? a.group(2) : a.group(3) != null ? a.group(3) : a.group(4);
                if (name.equals("property") || name.equals("name") || name.equals("itemprop")) key = value.toLowerCase(Locale.ROOT).trim();
                else if (name.equals("content")) content = value;
            }
            if (key != null && content != null && !content.trim().isEmpty() && !out.containsKey(key)) {
                out.put(key, entities(content));
            }
        }
        return out;
    }

    static String decode(byte[] body, String contentType) {
        String cs = null;
        Matcher m = CHARSET.matcher(contentType);
        if (m.find()) cs = m.group(1);
        if (cs == null) {
            String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);
            Matcher h = CHARSET.matcher(head);
            if (h.find()) cs = h.group(1);
        }
        Charset charset = StandardCharsets.UTF_8;
        try {
            if (cs != null) charset = Charset.forName(cs);
        } catch (RuntimeException ignored) {
            // jeu de caractères inconnu : UTF-8
        }
        return new String(body, charset);
    }

    private static String first(Map<String, String> meta, String... keys) {
        for (String k : keys) {
            String v = meta.get(k);
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return null;
    }

    private static String clean(String s) {
        if (s == null) return null;
        s = entities(s).replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return null;
        return s.length() > 200 ? s.substring(0, 197) + "…" : s;
    }

    private static String host(String url) {
        try {
            String h = new URL(url).getHost();
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (IOException e) {
            return null;
        }
    }

    private static final Map<String, String> NAMED = new HashMap<>();

    static {
        String[] e = {"amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " ",
                "eacute", "é", "egrave", "è", "ecirc", "ê", "euml", "ë", "agrave", "à", "acirc", "â",
                "ccedil", "ç", "ocirc", "ô", "ucirc", "û", "ugrave", "ù", "icirc", "î", "iuml", "ï",
                "Eacute", "É", "Egrave", "È", "Agrave", "À", "Ccedil", "Ç", "oelig", "œ",
                "rsquo", "\u2019", "lsquo", "\u2018", "ldquo", "\u201c", "rdquo", "\u201d",
                "laquo", "«", "raquo", "»", "hellip", "…", "ndash", "–", "mdash", "—", "copy", "©", "euro", "€"};
        for (int i = 0; i < e.length; i += 2) NAMED.put(e[i], e[i + 1]);
    }

    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|[A-Za-z]+);");

    static String entities(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String g = m.group(1), rep;
            try {
                if (g.startsWith("#x") || g.startsWith("#X")) rep = new String(Character.toChars(Integer.parseInt(g.substring(2), 16)));
                else if (g.startsWith("#")) rep = new String(Character.toChars(Integer.parseInt(g.substring(1))));
                else rep = NAMED.getOrDefault(g, m.group());
            } catch (RuntimeException ex) {
                rep = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Valeur d'un champ texte dans un JSON simple (sans bibliothèque). */
    static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) return null;
        String s = m.group(1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': break;
                case 'u':
                    if (i + 4 < s.length()) {
                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    break;
                default: sb.append(n);
            }
        }
        String r = sb.toString().trim();
        return r.isEmpty() ? null : r;
    }
}
