package mil.nga.giat.geowave.test.query;

import java.io.IOException;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import mil.nga.giat.geowave.adapter.vector.FeatureDataAdapter;
import mil.nga.giat.geowave.adapter.vector.index.IndexQueryStrategySPI;
import mil.nga.giat.geowave.adapter.vector.plugin.GeoWaveGTDataStore;
import mil.nga.giat.geowave.adapter.vector.plugin.GeoWavePluginConfig;
import mil.nga.giat.geowave.adapter.vector.plugin.GeoWavePluginException;
import mil.nga.giat.geowave.core.geotime.index.dimension.TemporalBinningStrategy.Unit;
import mil.nga.giat.geowave.core.geotime.ingest.SpatialTemporalDimensionalityTypeProvider.SpatialTemporalIndexBuilder;
import mil.nga.giat.geowave.core.geotime.store.query.SpatialTemporalQuery;
import mil.nga.giat.geowave.core.index.ByteArrayId;
import mil.nga.giat.geowave.core.store.CloseableIterator;
import mil.nga.giat.geowave.core.store.CloseableIteratorWrapper;
import mil.nga.giat.geowave.core.store.DataStore;
import mil.nga.giat.geowave.core.store.IndexWriter;
import mil.nga.giat.geowave.core.store.adapter.statistics.DataStatistics;
import mil.nga.giat.geowave.core.store.index.Index;
import mil.nga.giat.geowave.core.store.index.PrimaryIndex;
import mil.nga.giat.geowave.core.store.memory.DataStoreUtils;
import mil.nga.giat.geowave.core.store.query.BasicQuery;
import mil.nga.giat.geowave.core.store.query.QueryOptions;
import mil.nga.giat.geowave.datastore.accumulo.AccumuloDataStore;
import mil.nga.giat.geowave.datastore.accumulo.AccumuloStoreFactoryFamily;
import mil.nga.giat.geowave.datastore.accumulo.BasicAccumuloOperations;
import mil.nga.giat.geowave.test.GeoWaveTestEnvironment;

import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.filter.text.cql2.CQL;
import org.geotools.filter.text.cql2.CQLException;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opengis.feature.simple.SimpleFeature;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;

