package com.casklabs.ghostbuffer.preview;

/** Reads JPEG SOF dimensions without decoding a bitmap or trusting the requested size. */
public final class JpegDimensions {
    private JpegDimensions() {}

    public static int[] read(byte[] data, int length) {
        if (data == null || length < 4 || length > data.length
            || (data[0] & 255) != 255 || (data[1] & 255) != 216) return null;
        int offset = 2;
        while (offset < length) {
            if ((data[offset++] & 255) != 255) return null;
            while (offset < length && (data[offset] & 255) == 255) offset++;
            if (offset >= length) return null;
            int marker = data[offset++] & 255;
            if (marker == 217 || marker == 218 || marker == 0) return null;
            if (marker == 1 || (marker >= 208 && marker <= 215)) continue;
            if (offset + 2 > length) return null;
            int segmentLength = unsignedShort(data, offset);
            if (segmentLength < 2 || segmentLength > length - offset) return null;
            if (marker >= 192 && marker <= 207 && marker != 196 && marker != 200 && marker != 204) {
                if (segmentLength < 8) return null;
                int height = unsignedShort(data, offset + 3);
                int width = unsignedShort(data, offset + 5);
                int components = data[offset + 7] & 255;
                if (width == 0 || height == 0 || components == 0
                    || segmentLength < 8 + 3 * components) return null;
                return new int[] { width, height };
            }
            offset += segmentLength;
        }
        return null;
    }

    private static int unsignedShort(byte[] data, int offset) {
        return ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
    }
}
