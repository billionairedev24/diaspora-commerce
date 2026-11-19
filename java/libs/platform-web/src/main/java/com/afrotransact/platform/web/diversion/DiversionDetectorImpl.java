package com.afrotransact.platform.web.diversion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

final class DiversionDetectorImpl implements DiversionDetector {

    private static final Pattern URL_SCHEME = Pattern.compile("(?:https?|ftp)://|(?:^|[^a-z0-9])www\\s?\\.");
    private static final Pattern DOMAIN = Pattern.compile("(?:^|[^a-z0-9])[a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)*\\."
            + "(?:com|net|org|info|biz|shop|store|online|site|xyz|app|link|uk|ng|gh|ke|za|fr)(?:$|[^a-z0-9])");
    private static final Pattern SHORT = Pattern.compile("(?:^|[^a-z0-9])(?:bit\\.ly|wa\\.me|t\\.me|linktr\\.ee|tinyurl\\.com|goo\\.gl"
            + "|ig\\.me|fb\\.me|m\\.me|instagr\\.am|youtu\\.be)(?:$|[^a-z0-9])");
    private static final Pattern AMBIGUOUS_TLD = Pattern.compile(
            "(?:^|[^A-Za-z0-9])[a-z0-9][a-z0-9-]{2,}\\.(?:co|io|me|us|ca|ly|ee)(?:$|[^A-Za-z0-9])");
    private static final Pattern BARE_TLD = Pattern.compile(
            "(?:^|\\s)\\.(?:com|net|org|info|biz|shop|store|online|site|xyz|app|link)(?:$|[^a-z0-9])");
    private static final Pattern EMAIL = Pattern.compile("[a-z0-9][a-z0-9._%+-]*@[a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)+");
    private static final Pattern MAIL_HOST = Pattern.compile(
            "\\b(?:at|@)\\s*(?:gmail|googlemail|yahoo|hotmail|outlook|icloud|protonmail|proton|aol|live|msn)\\b"
                    + "|\\b(?:gmail|hotmail|protonmail)\\s*(?:dot|\\.)\\s*com\\b");
    private static final Pattern HANDLE = Pattern.compile("(?:^|[^a-z0-9_@])@[a-z0-9_][a-z0-9_.]{1,}");

    private static final Pattern DOT_WORD = Pattern.compile("\\s*[\\[({]\\s*(?:dot|d0t)\\s*[\\])}]\\s*"
            + "|(?:^|\\s+)(?:dot|d0t)\\s+(?:com|c0m|net|org|info|biz|shop|store|online|site|xyz|app|link)\\b");
    private static final Pattern AT_WORD = Pattern.compile("\\s*[\\[({]\\s*at\\s*[\\])}]\\s*");
    private static final Pattern OH_DIGIT = Pattern.compile("(\\d)[o](\\d)");
    private static final Pattern SPACE_RUN = Pattern.compile("[ \\t]+");

    private static final List<Pattern> PHONE = List.of(
            Pattern.compile("\\d{10,}"),
            Pattern.compile("\\+\\s?\\d[\\d ()\\-.]{6,}\\d"),
            Pattern.compile("\\(\\d{3}\\)\\s?\\d{3}[ .-]?\\d{4}"),
            Pattern.compile("(?:^|[^\\d])\\d{3}[ .-]\\d{3}[ .-]\\d{4}(?:$|[^\\d])"),
            Pattern.compile("(?:^|[^\\d])\\d{4}[ .-]\\d{3}[ .-]\\d{4}(?:$|[^\\d])"),
            Pattern.compile("(?:^|[^\\d])\\d{3}[ .-]\\d{3}[ .-]\\d{3}(?:$|[^\\d])"),
            Pattern.compile("(?:^|[^\\d])\\d{3}[ .-]\\d{4}[ .-]\\d{4}(?:$|[^\\d])"),
            Pattern.compile("(?:^|[^\\d])0\\d(?:[ .-]\\d{2}){4}(?:$|[^\\d])"),
            Pattern.compile("(?:^|[^\\d])\\d{3}[-.]\\d{4}(?:$|[^\\d.-])"));
    private static final Pattern PHONE_STRICT = Pattern.compile("\\d{7,}");

