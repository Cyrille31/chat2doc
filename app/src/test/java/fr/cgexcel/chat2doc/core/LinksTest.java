/*
 * Chat2Doc — CGExcel
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.Test;

public class LinksTest {

    @Test
    public void reperageDesAdresses() {
        List<Links.Found> f = Links.find("Voir https://exemple.fr/page?a=1&b=2. Et www.site.org, puis (https://x.io/y).");
        assertEquals(3, f.size());
        assertEquals("https://exemple.fr/page?a=1&b=2", f.get(0).url);
        assertEquals("http://www.site.org", f.get(1).url);
        assertEquals("https://x.io/y", f.get(2).url);
    }

    @Test
    public void videosYoutube() {
        assertEquals("dQw4w9WgXcQ", Links.youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", Links.youtubeId("https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"));
        assertEquals("abcdefghijk", Links.youtubeId("https://youtu.be/abcdefghijk?si=xyz"));
        assertEquals("abcdefghijk", Links.youtubeId("https://youtube.com/shorts/abcdefghijk"));
        assertNull(Links.youtubeId("https://www.youtube.com/@chaine"));
    }

    @Test
    public void balisesOpenGraph() {
        String html = "<html><head><title>T</title>"
                + "<meta content=\"L&#39;&eacute;t&eacute; &amp; nous\" property=\"og:title\">"
                + "<meta property='og:image' content='/i.jpg'/><meta name=\"twitter:title\" content=\"autre\">"
                + "</head><body><meta property=\"og:title\" content=\"ignoré\"></body></html>";
        Map<String, String> m = LinkPreviewFetcher.metaTags(html);
        assertEquals("L'été & nous", m.get("og:title"));
        assertEquals("/i.jpg", m.get("og:image"));
    }

    @Test
    public void jsonYoutube() {
        String json = "{\"title\":\"Concert \\u00e9t\\u00e9 \\\"live\\\"\",\"author_name\":\"Cha\\u00eene\"}";
        assertEquals("Concert été \"live\"", LinkPreviewFetcher.jsonString(json, "title"));
        assertEquals("Chaîne", LinkPreviewFetcher.jsonString(json, "author_name"));
        assertNull(LinkPreviewFetcher.jsonString(json, "absent"));
    }

    @Test
    public void estimationDeDuree() {
        assertEquals("moins d’une minute", LinkPreviewFetcher.describeDuration(LinkPreviewFetcher.estimateSeconds(20)));
        assertEquals("environ 6 minutes", LinkPreviewFetcher.describeDuration(LinkPreviewFetcher.estimateSeconds(1000)));
    }
}
