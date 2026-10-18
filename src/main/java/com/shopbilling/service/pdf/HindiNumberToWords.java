package com.shopbilling.service.pdf;

/**
 * Converts monetary amounts to words in Hindi using Indian numbering format
 * (करोड़, लाख, हज़ार, सौ) (PRD Section 8 Rule 1, Section 15 Question 8).
 */
public final class HindiNumberToWords {

    private static final String[] UNITS = {
            "", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ",
            "दस", "ग्यारह", "बारह", "तेरह", "चौदह", "पंद्रह", "सोलह", "सत्रह", "अठारह", "उन्नीस"
    };

    private static final String[] TENS = {
            "", "", "बीस", "तीस", "चालीस", "पचास", "साठ", "सत्तर", "अस्सी", "नब्बे"
    };

    private HindiNumberToWords() {}

    public static String convertToWords(long paise) {
        if (paise == 0) {
            return "शून्य रुपये केवल";
        }

        long rupees = paise / 100;
        long remainingPaise = paise % 100;

        StringBuilder sb = new StringBuilder();

        if (rupees > 0) {
            sb.append(convertNumber(rupees)).append(" रुपये");
        }

        if (remainingPaise > 0) {
            if (rupees > 0) {
                sb.append(" और ");
            }
            sb.append(convertNumber(remainingPaise)).append(" पैसे");
        }

        sb.append(" केवल");
        return sb.toString().trim();
    }

    private static String convertNumber(long n) {
        if (n == 0) return "";

        StringBuilder sb = new StringBuilder();

        if (n >= 10000000) { // करोड़ (1 Crore = 10,000,000)
            long crore = n / 10000000;
            sb.append(convertTwoDigits(crore)).append(" करोड़ ");
            n %= 10000000;
        }

        if (n >= 100000) { // लाख (1 Lakh = 100,000)
            long lakh = n / 100000;
            sb.append(convertTwoDigits(lakh)).append(" लाख ");
            n %= 100000;
        }

        if (n >= 1000) { // हज़ार (1 Thousand = 1,000)
            long thousand = n / 1000;
            sb.append(convertTwoDigits(thousand)).append(" हज़ार ");
            n %= 1000;
        }

        if (n >= 100) { // सौ (1 Hundred = 100)
            long hundred = n / 100;
            sb.append(convertTwoDigits(hundred)).append(" सौ ");
            n %= 100;
        }

        if (n > 0) {
            sb.append(convertTwoDigits(n));
        }

        return sb.toString().trim();
    }

    private static String convertTwoDigits(long n) {
        if (n < 20) {
            return UNITS[(int) n];
        }
        long tens = n / 10;
        long units = n % 10;
        if (units == 0) {
            return TENS[(int) tens];
        }
        return TENS[(int) tens] + " " + UNITS[(int) units];
    }
}
