package dev.applewatchandroid.companion.apple_watch_companion;

/** CoreFoundation composed ranges, using captured 23S303 properties.
 * Algorithm reference: swift-corelibs-foundation CFString.c, commit
 * b2112d2d80c4365dbb32479d89bb3177bfda8ea8 (Apache-2.0 with Runtime Exception).
 * Native replay verifies casing, contextual boundaries and the complete pipeline. */
final class NativeFoundationClusters {
    private static final NativeFoundationTextData DATA = NativeFoundationTextData.INSTANCE;
    private static final int[] HINT_LENGTH = {2, 3, 4, 4, 4, 4, 4, 2, 2, 2, 2, 4, 0, 0, 0, 0};
    private static final int L = 0, V = 1, T = 2, LV = 3, LVT = 4, BREAK = 5;
    private NativeFoundationClusters() { }

    static int prefixEnd(String text, int limit) {
        int end = Math.min(limit, text.length());
        return end == 0 ? 0 : cluster(text, end - 1).end;
    }

    private static int unit(String text, int index) {
        return index < 0 || index >= text.length() ? 0 : text.charAt(index);
    }
    private static Range scalarRange(String text, int index) {
        if (index < 0 || index >= text.length()) return new Range(-1, -1);
        if (Character.isLowSurrogate(text.charAt(index)) && index > 0) index--;
        return new Range(index, index + Character.charCount(text.codePointAt(index)));
    }
    private static int scalar(String text, Range range) {
        return range.start < 0 ? 0 : text.codePointAt(range.start);
    }
    private static boolean combining(int cp) {
        // 23S303 basic ranges also include ZWJ (c615c/c6980); open-source CF differs.
        return cp == 0x200d || DATA.nonBase(cp) || cp >= 0x1f3fb && cp <= 0x1f3ff
            || cp >= 0xe0020 && cp <= 0xe007f || cp == 0xff9e || cp == 0xff9f
            || (cp & 0x1ffff0) == 0xf870;
    }
    private static boolean hangul(int cp) {
        return cp >= 0x1100 && cp <= 0x11f9 || cp >= 0xac00 && cp <= 0xd7af;
    }
    private static boolean lvt(int cp) { return (cp - 0xac00) % 28 != 0; }
    private static int hangulState(int cp) {
        return cp < 0x1160 ? L : cp < 0x11a8 ? V : cp < 0xac00 ? T : lvt(cp) ? LVT : LV;
    }
    private static Range basic(String text, int index) {
        Range initial = scalarRange(text, index);
        int start = initial.start, end = initial.end, cp = scalar(text, initial);
        while (start > 0 && combining(cp)) {
            Range previous = scalarRange(text, start - 1);
            start = previous.start;
            cp = scalar(text, previous);
        }
        if (hangul(cp)) {
            int state = hangulState(cp), initialState = state;
            while (start > 0 && hangul(cp = unit(text, start - 1))) {
                switch (state) {
                    case V:
                        if (cp <= 0x115f) state = L;
                        else if (cp >= 0xac00 && !lvt(cp)) state = LV;
                        else if (cp > 0x11a2) state = BREAK;
                        break;
                    case T:
                        if (cp >= 0x1160 && cp <= 0x11a2) state = V;
                        else if (cp >= 0xac00) state = lvt(cp) ? LVT : LV;
                        else if (cp < 0x1160) state = BREAK;
                        break;
                    default: state = cp < 0x1160 ? L : BREAK;
                }
                if (state == BREAK) break;
                start--;
            }
            state = initialState;
            while (hangul(cp = unit(text, end))) {
                switch (state) {
                    case LV: case V:
                        state = cp >= 0x1160 && cp <= 0x11f9 ? (cp < 0x11a8 ? V : T) : BREAK;
                        break;
                    case LVT: case T:
                        state = cp >= 0x11a8 && cp <= 0x11f9 ? T : BREAK;
                        break;
                    default:
                        state = cp < 0x1160 ? L : cp < 0x11a8 ? V : cp >= 0xac00 ? (lvt(cp) ? LVT : LV) : BREAK;
                }
                if (state == BREAK) break;
                end++;
            }
        }
        while (end < text.length()) {
            cp = text.codePointAt(end);
            if (cp == 0 || !combining(cp)) break;
            end += Character.charCount(cp);
        }
        return new Range(start, end);
    }
    private static boolean virama(String text, int index) {
        int cp = unit(text, index);
        if (cp == 0x0bcd) {
            // Native __CFStringIsJoiningPulliAtIndex c6aa4: Tamil conjunct policy.
            if (index <= 0) return false;
            int previous = unit(text, index - 1), next = unit(text, index + 1);
            return previous == 0x0b95 && next == 0x0bb7
                || (previous == 0x0bb6 || previous == 0x0bb8) && next == 0x0bb0 && unit(text, index + 2) == 0x0bc0;
        }
        return cp == 0x034f || DATA.combining(cp) == 9;
    }
    private static boolean regional(String text, int index) {
        if (index < 0 || index + 1 >= text.length()) return false;
        int cp = text.codePointAt(index);
        return cp >= 0x1f1e6 && cp <= 0x1f1ff;
    }
    private static Range cluster(String text, int index) {
        Range range = basic(text, index);
        int cp = text.codePointAt(range.start);
        if (cp == 0x200d || DATA.letter(cp)) {
            int cursor = range.start;
            while (cursor > 1) {
                cp = unit(text, --cursor);
                if (!(virama(text, cursor) || cp == 0x200d && virama(text, --cursor)) || cursor <= 0) break;
                cursor = basic(text, --cursor).start;
                // Foundation's backward joiner check reads the UTF-16 unit at this position.
                cp = unit(text, cursor);
                if (!DATA.letter(cp)) break;
                range.start = cursor;
            }
            if (range.length() > 1 && range.end < text.length()) {
                cursor = range.end;
                do {
                    cp = unit(text, cursor - 1);
                    if (cp != 0x200d && !virama(text, cursor - 1)) break;
                    cp = unit(text, cursor);
                    if (cp == 0x200d) cp = unit(text, ++cursor);
                    if (cursor < text.length()) cp = text.codePointAt(cursor);
                    if (!DATA.letter(cp)) break;
                    cursor = basic(text, cursor).end;
                } while (cursor < text.length());
                range.end = cursor;
            }
        }
        for (int cursor = Math.max(0, range.end - 5); cursor <= range.start; cursor++) {
            cp = unit(text, cursor);
            if ((cp & 0x1ffff0) != 0xf860) continue;
            int end = cursor + HINT_LENGTH[cp - 0xf860] + 1;
            if (end < range.end) continue;
            if (end <= text.length()) {
                for (int check = cursor + 1; check < end;) {
                    Range next = basic(text, check);
                    check = next.end;
                    if (check > end) { end = next.start; break; }
                }
                range = new Range(cursor, end);
            }
            break;
        }
        if (range.length() == 2 && regional(text, range.start)) {
            int first = range.start;
            while (first > 1 && regional(text, first - 2)) first -= 2;
            if (range.start > first && (range.start - first) % 4 != 0) range.start -= 2;
            if (range.length() == 2 && range.end + 2 <= text.length() && regional(text, range.end)) range.end += 2;
        }
        Range picture = pictographic(text, range.start);
        if (picture != null && picture.start <= range.start && picture.end >= range.end) range = picture;
        if (range.start > 0 && range.length() == 1 && unit(text, range.start) == 0x200d) {
            Range previous = basic(text, range.start - 1);
            if (previous.end == range.start) range = new Range(previous.start, range.end);
        }
        if (range.end < text.length() && unit(text, range.end) == 0x200d) range.end++;
        return range;
    }

