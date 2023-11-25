package net.osmand.router;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import net.osmand.binary.BinaryMapIndexReader;
import org.apache.commons.logging.Log;

import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.obf.preparation.DBDialect;
import org.bouncycastle.util.test.Test;

public class RandomRouteTest {
	private class TestConfig {
		private int ITERATIONS = 10; // number of random routes
		private int MAX_INTER_POINTS = 2; // 0-2 intermediate points
		private int MAX_DISTANCE_KM = 50; // 0-50 km distance between start and finish
		private int MAX_DEVIATE_START_FINISH_M = 100; // 0-100 meters start/finish deviation from way-nodes
		private Map<String, String[]> profiles = new HashMap<>() {{ // profiles: {"profile:tag"} = [options]
			put("car", new String[0]);
			put("bicycle", new String[0]);
			put("bicycle:elevation", new String[]{"height_obstacles"});
		}};
	}

	private TestConfig config;
	private final Log LOG = PlatformUtil.getLog(RandomRouteTest.class);
	private List<BinaryMapIndexReader> obfReaders = new ArrayList<>();
	private HashMap<String, Connection> hhConnections = new HashMap<>(); // [Profile]

	RandomRouteTest() {
		this.config = new TestConfig();
	}

	private List<BinaryMapIndexReader> getObfReaders() {
		return obfReaders;
	}

	private Connection getHHconnection(String profile) {
		return hhConnections.get(profile);
	}

//	private static LatLon START = new LatLon(48.002242, 11.379100);
//	private static LatLon FINISH = new LatLon(48.201151, 11.771207);

//	private static RoutingContext hhContext;
//	private static HHRoutePlanner hhPlanner;
//	private static BinaryRoutePlanner brPlanner;

	public static void main(String[] args) throws Exception {
		RandomRouteTest test = new RandomRouteTest();

		File obfDirectory = new File(args.length == 0 ? "." : args[0]);
		test.initHHsqliteConnections(obfDirectory, HHRoutingDB.EXT);
		test.initObfReaders(obfDirectory);

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
	}

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
		Collections.sort(obfFiles, (f1, f2) -> f1.getName().compareTo(f2.getName()));

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
		Collections.sort(sqliteFiles, (f1, f2) -> f1.getName().compareTo(f2.getName()));

		for (File source : sqliteFiles) {
			String[] parts = source.getName().split("[_.]"); // Maps_PROFILE.hhdb
			if (parts.length > 2) {
				String profile = parts[parts.length - 2];
				System.out.printf("Use HH (%s) %s...\n", profile, source.getName());
				hhConnections.put(profile, DBDialect.SQLITE.getDatabaseConnection(source.getAbsolutePath(), LOG));
			}
		}
	}

	// return fixed (pseudo) random int >=0 and < bound
	// use current week number + action (enum) + i + j as the random seed
	private static int fixedRandom(int bound, randomActions action, int i, int j) {
		final int week = Calendar.getInstance().get(Calendar.WEEK_OF_YEAR); // 01-52
		final int seed = (week << 24) + (action.ordinal() << 16) + (i << 1) + j;
		return bound > 0 ? new Random(seed).nextInt(bound) : 0;
	}

	private enum randomActions {
		GET_START,
		GET_FINISH,
		GET_PROFILE
	}
}

/*

http://localhost:3000/map/?start=48.002242,11.379100&finish=48.201151,11.771207&type=osmand&profile=car#11/48.1567/11.5315

RandomRouteTest - главный класс (инициализация obf, главный цикл: выбор точек, запуск ротуров, сравнение)

Приватные классы:

RandomRoutePoints

Vik notes:

BinaryInspector.printRouteDetailInfo - считаете все точки в дорогах и можно хоть в память прочитать, хотя каждый раз читать

random.nextInt() - на номер файла
random.nextInt() - на номер дороги в файле
random.nextInt() - на номер отрезка в дороге

OBF.proto
utilities.sh
MainUtilities.java
 */


// TODO RR-1 Test height_obstacles uphill (Petros) for the up-vs-down bug
// TODO RR-2 Equalise Binary Native lib call (interpoints==0 vs interpoints>0)
// TODO RR-3 MapCreator - parse start/finish from url, share route url, route hotkeys (Ctrl + 1/2/3/4/5)
// TODO RR-4 fix start segment calc: https://osmand.net/map/?start=50.450128,30.535611&finish=50.460479,30.589365&via=50.452647,30.588330&type=osmand&profile=car#14/50.4505/30.5511
