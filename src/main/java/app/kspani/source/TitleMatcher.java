package app.kspani.source;

import app.kspani.domain.AniMedia;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic provider-series matcher. Source match is persisted after selection. */
public final class TitleMatcher {
    private TitleMatcher() {}

    public static SourceSeries best(AniMedia media, List<SourceSeries> candidates) {
        return ranked(media, candidates).stream().findFirst().orElse(null);
    }

    public static List<SourceSeries> ranked(AniMedia media, List<SourceSeries> candidates) {
        List<Scored> scored = new ArrayList<>();
        for (SourceSeries candidate : candidates) {
            double score = score(media, candidate);
            scored.add(new Scored(candidate, score));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored.stream().map(Scored::series).toList();
    }

    public static double score(AniMedia media, SourceSeries candidate) {
        double best = 0;
        List<String> candidateNames = new ArrayList<>();
        candidateNames.add(candidate.name());
        candidateNames.addAll(candidate.otherNames());
        for (String expected : media.searchTitles()) {
            for (String actual : candidateNames) {
                best = Math.max(best, similarity(expected, actual));
            }
        }
        if (candidate.total() != null && media.knownProgress() > 0 && candidate.total() < media.knownProgress()) {
            best -= 0.35;
        }
        if (candidate.total() != null && media.totalEpisodes() != null && media.totalEpisodes() > 0) {
            int diff = Math.abs(candidate.total() - media.totalEpisodes());
            if (diff == 0) best += 0.08;
            else if (diff <= 2) best += 0.03;
            else if (candidate.total() < media.totalEpisodes()) best -= Math.min(0.20, diff / (double) media.totalEpisodes() * 0.20);
        }

        // Sequel/season titles are intentionally similar, so fuzzy text matching alone can rank
        // Season 2 above Season 3. When both sides explicitly identify an installment and the
        // numbers disagree, apply a hard enough penalty to fall below the coordinator's 0.55
        // automatic-match threshold. This prevents cross-season playback.
        Set<Integer> expectedInstallments = installmentNumbers(media.searchTitles());
        Set<Integer> candidateInstallments = installmentNumbers(candidateNames);
        if (explicitMismatch(expectedInstallments, candidateInstallments)) {
            best -= 0.40;
        }

        Set<Integer> expectedParts = partNumbers(media.searchTitles());
        Set<Integer> candidateParts = partNumbers(candidateNames);
        if (explicitMismatch(expectedParts, candidateParts)) {
            best -= 0.22;
        }
        return best;
    }

    private static final Pattern SEASON_NUMBER = Pattern.compile("\\bseason\\s*(\\d{1,2})\\b");
    private static final Pattern ORDINAL_SEASON = Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)\\s+season\\b");
    private static final Pattern PART_NUMBER = Pattern.compile("\\bpart\\s*(\\d{1,2})\\b");
    private static final Pattern ROMAN_INSTALLMENT = Pattern.compile("\\b(ii|iii|iv|v|vi|vii|viii|ix|x)\\b");

    private static Set<Integer> installmentNumbers(List<String> names) {
        Set<Integer> numbers = new HashSet<>();
        if (names == null) return numbers;
        for (String name : names) {
            String normalized = normalize(name);
            collectNumbers(SEASON_NUMBER, normalized, numbers);
            collectNumbers(ORDINAL_SEASON, normalized, numbers);
            Matcher roman = ROMAN_INSTALLMENT.matcher(normalized);
            while (roman.find()) {
                int value = romanValue(roman.group(1));
                if (value > 1) numbers.add(value);
            }
        }
        return numbers;
    }

    private static Set<Integer> partNumbers(List<String> names) {
        Set<Integer> numbers = new HashSet<>();
        if (names == null) return numbers;
        for (String name : names) collectNumbers(PART_NUMBER, normalize(name), numbers);
        return numbers;
    }

    private static void collectNumbers(Pattern pattern, String value, Set<Integer> target) {
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        while (matcher.find()) {
            try {
                int number = Integer.parseInt(matcher.group(1));
                if (number > 0) target.add(number);
            } catch (NumberFormatException ignored) {
                // Ignore malformed provider titles.
            }
        }
    }

    private static boolean explicitMismatch(Set<Integer> expected, Set<Integer> actual) {
        if (expected.isEmpty() || actual.isEmpty()) return false;
        Set<Integer> overlap = new HashSet<>(expected);
        overlap.retainAll(actual);
        return overlap.isEmpty();
    }

    private static int romanValue(String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "ii" -> 2;
            case "iii" -> 3;
            case "iv" -> 4;
            case "v" -> 5;
            case "vi" -> 6;
            case "vii" -> 7;
            case "viii" -> 8;
            case "ix" -> 9;
            case "x" -> 10;
            default -> 0;
        };
    }

    static double similarity(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isBlank() || y.isBlank()) return 0;
        if (x.equals(y)) return 1.0;
        if (x.contains(y) || y.contains(x)) {
            double ratio = (double) Math.min(x.length(), y.length()) / Math.max(x.length(), y.length());
            return 0.82 + 0.15 * ratio;
        }
        Set<String> ax = tokens(x);
        Set<String> by = tokens(y);
        Set<String> intersection = new HashSet<>(ax);
        intersection.retainAll(by);
        Set<String> union = new HashSet<>(ax);
        union.addAll(by);
        double jaccard = union.isEmpty() ? 0 : (double) intersection.size() / union.size();
        return Math.max(jaccard * 0.9, levenshteinRatio(x, y) * 0.8);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String s = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return s;
    }

    private static Set<String> tokens(String s) {
        return new HashSet<>(List.of(s.split(" ")));
    }

    private static double levenshteinRatio(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        int max = Math.max(a.length(), b.length());
        return max == 0 ? 1 : 1.0 - ((double) prev[b.length()] / max);
    }

    private record Scored(SourceSeries series, double score) {}
}
