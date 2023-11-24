package net.osmand.router;

import java.io.File;
import java.sql.Connection;
import java.util.List;

import org.apache.commons.logging.Log;

import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.obf.preparation.DBDialect;

public class RandomRouteTest {
	private static String ROUTING_PROFILE = "car"; // TODO args/random
	private static String[] ROUTING_PARAMS = new String[]{"height_obstacles"}; // TODO args
	private static LatLon START = new LatLon(48.002242, 11.379100); // TODO random
	private static LatLon FINISH = new LatLon(48.201151, 11.771207); // TODO random

	final static Log LOG = PlatformUtil.getLog(RandomRouteTest.class);

	private static RoutingContext hhContext;
	private static HHRoutePlanner hhPlanner;
	private static BinaryRoutePlanner brPlanner;

	public static void main(String[] args) throws Exception {
		// directory with *.obf and Maps_PROFILE.hhdb (symlink)
		File obfDirectory = new File(args.length == 0 ? "." : args[0]);
		File hhFile = new File(obfDirectory + "/" + "Maps_" + ROUTING_PROFILE + HHRoutingDB.EXT);

		// use HHRoutingPrepareContext to list *.obf and parse profile/params
		HHRoutingPrepareContext hhPrepareContext =
				new HHRoutingPrepareContext(obfDirectory, ROUTING_PROFILE, ROUTING_PARAMS[0].split(","));

		// run garbage collector, return ctx TODO does it need to use force = true every cycle?
		hhContext = hhPrepareContext.gcMemoryLimitToUnloadAll(hhContext, null, hhContext == null);

		// hhFile as SQLITE database now, but will be changed to obf-data later
		Connection conn = DBDialect.SQLITE.getDatabaseConnection(hhFile.getAbsolutePath(), LOG);

		// ready to use HHRoutePlanner class
		hhPlanner = HHRoutePlanner.create(hhContext, new HHRoutingDB(conn));
		// run test HH-routing
		HHRouteDataStructure.HHNetworkRouteRes hh = hhPlanner.runRouting(START, FINISH, null);

		////////////// TODO need fresh RoutingContext for next use! How to reset it??? //////////////////

//		// use BinaryRoutePlanner as default route frontend
//		RoutePlannerFrontEnd router = new RoutePlannerFrontEnd();
//		// run test BinaryRoutePlanner TODO is it correct to use hhContext here?
//		List<RouteSegmentResult> routeSegments = router.searchRoute(hhContext, START, FINISH, null);
	}
}

/*

http://localhost:3000/map/?start=48.002242,11.379100&finish=48.201151,11.771207&type=osmand&profile=car#11/48.1567/11.5315

int ITERATIONS = 10;
int MAX_DISTANCE_KM = 100; // km

int MIN_INTER_POINTS = 0;
int MAX_INTER_POINTS = 2;

RandomRouteTest - главный класс (инициализация obf, главный цикл: выбор точек, запуск ротуров, сравнение)

Приватные классы:

RandomRoutePoints

PseudoRandom: seed = (week_number + action_id + iteration)

Vik notes:

SimpleDateFormat sdf = new SimpleDateFormat("YYYY-MM-ww");
  Date roundToWeek = sdf.parse(sdf.format(new Date()));
  Random r = new Random(roundToWeek.getTime());
  System.out.println(sdf.parse(sdf.format(new Date())));

BinaryInspector.printRouteDetailInfo - считаете все точки в дорогах и можно хоть в память прочитать, хотя каждый раз читать

random.nextInt() - на номер файла
random.nextInt() - на номер дороги в файле
random.nextInt() - на номер отрезка в дороге

OBF.proto
utilities.sh
MainUtilities.java

 */