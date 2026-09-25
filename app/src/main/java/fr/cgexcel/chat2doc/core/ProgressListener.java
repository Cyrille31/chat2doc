/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

/** Suivi de l'avancement d'une conversion (appelé depuis le fil de travail). */
public interface ProgressListener {

    /**
     * @param stage libellé de l'étape en cours
     * @param done  éléments traités
     * @param total nombre total d'éléments (0 si inconnu)
     */
    void onProgress(String stage, int done, int total);

    /** La conversion doit s'interrompre dès que possible. */
    boolean isCancelled();

    /** Levée quand l'utilisateur annule la conversion. */
    final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("Conversion annulée");
        }
    }

    ProgressListener NONE = new ProgressListener() {
        @Override public void onProgress(String stage, int done, int total) { }
        @Override public boolean isCancelled() { return false; }
    };
}
