/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

import java.io.File;

/** Un fichier média de l'export, après rangement dans son sous-dossier. */
public final class MediaFile {
    public final String name;
    /** Chemin relatif au dossier du document, avec des '/' (ex. « Photos/IMG-20260924-WA0003.jpg »). */
    public final String relativePath;
    public final File file;
    public final MediaKind kind;

    public MediaFile(String name, String relativePath, File file, MediaKind kind) {
        this.name = name;
        this.relativePath = relativePath;
        this.file = file;
        this.kind = kind;
    }
}
