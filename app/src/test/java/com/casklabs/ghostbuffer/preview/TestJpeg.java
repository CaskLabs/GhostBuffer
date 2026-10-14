package com.casklabs.ghostbuffer.preview;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

/** Genuine JPEG fixtures generated on the test JVM, outside Android's boot classpath. */
public final class TestJpeg {
    private TestJpeg() {}

    public static byte[] create(int width, int height, boolean texture) throws Exception {
        Class<?> imageType = Class.forName("java.awt.image.BufferedImage");
        Object image = imageType.getConstructor(int.class, int.class, int.class).newInstance(width, height, 1);
        if (texture) {
            int[] pixels = new int[width * height];
            int random = 12345;
            for (int i = 0; i < pixels.length; i++) {
                random ^= random << 13; random ^= random >>> 17; random ^= random << 5;
                pixels[i] = random;
            }
            imageType.getMethod("setRGB", int.class, int.class, int.class, int.class,
                int[].class, int.class, int.class).invoke(image, 0, 0, width, height, pixels, 0, width);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Class.forName("javax.imageio.ImageIO").getMethod("write",
            Class.forName("java.awt.image.RenderedImage"), String.class, OutputStream.class)
            .invoke(null, image, "jpeg", out);
        return out.toByteArray();
    }
}
