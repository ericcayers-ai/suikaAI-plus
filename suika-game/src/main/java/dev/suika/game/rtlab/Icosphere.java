package dev.suika.game.rtlab;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Generates a unit-radius subdivided icosahedron — the shared mesh every fruit
 *  instance in the RT scene reuses (see RtScene: one BLAS, many TLAS instances,
 *  each translated/scaled to a fruit's actual position and radius). Positions
 *  double as normals since it's a unit sphere centered at the origin. */
final class Icosphere {
    final float[] vertices; // xyz triples
    final int[] indices;    // triangle triples

    private Icosphere(float[] vertices, int[] indices) {
        this.vertices = vertices;
        this.indices = indices;
    }

    static Icosphere build(int subdivisions) {
        List<float[]> verts = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();

        float t = (float) ((1.0 + Math.sqrt(5.0)) / 2.0);
        addVertex(verts, -1,  t,  0); addVertex(verts,  1,  t,  0);
        addVertex(verts, -1, -t,  0); addVertex(verts,  1, -t,  0);
        addVertex(verts,  0, -1,  t); addVertex(verts,  0,  1,  t);
        addVertex(verts,  0, -1, -t); addVertex(verts,  0,  1, -t);
        addVertex(verts,  t,  0, -1); addVertex(verts,  t,  0,  1);
        addVertex(verts, -t,  0, -1); addVertex(verts, -t,  0,  1);

        int[][] initialFaces = {
            {0,11,5}, {0,5,1}, {0,1,7}, {0,7,10}, {0,10,11},
            {1,5,9}, {5,11,4}, {11,10,2}, {10,7,6}, {7,1,8},
            {3,9,4}, {3,4,2}, {3,2,6}, {3,6,8}, {3,8,9},
            {4,9,5}, {2,4,11}, {6,2,10}, {8,6,7}, {9,8,1},
        };
        for (int[] f : initialFaces) faces.add(f);

        Map<Long, Integer> midpointCache = new HashMap<>();
        for (int s = 0; s < subdivisions; s++) {
            List<int[]> next = new ArrayList<>();
            for (int[] f : faces) {
                int a = midpoint(verts, midpointCache, f[0], f[1]);
                int b = midpoint(verts, midpointCache, f[1], f[2]);
                int c = midpoint(verts, midpointCache, f[2], f[0]);
                next.add(new int[]{f[0], a, c});
                next.add(new int[]{f[1], b, a});
                next.add(new int[]{f[2], c, b});
                next.add(new int[]{a, b, c});
            }
            faces = next;
        }

        float[] vArr = new float[verts.size() * 3];
        for (int i = 0; i < verts.size(); i++) {
            float[] v = verts.get(i);
            vArr[i * 3] = v[0]; vArr[i * 3 + 1] = v[1]; vArr[i * 3 + 2] = v[2];
        }
        int[] iArr = new int[faces.size() * 3];
        for (int i = 0; i < faces.size(); i++) {
            int[] f = faces.get(i);
            iArr[i * 3] = f[0]; iArr[i * 3 + 1] = f[1]; iArr[i * 3 + 2] = f[2];
        }
        return new Icosphere(vArr, iArr);
    }

    private static void addVertex(List<float[]> verts, float x, float y, float z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        verts.add(new float[]{(float) (x / len), (float) (y / len), (float) (z / len)});
    }

    private static int midpoint(List<float[]> verts, Map<Long, Integer> cache, int i0, int i1) {
        long key = (Math.min(i0, i1) & 0xFFFFFFFFL) << 32 | (Math.max(i0, i1) & 0xFFFFFFFFL);
        Integer cached = cache.get(key);
        if (cached != null) return cached;
        float[] a = verts.get(i0), b = verts.get(i1);
        float mx = (a[0] + b[0]) / 2f, my = (a[1] + b[1]) / 2f, mz = (a[2] + b[2]) / 2f;
        int idx = verts.size();
        addVertex(verts, mx, my, mz);
        cache.put(key, idx);
        return idx;
    }
}
