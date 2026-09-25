/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Un message de la discussion, tel qu'il a été reconstitué depuis le fichier texte de l'export. */
public final class Message {

    /** Date et heure d'envoi. */
    public final LocalDateTime time;

    /** Expéditeur, ou {@code null} pour un message système (création du groupe, ajout d'un membre...). */
    public final String sender;

    /** Texte du message (sans les mentions de pièces jointes), lignes séparées par '\n'. */
    public String text;

    /** Noms des fichiers joints, dans l'ordre où ils apparaissent. */
    public final List<String> attachments = new ArrayList<>();

    /** Le message contenait un média que WhatsApp n'a pas inclus dans l'export (« <Médias omis> »). */
    public boolean mediaOmitted;

    /** Le message a été supprimé par son auteur (« Ce message a été supprimé »). */
    public boolean deleted;

    /** Le message a été modifié après envoi. */
    public boolean edited;

    Message(LocalDateTime time, String sender, String text) {
        this.time = time;
        this.sender = sender;
        this.text = text;
    }

    public boolean isSystem() {
        return sender == null;
    }
}
