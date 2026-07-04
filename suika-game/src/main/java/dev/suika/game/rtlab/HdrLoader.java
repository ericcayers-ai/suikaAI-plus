package dev.suika.game.rtlab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Radiance (.hdr / RGBE) decoder for the RT Lab's HDRI environment map.
 * ImageIO cannot read Radiance files and pulling in a whole imaging library for
 * one bundled asset is overkill — the format is a text header followed by
 * scanlines of 4-byte RGBE pixels (shared-exponent), either flat or RLE-packed
 * per component ("new-style" RLE, which is what Poly Haven exports use).
 *
 * <p>Output is a direct ByteBuffer of RGBA32F pixels (alpha=1), top row first,
 * ready for {@code VK_FORMAT_R32G32B32A32_SFLOAT} upload.
 */
final class HdrLoader {

    final int width, height;
    final ByteBuffer rgba; // width*height*16 bytes, RGBA32F

    private HdrLoader(int width, int height, ByteBuffer rgba) {
        this.width = width;
        this.height = height;
        this.rgba = rgba;
    }

    static HdrLoader load(String resourcePath) {
        byte[] data;
        try (InputStream in = HdrLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException("HDR resource not found: " + resourcePath);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
            in.transferTo(bos);
            data = bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read HDRI " + resourcePath, e);
        }

        int pos = 0;
        // ---- header: text lines up to a blank line, then the resolution line ----
        String magic = readLine(data, pos);
        pos += magic.length() + 1;
        if (!magic.startsWith("#?")) throw new IllegalStateException("Not a Radiance file: " + resourcePath);
        boolean rgbeFormat = false;
        while (true) {
            String line = readLine(data, pos);
            pos += line.length() + 1;
            // Trim carriage returns to support cross-platform line endings (CRLF)
            if (line.trim().isEmpty()) break;
            if (line.startsWith("FORMAT=")) rgbeFormat = line.contains("32-bit_rle_rgbe");
        }
        if (!rgbeFormat) throw new IllegalStateException("Unsupported HDR pixel format (want 32-bit_rle_rgbe): " + resourcePath);
        String res = readLine(data, pos);
        pos += res.length() + 1;
        // Only the standard "-Y <h> +X <w>" orientation (top row first) is supported.
        String[] parts = res.trim().split("\\s+");
        if (parts.length != 4 || !"-Y".equals(parts[0]) || !"+X".equals(parts[2]))
            throw new IllegalStateException("Unsupported HDR orientation '" + res + "': " + resourcePath);
        int height = Integer.parseInt(parts[1]);
        int width = Integer.parseInt(parts[3]);

        ByteBuffer out = ByteBuffer.allocateDirect(width * height * 16).order(ByteOrder.nativeOrder());
        byte[] scan = new byte[width * 4]; // one scanline as R,G,B,E planes interleaved per pixel

        for (int y = 0; y < height; y++) {
            pos = readScanline(data, pos, scan, width, resourcePath);
            for (int x = 0; x < width; x++) {
                int r = scan[x * 4] & 0xFF, g = scan[x * 4 + 1] & 0xFF,
                        b = scan[x * 4 + 2] & 0xFF, e = scan[x * 4 + 3] & 0xFF;
                if (e == 0) {
                    out.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f);
                } else {
                    // shared exponent: value = mantissa/256 * 2^(e-128)
                    float scale = (float) Math.pow(2.0, e - 136); // -128 exponent bias, -8 for /256
                    out.putFloat(r * scale).putFloat(g * scale).putFloat(b * scale).putFloat(1f);
                }
            }
        }
        out.flip();
        return new HdrLoader(width, height, out);
    }

    /** Reads one scanline (RLE or flat) into {@code scan} as RGBE per pixel;
     *  returns the new read position. */
    private static int readScanline(byte[] data, int pos, byte[] scan, int width, String name) {
        int b0 = data[pos] & 0xFF, b1 = data[pos + 1] & 0xFF,
                b2 = data[pos + 2] & 0xFF, b3 = data[pos + 3] & 0xFF;

        // New-style RLE scanline marker: 0x02 0x02 then 16-bit width.
        if (b0 == 2 && b1 == 2 && ((b2 << 8) | b3) == width) {
            pos += 4;
            // Four separate component planes, each RLE-packed.
            for (int comp = 0; comp < 4; comp++) {
                int x = 0;
                while (x < width) {
                    int count = data[pos++] & 0xFF;
                    if (count > 128) {           // run: repeat next byte (count-128) times
                        byte v = data[pos++];
                        int n = count - 128;
                        for (int i = 0; i < n; i++) scan[(x + i) * 4 + comp] = v;
                        x += n;
                    } else {                     // literal: copy `count` bytes
                        for (int i = 0; i < count; i++) scan[(x + i) * 4 + comp] = data[pos++];
                        x += count;
                    }
                }
                if (x != width) throw new IllegalStateException("Corrupt HDR RLE scanline in " + name);
            }
            return pos;
        }

        // Flat (uncompressed) scanline: width consecutive RGBE quads. (Old-style
        // 1,1,1,n run-length repetition is not emitted by any modern encoder and
        // is deliberately unsupported — fail loudly instead of decoding garbage.)
        if (b0 == 1 && b1 == 1 && b2 == 1)
            throw new IllegalStateException("Old-style RLE HDR not supported: " + name);
        System.arraycopy(data, pos, scan, 0, width * 4);
        return pos + width * 4;
    }

    private static String readLine(byte[] data, int pos) {
        int end = pos;
        while (end < data.length && data[end] != '\n') end++;
        return new String(data, pos, end - pos, StandardCharsets.US_ASCII);
    }
}