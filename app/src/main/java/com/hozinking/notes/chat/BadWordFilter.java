package com.hozinking.notes.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filter kata kasar Bahasa Indonesia. Menormalkan leet-speak dan pengulangan
 * huruf sebelum mencocokkan; kata yang cocok disensor dengan '*' sepanjang kata.
 */
public final class BadWordFilter {

    private BadWordFilter() {
    }

    private static final String[] BAD = {
            "anjing", "anjir", "anjrit", "anjay", "bangsat", "bajingan", "brengsek",
            "kontol", "memek", "jembut", "ngentot", "ngentod", "ngewe", "jancuk",
            "jancok", "cuk", "cok", "asu", "asyu", "babi", "monyet", "tolol",
            "goblok", "goblog", "bodoh", "dungu", "idiot", "bejat", "laknat",
            "sialan", "sial", "kampret", "kampretos", "keparat", "kurangajar",
            "setan", "iblis", "tai", "taik", "tahi", "eek", "bego", "begok",
            "oon", "gila", "edan", "sarap", "pea", "bangke", "bangkai",
            "pelacur", "lonte", "jablay", "perek", "bispak", "sundal",
            "coli", "onani", "masturbasi", "porno", "bokep", "coli"
    };

    private static final Pattern REPEAT = Pattern.compile("(.)\\1{2,}");

    /** Normalisasi: lowercase, leet -> huruf, hapus pengulangan huruf berlebih. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String s = text.toLowerCase();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '4':
                    c = 'a';
                    break;
                case '3':
                    c = 'e';
                    break;
                case '1':
                    c = 'i';
                    break;
                case '0':
                    c = 'o';
                    break;
                case '5':
                    c = 's';
                    break;
                case '$':
                    c = 's';
                    break;
                case '@':
                    c = 'a';
                    break;
                default:
                    break;
            }
            sb.append(c);
        }
        // anjjing -> anjjing? hapus pengulangan 3+ jadi 1: "anjjing" (2x j) tetap.
        // Normalkan 3+ pengulangan -> 1 agar "anjiiing" tertangkap.
        Matcher m = REPEAT.matcher(sb.toString());
        return m.replaceAll("$1");
    }

    private static boolean wordMatches(String normalized, String bad) {
        // Cocok sebagai kata utuh (batas non-huruf) agar "bego" tidak kena di "bengong".
        Pattern p = Pattern.compile("(?<![a-z])" + Pattern.quote(bad) + "(?![a-z])");
        return p.matcher(normalized).find();
    }

    /** true bila teks mengandung kata kasar (setelah normalisasi). */
    public static boolean containsBadWord(String text) {
        String n = normalize(text);
        for (String bad : BAD) {
            if (wordMatches(n, bad)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sensor: tiap kata kasar diganti '*' sepanjang kata. Pencocokan dilakukan
     * pada teks ternormalisasi, tetapi penggantian menjaga panjang kata asli.
     */
    public static String censor(String text) {
        if (text == null) {
            return "";
        }
        String n = normalize(text);
        String result = text;
        for (String bad : BAD) {
            Pattern p = Pattern.compile("(?<![a-z])" + Pattern.quote(bad) + "(?![a-z])");
            Matcher m = p.matcher(n);
            StringBuffer sb = new StringBuffer();
            boolean found = false;
            while (m.find()) {
                found = true;
                m.appendReplacement(sb, repeat('*', bad.length()));
            }
            m.appendTail(sb);
            if (found) {
                // Petakan kembali ke teks asli: karena normalisasi bisa mengubah
                // panjang (pengulangan), lakukan per kata pada teks asli.
                result = censorOriginal(result, bad);
                n = normalize(result);
            }
        }
        return result;
    }

    /** Sensor berbasis kata pada teks asli (dipakai setelah deteksi ternormalisasi). */
    private static String censorOriginal(String text, String bad) {
        String[] words = text.split("(?<=\\s)|(?=\\s)");
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            String stripped = w.replaceAll("^[^a-zA-Z0-9]+|[^a-zA-Z0-9]+$", "");
            if (!stripped.isEmpty() && wordMatches(normalize(stripped), bad)) {
                int start = w.indexOf(stripped);
                int end = start + stripped.length();
                out.append(w, 0, start)
                        .append(repeat('*', stripped.length()))
                        .append(w.substring(end));
            } else {
                out.append(w);
            }
        }
        return out.toString();
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
