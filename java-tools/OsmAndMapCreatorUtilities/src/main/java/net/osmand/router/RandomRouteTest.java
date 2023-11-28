package net.osmand.router;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import net.osmand.ResultMatcher;
import net.osmand.binary.BinaryIndexPart;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapRouteReaderAdapter;
import net.osmand.binary.RouteDataObject;
import net.osmand.util.MapUtils;
import org.apache.commons.logging.Log;

import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.obf.preparation.DBDialect;

public class RandomRouteTest {
	private class TestConfig {
		// optional predefined routes, each in the url-format (imply ITERATIONS=0)
		private final String[] PREDEFINED_TESTS = {
//				"https://test.osmand.net/map/?start=48.211348,24.478998&finish=48.172382,24.421492&type=osmand&profile=bicycle&params=bicycle,height_obstacles#14/48.1852/24.4208",
//				"https://osmand.net/map/?start=50.450128,30.535611&finish=50.460479,30.589365&via=50.452647,30.588330&type=osmand&profile=car#14/50.4505/30.5511",
//				"start=48.211348,24.478998&finish=48.172382,24.421492&type=osmand&profile=bicycle&params=bicycle,height_obstacles",
//				"start=50.450128,30.535611&finish=50.460479,30.589365&via=50.452647,30.588330&profile=car",
//				"start=50.450128,30.535611&finish=50.460479,30.589365&via=1,2;3,4;5,6&profile=car",
//				"start=L,L&finish=L,L&via=L,L;L,L&profile=pedestrian&params=height_obstacles" // example
		};

		// random tests settings
		private final int ITERATIONS = 10; // number of random routes
		private final int MAX_INTER_POINTS = 2; // 0-2 intermediate points
		private final int MIN_DISTANCE_KM = 50; // min distance between start and finish
		private final int MAX_DISTANCE_KM = 100; // max distance between start and finish
		private final int MAX_SHIFT_ALL_POINTS_M = 500; // shift LatLon of all points by 0-500 meters
		private final String[] RANDOM_PROFILES = { // randomly selected profiles[,params] for each iteration
				"car",
				"bicycle",
				"bicycle,height_obstacles",
//				"bicycle,driving_style_prefer_unpaved,driving_style_balance:false,height_obstacles",
		};
	}

	private class TestResult {
		private String type;
		private double cost;
		private int runTime; // ms
		private int visitedSegments;
		// TODO distance, geometry, etc
		private TestEntry parent; // ref to start, finish, etc

		public String toString() {
			return parent.toURL(type);
		}
	}

	private class TestEntry {
		private LatLon start;
		private LatLon finish;
		private List<LatLon> via = new ArrayList<>(); // interpoints

		private String profile = "car";
		private List<String> params = new ArrayList<>();

		private List<TestResult> results = new ArrayList<>();

		public String toString() {
			return toURL("osrm");
		}

		private String toURL(String type) {
			String START = String.format("%f,%f", start.getLatitude(), start.getLongitude());
			String FINISH = String.format("%f,%f", finish.getLatitude(), finish.getLongitude());
			String TYPE = type == null ? "osmand" : type;
			String PROFILE = profile;
			String GO = String.format(
					"10/%f/%f",
					(start.getLatitude() + finish.getLatitude()) / 2,
					(start.getLongitude() + finish.getLongitude()) / 2
			);

			String hasVia = via.size() > 0 ? "&via=" : "";

			List<String> viaList = new ArrayList<>();
			via.forEach(ll -> viaList.add(String.format("%f,%f", ll.getLatitude(), ll.getLongitude())));
			String VIA = String.join(";", viaList);

			String hasParams = params.size() > 0 ? "&params=" : "";
			String PARAMS = String.join(",", params); // site will fix it to "profile,params"

			return String.format(
					"https://test.osmand.net/map/?start=%s&finish=%s%s%s&type=%s&profile=%s%s%s#%s",
					START, FINISH, hasVia, VIA, TYPE, PROFILE, hasParams, PARAMS, GO
			);
		}
	}

