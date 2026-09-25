/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Images déjà préparées pour le Word (photos réduites, vignettes des aperçus), conservées dans
 * l'archive : lors d'une mise à jour, on ne retraite que les nouvelles.
 */
final class ThumbCache {

    private static final String DIR = Archive.STATE + "/vignettes";
    private static final String INDEX = DIR + "/index.tsv";

    static final class Entry {
        final String file, extension;
        final int width, height;

        Entry(String file, String extension, int width, int height) {
            this.file = file;
            this.extension = extension;
            this.width = width;
            this.height = height;
        }
    }

    private final Archive archive;
    private final Map<String, Entry> entries = new HashMap<>();
    private int next = 1;
    private boolean dirty;

    ThumbCache(Archive archive) {
        this.archive = archive;
        for (String[] r : archive.readTable(INDEX)) {
            if (r.length < 5) continue;
            try {
                entries.put(r[0], new Entry(r[1], r[2], Integer.parseInt(r[3]), Integer.parseInt(r[4])));
                String num = r[1].replaceAll("\\D", "");
                if (!num.isEmpty()) next = Math.max(next, Integer.parseInt(num) + 1);
            } catch (NumberFormatException ignored) {
                // ligne abîmée : ignorée
            }
        }
    }

    Entry get(String key) {
        return entries.get(key);
    }

    /** Fichier local de l'image en cache (récupéré si besoin), ou {@code null}. */
    File file(Entry e) {
        return archive.fetch(DIR + "/" + e.file);
    }

    Entry put(String key, ImageProcessor.Result r) throws IOException {
        String name = "v" + (next++) + "." + r.extension;
        File f = archive.file(DIR + "/" + name);
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(r.data);
        }
        archive.markChanged(DIR + "/" + name);
        Entry e = new Entry(name, r.extension, r.width, r.height);
        entries.put(key, e);
        dirty = true;
        return e;
    }

    void save() throws IOException {
        if (!dirty) return;
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            Entry v = e.getValue();
            rows.add(new String[]{e.getKey(), v.file, v.extension, String.valueOf(v.width), String.valueOf(v.height)});
        }
        rows.sort((a, b) -> a[0].compareTo(b[0]));
        archive.writeTable(INDEX, "clé\tfichier\tformat\tlargeur\thauteur", rows);
        dirty = false;
    }
}