    private static final Pattern PHRASES = Pattern.compile("\\b(?:" + String.join("|",
            "what'?s\\s*app", "whatsapp", "watsapp", "whatsap", "telegram", "viber", "wechat", "we\\s+chat", "snapchat",
            "signal\\s+app", "zelle", "venmo", "cash\\s*app", "cashapp",
            "(?:dm|pm|inbox)\\s+(?:me|us)\\b", "(?:call|text|sms|ring|phone|email|e-mail|whatsapp)\\s+(?:me|us)\\b",
            "(?:message|msg|write\\s+to)\\s+(?:me|us)\\s+(?:on|at)\\b",
            "(?:order|buy|purchase|pay)\\s+(?:me\\s+)?direct(?:ly)?", "direct\\s+(?:order|sale|deal|payment)s?",
            "(?:cheaper|less|lower|better\\s+price)\\s+(?:if\\s+you\\s+)?(?:off|outside|away\\s+from|without)\\s+(?:the\\s+)?"
                    + "(?:site|platform|app|afrotransact|website)",
            "(?:off|outside)\\s+(?:the\\s+)?(?:site|platform|afrotransact)",
            "(?:skip|avoid)\\s+(?:the\\s+)?(?:platform\\s+|site\\s+|afrotransact\\s+)?fees",
            "contact\\s+me\\s+(?:direct|directly|privately|outside)") + ")");
    private static final Pattern PHRASES_STRICT = Pattern.compile("\\b(?:dm|inbox)\\b|\\bcontact\\s+(?:us|me)\\b"
            + "|\\breach\\s+(?:out|us|me)\\b|\\b(?:insta|ig|snap|fb)\\s*[:=]");

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s,.\\-/;:]+");
    private static final Set<String> DIGIT_WORDS = Set.of("zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine");

    @Override
    public List<DiversionFinding> scan(String text, boolean strict) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String t = normalise(text);
        String raw = foldKeepCase(text);
        List<DiversionFinding> out = new ArrayList<>();
        add(out, DiversionKind.EMAIL, EMAIL, t);
        add(out, DiversionKind.EMAIL, MAIL_HOST, t);
        add(out, DiversionKind.URL, URL_SCHEME, t);
        add(out, DiversionKind.URL, DOMAIN, t);
        add(out, DiversionKind.URL, SHORT, t);
        add(out, DiversionKind.URL, BARE_TLD, t);
        add(out, DiversionKind.URL, AMBIGUOUS_TLD, raw);
        add(out, DiversionKind.HANDLE, HANDLE, t);
        for (Pattern p : PHONE) {
            add(out, DiversionKind.PHONE, p, t);
        }
        if (strict) {
            add(out, DiversionKind.PHONE, PHONE_STRICT, t);
        }
        String run = spelledRun(t, strict);
        if (!run.isEmpty()) {
            out.add(new DiversionFinding(DiversionKind.SPELLED_DIGITS, run));
        }
        add(out, DiversionKind.PHRASE, PHRASES, t);
        if (strict) {
            add(out, DiversionKind.PHRASE, PHRASES_STRICT, t);
        }
        return dedupe(out);
    }

    @Override
    public String message(List<DiversionFinding> findings) {
        if (findings.isEmpty()) {
            return "";
        }
        List<String> nouns = findings.stream().map(f -> f.kind().noun()).toList();
        String list;
        if (nouns.size() == 1) {
            list = nouns.get(0);
        } else if (nouns.size() == 2) {
            list = nouns.get(0) + " and " + nouns.get(1);
        } else {
            list = String.join(", ", nouns.subList(0, nouns.size() - 1)) + " and " + nouns.get(nouns.size() - 1);
        }
        return "Please remove the " + list + " (" + quote(findings.get(0).excerpt()) + "). Customers reach you through "
                + "AfroTransact messages, and keeping sales on AfroTransact protects you and your customers.";
    }

    private static String quote(String s) {
        String shown = s.codePointCount(0, s.length()) > 40 ? s.substring(0, s.offsetByCodePoints(0, 40)) + "..." : s;
        return "\"" + shown + "\"";
    }

    private static void add(List<DiversionFinding> out, DiversionKind kind, Pattern p, String text) {
        var m = p.matcher(text);
        if (m.find()) {
            out.add(new DiversionFinding(kind, trim(m.group())));
        }
    }

    private static String trim(String s) {
        int a = 0;
        int b = s.length();
        String cut = " ,;:!?()\"'";
        while (a < b && cut.indexOf(s.charAt(a)) >= 0) {
            a++;
        }
        while (b > a && cut.indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(a, b);
    }

    private static boolean skipped(int cp) {
        return cp == 0x200b || cp == 0x200c || cp == 0x200d || cp == 0xfeff || cp == 0x00ad;
    }

    private static String foldKeepCase(String s) {
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (skipped(cp)) {
                return;
            }
            if (cp == 0x3002 || cp == 0xff0e) {
                b.append('.');
            } else if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                b.append(' ');
            } else {
                b.appendCodePoint(cp);
            }
        });
        return b.toString();
    }

    private static String normalise(String s) {
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (skipped(cp)) {
                return;
            }
            if (cp >= 0x0660 && cp <= 0x0669) {
                b.append((char) ('0' + (cp - 0x0660)));
            } else if (cp >= 0x06f0 && cp <= 0x06f9) {
                b.append((char) ('0' + (cp - 0x06f0)));
            } else if (cp >= 0xff10 && cp <= 0xff19) {
                b.append((char) ('0' + (cp - 0xff10)));
            } else if (cp == 0xff20) {
                b.append('@');
            } else if (cp == 0x3002 || cp == 0xff0e) {
                b.append('.');
            } else if (cp == 0x2019 || cp == 0x2018) {
                b.append('\'');
            } else if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                b.append(' ');
            } else {
                b.appendCodePoint(Character.toLowerCase(cp));
            }
        });
        String out = DOT_WORD.matcher(b.toString()).replaceAll(DiversionDetectorImpl::dotReplacement);
        out = AT_WORD.matcher(out).replaceAll("@");
        out = OH_DIGIT.matcher(out).replaceAll(m -> m.group(1) + "0" + m.group(2));
        out = OH_DIGIT.matcher(out).replaceAll(m -> m.group(1) + "0" + m.group(2));
        return SPACE_RUN.matcher(out).replaceAll(" ");
    }

    private static String dotReplacement(MatchResult m) {
        String t = m.group().strip();
        if (t.indexOf('[') >= 0 || t.indexOf('(') >= 0 || t.indexOf('{') >= 0) {
            return ".";
        }
        String[] f = t.split("\\s+");
        return "." + f[f.length - 1].replace("c0m", "com");
    }

    private static String spelledRun(String t, boolean strict) {
        int need = strict ? 2 : 3;
        String[] toks = TOKEN_SPLIT.split(t, -1);
        int words = 0;
        int n = 0;
        int start = 0;
        for (int i = 0; i < toks.length; i++) {
            String tok = toks[i];
            boolean isWord = DIGIT_WORDS.contains(tok);
            boolean isNum = tok.length() == 1 && tok.charAt(0) >= '0' && tok.charAt(0) <= '9';
            if (isWord || isNum) {
                if (n == 0) {
                    start = i;
                }
                n++;
                if (isWord) {
                    words++;
                }
                continue;
            }
            if (words >= need || n >= 7) {
                return String.join(" ", Arrays.copyOfRange(toks, start, i));
            }
            words = 0;
            n = 0;
        }
        if (words >= need || n >= 7) {
            return String.join(" ", Arrays.copyOfRange(toks, start, toks.length));
        }
        return "";
    }

    private static List<DiversionFinding> dedupe(List<DiversionFinding> in) {
        Set<DiversionKind> seen = EnumSet.noneOf(DiversionKind.class);
        List<DiversionFinding> out = new ArrayList<>();
        for (DiversionFinding f : in) {
            if (seen.add(f.kind())) {
                out.add(f);
            }
        }
        return out;
    }
}
