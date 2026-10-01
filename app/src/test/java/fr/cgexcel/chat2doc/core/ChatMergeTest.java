/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 * En échange, une seule chose vous est demandée, sur l'honneur : faire une bonne action chaque jour.
 * Aider un voisin, sourire à un inconnu, ramasser un papier… c'est vous qui voyez.
 */
package fr.cgexcel.chat2doc.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class ChatMergeTest {

    private static List<Message> parse(String text, String... files) {
        return new ChatParser(Arrays.asList(files), true, null).parse(text);
    }

    @Test
    public void fusionSansEtAvecMedias() {
        // Export « sans les médias » : remonte plus loin, photos remplacées par <Médias omis>
        List<Message> sans = parse(String.join("\n",
                "05/01/2026 09:01 - Marie: Bonne année !",
                "20/08/2026 10:00 - Marie: ok",
                "20/08/2026 10:00 - Marie: ok",
                "20/08/2026 10:05 - Paul: <Médias omis>",
                "Vue du sommet"));
        // Export « avec les médias » : messages récents seulement
        List<Message> avec = parse(String.join("\n",
                "20/08/2026 10:00 - Marie: ok",
                "20/08/2026 10:00 - Marie: ok",
                "20/08/2026 10:05 - Paul: IMG-20260820-WA0001.jpg (fichier joint)",
                "Vue du sommet",
                "01/09/2026 17:30 - Sophie: Nouveau"), "IMG-20260820-WA0001.jpg");

        List<Message> m = ChatMerge.mergeChronological(Arrays.asList(sans, avec));
        assertEquals(5, m.size());
        assertEquals("Bonne année !", m.get(0).text);
        assertEquals("ok", m.get(1).text);
        assertEquals("ok", m.get(2).text);                      // message répété : conservé deux fois
        assertEquals(Collections.singletonList("IMG-20260820-WA0001.jpg"), m.get(3).attachments); // version avec photo
        assertEquals("Vue du sommet", m.get(3).text);
        assertEquals("Nouveau", m.get(4).text);
    }

    @Test
    public void messageSupprimeDepuis() {
        List<Message> avant = parse(String.join("\n",
                "01/01/2026 10:00 - A: très ancien",
                "01/10/2026 17:30 - A: un",
                "01/10/2026 17:31 - B: à supprimer",
                "01/10/2026 17:32 - A: trois"));
        List<Message> apres = parse(String.join("\n",
                "01/10/2026 17:30 - A: un",
                "01/10/2026 17:32 - A: trois",
                "01/10/2026 17:40 - B: nouveau"));
        List<Message> m = ChatMerge.mergeChronological(Arrays.asList(avant, apres));
        assertEquals(4, m.size());
        assertEquals("très ancien", m.get(0).text);   // antérieur à l'export récent : conservé
        assertEquals("un", m.get(1).text);
        assertEquals("trois", m.get(2).text);          // « à supprimer » a disparu
        assertEquals("nouveau", m.get(3).text);
    }

    @Test
    public void exportSansMediasPlusRecent() {
        // L'export récent sans médias ne fait pas perdre les photos d'un export plus ancien avec médias
        List<Message> avec = parse("20/08/2026 10:05 - Paul: IMG-20260820-WA0001.jpg (fichier joint)\nVue",
                "IMG-20260820-WA0001.jpg");
        List<Message> sans = parse("20/08/2026 10:05 - Paul: <Médias omis>\nVue\n21/08/2026 09:00 - Paul: Suite");
        List<Message> m = ChatMerge.mergeChronological(Arrays.asList(avec, sans));
        assertEquals(2, m.size());
        assertEquals(1, m.get(0).attachments.size());
    }

    @Test
    public void legendeApresMediaOmis() {
        List<Message> m = parse("20/08/2026 10:05 - Paul: <Médias omis>\nVue du sommet");
        assertTrue(m.get(0).mediaOmitted);
        assertEquals("Vue du sommet", m.get(0).text);
    }

    @Test
    public void dateEnFrancais() {
        assertEquals("1er septembre 2026", DocxWriter.dateFr(java.time.LocalDate.of(2026, 9, 1), false));
        assertEquals("mardi 22 septembre 2026", DocxWriter.dateFr(java.time.LocalDate.of(2026, 9, 22), true));
    }
}
