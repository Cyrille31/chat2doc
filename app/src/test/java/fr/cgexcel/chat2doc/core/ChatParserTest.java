/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class ChatParserTest {

    private static final String LRM = "‎";

    @Test
    public void exportAndroidFrancais() {
        String txt = String.join("\n",
                "12/03/2026 09:58 - Marie a créé le groupe « Famille : été »",
                "12/03/2026 10:02 - Marie: Bonjour à tous !",
                "Deuxième ligne",
                "12/03/2026 10:03 - Paul: IMG-20260312-WA0001.jpg (fichier joint)",
                "La plage",
                "13/03/2026 08:15 - Sophie: " + LRM + "Rapport.pdf • " + LRM + "3 pages " + LRM + "(fichier joint)",
                "13/03/2026 08:16 - Paul: <Médias omis>",
                "13/03/2026 08:17 - Paul: Ce message a été supprimé",
                "13/03/2026 08:18 - Marie: À demain <Ce message a été modifié>");
        ChatParser p = new ChatParser(Arrays.asList("IMG-20260312-WA0001.jpg", "Rapport.pdf"), true, "Famille");
        List<Message> m = p.parse(txt);

        assertEquals(7, m.size());
        assertNull(m.get(0).sender);
        assertEquals("Marie", m.get(1).sender);
        assertEquals("Bonjour à tous !\nDeuxième ligne", m.get(1).text);
        assertEquals(LocalDateTime.of(2026, 3, 12, 10, 2), m.get(1).time);
        assertEquals(Collections.singletonList("IMG-20260312-WA0001.jpg"), m.get(2).attachments);
        assertEquals("La plage", m.get(2).text);
        assertEquals(Collections.singletonList("Rapport.pdf"), m.get(3).attachments);
        assertEquals("", m.get(3).text);
        assertTrue(m.get(4).mediaOmitted);
        assertTrue(m.get(5).deleted);
        assertTrue(m.get(6).edited);
        assertEquals("À demain", m.get(6).text);
    }

    @Test
    public void exportIphoneAnglais() {
        String txt = String.join("\n",
                "[9/20/26, 1:58:02 PM] Hiking club: " + LRM + "Messages and calls are end-to-end encrypted.",
                "[9/20/26, 2:02:11 PM] Anna: " + LRM + "<attached: 00000004-PHOTO-2026-09-20-14-02-11.jpg>",
                "[9/21/26, 12:30:00 AM] Bob: Late night");
        ChatParser p = new ChatParser(Collections.singletonList("00000004-PHOTO-2026-09-20-14-02-11.jpg"), true, "Hiking club");
        List<Message> m = p.parse(txt);

        assertEquals(3, m.size());
        assertTrue(m.get(0).isSystem());
        assertEquals(LocalDateTime.of(2026, 9, 20, 13, 58), m.get(0).time);
        assertEquals("Anna", m.get(1).sender);
        assertEquals(1, m.get(1).attachments.size());
        assertEquals(LocalDateTime.of(2026, 9, 21, 0, 30), m.get(2).time);
    }

    @Test
    public void ordreDesDatesDeduitDuFichier() {
        // « 03/04 » est ambigu, mais « 04/13 » impose l'ordre mois/jour
        String txt = "03/04/2026 10:00 - A: un\n04/13/2026 10:00 - B: deux";
        List<Message> m = new ChatParser(Collections.<String>emptyList(), true, null).parse(txt);
        assertEquals(LocalDateTime.of(2026, 3, 4, 10, 0), m.get(0).time);
        assertEquals(LocalDateTime.of(2026, 4, 13, 10, 0), m.get(1).time);
    }

    @Test
    public void reconnaissanceDUnExport() {
        assertTrue(ChatParser.looksLikeChat("24/09/2026 21:15 - Marie: Bonjour\n24/09/2026 21:16 - Paul: Salut"));
        assertFalse(ChatParser.looksLikeChat("Liste de courses\npain\nlait"));
    }

    @Test
    public void titreDeLaDiscussion() {
        assertEquals("Famille", Converter.guessTitle("Discussion WhatsApp avec Famille.txt", null));
        assertEquals("Club rando", Converter.guessTitle("_chat.txt", "WhatsApp Chat - Club rando.zip"));
        assertEquals("A_B", Converter.safeFileName("A/B"));
    }
}