	private TestConfig config;
	private List<TestEntry> testList = new ArrayList<>();
	private List<BinaryMapIndexReader> obfReaders = new ArrayList<>();
	private HashMap<String, Connection> hhConnections = new HashMap<>(); // [Profile]
	private final Log LOG = PlatformUtil.getLog(RandomRouteTest.class);

	public static void main(String[] args) throws Exception {
		RandomRouteTest test = new RandomRouteTest();

		File obfDirectory = new File(args.length == 0 ? "." : args[0]); // args[0] is a path to *.obf and hh-files

//		test.initHHsqliteConnections(obfDirectory, HHRoutingDB.EXT);
		test.initObfReaders(obfDirectory);
		test.generateTestList();
	}

//	private static RoutingContext hhContext;
//	private static HHRoutePlanner hhPlanner;
//	private static BinaryRoutePlanner brPlanner;

//		// use HHRoutingPrepareContext to list *.obf and parse profile/params
//		TestPrepareContext prepareContext = new TestPrepareContext(obfDirectory, ROUTING_PROFILE, ROUTING_PARAMS[0].split(","));
//
//		// run garbage collector, return ctx TODO does it need to use force = true every cycle?
//		hhContext = prepareContext.gcMemoryLimitToUnloadAll(hhContext, null, hhContext == null);
//
//		// hhFile as SQLITE database now, but will be changed to obf-data later
//		Connection conn = DBDialect.SQLITE.getDatabaseConnection(hhFile.getAbsolutePath(), LOG);
//		// ready to use HHRoutePlanner class
//		hhPlanner = HHRoutePlanner.create(hhContext, new HHRoutingDB(conn));
//
//		HHRouteDataStructure.HHRoutingConfig hhConfig = new HHRouteDataStructure.HHRoutingConfig().astar(0);
////		HHRouteDataStructure.HHRoutingConfig hhConfig = new HHRouteDataStructure.HHRoutingConfig().dijkstra(0);
//		// run test HH-routing
//		HHRouteDataStructure.HHNetworkRouteRes hh = hhPlanner.runRouting(START, FINISH, hhConfig);

	////////////// TODO need fresh RoutingContext for next use! How to reset it??? //////////////////
//		hhContext = hhPrepareContext.gcMemoryLimitToUnloadAll(hhContext, null, true);
//		hhContext.routingTime = 0;
//
//		// use BinaryRoutePlanner as default route frontend
//		RoutePlannerFrontEnd router = new RoutePlannerFrontEnd();
//		// run test BinaryRoutePlanner TODO is it correct to use hhContext here?
//		List<RouteSegmentResult> routeSegments = router.searchRoute(hhContext, START, FINISH, null);

//	private static class TestPrepareContext extends HHRoutingPrepareContext {
//		public TestPrepareContext(File obfFile, String routingProfile, String... profileSettings) {
//			super(obfFile, routingProfile, profileSettings);
//		}
//
//		@Override
//		public RoutingConfiguration getRoutingConfig() {
//			RoutingConfiguration config = super.getRoutingConfig();
//			config.heuristicCoefficient = 1; // Binary A*
////			config.planRoadDirection = 1;
//			return config;
//		}
//	}

	private void initObfReaders(File obfDirectory) throws IOException {
		List<File> obfFiles = new ArrayList<>();

		if (obfDirectory.isDirectory()) {
			for (File f : obfDirectory.listFiles()) {
				if (f.isFile() && f.getName().endsWith(".obf")) {
					obfFiles.add(f);
				}
			}
		} else {
			obfFiles.add(obfDirectory);
		}

		// sort files by name to improve pseudo-random reproducibility
		obfFiles.sort((f1, f2) -> f1.getName().compareTo(f2.getName()));

		for (File source : obfFiles) {
			System.out.printf("Use OBF %s...\n", source.getName());
			obfReaders.add(new BinaryMapIndexReader(new RandomAccessFile(source, "r"), source));
		}
	}

