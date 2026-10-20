package com.appliedenhancements.client.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AelisMarkTextureTest {
    @Test
    void markKeepsFiveSeparateLettersAndFramePaddingEvenAtSmallestGuiScale() throws Exception {
        try (var stream = getClass().getResourceAsStream("/assets/appliedenhancements/textures/gui/aelis_mark.png")) {
            assertNotNull(stream);
            BufferedImage image = ImageIO.read(stream);
            assertEquals(48, image.getWidth());
            assertEquals(48, image.getHeight());
            for (int step : new int[] {1, 2}) {
                int size = 48 / step;
                boolean[][] ink = new boolean[size][size];
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int sx = x * step + step / 2;
                        int sy = y * step + step / 2;
                        ink[y][x] = image.getRGB(sx, sy) == 0xFFBA4F90;
                        if (ink[y][x]) {
                            assertTrue(sx >= 8 && sx <= 39 && sy >= 8 && sy <= 39,
                                    "Lettering must not touch the frame");
                            assertFalse(sy >= 22 && sy <= 25, "AE and LIS need a blank line");
                        }
                    }
                }
                assertEquals(5, components(ink), "Letters must not merge when sampled at GUI scale 1 or 2");
            }
        }
    }

    private static int components(boolean[][] ink) {
        int count = 0;
        int size = ink.length;
        var queue = new ArrayDeque<int[]>();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!ink[y][x]) continue;
                count++;
                ink[y][x] = false;
                queue.add(new int[] {x, y});
                while (!queue.isEmpty()) {
                    var pixel = queue.remove();
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = pixel[0] + dx, ny = pixel[1] + dy;
                            if (nx >= 0 && nx < size && ny >= 0 && ny < size && ink[ny][nx]) {
                                ink[ny][nx] = false;
                                queue.add(new int[] {nx, ny});
                            }
                        }
                    }
                }
            }
        }
        return count;
    }
}