public class SpatialTemporalQueryIT extends
		GeoWaveTestEnvironment
{
	private static final SimpleDateFormat CQL_DATE_FORMAT = new SimpleDateFormat(
			"yyyy-MM-dd HH:mm:ssa");
	private static int MULTI_DAY_YEAR = 2016;
	private static int MULTI_DAY_MONTH = 1;
	private static int MULTI_MONTH_YEAR = 2000;
	private static int MULTI_YEAR_MIN = 1980;
	private static int MULTI_YEAR_MAX = 1995;
	private static PrimaryIndex DAY_INDEX = new SpatialTemporalIndexBuilder().setPeriodicity(
			Unit.DAY).createIndex();
	private static PrimaryIndex MONTH_INDEX = new SpatialTemporalIndexBuilder().setPeriodicity(
			Unit.MONTH).createIndex();
	private static PrimaryIndex YEAR_INDEX = new SpatialTemporalIndexBuilder().setPeriodicity(
			Unit.YEAR).createIndex();
	private static FeatureDataAdapter timeStampAdapter;
	private static FeatureDataAdapter timeRangeAdapter;
	private static DataStore dataStore;
	private static GeoWaveGTDataStore geowaveGtDataStore;
	private static PrimaryIndex currentGeotoolsIndex;

	@BeforeClass
	public static void initSpatialTemporalTestData()
			throws IOException,
			GeoWavePluginException {

		dataStore = new AccumuloDataStore(
				accumuloOperations);
		SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
		builder.setName("simpletimestamp");
		builder.add(
				"geo",
				Point.class);
		builder.add(
				"timestamp",
				Date.class);
		timeStampAdapter = new FeatureDataAdapter(
				builder.buildFeatureType());

		builder = new SimpleFeatureTypeBuilder();
		builder.setName("simpletimerange");
		builder.add(
				"geo",
				Point.class);
		builder.add(
				"startTime",
				Date.class);
		builder.add(
				"endTime",
				Date.class);
		timeRangeAdapter = new FeatureDataAdapter(
				builder.buildFeatureType());

		Calendar cal = getInitialDayCalendar();
		final GeometryFactory geomFactory = new GeometryFactory();
		final SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(
				timeStampAdapter.getType());
		final SimpleFeatureBuilder featureTimeRangeBuilder = new SimpleFeatureBuilder(
				timeRangeAdapter.getType());
		try (IndexWriter yearWriter = dataStore.createIndexWriter(
				YEAR_INDEX,
				DataStoreUtils.DEFAULT_VISIBILITY)) {
			try (IndexWriter monthWriter = dataStore.createIndexWriter(
					MONTH_INDEX,
					DataStoreUtils.DEFAULT_VISIBILITY)) {
				try (IndexWriter dayWriter = dataStore.createIndexWriter(
						DAY_INDEX,
						DataStoreUtils.DEFAULT_VISIBILITY)) {
					final IndexWriter[] writers = new IndexWriter[] {
						yearWriter,
						monthWriter,
						dayWriter
					};
					for (int day = cal.getActualMinimum(Calendar.DAY_OF_MONTH); day <= cal.getActualMaximum(Calendar.DAY_OF_MONTH); day++) {
						final double ptVal = ((((day + 1.0) - cal.getActualMinimum(Calendar.DAY_OF_MONTH)) / ((cal.getActualMaximum(Calendar.DAY_OF_MONTH) - cal.getActualMinimum(Calendar.DAY_OF_MONTH)) + 2.0)) * 2) - 1;
						cal.set(
								Calendar.DAY_OF_MONTH,
								day);
						final Point pt = geomFactory.createPoint(new Coordinate(
								ptVal,
								ptVal));
						featureBuilder.add(pt);
						featureBuilder.add(cal.getTime());
						final SimpleFeature feature = featureBuilder.buildFeature("day:" + day);
						yearWriter.write(
								timeStampAdapter,
								feature);

						monthWriter.write(
								timeStampAdapter,
								feature);

						dayWriter.write(
								timeStampAdapter,
								feature);
					}

					ingestTimeRangeData(
							cal,
							writers,
							featureTimeRangeBuilder,
							cal.getActualMinimum(Calendar.DAY_OF_MONTH),
							cal.getActualMaximum(Calendar.DAY_OF_MONTH),
							Calendar.DAY_OF_MONTH,
							"day");
					cal = getInitialMonthCalendar();
					for (int month = cal.getActualMinimum(Calendar.MONTH); month <= cal.getActualMaximum(Calendar.MONTH); month++) {
						cal.set(
								Calendar.MONTH,
								month);

						final double ptVal = ((((month + 1.0) - cal.getActualMinimum(Calendar.MONTH)) / ((cal.getActualMaximum(Calendar.MONTH) - cal.getActualMinimum(Calendar.MONTH)) + 2.0)) * 2) - 1;
						final Point pt = geomFactory.createPoint(new Coordinate(
								ptVal,
								ptVal));
						featureBuilder.add(pt);
						featureBuilder.add(cal.getTime());
						final SimpleFeature feature = featureBuilder.buildFeature("month:" + month);
						yearWriter.write(
								timeStampAdapter,
								feature);
						monthWriter.write(
								timeStampAdapter,
								feature);
						dayWriter.write(
								timeStampAdapter,
								feature);
					}
					ingestTimeRangeData(
							cal,
							writers,
							featureTimeRangeBuilder,
							cal.getActualMinimum(Calendar.MONTH),
							cal.getActualMaximum(Calendar.MONTH),
							Calendar.MONTH,
							"month");
					cal = getInitialYearCalendar();
					for (int year = MULTI_YEAR_MIN; year <= MULTI_YEAR_MAX; year++) {
						final double ptVal = ((((year + 1.0) - MULTI_YEAR_MIN) / ((MULTI_YEAR_MAX - MULTI_YEAR_MIN) + 2.0)) * 2) - 1;
						cal.set(
								Calendar.YEAR,
								year);
						final Point pt = geomFactory.createPoint(new Coordinate(
								ptVal,
								ptVal));
						featureBuilder.add(pt);
						featureBuilder.add(cal.getTime());

						final SimpleFeature feature = featureBuilder.buildFeature("year:" + year);
						yearWriter.write(
								timeStampAdapter,
								feature);
						monthWriter.write(
								timeStampAdapter,
								feature);
						dayWriter.write(
								timeStampAdapter,
								feature);
					}

					ingestTimeRangeData(
							cal,
							writers,
							featureTimeRangeBuilder,
							MULTI_YEAR_MIN,
							MULTI_YEAR_MAX,
							Calendar.YEAR,
							"year");

					Point pt = geomFactory.createPoint(new Coordinate(
							-50,
							-50));
					featureBuilder.add(pt);
					featureBuilder.add(cal.getTime());
					SimpleFeature feature = featureBuilder.buildFeature("outlier1timestamp");
					yearWriter.write(
							timeStampAdapter,
							feature);

					monthWriter.write(
							timeStampAdapter,
							feature);

					dayWriter.write(
							timeStampAdapter,
							feature);
					pt = geomFactory.createPoint(new Coordinate(
							50,
							50));
					featureBuilder.add(pt);
					featureBuilder.add(cal.getTime());
					feature = featureBuilder.buildFeature("outlier2timestamp");
					yearWriter.write(
							timeStampAdapter,
							feature);

					monthWriter.write(
							timeStampAdapter,
							feature);

					dayWriter.write(
							timeStampAdapter,
							feature);

					pt = geomFactory.createPoint(new Coordinate(
							-50,
							-50));
					featureTimeRangeBuilder.add(pt);
					featureTimeRangeBuilder.add(cal.getTime());
					cal.roll(
							Calendar.MINUTE,
							5);
					featureTimeRangeBuilder.add(cal.getTime());
					feature = featureTimeRangeBuilder.buildFeature("outlier1timerange");
					yearWriter.write(
							timeStampAdapter,
							feature);

					monthWriter.write(
							timeStampAdapter,
							feature);

					dayWriter.write(
							timeStampAdapter,
							feature);
					pt = geomFactory.createPoint(new Coordinate(
							50,
							50));
					featureTimeRangeBuilder.add(pt);
					featureTimeRangeBuilder.add(cal.getTime());
					cal.roll(
							Calendar.MINUTE,
							5);
					featureTimeRangeBuilder.add(cal.getTime());
					feature = featureTimeRangeBuilder.buildFeature("outlier2timerange");
					yearWriter.write(
							timeStampAdapter,
							feature);

					monthWriter.write(
							timeStampAdapter,
							feature);

					dayWriter.write(
							timeStampAdapter,
							feature);
				}
			}
		}
		final Map<String, Serializable> config = new HashMap<String, Serializable>();
		config.put(
				GeoWavePluginConfig.GEOWAVE_NAMESPACE_KEY,
				TEST_NAMESPACE);
		config.put(
				BasicAccumuloOperations.INSTANCE_CONFIG_NAME,
				accumuloInstance);
		config.put(
				BasicAccumuloOperations.PASSWORD_CONFIG_NAME,
				accumuloPassword);
		config.put(
				BasicAccumuloOperations.USER_CONFIG_NAME,
				accumuloUser);
		config.put(
				BasicAccumuloOperations.ZOOKEEPER_CONFIG_NAME,
				zookeeper);
		geowaveGtDataStore = new GeoWaveGTDataStore(
				new GeoWavePluginConfig(
						new AccumuloStoreFactoryFamily(),
						config) {
					@Override
					public IndexQueryStrategySPI getIndexQueryStrategy() {
						return new IndexQueryStrategySPI() {

							@Override
							public CloseableIterator<Index<?, ?>> getIndices(
									final Map<ByteArrayId, DataStatistics<SimpleFeature>> stats,
									final BasicQuery query,
									final CloseableIterator<Index<?, ?>> indices ) {
								final ServiceLoader<IndexQueryStrategySPI> ldr = ServiceLoader.load(IndexQueryStrategySPI.class);
								final Iterator<IndexQueryStrategySPI> it = ldr.iterator();
								final List<Index<?, ?>> indexList = new ArrayList<Index<?, ?>>();
								while (indices.hasNext()) {
									indexList.add(indices.next());
								}
								while (it.hasNext()) {
									final IndexQueryStrategySPI strategy = it.next();
									final CloseableIterator<Index<?, ?>> indexStrategyIt = strategy.getIndices(
											stats,
											query,
											new CloseableIterator.Wrapper<Index<?, ?>>(
													indexList.iterator()));
									Assert.assertTrue(
											"Index Strategy '" + strategy.toString() + "' must at least choose one index.",
											indexStrategyIt.hasNext());
								}
								return new CloseableIteratorWrapper<Index<?, ?>>(
										indices,
										(Iterator) Collections.singleton(
												currentGeotoolsIndex).iterator());
							}
						};
					}
				});
	}

	private static Calendar getInitialDayCalendar() {
		final Calendar cal = Calendar.getInstance();
		cal.set(
				Calendar.YEAR,
				MULTI_DAY_YEAR);
		cal.set(
				Calendar.MONTH,
				MULTI_DAY_MONTH);
		cal.set(
				Calendar.HOUR_OF_DAY,
				1);
		cal.set(
				Calendar.MINUTE,
				1);
		cal.set(
				Calendar.SECOND,
				1);
		cal.set(
				Calendar.MILLISECOND,
				0);
		return cal;
	}

	private static Calendar getInitialMonthCalendar() {
		final Calendar cal = Calendar.getInstance();
		cal.set(
				Calendar.YEAR,
				MULTI_MONTH_YEAR);
		cal.set(
				Calendar.DAY_OF_MONTH,
				1);
		cal.set(
				Calendar.HOUR_OF_DAY,
				1);
		cal.set(
				Calendar.MINUTE,
				1);
		cal.set(
				Calendar.SECOND,
				1);
		cal.set(
				Calendar.MILLISECOND,
				0);
		return cal;
	}

	private static Calendar getInitialYearCalendar() {
		final Calendar cal = Calendar.getInstance();
		cal.set(
				Calendar.DAY_OF_MONTH,
				1);
		cal.set(
				Calendar.MONTH,
				1);
		cal.set(
				Calendar.HOUR_OF_DAY,
				1);
		cal.set(
				Calendar.MINUTE,
				1);
		cal.set(
				Calendar.SECOND,
				1);
		cal.set(
				Calendar.MILLISECOND,
				0);
		return cal;
	}

	private static void ingestTimeRangeData(
			final Calendar cal,
			final IndexWriter[] writers,
			final SimpleFeatureBuilder featureTimeRangeBuilder,
			final int min,
			final int max,
			final int field,
			final String name ) {
		final GeometryFactory geomFactory = new GeometryFactory();
		final int midPoint = (int) Math.floor((min + max) / 2.0);
		cal.set(
				field,
				min);
		featureTimeRangeBuilder.add(geomFactory.createPoint(new Coordinate(
				0,
				0)));
		featureTimeRangeBuilder.add(cal.getTime());
		cal.set(
				field,
				max);
		featureTimeRangeBuilder.add(cal.getTime());
		SimpleFeature feature = featureTimeRangeBuilder.buildFeature(name + ":fullrange");
		for (final IndexWriter writer : writers) {
			writer.write(
					timeRangeAdapter,
					feature);
		}
		cal.set(
				field,
				min);
		featureTimeRangeBuilder.add(geomFactory.createPoint(new Coordinate(
				-0.1,
				-0.1)));
		featureTimeRangeBuilder.add(cal.getTime());
		cal.set(
				field,
				midPoint);
		featureTimeRangeBuilder.add(cal.getTime());
		feature = featureTimeRangeBuilder.buildFeature(name + ":firsthalfrange");
		for (final IndexWriter writer : writers) {
			writer.write(
					timeRangeAdapter,
					feature);
		}
		featureTimeRangeBuilder.add(geomFactory.createPoint(new Coordinate(
				0.1,
				0.1)));
		featureTimeRangeBuilder.add(cal.getTime());
		cal.set(
				field,
				max);

		featureTimeRangeBuilder.add(cal.getTime());
		feature = featureTimeRangeBuilder.buildFeature(name + ":secondhalfrange");
		for (final IndexWriter writer : writers) {
			writer.write(
					timeRangeAdapter,
					feature);
		}
	}

	private void testQueryMultipleBins(
			final Calendar cal,
			final int field,
			final int min,
			final int max,
			final QueryOptions options,
			final String name )
			throws IOException,
			CQLException {
		options.setAdapter(timeStampAdapter);
		cal.set(
				field,
				min);
		Date startOfQuery = cal.getTime();
		final int midPoint = (int) Math.floor((min + max) / 2.0);
		cal.set(
				field,
				midPoint);
		Date endOfQuery = cal.getTime();

		testQueryMultipleBinsGivenDateRange(
				options,
				name,
				min,
				midPoint,
				startOfQuery,
				endOfQuery);
		cal.set(
				field,
				midPoint);
		startOfQuery = cal.getTime();
		cal.set(
				field,
				max);
		endOfQuery = cal.getTime();

		testQueryMultipleBinsGivenDateRange(
				options,
				name,
				midPoint,
				max,
				startOfQuery,
				endOfQuery);
	}

	private void testQueryMultipleBinsGivenDateRange(
			final QueryOptions options,
			final String name,
			final int minExpectedResult,
			final int maxExpectedResult,
			final Date startOfQuery,
			final Date endOfQuery )
			throws CQLException,
			IOException {
		final Set<String> fidExpectedResults = new HashSet<String>(
				(maxExpectedResult - minExpectedResult) + 1);
		for (int i = minExpectedResult; i <= maxExpectedResult; i++) {
			fidExpectedResults.add(name + ":" + i);
		}
		testQueryGivenDateRange(
				options,
				name,
				fidExpectedResults,
				startOfQuery,
				endOfQuery,
				timeStampAdapter.getAdapterId().getString(),
				"timestamp",
				"timestamp");
	}

	private void testQueryGivenDateRange(
			final QueryOptions options,
			final String name,
			final Set<String> fidExpectedResults,
			final Date startOfQuery,
			final Date endOfQuery,
			final String adapterId,
			final String startTimeAttribute,
			final String endTimeAttribute )
			throws CQLException,
			IOException {
		final String cqlPredicate = "BBOX(\"geo\",-1,-1,1,1) AND \"" + startTimeAttribute + "\" <= '" + CQL_DATE_FORMAT.format(endOfQuery) + "' AND \"" + endTimeAttribute + "\" >= '" + CQL_DATE_FORMAT.format(startOfQuery) + "'";
		final Set<String> fidResults = new HashSet<String>();
		try (CloseableIterator<SimpleFeature> it = dataStore.query(
				options,
				new SpatialTemporalQuery(
						startOfQuery,
						endOfQuery,
						new GeometryFactory().toGeometry(new Envelope(
								-1,
								1,
								-1,
								1))))) {
			while (it.hasNext()) {
				final SimpleFeature feature = it.next();
				fidResults.add(feature.getID());
			}
		}
		assertFidsMatchExpectation(
				name,
				fidExpectedResults,
				fidResults);

		final Set<String> geotoolsFidResults = new HashSet<String>();
		// now make sure geotools results match
		try (final SimpleFeatureIterator features = geowaveGtDataStore.getFeatureSource(
				adapterId).getFeatures(
				CQL.toFilter(cqlPredicate)).features()) {
			while (features.hasNext()) {
				final SimpleFeature feature = features.next();
				geotoolsFidResults.add(feature.getID());
			}
		}
		assertFidsMatchExpectation(
				name,
				fidExpectedResults,
				geotoolsFidResults);
	}

	private void assertFidsMatchExpectation(
			final String name,
			final Set<String> fidExpectedResults,
			final Set<String> fidResults ) {
		Assert.assertEquals(
				"Expected result count does not match actual result count for " + name,
				fidExpectedResults.size(),
				fidResults.size());
		final Iterator<String> it = fidExpectedResults.iterator();
		while (it.hasNext()) {
			final String expectedFid = it.next();
			Assert.assertTrue(
					"Cannot find result for " + expectedFid,
					fidResults.contains(expectedFid));
		}
	}

	@Test
	public void testQueryMultipleBinsDay()
			throws IOException,
			CQLException {
		final QueryOptions options = new QueryOptions();
		options.setIndex(DAY_INDEX);
		currentGeotoolsIndex = DAY_INDEX;
		final Calendar cal = getInitialDayCalendar();
		testQueryMultipleBins(
				cal,
				Calendar.DAY_OF_MONTH,
				cal.getActualMinimum(Calendar.DAY_OF_MONTH),
				cal.getActualMaximum(Calendar.DAY_OF_MONTH),
				options,
				"day");
	}

	@Test
	public void testQueryMultipleBinsMonth()
			throws IOException,
			CQLException {
		final QueryOptions options = new QueryOptions();
		options.setIndex(MONTH_INDEX);
		currentGeotoolsIndex = MONTH_INDEX;
		final Calendar cal = getInitialMonthCalendar();
		testQueryMultipleBins(
				cal,
				Calendar.MONTH,
				cal.getActualMinimum(Calendar.MONTH),
				cal.getActualMaximum(Calendar.MONTH),
				options,
				"month");

	}

	@Test
	public void testQueryMultipleBinsYear()
			throws IOException,
			CQLException {
		final QueryOptions options = new QueryOptions();
		options.setIndex(YEAR_INDEX);
		currentGeotoolsIndex = YEAR_INDEX;
		final Calendar cal = getInitialYearCalendar();

		testQueryMultipleBins(
				cal,
				Calendar.YEAR,
				MULTI_YEAR_MIN,
				MULTI_YEAR_MAX,
				options,
				"year");
	}

	private void testTimeRangeAcrossBins(
			final Calendar cal,
			final int field,
			final int min,
			final int max,
			final QueryOptions options,
			final String name )
			throws IOException,
			CQLException {
		cal.set(
				field,
				min);
		Date startOfQuery = cal.getTime();
		final int midPoint = (int) Math.floor((min + max) / 2.0);
		cal.set(
				field,
				midPoint - 1);
		Date endOfQuery = cal.getTime();
		Set<String> fidExpectedResults = new HashSet<String>();
		fidExpectedResults.add(name + ":fullrange");
		fidExpectedResults.add(name + ":firsthalfrange");

		testQueryGivenDateRange(
				options,
				name,
				fidExpectedResults,
				startOfQuery,
				endOfQuery,
				timeRangeAdapter.getAdapterId().getString(),
				"startTime",
				"endTime");

		cal.set(
				field,
				midPoint + 1);
		startOfQuery = cal.getTime();
		cal.set(
				field,
				max);
		endOfQuery = cal.getTime();
		fidExpectedResults = new HashSet<String>();
		fidExpectedResults.add(name + ":fullrange");
		fidExpectedResults.add(name + ":secondhalfrange");

		testQueryGivenDateRange(
				options,
				name,
				fidExpectedResults,
				startOfQuery,
				endOfQuery,
				timeRangeAdapter.getAdapterId().getString(),
				"startTime",
				"endTime");

		cal.set(
				field,
				min);
		startOfQuery = cal.getTime();
		cal.set(
				field,
				max);
		endOfQuery = cal.getTime();

		fidExpectedResults.add(name + ":fullrange");
		fidExpectedResults.add(name + ":firsthalfrange");
		fidExpectedResults.add(name + ":secondhalfrange");
		testQueryGivenDateRange(
				options,
				name,
				fidExpectedResults,
				startOfQuery,
				endOfQuery,
				timeRangeAdapter.getAdapterId().getString(),
				"startTime",
				"endTime");
	}

	@Test
	public void testTimeRangeAcrossBinsDay()
			throws IOException,
			CQLException {
		final QueryOptions options = new QueryOptions();
		options.setIndex(DAY_INDEX);
		currentGeotoolsIndex = DAY_INDEX;
		options.setAdapter(timeRangeAdapter);
		final Calendar cal = getInitialDayCalendar();
		testTimeRangeAcrossBins(
				cal,
				Calendar.DAY_OF_MONTH,
				cal.getActualMinimum(Calendar.DAY_OF_MONTH),
				cal.getActualMaximum(Calendar.DAY_OF_MONTH),
				options,
				"day");
	}

	@Test
	public void testTimeRangeAcrossBinsMonth()
			throws IOException,
			CQLException {
		final QueryOptions options = new QueryOptions();
		options.setIndex(MONTH_INDEX);
		currentGeotoolsIndex = MONTH_INDEX;
		options.setAdapter(timeRangeAdapter);
		final Calendar cal = getInitialMonthCalendar();
		testTimeRangeAcrossBins(
				cal,
				Calendar.MONTH,
				cal.getActualMinimum(Calendar.MONTH),
				cal.getActualMaximum(Calendar.MONTH),
				options,
				"month");
	}

	@Test
	public void testTimeRangeAcrossBinsYear()
			throws IOException,
			CQLException {
		final QueryOptions options = new QueryOptions();
		options.setIndex(YEAR_INDEX);
		currentGeotoolsIndex = YEAR_INDEX;
		options.setAdapter(timeRangeAdapter);
		final Calendar cal = getInitialYearCalendar();
		testTimeRangeAcrossBins(
				cal,
				Calendar.YEAR,
				MULTI_YEAR_MIN,
				MULTI_YEAR_MAX,
				options,
				"year");

	}
}