	private void initHHsqliteConnections(File obfDirectory, String ext) throws SQLException {
		List<File> sqliteFiles = new ArrayList<>();

		if (obfDirectory.isDirectory()) {
			for (File f : obfDirectory.listFiles()) {
				if (f.isFile() && f.getName().endsWith(ext)) {
					sqliteFiles.add(f);
				}
			}
		}

		// sort files by name to improve pseudo-random reproducibility
		sqliteFiles.sort((f1, f2) -> f1.getName().compareTo(f2.getName()));

		for (File source : sqliteFiles) {
			String[] parts = source.getName().split("[_.]"); // Maps_PROFILE.hhdb
			if (parts.length > 2) {
				String profile = parts[parts.length - 2];
				System.out.printf("Use HH (%s) %s...\n", profile, source.getName());
				hhConnections.put(profile, DBDialect.SQLITE.getDatabaseConnection(source.getAbsolutePath(), LOG));
			}
		}
	}

	private void generateTestList() {
		if (config.PREDEFINED_TESTS.length > 0) {
			parsePredefinedTests();
		} else {
			try {
				generateRandomTests();
			} catch (IOException e) {
				throw new IllegalStateException("generateRandomTests()");
			}
		}
	}

	private void parsePredefinedTests() {
		for (String url : config.PREDEFINED_TESTS) {
			TestEntry entry = new TestEntry();
			String opts = url.replaceAll(".*\\?", "").replaceAll("#.*", "");
			if (opts.contains("&")) {
				for (String keyval : opts.split("&")) {
					if (keyval.contains("=")) {
						String k = keyval.split("=")[0];
						String v = keyval.split("=")[1];
						if ("profile".equals(k)) { // profile=string
							entry.profile = v;
						} else if ("start".equals(k) && v.contains(",")) { // start=L,L
							double lat = Double.parseDouble(v.split(",")[0]);
							double lon = Double.parseDouble(v.split(",")[1]);
							entry.start = new LatLon(lat, lon);
						} else if ("finish".equals(k) && v.contains(",")) { // finish=L,L
							double lat = Double.parseDouble(v.split(",")[0]);
							double lon = Double.parseDouble(v.split(",")[1]);
							entry.finish = new LatLon(lat, lon);
						} else if ("via".equals(k)) { // via=L,L;L,L...
							for (String ll : v.split(";")) {
								if (ll.contains(",")) {
									double lat = Double.parseDouble(ll.split(",")[0]);
									double lon = Double.parseDouble(ll.split(",")[1]);
									entry.via.add(new LatLon(lat, lon));
								}
							}
						} else if ("params".equals(k)) { // params=string,string...
							for (String param : v.split(",")) {
								if (entry.profile.equals(param)) { // /profile/,param1,param2 -> param1,param2
									continue;
								}
								entry.params.add(param);
							}
						}
					}
				}
			}
			if (entry.start != null && entry.finish != null) {
				System.err.printf("+ %s\n", entry);
				testList.add(entry);
			}
		}
	}

	// return fixed (pseudo) random int >=0 and < bound
	// use current week number + action (enum) + i + j as the random seed
	private int fixedRandom(int bound, randomActions action, long i, long j) {
		final long week = Calendar.getInstance().get(Calendar.WEEK_OF_YEAR); // 01-52
		final long seed = (week << 56) + (action.ordinal() << 48) + (i << 1) + j;
		return bound > 0 ? Math.abs(new Random(seed).nextInt()) % bound : 0;
	}

	private enum randomActions {
		HIGHWAY_SKIP_DIV,
		HIGHWAY_TO_POINT,
		N_INTER_POINTS,
		GET_START,
		GET_POINTS,
		GET_PROFILE,
		SHIFT_METERS,
	}

