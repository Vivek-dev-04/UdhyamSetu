package com.shopbilling.service.pdf.qrcode;

import java.util.Arrays;
import java.util.Objects;

/**
 * An appendable sequence of bits (0s and 1s).
 */
public final class BitBuffer implements Cloneable {

    private byte[] data;
    private int bitLength;

    public BitBuffer() {
        data = new byte[64];
        bitLength = 0;
    }

    public int bitLength() {
        return bitLength;
    }

    public byte[] getBytes() {
        return Arrays.copyOf(data, (bitLength + 7) / 8);
    }

    public int getBit(int index) {
        if (index < 0 || index >= bitLength) {
            throw new IndexOutOfBoundsException();
        }
        return (data[index >>> 3] >>> (7 - (index & 7))) & 1;
    }

    public void appendBits(int val, int len) {
        if (len < 0 || len > 31 || val >>> len != 0) {
            throw new IllegalArgumentException("Value out of range");
        }
        for (int i = len - 1; i >= 0; i--) {
            appendBit((val >>> i) & 1);
        }
    }

    public void appendBit(int bit) {
        if (bit != 0 && bit != 1) {
            throw new IllegalArgumentException("Bit must be 0 or 1");
        }
        if (bitLength == data.length * 8) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[bitLength >>> 3] |= (byte) (bit << (7 - (bitLength & 7)));
        bitLength++;
    }

    public void appendData(BitBuffer bb) {
        Objects.requireNonNull(bb);
        for (int i = 0; i < bb.bitLength; i++) {
            appendBit(bb.getBit(i));
        }
    }

    @Override
    public BitBuffer clone() {
        try {
            BitBuffer result = (BitBuffer) super.clone();
            result.data = result.data.clone();
            return result;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }
}
