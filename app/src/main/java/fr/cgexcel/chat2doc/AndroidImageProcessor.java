/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import fr.cgexcel.chat2doc.core.ImageProcessor;

/** Préparation des photos avec les outils d'Android (JPEG, PNG, WebP, GIF, HEIC selon la version). */
final class AndroidImageProcessor implements ImageProcessor {

    private static final int JPEG_QUALITY = 82;

    @Override
    public Result process(File source, int maxPx) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        String mime = bounds.outMimeType == null ? "" : bounds.outMimeType;
        int rotation = exifRotation(source, mime);

        // Taille d'origine demandée, et fichier déjà au bon format : on le reprend tel quel
        if (maxPx == 0 && rotation == 0 && (mime.equals("image/jpeg") || mime.equals("image/png"))) {
            byte[] data = Files.readAllBytes(source.toPath());
            return new Result(data, mime.equals("image/png") ? "png" : "jpg", bounds.outWidth, bounds.outHeight);
        }

        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        if (maxPx > 0) {
            while (longest / (opts.inSampleSize * 2) >= maxPx) opts.inSampleSize *= 2;
        }
        Bitmap bmp = BitmapFactory.decodeFile(source.getPath(), opts);
        if (bmp == null) return null;

        try {
            // Réduction exacte au plus grand côté demandé
            int w = bmp.getWidth(), h = bmp.getHeight();
            if (maxPx > 0 && Math.max(w, h) > maxPx) {
                double k = (double) maxPx / Math.max(w, h);
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, (int) Math.round(w * k)),
                        Math.max(1, (int) Math.round(h * k)), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            // Redressement selon l'orientation enregistrée par l'appareil photo
            if (rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (rotated != bmp) bmp.recycle();
                bmp = rotated;
            }

            boolean transparent = bmp.hasAlpha() && !mime.equals("image/jpeg");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (transparent) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            } else {
                if (bmp.hasAlpha()) {
                    Bitmap opaque = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(opaque);
                    c.drawColor(Color.WHITE);
                    c.drawBitmap(bmp, 0, 0, null);
                    bmp.recycle();
                    bmp = opaque;
                }
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
            }
            return new Result(bos.toByteArray(), transparent ? "png" : "jpg", bmp.getWidth(), bmp.getHeight());
        } finally {
            bmp.recycle();
        }
    }

    private static int exifRotation(File f, String mime) {
        if (!mime.equals("image/jpeg") && !mime.equals("image/heif") && !mime.equals("image/heic")) return 0;
        try {
            ExifInterface exif = new ExifInterface(f.getPath());
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }
}
