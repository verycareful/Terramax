package com.fury.terramax.core.fluvial;

import com.fury.terramax.core.terrain.HeightField;
import java.util.function.IntToDoubleFunction;

/**
 * A bounded square lattice that can be flooded, routed and accumulated.
 *
 * <p>Used by both routing tiers: tier 1 at 8,000 blocks to assign basin identity, tier
 * 2 at 1,000 blocks to build one basin's network. The algorithms are identical and
 * only the extent and the spacing differ, so they live here once.
 *
 * <p><b>Priority-flood is what makes the water surface monotonic.</b> After filling,
 * every cell that is not an outlet has a neighbour at or below it, and flow terminates
 * only at an outlet. A channel's elevation therefore cannot increase going downstream.
 * That is the invariant the entire fluvial design rests on, and it is <i>bought</i>
 * here by construction rather than checked for afterwards and patched.
 *
 * <p><b>Three passes, and each earns its place.</b> The flood produces filled
 * elevations and, as a by-product, a flood tree: the cell each cell was first reached
 * from. That tree is already a valid drainage network, but it follows the order the
 * flood expanded rather than the steepest way down, so a separate D8 pass takes over
 * wherever there is real relief. The flood tree stays as the fallback on flats, where
 * D8 has nothing to choose between and would otherwise leave a lake bottom undrained.
 *
 * <p>The order cells leave the heap is kept for a third reason. It rises monotonically
 * in filled elevation, so reversing it is a valid topological order for accumulation
 * and no separate sort is needed.
 */
public final class FlowLattice {
	/** Eight neighbours, in the order used to alternate cardinal and diagonal. */
	private static final int[] NEIGHBOUR_X = {1, 1, 0, -1, -1, -1, 0, 1};
	private static final int[] NEIGHBOUR_Z = {0, 1, 1, 1, 0, -1, -1, -1};

	private static final double DIAGONAL = Math.sqrt(2.0);

	private final int width;
	private final int depth;
	private final double spacing;
	private final double originX;
	private final double originZ;

	private final double[] surface;
	private final double[] filled;
	private final int[] floodParent;
	private final int[] downstream;
	private final double[] accumulated;
	private final int[] processOrder;

	private int processed;

	public FlowLattice(
			final int width, final int depth, final double spacingBlocks,
			final double originX, final double originZ) {
		if (width <= 0 || depth <= 0) {
			throw new IllegalArgumentException("lattice must have positive extent");
		}

		this.width = width;
		this.depth = depth;
		this.spacing = spacingBlocks;
		this.originX = originX;
		this.originZ = originZ;

		int size = width * depth;
		this.surface = new double[size];
		this.filled = new double[size];
		this.floodParent = new int[size];
		this.downstream = new int[size];
		this.accumulated = new double[size];
		this.processOrder = new int[size];
	}

	public int width() {
		return width;
	}

	public int depth() {
		return depth;
	}

	public int size() {
		return width * depth;
	}

	public double spacing() {
		return spacing;
	}

	public double originX() {
		return originX;
	}

	public double originZ() {
		return originZ;
	}

	public int index(final int ix, final int iz) {
		return iz * width + ix;
	}

	public int cellX(final int i) {
		return i % width;
	}

	public int cellZ(final int i) {
		return i / width;
	}

	public double worldX(final int ix) {
		return originX + (ix + 0.5) * spacing;
	}

	public double worldZ(final int iz) {
		return originZ + (iz + 0.5) * spacing;
	}

	public double surface(final int i) {
		return surface[i];
	}

	public double filled(final int i) {
		return filled[i];
	}

	public int downstream(final int i) {
		return downstream[i];
	}

	public double accumulated(final int i) {
		return accumulated[i];
	}

	public int[] processOrder() {
		return processOrder;
	}

