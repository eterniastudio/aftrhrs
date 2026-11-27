package net.axther.aftrhrs.client.claims;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Outlines of claims as straight wall runs on the block grid. Edges between two pieces of the same claim are left out
 * (a claim made of several chunks has one outline), and only the part within {@code reach} of the player is built.
 *
 * <p>Cheap to rebuild: the pieces are rasterised once into an occupancy grid over the window, only each piece's
 * perimeter is walked (rows the piece covers on both sides have no edge), and unit edges are packed longs sorted
 * once and merged into runs. The scratch buffers are shared: main (render) thread only.
 */
public final class ClaimShapes {
	/** One wall run from (x0, z0) to (x1, z1); axis-aligned, on block boundaries. */
	public record Edge(int x0, int z0, int x1, int z1) {
		public boolean alongX() {
			return z0 == z1;
		}

		public int length() {
			return Math.abs(x1 - x0) + Math.abs(z1 - z0);
		}
	}

	private ClaimShapes() {
	}

	// Scratch, main thread only: the occupancy grid of the window and the unit edges (line << 32 | pos with its sign flipped). The grid is
	// all false between calls: each call clears the cells it set (the box of rows and columns it filled).
	private static boolean[] grid = new boolean[0];
	private static int gridX, gridZ, gridW;
	private static long[] horizontal = new long[256], vertical = new long[256];
	private static int nh, nv;

	/** The outline of the union of {@code pieces}, near (px, pz): every edge within {@code reach} (a square window). */
	public static List<Edge> outline(List<ClaimState.Piece> pieces, int px, int pz, int reach) {
		// The window with a 1-cell margin, so the neighbour tests of edge cells stay inside it.
		gridX = px - reach - 1;
		gridZ = pz - reach - 1;
		int w = gridW = 2 * reach + 3;
		int cells = w * w;
		if (grid.length < cells) grid = new boolean[cells];
		int dx0 = Integer.MAX_VALUE, dx1 = Integer.MIN_VALUE, dz0 = Integer.MAX_VALUE, dz1 = Integer.MIN_VALUE;
		for (ClaimState.Piece p : pieces) {
			int z0 = Math.max(p.minZ(), gridZ), z1 = Math.min(p.maxZ(), gridZ + w - 1);
			for (int z = z0; z <= z1; z++) {
				int a = Math.max(p.spanFrom(z), gridX), b = Math.min(p.spanTo(z), gridX + w - 1);
				if (a > b) continue;
				int row = (z - gridZ) * w - gridX;
				Arrays.fill(grid, row + a, row + b + 1, true);
				dz0 = Math.min(dz0, z - gridZ);
				dz1 = Math.max(dz1, z - gridZ);
				dx0 = Math.min(dx0, a - gridX);
				dx1 = Math.max(dx1, b - gridX);
			}
		}
		nh = 0;
		nv = 0;
		for (ClaimState.Piece p : pieces) {
			int z0 = Math.max(p.minZ(), pz - reach), z1 = Math.min(p.maxZ(), pz + reach);
			for (int z = z0; z <= z1; z++) {
				int s0 = p.spanFrom(z), s1 = p.spanTo(z);
				if (s0 > s1) continue;
				int x0 = Math.max(s0, px - reach), x1 = Math.min(s1, px + reach);
				if (x0 > x1) continue;
				// West and east sides of the row.
				if (x0 == s0 && !in(s0 - 1, z)) vertical = add(vertical, nv++, s0, z);
				if (x1 == s1 && !in(s1 + 1, z)) vertical = add(vertical, nv++, s1 + 1, z);
				// North and south faces: only where this piece's own neighbouring row doesn't cover the column.
				face(p, z - 1, z, s0, s1, x0, x1);
				face(p, z + 1, z + 1, s0, s1, x0, x1);
			}
		}
		// Back to all false for the next call: only the cells this one set.
		for (int r = dz0; r <= dz1; r++) Arrays.fill(grid, r * w + dx0, r * w + dx1 + 1, false);
		List<Edge> out = new ArrayList<>();
		runs(horizontal, nh, true, out);
		runs(vertical, nv, false, out);
		return out;
	}

	/** Faces of a row towards row {@code nz} (grid line {@code line}) where the piece's row nz leaves a gap. */
	private static void face(ClaimState.Piece p, int nz, int line, int s0, int s1, int x0, int x1) {
		int a0 = p.spanFrom(nz), a1 = p.spanTo(nz);
		if (a0 > a1) {
			faces(nz, line, x0, x1);
			return;
		}
		faces(nz, line, Math.max(x0, s0), Math.min(x1, Math.min(s1, a0 - 1)));
		faces(nz, line, Math.max(x0, Math.max(s0, a1 + 1)), Math.min(x1, s1));
	}

	private static void faces(int nz, int line, int from, int to) {
		for (int x = from; x <= to; x++) if (!in(x, nz)) horizontal = add(horizontal, nh++, line, x);
	}

	private static boolean in(int x, int z) {
		return grid[(z - gridZ) * gridW + (x - gridX)];
	}

	private static long[] add(long[] a, int i, int line, int pos) {
		if (i == a.length) a = Arrays.copyOf(a, a.length * 2);
		// pos with its sign bit flipped, so a signed sort of the longs orders positions as signed ints within a line
		a[i] = ((long) line << 32) | ((pos & 0xffffffffL) ^ 0x80000000L);
		return a;
	}

	/** Sorted unit edges → maximal runs [from, to) on each line (duplicates from overlapping pieces skipped). */
	private static void runs(long[] units, int n, boolean alongX, List<Edge> out) {
		if (n == 0) return;
		Arrays.sort(units, 0, n);
		int line = (int) (units[0] >> 32), start = (int) units[0] ^ Integer.MIN_VALUE, prev = start;
		for (int i = 1; i < n; i++) {
			int l = (int) (units[i] >> 32), pos = (int) units[i] ^ Integer.MIN_VALUE;
			if (l == line && pos == prev) continue;
			if (l != line || pos != prev + 1) {
				out.add(alongX ? new Edge(start, line, prev + 1, line) : new Edge(line, start, line, prev + 1));
				line = l;
				start = pos;
			}
			prev = pos;
		}
		out.add(alongX ? new Edge(start, line, prev + 1, line) : new Edge(line, start, line, prev + 1));
	}

	/** Claim cost in claim chunks, Feather's rule: every started 256 columns is one. */
	public static long chunks(long blocks) {
		return (blocks + 255) / 256;
	}
}