    private static Component component(String text, int index) {
        if (index < 0 || index >= text.length()) return null;
        Component match = new Component();
        int cursor = index;
        while (cursor >= 0) {
            Range read = scalarRange(text, cursor);
            int cp = scalar(text, read);
            if (DATA.extend(cp)) match.firstExtend = read.start;
            else if (cp == 0x200d) {
                if (match.firstExtend != -1 || match.zwj != -1) break;
                match.zwj = read.start;
            } else if (DATA.pictographic(cp)) {
                if (match.picture != -1 || match.zwj != -1 || match.firstExtend != -1) break;
                match.picture = read.start;
            } else break;
            int size = match.range.length();
            match.range.start = read.start;
            match.range.end = read.start + size + read.length();
            cursor = read.start - 1;
        }
        if (match.picture == -1 && match.zwj == -1 && match.firstExtend == -1) return null;
        if (match.picture != -1) {
            if (match.firstExtend != -1 && match.zwj == -1) match.range.start = match.picture;
            return match;
        }
        cursor = match.range.end;
        while (match.picture == -1 && cursor < text.length()) {
            Range read = scalarRange(text, cursor);
            int cp = scalar(text, read);
            if (DATA.extend(cp)) { if (match.zwj != -1) break; }
            else if (cp == 0x200d) { if (match.zwj != -1) break; match.zwj = read.start; }
            else if (DATA.pictographic(cp)) match.picture = read.start;
            else break;
            match.range.end = read.end;
            cursor = read.end;
        }
        return match.picture == -1 ? null : match;
    }
    private static Range pictographic(String text, int index) {
        Range read = scalarRange(text, index);
        int cp = scalar(text, read);
        Range post = new Range(read.length(), read.length());
        while (DATA.postcore(cp)) {
            int size = post.length();
            post.start = read.start; post.end = post.start + size + read.length();
            if (post.start == 0) return null;
            read = scalarRange(text, post.start - 1); cp = scalar(text, read);
        }
        Range core = new Range(read.start, read.start);
        Component previous = new Component(), found;
        int cursor = read.start;
        while ((found = component(text, cursor)) != null) {
            previous = found;
            int size = core.length();
            core.start = found.range.start; core.end = core.start + size + found.range.length();
            cursor = found.range.start - 1;
            if (found.zwj == -1) break;
        }
        boolean precore = previous.firstExtend == -1 && previous.zwj == -1;
        if (!precore) { core.start = previous.picture; cursor = previous.picture + 1; }
        if (post.length() > 0 && core.length() == 0) return null;
        Range pre = new Range(cursor, cursor);
        if (precore) {
            if (cursor >= 0) {
                read = scalarRange(text, cursor); cp = scalar(text, read);
                while (DATA.prepend(cp)) {
                    int size = pre.length();
                    pre.start = read.start; pre.end = pre.start + size + read.length();
                    if (pre.start == 0) break;
                    read = scalarRange(text, pre.start - 1); cp = scalar(text, read);
                }
            }
            cursor = pre.end;
            while (cursor < text.length()) {
                read = scalarRange(text, cursor);
                if (!DATA.prepend(scalar(text, read))) break;
                pre.end += read.length(); cursor = read.end;
            }
        }
        if (pre.length() == 0 && core.length() == 0) return null;
        if (core.length() == 0) core = new Range(pre.end, pre.end);
        cursor = core.end;
        while ((found = component(text, cursor)) != null) {
            if (core.length() > 0 && found.zwj == -1) break;
            core.end += found.range.length(); cursor += found.range.length();
        }
        if (post.length() > 0) {
            if (core.end >= post.end) post = new Range(core.end, core.end);
            cursor = post.end;
        } else post = new Range(cursor, cursor);
        while (cursor < text.length()) {
            read = scalarRange(text, cursor);
            if (!DATA.postcore(scalar(text, read))) break;
            post.end += read.length(); cursor = read.end;
        }
        if (core.length() == 0) return null;
        return new Range(pre.length() > 0 ? pre.start : core.start, core.end + post.length());
    }
    private static final class Range {
        int start, end;
        Range(int start, int end) { this.start = start; this.end = end; }
        int length() { return end - start; }
    }
    private static final class Component {
        final Range range = new Range(-1, -1);
        int firstExtend = -1, zwj = -1, picture = -1;
    }
}
