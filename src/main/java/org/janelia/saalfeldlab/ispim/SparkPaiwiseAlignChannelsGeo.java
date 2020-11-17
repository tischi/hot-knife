package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.MultiConsensusFilter;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import bdv.util.ConstantRandomAccessible;
import bdv.viewer.Interpolation;
import ij.ImageJ;
import ij.ImagePlus;
import loci.formats.FormatException;
import loci.formats.in.TiffReader;
import mpicbg.models.AffineModel2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.TranslationModel3D;
import mpicbg.trakem2.transform.AffineModel3D;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.list.ListImg;
import net.imglib2.interpolation.Interpolant;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.interpolation.stack.LinearRealRandomAccessibleStackInterpolatorFactory;
import net.imglib2.interpolation.stack.NearestNeighborRealRandomAccessibleStackInterpolatorFactory;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.outofbounds.OutOfBoundsConstantValueFactory;
import net.imglib2.outofbounds.OutOfBoundsFactory;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.fastrgldm.FRGLDMMatcher;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.RGLDMMatcher;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import scala.Tuple2;

public class SparkPaiwiseAlignChannelsGeo implements Callable<Void>, Serializable {

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--id", required = true, description = "Stack key, e.g. Pos012")
	private String id = null;

	@Option(names = "--channelA", required = true, description = "Channel A key, e.g. Ch488+561+647nm")
	private String channelA = null;

	@Option(names = "--channelB", required = true, description = "Channel B key, e.g. Ch405nm")
	private String channelB = null;

	@Option(names = "--camA", required = true, description = "CamA key, e.g. cam1")
	private String camA = null;

	@Option(names = "--camB", required = true, description = "CamB key, e.g. cam1")
	private String camB = null;

	@Option(names = {"-b", "--blocksize"}, required = false, description = "blocksize in z for point extraction (default: 20)")
	private int blocksize = 20;

	@Option(names = "--first", required = false, description = "First slice index, e.g. 0 (default 0)")
	private int firstSliceIndex = 0;

	@Option(names = "--last", required = false, description = "Last slice index, e.g. 1000 (default MAX)")
	private int lastSliceIndex = Integer.MAX_VALUE;

	@Option(names = "--tryLoadingPoints", required = false, description = "Try to load previously saved points (default: false)")
	private boolean tryLoadingPoints = false;

	@Option(names = "--minIntensity", required = false, description = "min intensity, if minIntensity==maxIntensity determine min/max per slice (default: 0)")
	private double minIntensity = 0;

	@Option(names = "--maxIntensity", required = false, description = "max intensity, if minIntensity==maxIntensity determine min/max per slice (default: 4096)")
	private double maxIntensity = 4096;

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {
		new CommandLine(new SparkPaiwiseAlignChannelsGeo()).execute(args);
	}

	public static class Block implements Serializable
	{
		private static final long serialVersionUID = -5770229677154496831L;

		final int from, to, gaussOverhead;
		final String channel, cam;
		final double sigma, threshold, minIntensity, maxIntensity;
		final double[] transform;

		public Block( final int from, final int to, final String channel, final String cam, final AffineTransform2D camtransform, final double sigma, final double threshold, final double minIntensity, final double maxIntensity )
		{
			this.from = from;
			this.to = to;
			this.channel = channel;
			this.cam = cam;
			this.sigma = sigma;
			this.threshold = threshold;
			this.gaussOverhead = DoGImgLib2.radiusDoG( 2.0 );
			this.transform = camtransform.getRowPackedCopy();
			this.minIntensity = minIntensity;
			this.maxIntensity = maxIntensity;
		}

		public AffineTransform2D getTransform()
		{
			final AffineTransform2D t = new AffineTransform2D();
			t.set( transform );
			return t;
		}
	}

