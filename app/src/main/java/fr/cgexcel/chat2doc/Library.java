/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fr.cgexcel.chat2doc.core.Archive;
import fr.cgexcel.chat2doc.core.Converter;
import fr.cgexcel.chat2doc.core.ProgressListener;

/**
 * Dossier Chat2Doc choisi par l'utilisateur (accès accordé par le sélecteur de dossiers d'Android) :
 * un sous-dossier par discussion, complété à chaque nouvel export.
 */
final class Library {

    private static final String PREFS = "chat2doc";
    private static final String KEY = "dossier_chat2doc";

    /** Une discussion enregistrée. */
    static final class Entry {
        String folder, title, docxName;
        LocalDateTime first, last, updated;
        int messages, exports;
        Node dir;
    }

    /** Un fichier ou un dossier du dossier Chat2Doc. */
    static final class Node {
        final Uri uri;
        final String name;
        final boolean dir;

        Node(Uri uri, String name, boolean dir) {
            this.uri = uri;
            this.name = name;
            this.dir = dir;
        }
    }

    private final Context ctx;
    private final Uri tree;
    private final Node root;
    /** Contenu des dossiers déjà parcourus : chemin relatif → (nom → document). */
    private final Map<String, Map<String, Node>> listings = new HashMap<>();

    private Library(Context ctx, Uri tree, Node root) {
        this.ctx = ctx;
        this.tree = tree;
        this.root = root;
    }

    /** Le dossier Chat2Doc, s'il est choisi et toujours accessible ; sinon {@code null}. */
    static Library open(Context ctx) {
        Uri uri = treeUri(ctx);
        if (uri == null) return null;
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
            String name = null;
            try (Cursor c = ctx.getContentResolver().query(doc, new String[]{Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (c == null || !c.moveToFirst()) return null;
                name = c.getString(0);
            }
            return new Library(ctx, uri, new Node(doc, name, true));
        } catch (RuntimeException e) {
            return null; // dossier supprimé ou autorisation retirée
        }
    }

    static Uri treeUri(Context ctx) {
        String s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
        return s == null ? null : Uri.parse(s);
    }

    static void choose(Context ctx, Uri tree) {
        ctx.getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tree.toString()).apply();
    }