	/**
	 * How many cells the flood actually reached.
	 *
	 * <p>Should equal {@link #size()}: every cell is connected to the lattice edge
	 * through the grid, and the edge is always seeded. Anything less means cells whose
	 * runoff never entered the accumulation at all, so it is worth being able to check
	 * rather than assume.
	 */
	public int processed() {
		return processed;
	}

	public boolean isOutlet(final int i) {
		return downstream[i] < 0;
	}

	/** True where the cell sits below its own filled level, so it is under water. */
	public boolean submerged(final int i) {
		return filled[i] > surface[i];
	}

	/** Fills the surface from a height field. One sample per cell centre. */
	public void sampleSurface(final HeightField source) {
		for (int iz = 0; iz < depth; iz++) {
			double z = worldZ(iz);

			for (int ix = 0; ix < width; ix++) {
				surface[index(ix, iz)] = source.heightAt(worldX(ix), z);
			}
		}
	}

	/**
	 * Priority-flood, after Barnes, Lehman and Mulla.
	 *
	 * <p>Cells at or below {@code baseLevel}, and cells on the lattice edge, are open:
	 * water leaves there and nothing pools behind them. Everything else is raised to
	 * the level of the lowest rim it sits behind, which is by definition its spill
	 * elevation, so a depression comes out of this already knowing where it would
	 * overflow.
	 */
	public void floodFill(final double baseLevel) {
		boolean[] seen = new boolean[size()];
		MinHeap open = new MinHeap(size());

		for (int i = 0; i < size(); i++) {
			int ix = cellX(i);
			int iz = cellZ(i);
			boolean edge = ix == 0 || iz == 0 || ix == width - 1 || iz == depth - 1;

			if (edge || surface[i] <= baseLevel) {
				filled[i] = surface[i];
				floodParent[i] = -1;
				seen[i] = true;
				open.push(surface[i], i);
			}
		}

		processed = 0;

		while (!open.isEmpty()) {
			int i = open.pop();
			processOrder[processed++] = i;

			int ix = cellX(i);
			int iz = cellZ(i);

			for (int n = 0; n < 8; n++) {
				int nx = ix + NEIGHBOUR_X[n];
				int nz = iz + NEIGHBOUR_Z[n];

				if (nx < 0 || nz < 0 || nx >= width || nz >= depth) {
					continue;
				}

				int j = index(nx, nz);

				if (seen[j]) {
					continue;
				}

				// The fill itself. A cell lower than the rim it sits behind is raised
				// to that rim, and a cell above it keeps its own height.
				filled[j] = Math.max(surface[j], filled[i]);
				floodParent[j] = i;
				seen[j] = true;
				open.push(filled[j], j);
			}
		}
	}

	/**
	 * D8 steepest descent on the filled surface, falling back to the flood tree.
	 *
	 * <p>The fallback is not a detail. On a filled lake bottom every neighbour is at
	 * the same level, so steepest descent has nothing to choose and would leave the
	 * whole surface undrained. The flood tree always points toward the rim the flood
	 * arrived from, which is the way out, and it cannot contain a cycle because a cell
	 * is only ever parented to one already popped.
	 */
	public void route() {
		for (int i = 0; i < size(); i++) {
			int ix = cellX(i);
			int iz = cellZ(i);
			int best = -1;
			double bestSlope = 0.0;

			for (int n = 0; n < 8; n++) {
				int nx = ix + NEIGHBOUR_X[n];
				int nz = iz + NEIGHBOUR_Z[n];

				if (nx < 0 || nz < 0 || nx >= width || nz >= depth) {
					continue;
				}

				int j = index(nx, nz);
				double run = (n % 2 == 0) ? spacing : spacing * DIAGONAL;
				double slope = (filled[i] - filled[j]) / run;

				if (slope > bestSlope) {
					bestSlope = slope;
					best = j;
				}
			}

			downstream[i] = best >= 0 ? best : floodParent[i];
		}
	}

