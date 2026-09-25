/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

import java.io.File;
import java.io.IOException;

/**
 * Prépare une image pour son insertion dans le document Word : redressement selon l'orientation EXIF,
 * réduction éventuelle, conversion en JPEG ou PNG (seuls formats lus partout).
 * Implémenté avec les API Android dans l'application, avec javax.imageio pour les tests sur PC.
 */
public interface ImageProcessor {

    /**
     * @param source image d'origine
     * @param maxPx  plus grand côté souhaité en pixels ; 0 = taille d'origine
     * @return l'image prête à insérer, ou {@code null} si le format n'est pas décodable
     */
    Result process(File source, int maxPx) throws IOException;

    final class Result {
        public final byte[] data;
        /** "jpg" ou "png". */
        public final String extension;
        public final int width;
        public final int height;

        public Result(byte[] data, String extension, int width, int height) {
            this.data = data;
            this.extension = extension;
            this.width = width;
            this.height = height;
        }
    }
}