	public static void alignChannels(
			final JavaSparkContext sc,
			final String n5Path,
			final String id,
			final String channelA,
			final String channelB,
			final String camA,
			final String camB,
			final double minIntensity,
			final double maxIntensity,
			final int firstSliceIndex,
			final int lastSliceIndex,
			final int blockSize,
			final boolean tryLoadingPoints ) throws FormatException, IOException, ClassNotFoundException
	{
		System.out.println( new Date(System.currentTimeMillis() ) + ": Opening N5." );

		final N5FSReader n5 = new N5FSReader(
				n5Path,
				new GsonBuilder().registerTypeAdapter(
						AffineTransform2D.class,
						new AffineTransform2DAdapter()));

		final HashMap<String, HashMap<String, AffineTransform2D>> camTransforms = n5.getAttribute(
				"/",
				"camTransforms",
				new TypeToken<HashMap<String, HashMap<String, AffineTransform2D>>>() {}.getType());
		final ArrayList<String> ids = n5.getAttribute(
				"/",
				"stacks",
				new TypeToken<ArrayList<String>>() {}.getType());

		if (!ids.contains(id))
		{
			System.err.println("Id '" + id + "' does not exist in '" + n5Path + "'.");
			return;
		}
	
		System.out.println( new Date(System.currentTimeMillis() ) + ": Loading alignments." );

		final HashMap<String, HashMap<String, List<Slice>>> stacks = new HashMap<>();
		final HashMap<String, RandomAccessible<AffineTransform2D>> alignments = new HashMap<>();

		int localLastSliceIndex = lastSliceIndex;

		for (final Entry<String, HashMap<String, AffineTransform2D>> channel : camTransforms.entrySet()) {
			final HashMap<String, List<Slice>> channelStacks = new HashMap<>();
			stacks.put(channel.getKey(), channelStacks);

			/* stack alignment transforms */
			final ArrayList<AffineTransform2D> transforms = n5.getAttribute(
					id + "/" + channel.getKey(),
					"transforms",
					new TypeToken<ArrayList<AffineTransform2D>>(){}.getType());

			final RandomAccessible<AffineTransform2D> alignmentTransforms = Views.extendBorder(new ListImg<>(transforms, transforms.size()));

			alignments.put(channel.getKey(), alignmentTransforms);

			/* add all camera stacks that exist */
			for (final String camKey : channel.getValue().keySet()) {
				final String groupName = id + "/" + channel.getKey() + "/" + camKey;
				if (n5.exists(groupName)) {
					final ArrayList<Slice> stack = n5.getAttribute(
							groupName,
							"slices",
							new TypeToken<ArrayList<Slice>>(){}.getType());
					channelStacks.put(
							camKey,
							stack);

					localLastSliceIndex = Math.min(localLastSliceIndex, stack.size() - 1);
				}
			}
		}

		System.out.println( new Date(System.currentTimeMillis() ) + ": localLastSliceIndex=" + localLastSliceIndex );
		final Gson gson = new GsonBuilder().registerTypeAdapter(
				AffineTransform2D.class,
				new AffineTransform2DAdapter()).create();

		System.out.println(gson.toJson(camTransforms));
		System.out.println(gson.toJson(ids));
		// System.out.println(new Gson().toJson(stacks));

		new ImageJ();

		ArrayList<InterestPoint> pointsChA = null;
		ArrayList<InterestPoint> pointsChB = null;

		if ( tryLoadingPoints )
		{
			System.out.println( "trying to load points ... " );

			try
			{
				final String datasetNameA = id + "/" + channelA + "/Stack-DoG-detections";
				final DatasetAttributes datasetAttributesA = n5.getDatasetAttributes(datasetNameA);
	
				final String datasetNameB = id + "/" + channelB + "/Stack-DoG-detections";
				final DatasetAttributes datasetAttributesB = n5.getDatasetAttributes(datasetNameB);
	
				pointsChA = n5.readSerializedBlock(datasetNameA, datasetAttributesA, new long[] {0});
				pointsChB = n5.readSerializedBlock(datasetNameB, datasetAttributesB, new long[] {0});
			}
			catch ( Exception e ) // java.nio.file.NoSuchFileException
			{
				pointsChA = pointsChB = null;
				e.printStackTrace();
			}
		}

		if ( pointsChA == null || pointsChB == null )
		{
			if ( tryLoadingPoints )
				System.out.println( "could not load points ... " );

			System.out.println( "extracting points ... " );

			final int numSlices = localLastSliceIndex - firstSliceIndex + 1;
			final int numBlocks = numSlices / blockSize + (numSlices % blockSize > 0 ? 1 : 0);
			final int localLastSlice = localLastSliceIndex;
			final ArrayList<Block> blocks = new ArrayList<>();
	
			for ( int i = 0; i < numBlocks; ++i )
			{
				final int from  = i * blockSize + firstSliceIndex;
				final int to = Math.min( localLastSliceIndex, from + blockSize - 1 );
	
				final Block blockChannelA = new Block(from, to, channelA, camA, camTransforms.get( channelA ).get( camA ), 2.0, /*0.02*/0.005, minIntensity, maxIntensity );
				final Block blockChannelB = new Block(from, to, channelB, camB, camTransforms.get( channelB ).get( camB ), 2.0, /*0.01*/0.005, minIntensity, maxIntensity );
	
				blocks.add( blockChannelA );
				blocks.add( blockChannelB );
		
				System.out.println( "block " + i + ": " + from + " >> " + to );

				/*
				// visible error: from=200, to=219, ch=Ch515+594nm (cam=cam1) Pos012
				if ( blockChannelB.from == 200 )
				{
					new ImageJ();
					viewBlock( stacks.get( blockChannelB.channel ), blockChannelB, firstSliceIndex, localLastSlice, n5Path, id );
					SimpleMultiThreading.threadHaltUnClean();
				}*/
			}
	
			final JavaRDD<Block> rddSlices = sc.parallelize( blocks );
	
			final JavaPairRDD<Block, ArrayList< InterestPoint >> rddFeatures = rddSlices.mapToPair(
				block ->
				{
					final HashMap<String, List<Slice>> ch = stacks.get( block.channel );
					final List< Slice > slices = ch.get( block.cam );
	
					/* this is the inverse */
					final AffineTransform2D camtransform = block.getTransform();//camTransforms.get( block.channel ).get( block.cam );
	
					final N5FSReader n5Local = new N5FSReader(
							n5Path,
							new GsonBuilder().registerTypeAdapter(
									AffineTransform2D.class,
									new AffineTransform2DAdapter()));
	
					final ArrayList<AffineTransform2D> transforms = n5Local.getAttribute(
							id + "/" + block.channel,
							"transforms",
							new TypeToken<ArrayList<AffineTransform2D>>(){}.getType());
	
					//if ( block.from != 200 )
					//	return new Tuple2<>(block, new ArrayList<>());
	
					final RandomAccessible<AffineTransform2D> alignmentTransforms = Views.extendBorder(new ListImg<>(transforms, transforms.size()));
	
					System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + block.from + ", to=" + block.to + ", ch=" + block.channel + " (cam=" + block.cam + "): opening images." );
	
					final Pair<RandomAccessibleInterval< UnsignedShortType >, RandomAccessibleInterval< UnsignedShortType >> imgs =
							PairwiseAlignChannelsUtil.openRandomAccessibleIntervals(
									slices,
									new UnsignedShortType(0),
									Interpolation.NLINEAR,
									camtransform,
									alignmentTransforms,//alignments.get( channelA ),
									Math.max( firstSliceIndex, block.from  - block.gaussOverhead ),
									Math.min( localLastSlice, block.to + block.gaussOverhead ) );
	
					System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + block.from + ", to=" + block.to + ", ch=" + block.channel + " (cam=" + block.cam + "): finding points." );
	
					final ExecutorService service = Executors.newFixedThreadPool( 1 );
					final ArrayList< InterestPoint > initialPoints =
							DoGImgLib2.computeDoG(
									imgs.getA(),
									imgs.getB(),
									block.sigma,
									block.threshold,
									1, /*localization*/
									false, /*findMin*/
									true, /*findMax*/
									block.minIntensity, /* min intensity */
									block.maxIntensity, /* max intensity */
									service,
									1 );
	
					service.shutdown();
	
					// exclude points that lie within the Gauss overhead
					final ArrayList< InterestPoint > points = new ArrayList<>();
	
					for ( final InterestPoint ip : initialPoints )
						if ( ip.getDoublePosition( 2 ) > block.from - 0.5 && ip.getDoublePosition( 2 ) < block.to + 0.5 )
							points.add( ip );
	
					System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + block.from + ", to=" + block.to + ", ch=" + block.channel + " (cam=" + block.cam + "): " + points.size() + " points" );
	
					/*if ( block.from == 200 )
					{
						final ImagePlus impA = ImageJFunctions.wrap(imgs.getA(), block.channel, Executors.newFixedThreadPool( 8 ) ).duplicate();
						impA.setDimensions( 1, impA.getStackSize(), 1 );
						impA.resetDisplayRange();
						impA.show();
		
						//final ImagePlus impAw = ImageJFunctions.wrap(imgs.getB(), block.channel + "_w", Executors.newFixedThreadPool( 8 ) ).duplicate();
						//impAw.setDimensions( 1, impAw.getStackSize(), 1 );
						//impAw.resetDisplayRange();
						//impAw.show();
	
						final long[] dim = new long[ imgs.getA().numDimensions() ];
						final long[] min = new long[ imgs.getA().numDimensions() ];
						imgs.getA().dimensions( dim );
						imgs.getA().min( min );
	
						final RandomAccessibleInterval< FloatType > dots = Views.translate( ArrayImgs.floats( dim ), min );
						final RandomAccess< FloatType > rDots = dots.randomAccess();
	
						for ( final InterestPoint ip : points )
						{
							for ( int d = 0; d < dots.numDimensions(); ++d )
								rDots.setPosition( Math.round( ip.getFloatPosition( d ) ), d );
	
							rDots.get().setOne();
						}
	
						Gauss3.gauss( 1, Views.extendZero( dots ), dots );
						final ImagePlus impP = ImageJFunctions.wrap( dots, "detections_" + block.channel, Executors.newFixedThreadPool( 8 ) ).duplicate();
						impP.setDimensions( 1, impP.getStackSize(), 1 );
						impP.setSlice( impP.getStackSize() / 2 );
						impP.resetDisplayRange();
						impP.show();
					}*/
	
					return new Tuple2<>(block, points);
				});
	
			/* cache the booleans, so features aren't regenerated every time */
			rddFeatures.cache();
	
			/* collect the results */
			final List<Tuple2<Block, ArrayList< InterestPoint >>> results = rddFeatures.collect();
	
			pointsChA = new ArrayList<>();
			pointsChB = new ArrayList<>();
	
			for ( final Tuple2<Block, ArrayList< InterestPoint >> tuple : results )
			{
				if ( tuple._1().channel.equals( channelA ) )
					pointsChA.addAll( tuple._2() );
				else
					pointsChB.addAll( tuple._2() );

				if ( tuple._2().size() == 0 )
					System.out.println( "Warning: block " + tuple._1.from + " has 0 detections");
			}

			System.out.println( "saving points ... " );

			final N5FSWriter n5Writer = new N5FSWriter(n5Path);

			if (pointsChA.size() > 0)
			{
				final String featuresGroupName = n5Writer.groupPath(id + "/" + channelA, "Stack-DoG-detections");
	
				if (n5Writer.exists(featuresGroupName))
					n5Writer.remove(featuresGroupName);
				
				n5Writer.createDataset(
						featuresGroupName,
						new long[] {1},
						new int[] {1},
						DataType.OBJECT,
						new GzipCompression());
	
				final String datasetName = id + "/" + channelA + "/Stack-DoG-detections";
				final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(datasetName);
	
				n5Writer.writeSerializedBlock(
						pointsChA,
						datasetName,
						datasetAttributes,
						new long[] {0});
			}
	
			if (pointsChB.size() > 0)
			{
				final String featuresGroupName = n5Writer.groupPath(id + "/" + channelB, "Stack-DoG-detections");
	
				if (n5Writer.exists(featuresGroupName))
					n5Writer.remove(featuresGroupName);
				
				n5Writer.createDataset(
						featuresGroupName,
						new long[] {1},
						new int[] {1},
						DataType.OBJECT,
						new GzipCompression());
	
				final String datasetName = id + "/" + channelB + "/Stack-DoG-detections";
				final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(datasetName);
	
				n5Writer.writeSerializedBlock(
						pointsChB,
						datasetName,
						datasetAttributes,
						new long[] {0});
			}
		}

		System.out.println( new Date(System.currentTimeMillis() ) + ": channelA: " + pointsChA.size() + " points" );
		System.out.println( new Date(System.currentTimeMillis() ) + ": channelB: " + pointsChB.size() + " points" );

		//
		// alignment
		//

		final int numNeighbors = 3;
		final int redundancy = 1;
		final double ratioOfDistance = 3;
		final double differenceThreshold = Double.MAX_VALUE;
		final int numIterations = 10000;
		final double maxEpsilon = 5;
		final int minNumInliers = 25;

		// not enough points to build a descriptor
		if ( pointsChA.size() < numNeighbors + redundancy + 1 || pointsChB.size() < numNeighbors + redundancy + 1 )
			return;

		final List< PointMatch > candidates = 
				new FRGLDMMatcher<>().extractCorrespondenceCandidates(
						pointsChA,
						pointsChB,
						redundancy,
						ratioOfDistance ).stream().map( v -> (PointMatch)v).collect( Collectors.toList() );

		/*final List< PointMatch > candidates = 
				new RGLDMMatcher<>().extractCorrespondenceCandidates(
						pointsChA,
						pointsChB,
						3,
						redundancy,
						ratioOfDistance,
						Double.MAX_VALUE ).stream().map( v -> (PointMatch)v).collect( Collectors.toList() );*/

		double minZ = localLastSliceIndex;
		double maxZ = firstSliceIndex;

		for ( final PointMatch pm : candidates )
		{
			//Mon Oct 19 20:26:19 EDT 2020: channelA: 60121 points
			//Mon Oct 19 20:26:19 EDT 2020: channelB: 86909 points
			minZ = Math.min( minZ, Math.min( pm.getP1().getL()[ 2 ], pm.getP2().getL()[ 2 ] ) );
			maxZ = Math.max( maxZ, Math.max( pm.getP1().getL()[ 2 ], pm.getP2().getL()[ 2 ] ) );
		}

		System.out.println( "candidates: " + candidates.size() + " from(z) " + minZ + " to(z) " + maxZ );

		final MultiConsensusFilter filter = new MultiConsensusFilter<>(
//				new Transform.InterpolatedAffineModel2DSupplier(
				(Supplier<AffineModel2D> & Serializable)AffineModel2D::new,
//				(Supplier<RigidModel2D> & Serializable)RigidModel2D::new, 0.25),
//				(Supplier<TranslationModel2D> & Serializable)TranslationModel2D::new,
//				(Supplier<RigidModel2D> & Serializable)RigidModel2D::new,
				numIterations,
				maxEpsilon,
				0,
				minNumInliers);

		final ArrayList<PointMatch> matches = filter.filter(candidates);

		minZ = localLastSliceIndex;
		maxZ = firstSliceIndex;

		for ( final PointMatch pm : matches )
		{
			minZ = Math.min( minZ, Math.min( pm.getP1().getL()[ 2 ], pm.getP2().getL()[ 2 ] ) );
			maxZ = Math.max( maxZ, Math.max( pm.getP1().getL()[ 2 ], pm.getP2().getL()[ 2 ] ) );
		}

		System.out.println( "matches: " + matches.size() + " from(z) " + minZ + " to(z) " + maxZ );

		try
		{
			final AffineModel3D affine = new AffineModel3D();
			affine.fit( matches );
			System.out.println( "affine (" + PointMatch.meanDistance( matches ) + "): " + affine );

			final TranslationModel3D translation = new TranslationModel3D();
			translation.fit( matches );
			System.out.println( "translation(" + PointMatch.meanDistance( matches ) + ")" + translation );

			BdvStackSource<?> bdv = null;

			final AffineTransform3D transformB_a = TransformationTools.getAffineTransform( affine ).inverse();

			bdv = displayOverlap( bdv, channelA, camA, stacks.get( channelA ).get( camA ), alignments.get( channelA ), camTransforms.get( channelA ).get( camA ), new AffineTransform3D(), firstSliceIndex, localLastSliceIndex );
			bdv = displayOverlap( bdv, channelB, camB, stacks.get( channelB ).get( camB ), alignments.get( channelB ), camTransforms.get( channelB ).get( camB ), transformB_a, firstSliceIndex, localLastSliceIndex );

			System.out.println( "done" );
		}
		catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			e.printStackTrace();
		}


		// cam4 (Ch488+561+647nm) vs cam4 (Ch515+594nm)
		// cam1 (Ch405nm) vs cam3 (Ch515+594nm)
		// cam1 (Ch405nm) vs cam3 (Ch488+561+647nm)**
	}

	protected static BdvStackSource<?> displayOverlap(
			final BdvStackSource<?> bdv,
			final String channel,
			final String cam, 
			final List<Slice> slices,
			final RandomAccessible<AffineTransform2D> alignments,
			final AffineTransform2D camTransform,
			final AffineGet transform,
			final int firstSliceIndex,
			final int lastSliceIndex ) throws FormatException, IOException
	{
		/* this is the inverse */
		final String title = channel + " " + cam;

		return ViewISPIMStack.showCamSource(
				bdv,
				title,
				slices,
				new UnsignedShortType(0),
				Interpolation.NEARESTNEIGHBOR,
				camTransform.inverse(), // pass the forward transform
				transform,
				alignments,
				firstSliceIndex,
				lastSliceIndex);
	}

	protected static void viewBlock( final HashMap<String, List<Slice>> ch, final Block block, final int firstSliceIndex, final int localLastSlice, final String n5Path, final String id ) throws IOException, FormatException
	{
		//final HashMap<String, List<Slice>> ch = stacks.get( block.channel );
		final List< Slice > slices = ch.get( block.cam );

		// this is the inverse
		final AffineTransform2D camtransform = block.getTransform();//camTransforms.get( block.channel ).get( block.cam );

		final N5FSReader n5Local = new N5FSReader(
				n5Path,
				new GsonBuilder().registerTypeAdapter(
						AffineTransform2D.class,
						new AffineTransform2DAdapter()));

		final ArrayList<AffineTransform2D> transforms = n5Local.getAttribute(
				id + "/" + block.channel,
				"transforms",
				new TypeToken<ArrayList<AffineTransform2D>>(){}.getType());

		//if ( block.from != 200 )
		//	return new Tuple2<>(block, new ArrayList<>());

		final RandomAccessible<AffineTransform2D> alignmentTransforms = Views.extendBorder(new ListImg<>(transforms, transforms.size()));

		System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + block.from + ", to=" + block.to + ", ch=" + block.channel + " (cam=" + block.cam + "): opening images." );

		final Pair<RandomAccessibleInterval< UnsignedShortType >, RandomAccessibleInterval< UnsignedShortType >> imgs =
				PairwiseAlignChannelsUtil.openRandomAccessibleIntervals(
						slices,
						new UnsignedShortType(0),
						Interpolation.NLINEAR,
						camtransform,
						alignmentTransforms,//alignments.get( channelA ),
						Math.max( firstSliceIndex, block.from  - block.gaussOverhead ),
						Math.min( localLastSlice, block.to + block.gaussOverhead ) );

		final ImagePlus impA = ImageJFunctions.wrap(imgs.getA(), block.channel, Executors.newFixedThreadPool( 8 ) ).duplicate();
		impA.setDimensions( 1, impA.getStackSize(), 1 );
		impA.resetDisplayRange();
		impA.show();
		
	}

	@SuppressWarnings("serial")
	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException, ClassNotFoundException
	{
		// System property for locally calling spark has to be set, e.g. -Dspark.master=local[4]
		final String sparkLocal = System.getProperty( "spark.master" );

		// only do that if the system property is not set
		if ( sparkLocal == null || sparkLocal.trim().length() == 0 )
		{
			System.out.println( "Spark System property not set: " + sparkLocal );
			System.setProperty( "spark.master", "local[" + Math.max( 1, Runtime.getRuntime().availableProcessors() ) + "]" );
		}

		System.out.println( "Spark System property is: " + System.getProperty( "spark.master" ) );

		final SparkConf conf = new SparkConf().setAppName("SparkAlignChannels");

		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		alignChannels(
				sc,
				n5Path,
				id,
				channelA,
				channelB,
				camA,
				camB,
				minIntensity,
				maxIntensity,
				firstSliceIndex,
				lastSliceIndex,
				blocksize,
				tryLoadingPoints );

		sc.close();

		return null;
	}
}
