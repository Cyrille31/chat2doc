/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fusion de plusieurs exports d'une même discussion (par exemple un export « sans les médias », qui
 * remonte plus loin, et un export « avec les médias », limité aux messages récents ; ou des exports
 * faits à des mois d'intervalle).
 *
 * <p>Deux messages sont identiques s'ils ont la même heure, le même auteur et le même texte (légende
 * pour un média). Comme un même message peut légitimement se répéter dans la même minute (« ok »,
 * deux photos...), on compte les occurrences : pour chaque message, on retient l'export qui en
 * contient le plus, et à égalité celui qui a le plus de pièces jointes (celui « avec les médias »).
 */
public final class ChatMerge {

    private ChatMerge() { }

    private static final class Pos {
        final Message msg;
        final int source, index;

        Pos(Message msg, int source, int index) {
            this.msg = msg;
            this.source = source;
            this.index = index;
        }
    }

    public static List<Message> merge(List<List<Message>> sources) {
        if (sources.isEmpty()) return new ArrayList<>();
        if (sources.size() == 1) return sources.get(0);

        // Occurrences de chaque message, export par export
        Map<String, List<List<Pos>>> byKey = new LinkedHashMap<>();
        for (int s = 0; s < sources.size(); s++) {
            List<Message> list = sources.get(s);
            for (int i = 0; i < list.size(); i++) {
                Message m = list.get(i);
                List<List<Pos>> perSource = byKey.computeIfAbsent(key(m), k -> {
                    List<List<Pos>> l = new ArrayList<>();
                    for (int x = 0; x < sources.size(); x++) l.add(new ArrayList<>());
                    return l;
                });
                perSource.get(s).add(new Pos(m, s, i));
            }
        }

        List<Pos> kept = new ArrayList<>();
        for (List<List<Pos>> perSource : byKey.values()) {
            int best = -1, bestCount = -1, bestAtt = -1;
            for (int s = 0; s < perSource.size(); s++) {
                List<Pos> occ = perSource.get(s);
                int att = 0;
                for (Pos p : occ) att += p.msg.attachments.size();
                if (occ.size() > bestCount || (occ.size() == bestCount && att >= bestAtt)) {
                    best = s;
                    bestCount = occ.size();
                    bestAtt = att;
                }
            }
            kept.addAll(perSource.get(best));
        }

        kept.sort((a, b) -> {
            int c = a.msg.time.compareTo(b.msg.time);
            if (c != 0) return c;
            if (a.source != b.source) return Integer.compare(a.source, b.source);
            return Integer.compare(a.index, b.index);
        });
        List<Message> out = new ArrayList<>(kept.size());
        for (Pos p : kept) out.add(p.msg);
        return out;
    }

    static String key(Message m) {
        String text = m.text == null ? "" : m.text.replaceAll("\\s+", " ").trim();
        return m.time + "\u0001" + (m.sender == null ? "" : m.sender) + "\u0001" + text;
    }
}
