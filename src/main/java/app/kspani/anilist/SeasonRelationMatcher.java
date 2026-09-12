package app.kspani.anilist;

import app.kspani.domain.AniMedia;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Keeps the details-page season selector limited to actual same-series seasonal entries. */
public final class SeasonRelationMatcher {
    private SeasonRelationMatcher() {}

    public static boolean isSeasonFormat(String format) {
        return "TV".equalsIgnoreCase(format) || "TV_SHORT".equalsIgnoreCase(format)
                || "ONA".equalsIgnoreCase(format);
    }

    public static boolean sameFamily(AniMedia seed, List<String> candidateTitles) {
        if (seed == null || candidateTitles == null) return false;
        for (String a : seed.searchTitles()) {
            String na = familyKey(a);
            if (na.length() < 3) continue;
            for (String b : candidateTitles) {
                String nb = familyKey(b);
                if (nb.length() < 3) continue;
                if (na.equals(nb)) return true;
                if (seasonalPrefixMatch(na, nb) || seasonalPrefixMatch(nb, na)) return true;
                Set<String> ta = new HashSet<>(List.of(na.split(" ")));
                Set<String> tb = new HashSet<>(List.of(nb.split(" ")));
                ta.removeIf(String::isBlank);
                tb.removeIf(String::isBlank);
                Set<String> intersection = new HashSet<>(ta);
                intersection.retainAll(tb);
                int min = Math.min(ta.size(), tb.size());
                if (min >= 2 && intersection.size() >= Math.max(2, (int)Math.ceil(min * 0.70))) return true;
            }
        }
        return false;
    }

    private static boolean seasonalPrefixMatch(String longer, String shorter) {
        if (!longer.startsWith(shorter + " ")) return false;
        int shorterTokens = shorter.split(" ").length;
        if (shorterTokens >= 2) return true;
        String remainder = longer.substring(shorter.length()).trim();
        return remainder.matches("(?i)(?:[0-9]+|[ivx]+|[0-9]+(?:st|nd|rd|th))(?:\\s+.*)?");
    }

    static String familyKey(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("(?i)\\b(?:the\\s+)?(?:final\\s+)?season\\s*[0-9ivx]*\\b", " ")
                .replaceAll("(?i)\\b(?:part|cour)\\s*[0-9ivx]+\\b", " ")
                .replaceAll("(?i)\\b[0-9]+(?:st|nd|rd|th)\\s+season\\b", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ").trim();
    }
}
