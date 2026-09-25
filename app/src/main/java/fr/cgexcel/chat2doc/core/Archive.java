/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dossier d'archive d'une discussion : document Word, médias rangés, textes des exports successifs,
 * et un sous-dossier caché {@code .chat2doc} (index des médias, vignettes, aperçus des liens).
 *
 * <p>L'archive de travail est toujours un dossier local. Quand l'archive de référence est ailleurs
 * (dossier Chat2Doc choisi sur le téléphone), seuls les petits fichiers sont recopiés d'avance ;
 * les autres sont lus à la demande par {@link Remote}, et les fichiers créés ou modifiés sont notés
 * dans {@link #changed} pour être recopiés à la fin.
 */
public final class Archive {

    /** Accès à l'archive de référence, quand elle n'est pas locale. */
    public interface Remote {
        /** Copie le fichier {@code relPath} de l'archive de référence vers {@code dest} ; faux s'il n'existe pas. */
        boolean fetch(String relPath, File dest) throws IOException;
    }

    public static final String STATE = ".chat2doc";
    public static final String TEXTS = "Texte original";

    public final File dir;
    private final Remote remote;
    /** Fichiers créés ou modifiés (chemins relatifs avec '/'), dans l'ordre. */
    public final Set<String> changed = new LinkedHashSet<>();

    public Archive(File dir, Remote remote) {
        this.dir = dir;
        this.remote = remote;
    }

    public boolean isRemote() {
        return remote != null;
    }

    /** Emplacement local d'un fichier de l'archive (sans le récupérer). */
    public File file(String rel) {
        return new File(dir, rel.replace('/', File.separatorChar));
    }

    /** Fichier local, récupéré depuis l'archive de référence si besoin ; {@code null} s'il n'existe nulle part. */
    public File fetch(String rel) {
        File f = file(rel);
        if (f.exists()) return f;
        if (remote == null) return null;
        try {
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            if (remote.fetch(rel, f) && f.exists()) return f;
        } catch (IOException | RuntimeException e) {
            // fichier inaccessible : traité comme absent
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        return null;
    }

    /** Vrai si le fichier a été récupéré de l'archive de référence et peut être effacé après usage. */
    public boolean isTransient(File f, String rel) {
        return remote != null && !changed.contains(rel) && f != null && f.equals(file(rel));
    }

    public void markChanged(String rel) {
        changed.add(rel);
    }

    public static String rel(String... parts) {
        return String.join("/", parts);
    }

    // ------------------------------------------------------------------------------------------------
    // Petits fichiers de tableaux (champs séparés par des tabulations)

    /** Lit un fichier TSV de l'archive ; liste vide s'il n'existe pas. */
    public List<String[]> readTable(String rel) {
        List<String[]> rows = new ArrayList<>();
        File f = fetch(rel);
        if (f == null) return rows;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] cols = line.split("\t", -1);
                for (int i = 0; i < cols.length; i++) cols[i] = unescape(cols[i]);
                rows.add(cols);
            }
        } catch (IOException e) {
            rows.clear();
        }
        return rows;
    }

    public void writeTable(String rel, String header, List<String[]> rows) throws IOException {
        File f = file(rel);
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            w.write("# " + header + "\n");
            for (String[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) w.write('\t');
                    w.write(escape(row[i] == null ? "" : row[i]));
                }
                w.write('\n');
            }
        }
        markChanged(rel);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