	/** Accumulation with nothing lost along the way. */
	public void accumulate(final IntToDoubleFunction gainAt) {
		accumulate(gainAt, i -> 1.0);
	}

	/**
	 * Pushes flow downstream, gaining locally and losing along the way.
	 *
	 * <p>Two terms rather than one, because a cell does two different things to a
	 * river. It <b>adds</b> its own runoff, which is rainfall minus what evaporates
	 * before it can leave. That can be nearly nothing in a desert but is never
	 * negative: dry ground contributes no water, it does not consume the sky's.
	 *
	 * <p>It also <b>takes</b> from whatever is already passing through, because a
	 * channel crossing dry air loses water over its whole length. That is a
	 * multiplicative loss on the inherited total, not a subtraction from the local
	 * gain, and the distinction is what lets a river arrive large, cross a desert and
	 * die there. Subtracting a local term instead would let a small headwater go
	 * negative in the same air, which is not a thing that happens.
	 *
	 * @param gainAt      runoff produced by this cell, at or above zero
	 * @param retentionAt share of inherited flow surviving this cell, above 0 up to 1
	 */
	public void accumulate(final IntToDoubleFunction gainAt, final IntToDoubleFunction retentionAt) {
		java.util.Arrays.fill(accumulated, 0.0);

		// Reverse pop order is a valid topological order. Pop order is nondecreasing
		// in filled elevation, and every downstream link points to a cell strictly
		// lower or to a flood parent popped earlier, so a cell is always visited here
		// before the cell it drains into.
		for (int rank = processed - 1; rank >= 0; rank--) {
			int i = processOrder[rank];
			double total = Math.max(0.0,
					accumulated[i] * retentionAt.applyAsDouble(i) + gainAt.applyAsDouble(i));
			accumulated[i] = total;

			int next = downstream[i];

			if (next >= 0) {
				accumulated[next] += total;
			}
		}
	}

	/** Follows downstream to the terminal cell, which is this cell's outlet. */
	public int outletOf(final int start) {
		int i = start;

		// Guard rather than trust. A cycle here would hang world generation, and a
		// bounded walk that returns a wrong answer is far easier to diagnose than a
		// generator that never returns.
		for (int guard = size(); guard > 0; guard--) {
			int next = downstream[i];

			if (next < 0) {
				return i;
			}

			i = next;
		}

		return i;
	}

	/**
	 * A binary min-heap over (elevation, cell) pairs.
	 *
	 * <p>Hand-rolled because {@code PriorityQueue} would box every entry, and this is
	 * pushed once per cell: 16,384 times for a province tile and up to 90,000 for a
	 * large basin.
	 */
	private static final class MinHeap {
		private final double[] keys;
		private final int[] values;

		private int count;

		MinHeap(final int capacity) {
			this.keys = new double[capacity + 1];
			this.values = new int[capacity + 1];
		}

		boolean isEmpty() {
			return count == 0;
		}

		void push(final double key, final int value) {
			int i = count++;
			keys[i] = key;
			values[i] = value;

			while (i > 0) {
				int parent = (i - 1) >>> 1;

				if (keys[parent] <= keys[i]) {
					break;
				}

				swap(parent, i);
				i = parent;
			}
		}

		int pop() {
			int top = values[0];

			count--;
			keys[0] = keys[count];
			values[0] = values[count];

			int i = 0;

			while (true) {
				int left = i * 2 + 1;
				int right = left + 1;
				int smallest = i;

				if (left < count && keys[left] < keys[smallest]) {
					smallest = left;
				}

				if (right < count && keys[right] < keys[smallest]) {
					smallest = right;
				}

				if (smallest == i) {
					break;
				}

				swap(smallest, i);
				i = smallest;
			}

			return top;
		}

		private void swap(final int a, final int b) {
			double key = keys[a];
			keys[a] = keys[b];
			keys[b] = key;

			int value = values[a];
			values[a] = values[b];
			values[b] = value;
		}
	}
}
