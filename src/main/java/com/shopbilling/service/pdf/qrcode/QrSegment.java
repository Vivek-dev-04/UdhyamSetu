package com.shopbilling.service.pdf.qrcode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A segment of character/binary/control data in a QR Code symbol.
 * Instances of this class are immutable.
 */
public final class QrSegment {

    public enum Mode {
        NUMERIC(0x1, 10, 12, 14),
        ALPHANUMERIC(0x2, 9, 11, 13),
        BYTE(0x4, 8, 16, 16),
        KANJI(0x8, 8, 10, 12),
        ECI(0x7, 0, 0, 0);

        final int modeBits;
        private final int[] numBitsCharCount;

        Mode(int modeBits, int... ccbits) {
            this.modeBits = modeBits;
            this.numBitsCharCount = ccbits;
        }

        int numCharCountBits(int version) {
            return numBitsCharCount[(version + 7) / 17];
        }
    }

    public final Mode mode;
    public final int numChars;
    final BitBuffer data;

    public QrSegment(Mode md, int numCh, BitBuffer data) {
        this.mode = Objects.requireNonNull(md);
        this.data = Objects.requireNonNull(data).clone();
        if (numCh < 0) {
            throw new IllegalArgumentException("Invalid character count");
        }
        this.numChars = numCh;
    }

    public static QrSegment makeBytes(byte[] data) {
        Objects.requireNonNull(data);
        BitBuffer bb = new BitBuffer();
        for (byte b : data) {
            bb.appendBits(b & 0xFF, 8);
        }
        return new QrSegment(Mode.BYTE, data.length, bb);
    }

    public static QrSegment makeNumeric(String digits) {
        Objects.requireNonNull(digits);
        if (!NUMERIC_REGEX.matcher(digits).matches()) {
            throw new IllegalArgumentException("String contains non-numeric characters");
        }
        BitBuffer bb = new BitBuffer();
        int i = 0;
        while (i <= digits.length() - 3) {
            bb.appendBits(Integer.parseInt(digits.substring(i, i + 3)), 10);
            i += 3;
        }
        int rem = digits.length() - i;
        if (rem > 0) {
            bb.appendBits(Integer.parseInt(digits.substring(i)), rem == 1 ? 4 : 7);
        }
        return new QrSegment(Mode.NUMERIC, digits.length(), bb);
    }

    public static QrSegment makeAlphanumeric(String text) {
        Objects.requireNonNull(text);
        if (!ALPHANUMERIC_REGEX.matcher(text).matches()) {
            throw new IllegalArgumentException("String contains unencodable characters in alphanumeric mode");
        }
        BitBuffer bb = new BitBuffer();
        int i;
        for (i = 0; i <= text.length() - 2; i += 2) {
            int temp = ALPHANUMERIC_CHARSET.indexOf(text.charAt(i)) * 45;
            temp += ALPHANUMERIC_CHARSET.indexOf(text.charAt(i + 1));
            bb.appendBits(temp, 11);
        }
        if (i < text.length()) {
            bb.appendBits(ALPHANUMERIC_CHARSET.indexOf(text.charAt(i)), 6);
        }
        return new QrSegment(Mode.ALPHANUMERIC, text.length(), bb);
    }

    public static List<QrSegment> makeSegments(String text) {
        Objects.requireNonNull(text);
        List<QrSegment> result = new ArrayList<>();
        if (text.isEmpty()) {
            // Leave empty
        } else if (NUMERIC_REGEX.matcher(text).matches()) {
            result.add(makeNumeric(text));
        } else if (ALPHANUMERIC_REGEX.matcher(text).matches()) {
            result.add(makeAlphanumeric(text));
        } else {
            result.add(makeBytes(text.getBytes(StandardCharsets.UTF_8)));
        }
        return result;
    }

    static int getTotalBits(List<QrSegment> segs, int version) {
        Objects.requireNonNull(segs);
        long result = 0;
        for (QrSegment seg : segs) {
            Objects.requireNonNull(seg);
            int ccbits = seg.mode.numCharCountBits(version);
            if (seg.numChars >= (1 << ccbits)) {
                return -1; // Exceeds character limit
            }
            result += 4L + ccbits + seg.data.bitLength();
            if (result > Integer.MAX_VALUE) {
                return -1;
            }
        }
        return (int) result;
    }

    public BitBuffer getData() {
        return data.clone();
    }

    static final String ALPHANUMERIC_CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
    private static final Pattern NUMERIC_REGEX = Pattern.compile("[0-9]*");
    private static final Pattern ALPHANUMERIC_REGEX = Pattern.compile("[A-Z0-9 $%*+./:-]*");
}
