/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Décompression d'un export .zip et compression de l'archive finale. */
public final class Zips {

    private Zips() { }

    /** Extensions déjà compressées : inutile (et lent) de les recompresser. */
    private static final String STORED = "|jpg|jpeg|png|gif|webp|heic|heif|mp4|3gp|mov|m4v|mkv|webm|opus|ogg|m4a|mp3|aac|amr"
            + "|docx|xlsx|pptx|xlsm|zip|rar|7z|pdf|epub|apk|odt|ods|odp|";

    /** Décompresse {@code in} dans {@code dir} en refusant tout chemin qui sortirait du dossier. */
    public static void unzip(InputStream in, File dir, ProgressListener progress) throws IOException {
        String root = dir.getCanonicalPath() + File.separator;
        byte[] buf = new byte[1 << 16];
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            int n = 0;
            while ((e = zis.getNextEntry()) != null) {
                if (progress.isCancelled()) throw new ProgressListener.CancelledException();
                File out = new File(dir, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) continue; // « zip slip »
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
                    int r;
                    while ((r = zis.read(buf)) > 0) os.write(buf, 0, r);
                }
                progress.onProgress("Décompression de l’export", ++n, 0);
            }
        }
    }

    /** Compresse le dossier {@code dir} (lui compris, comme dossier racine de l'archive) dans {@code zipFile}. */
    public static void zipFolder(File dir, File zipFile, ProgressListener progress) throws IOException {
        List<File> files = new ArrayList<>();
        collect(dir, files);
        String base = dir.getCanonicalFile().getParentFile().getCanonicalPath();
        byte[] buf = new byte[1 << 16];
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile), 1 << 16))) {
            int n = 0;
            for (File f : files) {
                if (progress.isCancelled()) throw new ProgressListener.CancelledException();
                String name = f.getCanonicalPath().substring(base.length() + 1).replace(File.separatorChar, '/');
                String ext = MediaKind.extension(f.getName()).toLowerCase(Locale.ROOT);
                zos.setLevel(STORED.contains("|" + ext + "|") ? Deflater.NO_COMPRESSION : Deflater.DEFAULT_COMPRESSION);
                ZipEntry ze = new ZipEntry(name);
                ze.setTime(f.lastModified());
                zos.putNextEntry(ze);
                try (InputStream is = new FileInputStream(f)) {
                    int r;
                    while ((r = is.read(buf)) > 0) zos.write(buf, 0, r);
                }
                zos.closeEntry();
                progress.onProgress("Compression de l’archive", ++n, files.size());
            }
        }
    }

    private static void collect(File dir, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) return;
        java.util.Arrays.sort(list);
        for (File f : list) {
            if (f.isDirectory()) collect(f, out);
            else out.add(f);
        }
    }

    /** Supprime un dossier et son contenu. */
    public static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] list = f.listFiles();
        if (list != null) for (File c : list) deleteRecursively(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
