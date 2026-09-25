/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

import java.util.Locale;

/** Catégories de médias, chacune rangée dans son propre sous-dossier de l'archive. */
public enum MediaKind {
    PHOTO("Photos", "Photo"),
    STICKER("Photos", "Autocollant"),
    VIDEO("Videos", "Vidéo"),
    AUDIO("Audio", "Audio"),
    VOICE("Audio", "Message vocal"),
    DOCUMENT("Documents", "Document"),
    CONTACT("Contacts", "Contact"),
    OTHER("Autres", "Fichier");

    /** Nom du sous-dossier (sans accent, pour rester lisible sur tous les systèmes). */
    public final String folder;
    /** Libellé affiché dans le document. */
    public final String label;

    MediaKind(String folder, String label) {
        this.folder = folder;
        this.label = label;
    }

    public boolean isImage() {
        return this == PHOTO || this == STICKER;
    }

    public static MediaKind of(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        String ext = extension(name);
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif": case "bmp": case "heic": case "heif":
                return PHOTO;
            case "webp":
                // Les autocollants WhatsApp sont des .webp nommés STK-... (Android) ou ...-STICKER-... (iPhone)
                return (name.startsWith("stk-") || name.contains("sticker")) ? STICKER : PHOTO;
            case "mp4": case "3gp": case "mov": case "avi": case "mkv": case "webm": case "m4v":
                return VIDEO;
            case "opus":
                return VOICE;
            case "ogg": case "m4a": case "mp3": case "aac": case "wav": case "amr": case "flac":
                return (name.startsWith("ptt-") || name.contains("audio-")) ? VOICE : AUDIO;
            case "pdf": case "doc": case "docx": case "xls": case "xlsx": case "xlsm": case "ppt": case "pptx":
            case "odt": case "ods": case "odp": case "txt": case "csv": case "rtf": case "zip": case "rar": case "7z":
            case "epub": case "apk": case "html": case "htm": case "json": case "xml": case "pages": case "numbers": case "key":
                return DOCUMENT;
            case "vcf":
                return CONTACT;
            default:
                return OTHER;
        }
    }

    static String extension(String name) {
        int p = name.lastIndexOf('.');
        return p < 0 ? "" : name.substring(p + 1).toLowerCase(Locale.ROOT);
    }
}
