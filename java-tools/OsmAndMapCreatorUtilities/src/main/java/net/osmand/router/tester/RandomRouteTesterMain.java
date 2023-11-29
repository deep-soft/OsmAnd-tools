package net.osmand.router.tester;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import org.apache.commons.logging.Log;

import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.obf.preparation.DBDialect;
import net.osmand.router.HHRoutingDB;
import net.osmand.PlatformUtil;

class RandomRouteTesterMain {
	public static void main(String[] args) throws Exception {
		RandomRouteTesterMain test = new RandomRouteTesterMain();
		RandomRouteTesterConfig config = new RandomRouteTesterConfig();

		File obfDirectory = new File(args.length == 0 ? "." : args[0]); // args[0] is a path to *.obf and hh-files

		test.initHHsqliteConnections(obfDirectory, HHRoutingDB.EXT);
		test.initObfReaders(obfDirectory);

		test.testList = config.generateTestList(test.obfReaders);
	}

	private List<RandomRouteTesterEntry> testList = new ArrayList<>();
	private List<BinaryMapIndexReader> obfReaders = new ArrayList<>();
	private HashMap<String, Connection> hhConnections = new HashMap<>(); // [Profile]
	private final Log LOG = PlatformUtil.getLog(RandomRouteTesterMain.class);

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
}

// TODO RR-1 Test height_obstacles uphill (Petros) for the up-vs-down bug
// TODO RR-2 Equalise Binary Native lib call (interpoints==0 vs interpoints>0)
// TODO RR-3 MapCreator - parse start/finish from url, share route url, route hotkeys (Ctrl + 1/2/3/4/5)
// TODO RR-4 fix start segment calc: https://osmand.net/map/?start=50.450128,30.535611&finish=50.460479,30.589365&via=50.452647,30.588330&type=osmand&profile=car#14/50.4505/30.5511

// BinaryRoutePlanner.TRACE_ROUTING = s.getRoad().getId() / 64 == 451406223; // 233801367L;
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