	private void generateRandomTests() throws IOException {
		List<LatLon> randomPoints = new ArrayList<>();
		Set<LatLon> avoidDupes = new HashSet<>();

		int replenishCounter = 0;
		final int REPLENISH_LIMIT = 10; // avoid looping in case of bad config
		replenishRandomPoints(randomPoints); // read initial random points list

		for (int i = 0; i < config.ITERATIONS; i++) {
			TestEntry entry = new TestEntry();

			// 1) select profile,params
			if (config.RANDOM_PROFILES.length > 0) {
				boolean isProfileName = true; // "profile[,params]"
				int profileIndex = fixedRandom(config.RANDOM_PROFILES.length, randomActions.GET_PROFILE, i, 0);
				for (String param : config.RANDOM_PROFILES[profileIndex].split(",")) {
					if (isProfileName) {
						entry.profile = param;
						isProfileName = false;
					} else {
						entry.params.add(param);
					}
				}
			}

			// 2) select start
			for (int j = 0; j < randomPoints.size(); j++) {
				int startIndex = fixedRandom(randomPoints.size(), randomActions.GET_START, i, j);
				entry.start = randomPoints.get(startIndex);
				if (false == avoidDupes.contains(entry.start)) {
					break;
				}
			}
			avoidDupes.add(entry.start);

			// 3) select via (inter points) and finish points, restart if no suitable points found
			int nInterpoints = fixedRandom(config.MAX_INTER_POINTS + 1, randomActions.N_INTER_POINTS, i, 0);
			int nNextPoints = 1 + nInterpoints; // as minimum, the one (finish) point must be added
			int minDistanceKm = config.MIN_DISTANCE_KM / nNextPoints;
			int maxDistanceKm = config.MAX_DISTANCE_KM / nNextPoints;
			LatLon prevPoint = entry.start;

			boolean restart = false;
			while (nNextPoints-- > 0) {
				LatLon point = null;
				boolean pointFound = false;
				for (int j = 0; j < randomPoints.size(); j++) {
					int pointIndex = fixedRandom(randomPoints.size(), randomActions.GET_POINTS, i, nNextPoints + j);
					point = randomPoints.get(pointIndex);
					double km = MapUtils.getDistance(prevPoint, point) / 1000;
					if (km >= minDistanceKm && km <= maxDistanceKm && false == avoidDupes.contains((point))) {
						pointFound = true;
						break;
					}
				}
				if (pointFound == false) {
					restart = true;
					break;
				} else if (point != null) {
					prevPoint = point;
					avoidDupes.add(point);
					if (nNextPoints > 0) {
						entry.via.add(point);
					} else {
						entry.finish = point;
					}
				} else {
					throw new IllegalStateException("unable to find points after start");
				}
			}

			if (restart) {
				if (replenishCounter++ >= REPLENISH_LIMIT) {
					throw new IllegalStateException("point replenish limit reached");
				}
				replenishRandomPoints(randomPoints); // read more points
				System.err.printf("Read more points i=%d size=%d\n", i, randomPoints.size());
				i--; // retry
				continue;
			}

			// 4) shift points from their exact LatLon
			if (config.MAX_SHIFT_ALL_POINTS_M > 0) {
				class Shifter {
					LatLon shiftLatLon(LatLon ll, int i, int j) {
						int meters = fixedRandom(config.MAX_SHIFT_ALL_POINTS_M, randomActions.SHIFT_METERS, i, j);
						double shift = meters / 111_000F; // enough approx meters to lat/lon
						double lat = ll.getLatitude() + shift;
						double lon = ll.getLongitude() + shift;
						return new LatLon(lat, lon);
					}
				}
				int n = 0;
				Shifter shifter = new Shifter();
				entry.start = shifter.shiftLatLon(entry.start, i, n++);
				entry.finish = shifter.shiftLatLon(entry.finish, i, n++);
				for (int j=0; j < entry.via.size(); j++) {
					entry.via.set(j, shifter.shiftLatLon(entry.via.get(j), i, n++));
				}
			}

			// 5) finally, add TestEntry to the testList
			if (entry.start != null && entry.finish != null) {
				System.err.printf("+ %s\n", entry);
				replenishCounter = 0;
				testList.add(entry);
			}
		}
	}

