/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.hotknife.util;

import static net.imglib2.type.PrimitiveType.BYTE;
import static net.imglib2.type.PrimitiveType.DOUBLE;
import static net.imglib2.type.PrimitiveType.FLOAT;
import static net.imglib2.type.PrimitiveType.INT;
import static net.imglib2.type.PrimitiveType.LONG;
import static net.imglib2.type.PrimitiveType.SHORT;

import java.util.Set;
import java.util.function.Consumer;

import net.imglib2.Dimensions;
import org.janelia.saalfeldlab.hotknife.ops.UnaryComputerOpCellLoader;

import net.imagej.ops.Op;
import net.imagej.ops.OpService;
import net.imagej.ops.special.computer.UnaryComputerOp;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.Cache;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.LoadedCellCacheLoader;
import net.imglib2.cache.ref.SoftRefLoaderCache;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.basictypeaccess.ArrayDataAccessFactory;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.integer.GenericByteType;
import net.imglib2.type.numeric.integer.GenericIntType;
import net.imglib2.type.numeric.integer.GenericLongType;
import net.imglib2.type.numeric.integer.GenericShortType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;

/**
 * Convenience methods to create lazy evaluated cached cell images with ops or consumers.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class Lazy {

	private Lazy() {}

	/**
	 * Create a memory {@link CachedCellImg} with a cell {@link Cache}.
	 *
	 * @param grid
	 * @param cache
	 * @param type
	 * @param accessFlags
	 * @return
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <T extends NativeType<T>> CachedCellImg<T, ?> createImg(
			final CellGrid grid,
			final Cache<Long, Cell<?>> cache,
			final T type,
			final Set<AccessFlags> accessFlags) {

		final CachedCellImg<T, ?> img;

		if (GenericByteType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(BYTE, accessFlags));
		} else if (GenericShortType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(SHORT, accessFlags));
		} else if (GenericIntType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(INT, accessFlags));
		} else if (GenericLongType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(LONG, accessFlags));
		} else if (FloatType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(FLOAT, accessFlags));
		} else if (DoubleType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(DOUBLE, accessFlags));
		} else {
			img = null;
		}
		return img;
	}

	/**
	 * Create a memory {@link CachedCellImg} with a {@link CellLoader}.
	 *
	 * @param targetDimensions
	 * @param blockSize
	 * @param type
	 * @param accessFlags
	 * @param loader
	 * @return
	 */
	public static <T extends NativeType<T>> CachedCellImg<T, ?> createImg(
			final Dimensions targetDimensions,
			final int[] blockSize,
			final T type,
			final Set<AccessFlags> accessFlags,
			final CellLoader<T> loader) {

		final long[] dimensions = Intervals.dimensionsAsLongArray(targetDimensions);
		final CellGrid grid = new CellGrid(dimensions, blockSize);

		@SuppressWarnings({"unchecked", "rawtypes"})
		final Cache<Long, Cell<?>> cache =
				new SoftRefLoaderCache().withLoader(LoadedCellCacheLoader.get(grid, loader, type, accessFlags));

		return createImg(grid, cache, type, accessFlags);
	}

	/**
	 * Create a memory {@link CachedCellImg} with a cell generator {@link Consumer}.
	 *
	 * @param targetDimensions
	 * @param blockSize
	 * @param type
	 * @param accessFlags
	 * @param op
	 * @return
	 */
	public static <T extends NativeType<T>> CachedCellImg<T, ?> process(
			final Dimensions targetDimensions,
			final int[] blockSize,
			final T type,
			final Set<AccessFlags> accessFlags,
			final Consumer<RandomAccessibleInterval<T>> op) {

		return createImg(
				targetDimensions,
				blockSize,
				type,
				accessFlags,
				op::accept);
	}

	/**
	 * Create a memory {@link CachedCellImg} with a cell generator {@link UnaryComputerOp}.
	 *
	 * @param source
	 * @param sourceInterval
	 * @param blockSize
	 * @param type
	 * @param accessFlags
	 * @param op
	 * @return
	 */
	public static <I, O extends NativeType<O>> CachedCellImg<O, ?> process(
			final RandomAccessible<I> source,
			final Interval sourceInterval,
			final int[] blockSize,
			final O type,
			final Set<AccessFlags> accessFlags,
			final UnaryComputerOp<RandomAccessible<I>, RandomAccessibleInterval<O>> op) {

		return createImg(
				sourceInterval,
				blockSize,
				type,
				accessFlags,
				new UnaryComputerOpCellLoader<I, O, RandomAccessible<I>>(
					source,
					op));
	}

	/**
	 * Create a memory {@link CachedCellImg} with a cell generator
	 * {@link UnaryComputerOp} provided by an {@link OpService}.
	 *
	 * @param source
	 * @param sourceInterval
	 * @param blockSize
	 * @param type
	 * @param accessFlags
	 * @param opService
	 * @param opClass
	 * @param opArgs
	 * @return
	 */
	public static <I, O extends NativeType<O>, P extends Op> CachedCellImg<O, ?> process(
			final RandomAccessible<I> source,
			final Interval sourceInterval,
			final int[] blockSize,
			final O type,
			final Set<AccessFlags> accessFlags,
			final OpService opService,
			final Class<P> opClass,
			final Object... opArgs) {

		return createImg(
				sourceInterval,
				blockSize,
				type,
				accessFlags,
				new UnaryComputerOpCellLoader<I, O, RandomAccessible<I>>(
					source,
					opService,
					opClass,
					opArgs));
	}
}