    static void forget(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Uri uri = treeUri(ctx);
        if (uri != null) {
            for (UriPermission p : ctx.getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(uri)) {
                    try {
                        ctx.getContentResolver().releasePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } catch (SecurityException ignored) {
                        // déjà retirée
                    }
                }
            }
        }
        prefs.edit().remove(KEY).apply();
    }

    String name() {
        return root.name == null ? "Chat2Doc" : root.name;
    }

    // ================================================================================================
    // Liste des discussions enregistrées

    List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (Node dir : listing(root, "").values()) {
            if (!dir.dir) continue;
            Node state = child(dir, dir.name, Archive.STATE);
            if (state == null) continue;
            Node resume = child(state, dir.name + "/" + Archive.STATE, "resume.tsv");
            if (resume == null) continue;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    ctx.getContentResolver().openInputStream(resume.uri), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("#") || line.isEmpty()) continue;
                    String[] c = line.split("\t", -1);
                    if (c.length < 7) continue;
                    Entry e = new Entry();
                    e.folder = dir.name;
                    e.title = c[0];
                    e.first = parse(c[1]);
                    e.last = parse(c[2]);
                    e.messages = Integer.parseInt(c[3]);
                    e.exports = Integer.parseInt(c[4]);
                    e.updated = parse(c[5]);
                    e.docxName = c[6];
                    e.dir = dir;
                    out.add(e);
                    break;
                }
            } catch (IOException | RuntimeException ignored) {
                // résumé illisible : discussion non listée
            }
        }
        out.sort((a, b) -> {
            if (a.updated == null || b.updated == null) return a.title.compareToIgnoreCase(b.title);
            return b.updated.compareTo(a.updated);
        });
        return out;
    }

    Uri documentUri(Entry e) {
        Node f = child(e.dir, e.folder, e.docxName);
        return f == null ? null : f.uri;
    }

    private static LocalDateTime parse(String s) {
        try {
            return s == null || s.isEmpty() ? null : LocalDateTime.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ================================================================================================
    // Lecture et mise à jour d'une archive

    /**
     * Prépare l'archive de travail locale d'une discussion : recopie les textes des exports précédents
     * (les autres fichiers seront lus à la demande).
     *
     * @return l'archive de travail ; distante si la discussion existe déjà dans le dossier Chat2Doc
     */
    Archive checkout(String folder, File localDir, ProgressListener progress) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        localDir.mkdirs();
        Node dir = child(root, "", folder);
        if (dir == null || !dir.dir) return new Archive(localDir, null);

        Node texts = child(dir, folder, Archive.TEXTS);
        if (texts != null) {
            File out = new File(localDir, Archive.TEXTS);
            //noinspection ResultOfMethodCallIgnored
            out.mkdirs();
            int n = 0;
            for (Node t : listing(texts, folder + "/" + Archive.TEXTS).values()) {
                if (!t.dir) {
                    progress.onProgress("Lecture du dossier Chat2Doc", ++n, 0);
                    copy(t.uri, new File(out, t.name));
                }
            }
        }
        return new Archive(localDir, (rel, dest) -> {
            Node f = resolve(folder + "/" + rel, false);
            if (f == null || f.dir) return false;
            copy(f.uri, dest);
            return true;
        });
    }

    /** Recopie dans le dossier Chat2Doc les fichiers créés ou modifiés par la conversion. */
    void commit(Archive archive, String folder, ProgressListener progress) throws IOException {
        int n = 0, total = archive.changed.size();
        for (String rel : archive.changed) {
            if (progress.isCancelled()) throw new ProgressListener.CancelledException();
            progress.onProgress("Enregistrement dans le dossier Chat2Doc", n++, total);
            File src = archive.file(rel);
            if (!src.exists()) continue;
            String full = folder + "/" + rel;
            int slash = full.lastIndexOf('/');
            Node parent = resolve(full.substring(0, slash), true);
            if (parent == null) throw new IOException("Impossible de créer le dossier " + full.substring(0, slash));
            String name = full.substring(slash + 1);
            Node target = child(parent, full.substring(0, slash), name);
            Uri uri;
            if (target != null) {
                uri = target.uri;
            } else {
                uri = create(parent.uri, "application/octet-stream", name);
                if (uri == null) throw new IOException("Impossible de créer " + full);
                listing(parent, full.substring(0, slash)).put(name, new Node(uri, name, false));
            }
            try (InputStream is = new FileInputStream(src);
                 OutputStream os = ctx.getContentResolver().openOutputStream(uri, "wt")) {
                if (os == null) throw new IOException("Écriture impossible : " + full);
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
            }
        }
        progress.onProgress("Enregistrement dans le dossier Chat2Doc", total, total);
    }

    // ------------------------------------------------------------------------------------------------

    /** Document au chemin relatif {@code path} (dossiers séparés par '/'), créé si demandé. */
    private Node resolve(String path, boolean create) {
        Node cur = root;
        String curPath = "";
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            Node next = child(cur, curPath, part);
            if (next == null) {
                if (!create) return null;
                Uri uri = create(cur.uri, Document.MIME_TYPE_DIR, part);
                if (uri == null) return null;
                next = new Node(uri, part, true);
                listing(cur, curPath).put(part, next);
            }
            cur = next;
            curPath = curPath.isEmpty() ? part : curPath + "/" + part;
        }
        return cur;
    }

    private Uri create(Uri parent, String mime, String name) {
        try {
            return DocumentsContract.createDocument(ctx.getContentResolver(), parent, mime, name);
        } catch (Exception e) {
            return null;
        }
    }

    private Node child(Node dir, String dirPath, String name) {
        return listing(dir, dirPath).get(name);
    }

    /** Contenu d'un dossier, lu une seule fois et en une seule requête (ce système d'accès est lent). */
    private Map<String, Node> listing(Node dir, String dirPath) {
        Map<String, Node> m = listings.get(dirPath);
        if (m != null) return m;
        m = new HashMap<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir.uri));
        try (Cursor c = ctx.getContentResolver().query(children, new String[]{
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (id == null || name == null) continue;
                m.put(name, new Node(DocumentsContract.buildDocumentUriUsingTree(tree, id), name,
                        Document.MIME_TYPE_DIR.equals(mime)));
            }
        } catch (RuntimeException ignored) {
            // dossier illisible : considéré comme vide
        }
        listings.put(dirPath, m);
        return m;
    }

    private void copy(Uri from, File to) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        to.getParentFile().mkdirs();
        try (InputStream is = ctx.getContentResolver().openInputStream(from);
             OutputStream os = new FileOutputStream(to)) {
            if (is == null) throw new IOException("Lecture impossible");
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
        }
    }

    /** Nom du dossier d'une discussion dans le dossier Chat2Doc. */
    static String folderFor(Converter.Inspection ins) {
        return ins.safe;
    }
}