	private class Counter {
		private int value;
	}

	private void getObfHighwayRoadRandomPoints(
			BinaryMapIndexReader index, List<LatLon> randomPoints, int limit, int seed) throws IOException {
		Counter added = new Counter();

		// pointSkipDivisor used to hop over sequential points to enlarge distances between them
		// The idea is to read only 1 of 100 points, but the different 1 each method call (seed)
		int pointSkipDivisor = 1 + fixedRandom(100, randomActions.HIGHWAY_SKIP_DIV, 0, seed);

		for (BinaryIndexPart p : index.getIndexes()) {
			if (p instanceof BinaryMapRouteReaderAdapter.RouteRegion) {
				List<BinaryMapRouteReaderAdapter.RouteSubregion> regions =
						index.searchRouteIndexTree(
								BinaryMapIndexReader.buildSearchRequest(
										0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 15, null
								),
								((BinaryMapRouteReaderAdapter.RouteRegion) p).getSubregions()
						);
				index.loadRouteIndexData(regions, new ResultMatcher<RouteDataObject>() {
					@Override
					public boolean publish(RouteDataObject obj) {
						for (int i = 0; i < obj.getTypes().length; i++) {
							BinaryMapRouteReaderAdapter.RouteTypeRule rr =
									obj.region.quickGetEncodingRule(obj.getTypes()[i]);
							// use highway=primary|secondary as a universally suitable way for any profile
							if ("highway".equals(rr.getTag()) &&
									("primary".equals(rr.getValue()) || "secondary".equals(rr.getValue()))
							) {
								final int SHIFT_ID = 6;
								final long osmId = obj.getId() >> SHIFT_ID;
								if (osmId % pointSkipDivisor == 0) {
									int nPoints = obj.pointsX.length;
									// use object id and seed (number of class randomPoints) as a unique random seed
									int pointIndex = fixedRandom(nPoints, randomActions.HIGHWAY_TO_POINT, osmId, seed);
									double lat = MapUtils.get31LatitudeY(obj.pointsY[pointIndex]);
									double lon = MapUtils.get31LongitudeX(obj.pointsX[pointIndex]);
									randomPoints.add(new LatLon(lat, lon));
									added.value++;
									break;
								}
							}
						}
						return true;
					}

					@Override
					public boolean isCancelled() {
						return added.value > limit;
					}
				});
			}
		}
	}

	private void replenishRandomPoints(List<LatLon> randomPoints) throws IOException {
		if (obfReaders.size() == 0) {
			throw new IllegalStateException("empty obfReaders");
		}

		int seed = randomPoints.size(); // second random seed (unique for every method call)

		int pointsToRead = config.ITERATIONS * 10; // read up to 10 x ITERATIONS points every time
		int pointsPerObf = pointsToRead / obfReaders.size(); // how many to read per one obf
		pointsPerObf = pointsPerObf > 10 ? pointsPerObf : 10; // as minimum as 10

		for (int o = 0; o < obfReaders.size(); o++) {
			getObfHighwayRoadRandomPoints(obfReaders.get(o), randomPoints, pointsPerObf, seed);
		}
	}

	RandomRouteTest() {
		this.config = new TestConfig();
	}
}

// TODO RR-1 Test height_obstacles uphill (Petros) for the up-vs-down bug
// TODO RR-2 Equalise Binary Native lib call (interpoints==0 vs interpoints>0)
// TODO RR-3 MapCreator - parse start/finish from url, share route url, route hotkeys (Ctrl + 1/2/3/4/5)
// TODO RR-4 fix start segment calc: https://osmand.net/map/?start=50.450128,30.535611&finish=50.460479,30.589365&via=50.452647,30.588330&type=osmand&profile=car#14/50.4505/30.5511

// BinaryRoutePlanner.TRACE_ROUTING = s.getRoad().getId() / 64 == 451406223; // 233801367L;
