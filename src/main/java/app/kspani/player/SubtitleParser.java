package app.kspani.player;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal SRT/WebVTT parser for external English subtitle tracks. */
public final class SubtitleParser {
    private static final Pattern TIME = Pattern.compile(
            "(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?:[,.](\\d{1,3}))?\\s*-->\\s*"
                    + "(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?:[,.](\\d{1,3}))?.*");

    private SubtitleParser() {}

    public static List<SubtitleCue> parse(String source) {
        if (source == null || source.isBlank()) return List.of();
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        String[] blocks = normalized.split("\\n\\s*\\n");
        List<SubtitleCue> out = new ArrayList<>();
        for (String block : blocks) {
            String[] lines = block.strip().split("\\n");
            if (lines.length < 2) continue;
            int timeIndex = -1;
            Matcher matcher = null;
            for (int i=0;i<Math.min(lines.length,3);i++) {
                Matcher m = TIME.matcher(lines[i].trim());
                if (m.matches()) { timeIndex=i; matcher=m; break; }
            }
            if (timeIndex < 0 || matcher == null) continue;
            long start = millis(matcher,1,2,3,4);
            long end = millis(matcher,5,6,7,8);
            if (end <= start) continue;
            StringBuilder text = new StringBuilder();
            for (int i=timeIndex+1;i<lines.length;i++) {
                String line = lines[i]
                        .replaceAll("<[^>]+>", "")
                        .replace("&nbsp;", " ")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">").trim();
                if (line.isBlank()) continue;
                if (!text.isEmpty()) text.append('\n');
                text.append(line);
            }
            if (!text.isEmpty()) out.add(new SubtitleCue(start,end,text.toString()));
        }
        out.sort(java.util.Comparator.comparingLong(SubtitleCue::startMs));
        return List.copyOf(out);
    }

    public static String at(List<SubtitleCue> cues, long positionMs) {
        if (cues == null || cues.isEmpty()) return "";
        int low=0, high=cues.size()-1;
        while(low<=high) {
            int mid=(low+high)>>>1;
            SubtitleCue cue=cues.get(mid);
            if(positionMs<cue.startMs()) high=mid-1;
            else if(positionMs>cue.endMs()) low=mid+1;
            else return cue.text();
        }
        return "";
    }

    private static long millis(Matcher m,int h,int min,int sec,int ms) {
        long hours=m.group(h)==null?0:Long.parseLong(m.group(h));
        String fraction=m.group(ms);
        long milliseconds=fraction==null?0:Long.parseLong(
                fraction.length()==1?fraction+"00":fraction.length()==2?fraction+"0":fraction);
        return (((hours*60)+Long.parseLong(m.group(min)))*60+Long.parseLong(m.group(sec)))*1000+milliseconds;
    }
}
