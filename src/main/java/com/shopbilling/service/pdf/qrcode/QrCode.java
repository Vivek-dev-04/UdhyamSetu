package com.shopbilling.service.pdf.qrcode;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A QR Code symbol, which is a type of two-dimensional barcode.
 * Invented by Denso Wave and described in the ISO/IEC 18004 standard.
 * <p>Instances of this class represent an immutable square grid of dark and light cells along with
 * metadata. Provides static factory methods to create QR Codes from text or binary data.</p>
 */
public final class QrCode {

    public static final int MIN_VERSION = 1;
    public static final int MAX_VERSION = 40;

    public enum Ecc {
        LOW(1),
        MEDIUM(0),
        QUARTILE(3),
        HIGH(2);

        final int formatBits;

        Ecc(int fb) {
            this.formatBits = fb;
        }
    }

    public final int version;
    public final int size;
    public final Ecc errorCorrectionLevel;
    public final int mask;

    private final boolean[][] modules;
    private final boolean[][] isFunction;

    public static QrCode encodeText(String text, Ecc ecl) {
        Objects.requireNonNull(text);
        Objects.requireNonNull(ecl);
        List<QrSegment> segs = QrSegment.makeSegments(text);
        return encodeSegments(segs, ecl);
    }

    public static QrCode encodeBinary(byte[] data, Ecc ecl) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(ecl);
        return encodeSegments(List.of(QrSegment.makeBytes(data)), ecl);
    }

    public static QrCode encodeSegments(List<QrSegment> segs, Ecc ecl) {
        return encodeSegments(segs, ecl, MIN_VERSION, MAX_VERSION, -1, true);
    }

    public static QrCode encodeSegments(List<QrSegment> segs, Ecc ecl, int minVersion, int maxVersion, int mask, boolean boostEcl) {
        Objects.requireNonNull(segs);
        Objects.requireNonNull(ecl);
        if (!(MIN_VERSION <= minVersion && minVersion <= maxVersion && maxVersion <= MAX_VERSION) || mask < -1 || mask > 7) {
            throw new IllegalArgumentException("Invalid value");
        }

        // Find smallest version that fits data
        int version, dataUsedBits;
        for (version = minVersion; ; version++) {
            int dataCapacityBits = getNumDataCodewords(version, ecl) * 8;
            int usedBits = QrSegment.getTotalBits(segs, version);
            if (usedBits != -1 && usedBits <= dataCapacityBits) {
                dataUsedBits = usedBits;
                break;
            }
            if (version >= maxVersion) {
                throw new IllegalArgumentException("Data too long for QR Code");
            }
        }

        // Boost error correction level if data fits
        if (boostEcl) {
            for (Ecc newEcl : new Ecc[]{Ecc.MEDIUM, Ecc.QUARTILE, Ecc.HIGH}) {
                if (dataUsedBits <= getNumDataCodewords(version, newEcl) * 8) {
                    ecl = newEcl;
                }
            }
        }

        // Concatenate all segments
        BitBuffer bb = new BitBuffer();
        for (QrSegment seg : segs) {
            bb.appendBits(seg.mode.modeBits, 4);
            bb.appendBits(seg.numChars, seg.mode.numCharCountBits(version));
            bb.appendData(seg.getData());
        }

        // Add terminator and pad up to byte boundary
        int dataCapacityBits = getNumDataCodewords(version, ecl) * 8;
        bb.appendBits(0, Math.min(4, dataCapacityBits - bb.bitLength()));
        bb.appendBits(0, (8 - bb.bitLength() % 8) % 8);

        // Pad with alternating bytes
        for (int padByte = 0xEC; bb.bitLength() < dataCapacityBits; padByte ^= 0xEC ^ 0x11) {
            bb.appendBits(padByte, 8);
        }

        byte[] dataCodewords = bb.getBytes();
        return new QrCode(version, ecl, dataCodewords, mask);
    }

    public QrCode(int version, Ecc ecl, byte[] dataCodewords, int msk) {
        if (version < MIN_VERSION || version > MAX_VERSION) {
            throw new IllegalArgumentException("Version out of range");
        }
        if (msk < -1 || msk > 7) {
            throw new IllegalArgumentException("Mask out of range");
        }
        this.version = version;
        this.size = version * 4 + 17;
        this.errorCorrectionLevel = Objects.requireNonNull(ecl);

        modules = new boolean[size][size];
        isFunction = new boolean[size][size];

        drawFunctionPatterns();
        byte[] allCodewords = addEccAndInterleave(dataCodewords);
        drawCodewords(allCodewords);

        if (msk == -1) {
            int minPenalty = Integer.MAX_VALUE;
            int bestMask = 0;
            for (int i = 0; i < 8; i++) {
                applyMask(i);
                drawFormatBits(i);
                int penalty = getPenaltyScore();
                if (penalty < minPenalty) {
                    minPenalty = penalty;
                    bestMask = i;
                }
                applyMask(i); // Undoes mask
            }
            msk = bestMask;
        }
        this.mask = msk;
        applyMask(msk);
        drawFormatBits(msk);
        isFunction[0][0] = false; // dummy
    }

    public boolean getModule(int x, int y) {
        return (0 <= x && x < size && 0 <= y && y < size) && modules[y][x];
    }

    public BufferedImage toImage(int scale, int border) {
        return toImage(scale, border, 0xFFFFFF, 0x000000);
    }

    public BufferedImage toImage(int scale, int border, int lightColor, int darkColor) {
        if (scale <= 0 || border < 0) {
            throw new IllegalArgumentException("Value out of range");
        }
        int imgSize = (size + border * 2) * scale;
        BufferedImage result = new BufferedImage(imgSize, imgSize, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < imgSize; y++) {
            for (int x = 0; x < imgSize; x++) {
                int moduleX = x / scale - border;
                int moduleY = y / scale - border;
                boolean isDark = getModule(moduleX, moduleY);
                result.setRGB(x, y, isDark ? darkColor : lightColor);
            }
        }
        return result;
    }

    private void drawFunctionPatterns() {
        // Draw timing patterns
        for (int i = 0; i < size; i++) {
            setFunctionModule(6, i, i % 2 == 0);
            setFunctionModule(i, 6, i % 2 == 0);
        }

        // Draw 3 finder patterns
        drawFinderPattern(3, 3);
        drawFinderPattern(size - 4, 3);
        drawFinderPattern(3, size - 4);

        // Draw alignment patterns
        int[] alignPatPos = getAlignmentPatternPositions();
        int numAlign = alignPatPos.length;
        for (int i = 0; i < numAlign; i++) {
            for (int j = 0; j < numAlign; j++) {
                if (!(i == 0 && j == 0 || i == 0 && j == numAlign - 1 || i == numAlign - 1 && j == 0)) {
                    drawAlignmentPattern(alignPatPos[i], alignPatPos[j]);
                }
            }
        }

        // Reserve format bits
        drawFormatBits(0);
        drawVersion();
    }

    private void drawFormatBits(int msk) {
        int data = errorCorrectionLevel.formatBits << 3 | msk;
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int bits = (data << 10 | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) {
            setFunctionModule(8, i, getBit(bits, i));
        }
        setFunctionModule(8, 7, getBit(bits, 6));
        setFunctionModule(8, 8, getBit(bits, 7));
        setFunctionModule(7, 8, getBit(bits, 8));
        for (int i = 9; i < 15; i++) {
            setFunctionModule(14 - i, 8, getBit(bits, i));
        }

        for (int i = 0; i < 8; i++) {
            setFunctionModule(size - 1 - i, 8, getBit(bits, i));
        }
        for (int i = 8; i < 15; i++) {
            setFunctionModule(8, size - 15 + i, getBit(bits, i));
        }
        setFunctionModule(8, size - 8, true);
    }

    private void drawVersion() {
        if (version < 7) {
            return;
        }
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        int bits = version << 12 | rem;
        for (int i = 0; i < 18; i++) {
            boolean bit = getBit(bits, i);
            int a = size - 11 + i % 3;
            int b = i / 3;
            setFunctionModule(a, b, bit);
            setFunctionModule(b, a, bit);
        }
    }

    private void drawFinderPattern(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx;
                int yy = y + dy;
                if (0 <= xx && xx < size && 0 <= yy && yy < size) {
                    setFunctionModule(xx, yy, dist != 2 && dist != 4);
                }
            }
        }
    }

    private void drawAlignmentPattern(int x, int y) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setFunctionModule(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    private void setFunctionModule(int x, int y, boolean isDark) {
        modules[y][x] = isDark;
        isFunction[y][x] = true;
    }

    private byte[] addEccAndInterleave(byte[] data) {
        int numBlocks = NUM_ERROR_CORRECTION_BLOCKS[errorCorrectionLevel.ordinal()][version];
        int blockEccLen = ECC_CODEWORDS_PER_BLOCK[errorCorrectionLevel.ordinal()][version];
        int rawCodewords = getNumRawDataModules(version) / 8;
        int numShortBlocks = numBlocks - rawCodewords % numBlocks;
        int shortBlockLen = rawCodewords / numBlocks;

        byte[][] blocks = new byte[numBlocks][];
        byte[] rsDiv = reedSolomonComputeDivisor(blockEccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int blockLen = shortBlockLen - blockEccLen + (i >= numShortBlocks ? 1 : 0);
            byte[] dat = Arrays.copyOfRange(data, k, k + blockLen);
            k += blockLen;
            byte[] block = Arrays.copyOf(dat, dat.length + blockEccLen);
            byte[] ecc = reedSolomonComputeRemainder(dat, rsDiv);
            System.arraycopy(ecc, 0, block, block.length - blockEccLen, ecc.length);
            blocks[i] = block;
        }

        byte[] result = new byte[rawCodewords];
        for (int i = 0, p = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < blocks.length; j++) {
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                    result[p++] = blocks[j][i];
                }
            }
        }
        return result;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = getBit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int msk) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!isFunction[y][x]) {
                    boolean invert;
                    switch (msk) {
                        case 0:  invert = (x + y) % 2 == 0; break;
                        case 1:  invert = y % 2 == 0; break;
                        case 2:  invert = x % 3 == 0; break;
                        case 3:  invert = (x + y) % 3 == 0; break;
                        case 4:  invert = (x / 3 + y / 2) % 2 == 0; break;
                        case 5:  invert = x * y % 2 + x * y % 3 == 0; break;
                        case 6:  invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                        case 7:  invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                        default: throw new AssertionError();
                    }
                    modules[y][x] ^= invert;
                }
            }
        }
    }

    private int getPenaltyScore() {
        int result = 0;
        // Adjacent modules in row/column in same color
        for (int y = 0; y < size; y++) {
            boolean runColor = false;
            int runX = 0;
            int[] runHistory = new int[7];
            for (int x = 0; x < size; x++) {
                if (modules[y][x] == runColor) {
                    runX++;
                    if (runX == 5) {
                        result += 3;
                    } else if (runX > 5) {
                        result++;
                    }
                } else {
                    finderPenaltyAddHistory(runX, runHistory);
                    if (!runColor) {
                        result += finderPenaltyCountPatterns(runHistory) * 40;
                    }
                    runColor = modules[y][x];
                    runX = 1;
                }
            }
            result += finderPenaltyTerminateAndCount(runColor, runX, runHistory) * 40;
        }

        for (int x = 0; x < size; x++) {
            boolean runColor = false;
            int runY = 0;
            int[] runHistory = new int[7];
            for (int y = 0; y < size; y++) {
                if (modules[y][x] == runColor) {
                    runY++;
                    if (runY == 5) {
                        result += 3;
                    } else if (runY > 5) {
                        result++;
                    }
                } else {
                    finderPenaltyAddHistory(runY, runHistory);
                    if (!runColor) {
                        result += finderPenaltyCountPatterns(runHistory) * 40;
                    }
                    runColor = modules[y][x];
                    runY = 1;
                }
            }
            result += finderPenaltyTerminateAndCount(runColor, runY, runHistory) * 40;
        }

        // 2x2 blocks of same color
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean color = modules[y][x];
                if (color == modules[y][x + 1] && color == modules[y + 1][x] && color == modules[y + 1][x + 1]) {
                    result += 3;
                }
            }
        }

        // Balance of dark and light modules
        int dark = 0;
        for (boolean[] row : modules) {
            for (boolean color : row) {
                if (color) {
                    dark++;
                }
            }
        }
        int total = size * size;
        int k = (int) (Math.abs(dark * 20L - total * 10L) / total) - 1;
        result += Math.max(k, 0) * 10;
        return result;
    }

    private int[] getAlignmentPatternPositions() {
        if (version == 1) {
            return new int[]{};
        } else {
            int num = version / 7 + 2;
            int step = (version == 32) ? 26 : (version * 4 + num * 2 + 1) / (num * 2 - 2) * 2;
            int[] result = new int[num];
            result[0] = 6;
            for (int i = result.length - 1, pos = size - 7; i >= 1; i--, pos -= step) {
                result[i] = pos;
            }
            return result;
        }
    }

    private static int getNumDataCodewords(int ver, Ecc ecl) {
        return getNumRawDataModules(ver) / 8 - ECC_CODEWORDS_PER_BLOCK[ecl.ordinal()][ver] * NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal()][ver];
    }

    private static int getNumRawDataModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (ver >= 7) {
                result -= 36;
            }
        }
        return result;
    }

    private static byte[] reedSolomonComputeDivisor(int degree) {
        if (degree < 1 || degree > 255) {
            throw new IllegalArgumentException("Degree out of range");
        }
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < result.length; j++) {
                result[j] = (byte) reedSolomonMultiply(result[j] & 0xFF, root);
                if (j + 1 < result.length) {
                    result[j] ^= result[j + 1];
                }
            }
            root = reedSolomonMultiply(root, 0x02);
        }
        return result;
    }

    private static byte[] reedSolomonComputeRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < divisor.length; i++) {
                result[i] ^= (byte) reedSolomonMultiply(divisor[i] & 0xFF, factor);
            }
        }
        return result;
    }

    private static int reedSolomonMultiply(int x, int y) {
        if (x >>> 8 != 0 || y >>> 8 != 0) {
            throw new IllegalArgumentException("Byte out of range");
        }
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static void finderPenaltyAddHistory(int currentRunLength, int[] runHistory) {
        if (runHistory[0] == 0) {
            currentRunLength += runHistory.length; // dummy
        }
        System.arraycopy(runHistory, 0, runHistory, 1, runHistory.length - 1);
        runHistory[0] = currentRunLength;
    }

    private static int finderPenaltyCountPatterns(int[] runHistory) {
        int n = runHistory[1];
        boolean core = n > 0 && runHistory[2] == n && runHistory[3] == n * 3 && runHistory[4] == n && runHistory[5] == n;
        return (core && runHistory[0] >= n * 4 && runHistory[6] >= n ? 1 : 0)
                + (core && runHistory[6] >= n * 4 && runHistory[0] >= n ? 1 : 0);
    }

    private static int finderPenaltyTerminateAndCount(boolean currentRunColor, int currentRunLength, int[] runHistory) {
        if (currentRunColor) {
            finderPenaltyAddHistory(currentRunLength, runHistory);
            currentRunLength = 0;
        }
        currentRunLength += runHistory.length;
        finderPenaltyAddHistory(currentRunLength, runHistory);
        return finderPenaltyCountPatterns(runHistory);
    }

    private static boolean getBit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    // Tables of error correction parameters
    private static final byte[][] NUM_ERROR_CORRECTION_BLOCKS = {
        // Version: 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40
        {-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25}, // M
        {-1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22}, // L
        {-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 72, 74, 79}, // H
        {-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68}, // Q
    };

    private static final byte[][] ECC_CODEWORDS_PER_BLOCK = {
        // Version: 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40
        {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28}, // M
        {-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30}, // L
        {-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30}, // H
        {-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30}, // Q
    };
}
