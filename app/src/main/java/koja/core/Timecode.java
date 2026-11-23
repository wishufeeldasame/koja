package koja.core;

import java.util.Locale;

/** 표시용 시간 형식. m:ss, 1시간 넘으면 h:mm:ss (docs/설계.md 5장, 미확정 기본값). */
public final class Timecode {
    private Timecode() {}

    /** null(QUESTION_JA 행)은 빈 문자열. */
    public static String format(Long ms) {
        if (ms == null) {
            return "";
        }
        long s = ms / 1000;
        long h = s / 3600;
        return h > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", h, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
