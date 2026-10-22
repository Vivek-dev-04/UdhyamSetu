package com.shopbilling.service.pdf;

/**
 * Converts integer Rupee amounts into English words using the Indian numbering system
 * (Crores, Lakhs, Thousands, Hundreds).
 */
public final class IndianNumberToWords {

    private static final String[] ONES = {
            "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"
    };

    private static final String[] TENS = {
            "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    };

    private IndianNumberToWords() {
    }

    /**
     * Converts a non-negative Rupee amount to words.
     * Example: 1250 -> "One Thousand Two Hundred Fifty Rupees Only"
     * Example: 0 -> "Zero Rupees Only"
     */
    public static String convertToWords(long rupees) {
        if (rupees == 0) {
            return "Zero Rupees Only";
        }
        if (rupees < 0) {
            return "Minus " + convertToWords(-rupees);
        }

        StringBuilder sb = new StringBuilder();

        long crores = rupees / 10000000L;
        rupees %= 10000000L;

        long lakhs = rupees / 100000L;
        rupees %= 100000L;

        long thousands = rupees / 1000L;
        rupees %= 1000L;

        long hundreds = rupees / 100L;
        long remainder = rupees % 100L;

        if (crores > 0) {
            sb.append(convertThreeDigits((int) crores)).append(" Crore ");
        }
        if (lakhs > 0) {
            sb.append(convertThreeDigits((int) lakhs)).append(" Lakh ");
        }
        if (thousands > 0) {
            sb.append(convertThreeDigits((int) thousands)).append(" Thousand ");
        }
        if (hundreds > 0) {
            sb.append(ONES[(int) hundreds]).append(" Hundred ");
        }
        if (remainder > 0) {
            sb.append(convertTwoDigits((int) remainder)).append(" ");
        }

        return sb.toString().trim() + " Rupees Only";
    }

    /**
     * Converts a paise amount to formal INR words including paise if non-zero.
     * Example: 6558500 -> "INR Sixty Five Thousand Five Hundred Eighty Five Only"
     * Example: 1000454 -> "INR Ten Thousand Four and Fifty Four paise Only"
     */
    public static String convertPaiseToInrWords(long paise) {
        if (paise == 0) {
            return "INR Zero Only";
        }
        if (paise < 0) {
            return "Minus " + convertPaiseToInrWords(-paise);
        }
        long rupees = paise / 100;
        long remPaise = paise % 100;
        String rsStr = convertRawWords(rupees);
        if (rsStr.isEmpty()) {
            rsStr = "Zero";
        }
        if (remPaise > 0) {
            return "INR " + rsStr + " and " + convertRawWords(remPaise) + " paise Only";
        } else {
            return "INR " + rsStr + " Only";
        }
    }

    public static String convertRawWords(long rupees) {
        if (rupees == 0) return "";
        StringBuilder sb = new StringBuilder();

        long crores = rupees / 10000000L;
        rupees %= 10000000L;

        long lakhs = rupees / 100000L;
        rupees %= 100000L;

        long thousands = rupees / 1000L;
        rupees %= 1000L;

        long hundreds = rupees / 100L;
        long remainder = rupees % 100L;

        if (crores > 0) {
            sb.append(convertThreeDigits((int) crores)).append(" Crore ");
        }
        if (lakhs > 0) {
            sb.append(convertThreeDigits((int) lakhs)).append(" Lakh ");
        }
        if (thousands > 0) {
            sb.append(convertThreeDigits((int) thousands)).append(" Thousand ");
        }
        if (hundreds > 0) {
            sb.append(ONES[(int) hundreds]).append(" Hundred ");
        }
        if (remainder > 0) {
            sb.append(convertTwoDigits((int) remainder)).append(" ");
        }

        return sb.toString().trim();
    }

    private static String convertThreeDigits(int n) {
        StringBuilder res = new StringBuilder();
        int hundreds = n / 100;
        int rem = n % 100;
        if (hundreds > 0) {
            res.append(ONES[hundreds]).append(" Hundred ");
        }
        if (rem > 0) {
            res.append(convertTwoDigits(rem));
        }
        return res.toString().trim();
    }

    private static String convertTwoDigits(int n) {
        if (n < 20) {
            return ONES[n];
        }
        int ten = n / 10;
        int unit = n % 10;
        if (unit > 0) {
            return TENS[ten] + " " + ONES[unit];
        }
        return TENS[ten];
    }
}
