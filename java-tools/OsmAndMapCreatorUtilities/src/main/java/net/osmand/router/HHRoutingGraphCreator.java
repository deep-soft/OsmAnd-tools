package net.osmand.router;

import static net.osmand.router.HHRoutingUtilities.addWay;
import static net.osmand.router.HHRoutingUtilities.calculateRoutePointInternalId;
import static net.osmand.router.HHRoutingUtilities.getPoint;
import static net.osmand.router.HHRoutingUtilities.makePositiveDir;
import static net.osmand.router.HHRoutingUtilities.saveOsmFile;
import static net.osmand.router.HHRoutingUtilities.visualizeWays;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.logging.Log;

import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.set.TLongSet;
import gnu.trove.set.hash.TLongHashSet;
import net.osmand.PlatformUtil;
import net.osmand.ResultMatcher;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteSubregion;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.osm.edit.Entity;
import net.osmand.router.BinaryRoutePlanner.MultiFinalRouteSegment;
import net.osmand.router.BinaryRoutePlanner.RouteSegment;
import net.osmand.router.BinaryRoutePlanner.RouteSegmentPoint;
import net.osmand.router.HHRoutingPreparationDB.NetworkRouteRegion;
import net.osmand.router.HHRoutingPreparationDB.NetworkDBPoint;
import net.osmand.router.HHRoutingPreparationDB.NetworkDBSegment;
import net.osmand.router.RoutePlannerFrontEnd.RouteCalculationMode;
import net.osmand.router.RoutingConfiguration.Builder;
import net.osmand.router.RoutingConfiguration.RoutingMemoryLimits;
import net.osmand.util.MapUtils;


// TODO 
// 1st phase - investigation
// 1.1 TODO think that island is not possible shortest way to reach boundaries
// 1.2 TODO check toVisitVertices including depth
// 1.3 TODO for long distance causes bugs if (pnt.index != 2005) { 2005-> 1861 } - 3372.75 vs 2598
// 1.4 BinaryRoutePlanner TODO routing 1/-1/0 FIX routing time 7288 / 7088 / 7188 (43.15274, 19.55169 -> 42.955495, 19.0972263)
// 1.5 BinaryRoutePlanner TODO double checkfix correct at all?  https://github.com/osmandapp/OsmAnd/issues/14148
// 1.6 BinaryRoutePlanner TODO ?? we don't stop here in order to allow improve found *potential* final segment - test case on short route
// 1.7 BinaryRoutePlanner TODO test that routing time is different with on & off!

// 2nd phase - improvements
// 2.1 Merge islands that are isolated
// 2.2 Better points distribution (a) island counts b) island border points c) island size)
// 2.3 Print edges info per vertices (stats group 1)   

// 3rd phase - speedup & Test
// 3.1 Speed up processing NL [400 MB - ~ 1-1.5% of World data] - [8 min + 9 min = 17 min]
//     ~ World processing - 1 360 min - ~ 22h ?
//     - don't unload routing (clean up)
// 3.2 Make process parallelized 
// 3.3? Make process rerunable ? (so you could stop any point)
// 3.4 Generate Germany / World for testing speed

// 4th phase - complex routing / data
// 4.1 Implement final routing algorithm including start / end segment search 
// 4.2 Save data structure optimally by access time
// 4.3 Save data structure optimally by size
// 4.4 Implement border crossing issue

// 5 Future
// 5.1 Introduce 3/4 level
// 5.2 Alternative routes
// 5.3 Avoid specific road
// 5.4 Deprioritize or exclude roads
// 5.5 Live data (think about it)
public class HHRoutingGraphCreator {

	final static Log LOG = PlatformUtil.getLog(HHRoutingGraphCreator.class);
	
	final static int PROCESS_SET_NETWORK_POINTS = 1;
	final static int PROCESS_REBUILD_NETWORK_SEGMENTS = 2;
	final static int PROCESS_BUILD_NETWORK_SEGMENTS = 3;
	static int PROCESS = PROCESS_SET_NETWORK_POINTS;
	
	static boolean DEBUG_STORE_ALL_ROADS = false;
	static int DEBUG_LIMIT_START_OFFSET = 0;
	static int DEBUG_LIMIT_PROCESS = -1;
	static int DEBUG_VERBOSE_LEVEL = 0;
	static long startTime = 0;
	
	final static int MEMORY_RELOAD_MB = 1000 ; //
	final static int MEMORY_RELOAD_TIMEOUT_SECONDS = 120;

	
	private List<File> sources = new ArrayList<File>(); 
	private String ROUTING_PROFILE = "car";
	
	// Constants / Tests for splitting building network points {7,7,7,7} - 50 - 50000
	protected static LatLon EX1 = new LatLon(52.3201813,4.7644685); // 337 - 4
	protected static LatLon EX2 = new LatLon(52.33265, 4.77738); // 301 - 12
	protected static LatLon EX3 = new LatLon(52.2728791, 4.8064803); // 632 - 14
	protected static LatLon EX4 = new LatLon(52.27757, 4.85731); // 218 - 7
	protected static LatLon EX5 = new LatLon(42.78725, 18.95036); // 391 - 8
	protected static LatLon EX = null; // for all - null; otherwise specific point
	
	// heuristics building network points
	private static int[] MAX_VERT_DEPTH_LOOKUP = new int[] {15,10,5} ; //new int[] {7,7,7,7};
	private static int MAX_NEIGHBOORS_N_POINTS = 50;
	private static float MAX_RADIUS_ISLAND = 50000; // max distance from "start point"
	
	public HHRoutingGraphCreator(List<File> sources) {
		this.sources = sources;
	}


	private static File sourceFile() {
		String name = "Montenegro_europe_2.road.obf";
//		name = "Netherlands_europe_2.road.obf";
//		name = "Ukraine_europe_2.road.obf";
//		name = "Germany";
		return new File(System.getProperty("maps.dir"), name);
	}

	
	public static void main(String[] args) throws Exception {
		File obfFile = args.length == 0 ? sourceFile() : new File(args[0]);
		for (String a : args) {
			if (a.equals("--setup-network-points")) {
				PROCESS = PROCESS_SET_NETWORK_POINTS;
			} else if (a.equals("--build-network-shortcuts")) {
				PROCESS = PROCESS_BUILD_NETWORK_SEGMENTS;
			} else if (a.equals("--rebuild-network-shortcuts")) {
				PROCESS = PROCESS_REBUILD_NETWORK_SEGMENTS;
			}
		}
		File folder = obfFile.isDirectory() ? obfFile : obfFile.getParentFile();
		String name = obfFile.getCanonicalFile().getName();
		
		HHRoutingPreparationDB networkDB = new HHRoutingPreparationDB(new File(folder, name + HHRoutingPreparationDB.EXT),
				  PROCESS == PROCESS_SET_NETWORK_POINTS ? HHRoutingPreparationDB.FULL_RECREATE
				: PROCESS == PROCESS_REBUILD_NETWORK_SEGMENTS ? HHRoutingPreparationDB.RECREATE_SEGMENTS
								: HHRoutingPreparationDB.READ);
		List<File> sources = new ArrayList<File>();
		if (obfFile.isDirectory()) {
			for (File f : obfFile.listFiles()) {
				if (!f.getName().endsWith(".obf")) {
					continue;
				}
				sources.add(f);
			}
		} else {
			sources.add(obfFile);
		}
		HHRoutingGraphCreator proc = new HHRoutingGraphCreator(sources);
		if (PROCESS == PROCESS_SET_NETWORK_POINTS) {
			FullNetwork network = proc.collectNetworkPoints(networkDB);
			List<Entity> objects = visualizeWays(network.visualPoints(), network.visualConnections(), 
					network.visitedVertices);
			saveOsmFile(objects, new File(folder, name + ".osm"));
		} else if (PROCESS == PROCESS_BUILD_NETWORK_SEGMENTS) {
			TLongObjectHashMap<NetworkDBPoint> pnts = networkDB.getNetworkPoints(true);
			networkDB.loadNetworkSegments(pnts);
			Collection<Entity> objects = proc.buildNetworkShortcuts(pnts, networkDB);
			saveOsmFile(objects, new File(folder, name + "-hh.osm")); 
		}
		networkDB.close();
	}



	private long lastMemoryReload = System.currentTimeMillis();
	private RoutingContext gcMemoryLimitToUnloadAll(RoutingContext ctx, List<NetworkRouteRegion> subRegions, boolean force) throws IOException {
		long usedMemory = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
		if (force || (
				usedMemory > MEMORY_RELOAD_MB && (System.currentTimeMillis() - lastMemoryReload) > MEMORY_RELOAD_TIMEOUT_SECONDS * 1000)) {
			System.gc();
			usedMemory = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
			List<File> fls = null; 
			if (subRegions != null) {
				fls = new ArrayList<File>();
				for (NetworkRouteRegion r : subRegions) {
					fls.add(r.file);
				}
			}
			ctx = prepareContext(fls, ctx);
			System.gc();
			long nwusedMemory = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;;
			lastMemoryReload = System.currentTimeMillis();
			logf("***** Reload memory used before %d MB - after gc and reload %d MB *****\n", 
					usedMemory, nwusedMemory);
		}
		return ctx;
	}
	
	private static void logf(String string, Object... a) {
		if(startTime == 0) {
			startTime = System.currentTimeMillis();
		}
		String ms = String.format("%3.1fs ", (System.currentTimeMillis() - startTime) / 1000.f);
		System.out.printf(ms + string + "\n", a);
		
	}


	private List<BinaryMapIndexReader> initReaders(List<File> hints) throws IOException {
		List<BinaryMapIndexReader> readers = new ArrayList<BinaryMapIndexReader>();
		for (File source : (hints != null ? hints : sources)) {
			BinaryMapIndexReader reader = new BinaryMapIndexReader(new RandomAccessFile(source, "r"), source);
			readers.add(reader);
		}
		return readers;
	}
	
	private RoutingContext prepareContext(List<File> fileSources, RoutingContext oldCtx) throws IOException {
		List<BinaryMapIndexReader> readers = initReaders(fileSources);
		if (oldCtx != null) {
			for (BinaryMapIndexReader r : oldCtx.map.keySet()) {
				r.close();
			}
		}
		RoutePlannerFrontEnd router = new RoutePlannerFrontEnd();
		Builder builder = RoutingConfiguration.getDefault();
		RoutingMemoryLimits memoryLimit = new RoutingMemoryLimits(RoutingConfiguration.DEFAULT_NATIVE_MEMORY_LIMIT,
				RoutingConfiguration.DEFAULT_NATIVE_MEMORY_LIMIT);
		Map<String, String> map = new TreeMap<String, String>();
		// map.put("avoid_ferries", "true");
		RoutingConfiguration config = builder.build(ROUTING_PROFILE, memoryLimit, map);
		config.planRoadDirection = 1;
		config.heuristicCoefficient = 0; // dijkstra
		return router.buildRoutingContext(config, null, readers.toArray(new BinaryMapIndexReader[readers.size()]), RouteCalculationMode.NORMAL);
	}
	

	//////////////////////////// BUILD NETWORK ISLANDS ////////////////////////

	class NetworkIsland {
		final NetworkIsland parent;
		final RouteSegment start;
		final PriorityQueue<RouteSegment> queue ;
		int index;
		TLongObjectHashMap<RouteSegment> visitedVertices = new TLongObjectHashMap<RouteSegment>();
		TLongObjectHashMap<RouteSegment> toVisitVertices = new TLongObjectHashMap<RouteSegment>();
		
		NetworkIsland(NetworkIsland parent, RouteSegment start) {
			this.parent = parent;
			this.start = start;
			this.queue = parent == null ? null : new PriorityQueue<>(parent.getExpandIslandComparator());
			if (start != null) {
				addSegmentToQueue(start);
			}
		}

		protected Comparator<RouteSegment> getExpandIslandComparator() {
			return parent.getExpandIslandComparator();
		}
		

		private NetworkIsland addSegmentToQueue(RouteSegment s) {
			queue.add(s);
			toVisitVertices.put(calculateRoutePointInternalId(s), s);
			return this;
		}
		
		
		public boolean testIfNetworkPoint(long pntId) {
			if (parent != null) {
				return parent.testIfNetworkPoint(pntId);
			}
			return false;
		}
		
		public boolean testIfPossibleNetworkPoint(long pntId) {
			if (toVisitVertices.containsKey(pntId)) {
				return true;
			}
			if (parent != null) {
				return parent.testIfPossibleNetworkPoint(pntId);
			}
			return false;
		}
		
		public boolean testIfVisited(long pntId) {
			if (visitedVertices.containsKey(pntId)) {
				return true;
			}
			if (parent != null) {
				return parent.testIfVisited(pntId);
			}
			return false;
		}

		public int visitedVerticesSize() {
			return visitedVertices.size() + (parent == null ? 0 : parent.visitedVerticesSize());
		}

		
		public int depth() {
			return 1 + (parent == null ? 0 : parent.depth());
		}

		public void printCurentState(String string, int lvl) {
			printCurentState(string, lvl, "");
		}
		
		public void printCurentState(String string, int lvl, String extra) {
			if (DEBUG_VERBOSE_LEVEL >= (parent == null ? lvl - 1 : lvl)) {
				StringBuilder tabs = new StringBuilder();
				for(int k = 0 ; k < depth(); k++) {
					tabs.append("   ");
				}
				System.out.println(String.format(" %s %-8s %d -> %d %s", tabs.toString(), string, visitedVerticesSize(), toVisitVertices.size(), extra));
			}
		}

		public RoutingContext getCtx() {
			return parent.getCtx();
		}
	}
	
	class FullNetwork extends NetworkIsland {

		RoutingContext ctx;
		// uses global entity by reference
		TLongObjectHashMap<Integer> networkPointsCluster = new TLongObjectHashMap<>();
		List<NetworkIsland> visualClusters = new ArrayList<>();
		FullNetwork(RoutingContext ctx) {
			super(null, null);
			this.ctx = ctx;
		}
		
		public RoutingContext getCtx() {
			return ctx;
		}
		
		protected Comparator<RouteSegment> getExpandIslandComparator() {
			return new Comparator<RouteSegment>() {

				@Override
				public int compare(RouteSegment o1, RouteSegment o2) {
					return ctx.roadPriorityComparator(o1.distanceFromStart, o1.distanceToEnd, o2.distanceFromStart,
							o2.distanceToEnd);
				}
			};
		}
		
		public boolean testIfNetworkPoint(long pntId) {
			if (networkPointsCluster.contains(pntId)) {
				return true;
			}
			return super.testIfNetworkPoint(pntId);
		}
		
		public int visitedVerticesSize() {
			return 0;
		}
		
		public boolean testIfPossibleNetworkPoint(long pntId) {
			return testIfNetworkPoint(pntId);
		}
		
		public void addCluster(NetworkIsland cluster, RouteSegmentPoint centerPoint) {
			// KISS: keep toVisitVertices - empty and use only networkPointsCluster
			if (DEBUG_STORE_ALL_ROADS) {
				visualClusters.add(cluster);
				toVisitVertices.putAll(cluster.toVisitVertices);
			}
			// no need to copy it's done before
			// networkPointsCluster.put(toVisitVertices)
			for (long key : cluster.visitedVertices.keys()) {
				if (visitedVertices.containsKey(key)) {
					throw new IllegalStateException();
				}
				visitedVertices.put(key, cluster.visitedVertices.get(key)); // reduce memory usage
			}
		}
		
		@Override
		public int depth() {
			return 0;
		}

		public TLongObjectHashMap<List<RouteSegment>> visualConnections() {
			TLongObjectHashMap<List<RouteSegment>> conn = new TLongObjectHashMap<>();
			for (NetworkIsland island : visualClusters) {
				List<RouteSegment> lst = new ArrayList<>();
				conn.put(calculateRoutePointInternalId(island.start), lst);
				for (RouteSegment border : island.toVisitVertices.valueCollection()) {
					lst.add(border);
				}
			}
			return conn;
		}
		
		public TLongObjectHashMap<RouteSegment> visualPoints() {
			TLongObjectHashMap<RouteSegment> visualPoints = new TLongObjectHashMap<>();
			visualPoints.putAll(toVisitVertices);
			for (NetworkIsland island : visualClusters) {
				visualPoints.put(calculateRoutePointInternalId(island.start), island.start);
			}
			return visualPoints;
		}

	}
	
	
	private class NetworkCollectPointCtx {
		int totalBorderPoints = 0;
		int maxBorder = 0;
		int minBorder = 10000;
		int maxPnts = 0;
		int minPnts = 10000;
		int isolatedIslands = 0;
		int shortcuts = 0;
		int clusterInd = 0;
		
		HHRoutingPreparationDB networkDB;
		NetworkRouteRegion currentProcessingRegion;
		List<NetworkRouteRegion> routeRegions = new ArrayList<>();
		RoutingContext rctx;
		
		// TODO store network points inside NetworkRouteRegion and retrieve similar to visited points
		TLongObjectHashMap<Integer> networkPointsCluster = new TLongObjectHashMap<>();
		
		public NetworkCollectPointCtx(RoutingContext rctx, HHRoutingPreparationDB networkDB) {
			this.rctx = rctx;
			this.networkDB = networkDB;
		}

		public int getTotalPoints() {
			int totalPoints = 0;
			for (NetworkRouteRegion r : routeRegions) {
				totalPoints += r.getPoints();
			}
			return totalPoints;
		}
		
		public int clusterSize() {
			return clusterInd;
		}

		public int borderPointsSize() {
			return networkPointsCluster.size();
		}


		public void printStatsNetworks() {
			// calculate stats
			logf("RESULT %d points -> %d border points, %d clusters (%d isolated), %d est shortcuts",
					getTotalPoints(), borderPointsSize(), clusterSize(), isolatedIslands, shortcuts);
			logf("       %.1f avg / %d min / %d max border points per cluster, %.1f avg / %d min / %d max points in cluster", 
					totalBorderPoints * 1.0 / clusterSize(), minBorder, maxBorder, 
					getTotalPoints() * 1.0 / clusterSize(), minPnts, maxPnts );
		}

		public FullNetwork startRegionProcess(NetworkRouteRegion nrouteRegion) throws IOException, SQLException {
			currentProcessingRegion = nrouteRegion;
			logf("Region %s %d of %d %s", nrouteRegion.region.getName(), nrouteRegion.id + 1, 
					routeRegions.size(), new Date().toString());
			for (NetworkRouteRegion nr : routeRegions) {
				nr.unload();
			}
			List<NetworkRouteRegion> subRegions = new ArrayList<>();
			for (NetworkRouteRegion nr : routeRegions) {
				if (nr != nrouteRegion && nr.intersects(nrouteRegion)) {
					subRegions.add(nr);
				}
			}
			
			subRegions.add(nrouteRegion);
			// force to have clean RouteRegion (important first time)
			rctx = gcMemoryLimitToUnloadAll(rctx, subRegions, true);
			
			FullNetwork network = new FullNetwork(rctx);
			for (NetworkRouteRegion nr : subRegions) {
				if (nr != nrouteRegion) {
					network.visitedVertices.putAll(nr.getVisitedVertices(networkDB));
					network.networkPointsCluster = this.networkPointsCluster; // ! use by reference
//					network.networkPointsCluster.putAll(nr.getNetworkPoints(networkDB));
				}
			}
			return network;
		}
		
		public void addCluster(NetworkIsland cluster, FullNetwork network, RouteSegmentPoint pntAround) {
			cluster.index = clusterInd++;
			if (currentProcessingRegion != null) {
				currentProcessingRegion.visitedVertices.putAll(cluster.visitedVertices);
			}
			try {
				networkDB.insertCluster(cluster, networkPointsCluster);
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
			int borderPoints = cluster.toVisitVertices.size();
			if (borderPoints == 0) {
				this.isolatedIslands++;
			} else {
				this.minBorder = Math.min(this.minBorder, borderPoints);
			}
			this.maxBorder = Math.max(this.maxBorder, borderPoints);
			this.totalBorderPoints += borderPoints;
			this.shortcuts += borderPoints * (borderPoints - 1) / 2;
			this.maxPnts = Math.max(this.maxPnts, cluster.visitedVertices.size());
			this.minPnts = Math.min(this.minPnts, cluster.visitedVertices.size());
		}

		public void finishRegionProcess() throws SQLException {
			logf("Saving visited %,d points from %s to db...", currentProcessingRegion.getPoints(), currentProcessingRegion.region.getName());
			networkDB.insertVisitedVertices(currentProcessingRegion);
			currentProcessingRegion.unload();
			logf("     saved - total %,d points", getTotalPoints());
			
		}

	}
	
	
	private class RouteDataObjectProcessor implements ResultMatcher<RouteDataObject> {
		int indProc = 0, prevPrintInd = 0;
		private float estimatedRoads;
		private FullNetwork network;
		private NetworkCollectPointCtx ctx;
		
		public RouteDataObjectProcessor(FullNetwork network, NetworkCollectPointCtx ctx, float estimatedRoads) {
			this.estimatedRoads = estimatedRoads;
			this.network = network;
			this.ctx = ctx;
		}
		
		@Override
		public boolean publish(RouteDataObject object) {
			if (!network.ctx.getRouter().acceptLine(object)) {
				return false;
			}
			indProc++;
			if (indProc < DEBUG_LIMIT_START_OFFSET || isCancelled()) {
				System.out.println("SKIP PROCESS " + indProc);
			} else {
				RouteSegmentPoint pntAround = new RouteSegmentPoint(object, 0, 0);
				long mainPoint = calculateRoutePointInternalId(pntAround);
				if (network.visitedVertices.contains(mainPoint)
						|| network.networkPointsCluster.containsKey(mainPoint)) {
					// already existing cluster
					return false;
				}
				NetworkIsland cluster = new NetworkIsland(network, pntAround);
				buildRoadNetworkIsland(cluster);
				if (DEBUG_VERBOSE_LEVEL >= 1) {
					int nwPoints = cluster.toVisitVertices.size();
					logf("CLUSTER: %2d border <- %4d points (%d segments) - %s",
							nwPoints, cluster.visitedVertices.size(), nwPoints * (nwPoints - 1), pntAround);
				}
				ctx.addCluster(cluster, network, pntAround);				
				network.addCluster(cluster, pntAround);
				if (DEBUG_VERBOSE_LEVEL >= 1 || indProc - prevPrintInd > 1000) {
					prevPrintInd = indProc;
					logf("%,d %.2f%%: %,d points -> %,d border points, %,d clusters",
							indProc, indProc * 100.0f / estimatedRoads , ctx.getTotalPoints(),
							ctx.borderPointsSize(),  ctx.clusterSize());
				}
				 
			}
			return false;
		}

		@Override
		public boolean isCancelled() {
			return DEBUG_LIMIT_PROCESS != -1 && indProc >= DEBUG_LIMIT_PROCESS;
		}
	}
	
	
	private FullNetwork collectNetworkPoints(HHRoutingPreparationDB networkDB) throws IOException, SQLException {
		RoutingContext rctx = prepareContext(null, null);
		if (EX != null) {
			DEBUG_STORE_ALL_ROADS = true;
			FullNetwork network = new FullNetwork(rctx);
			RoutePlannerFrontEnd router = new RoutePlannerFrontEnd();
			RouteSegmentPoint pnt = router.findRouteSegment(EX.getLatitude(), EX.getLongitude(), network.ctx, null);
			NetworkIsland cluster = new NetworkIsland(network, pnt);
			buildRoadNetworkIsland(cluster);
			network.addCluster(cluster, pnt);
			networkDB.insertCluster(cluster, network.networkPointsCluster);
			return network;
		}
		NetworkCollectPointCtx ctx = new NetworkCollectPointCtx(rctx, networkDB);
		Set<String> routeRegionNames = new TreeSet<>();
		for (RouteRegion r : rctx.reverseMap.keySet()) {
			if (routeRegionNames.add(r.getName())) {
				ctx.routeRegions.add(new NetworkRouteRegion(r, rctx.reverseMap.get(r).getFile()));
			} else {
				logf("Ignore route region %s as duplicate", r.getName());
			}
		}
		networkDB.insertRegions(ctx.routeRegions);
		FullNetwork network = null;
		for (NetworkRouteRegion nrouteRegion : ctx.routeRegions) {
			System.out.println("------------------------");
			network = ctx.startRegionProcess(nrouteRegion);
			RouteRegion routeRegion = null;
			for (RouteRegion rr : network.ctx.reverseMap.keySet()) {
				if (rr.getFilePointer() == nrouteRegion.region.getFilePointer() && nrouteRegion.region.getName().equals(rr.getName())) {
					routeRegion = rr;
					break;
				}
			}
			BinaryMapIndexReader reader = network.ctx.reverseMap.get(routeRegion);
			List<RouteSubregion> regions = reader
					.searchRouteIndexTree(BinaryMapIndexReader.buildSearchRequest(MapUtils.get31TileNumberX(nrouteRegion.region.getLeftLongitude() - 1),
							MapUtils.get31TileNumberX(nrouteRegion.region.getRightLongitude() + 1), MapUtils.get31TileNumberY(nrouteRegion.region.getTopLatitude() + 1),
							MapUtils.get31TileNumberY(nrouteRegion.region.getBottomLatitude() - 1), 16, null), routeRegion.getSubregions());
			
			final int estimatedRoads = 1 + routeRegion.getLength() / 150; // 5 000 / 1 MB - 1 per 200 Byte 
			reader.loadRouteIndexData(regions, new RouteDataObjectProcessor(network, ctx, estimatedRoads));
			
			ctx.finishRegionProcess();
		}
		ctx.printStatsNetworks();
		return network;
	}


	


	private void buildRoadNetworkIsland(NetworkIsland c) {
		c.printCurentState("START", 2);
		while (c.toVisitVertices.size() < MAX_VERT_DEPTH_LOOKUP[c.depth() - 1] && !c.queue.isEmpty()) {
			if (!proceed(c, c.queue.poll(), c.queue)) {
				break;
			}
			// mergeStraights(c, queue); // potential improvement
			c.printCurentState("VISIT", 3);
		}
		c.printCurentState("INITIAL", 2);
		mergeStraights(c, null);
		if (c.depth() < MAX_VERT_DEPTH_LOOKUP.length) {
			mergeConnected(c);
			mergeStraights(c, null);
		}
		c.printCurentState("END", 2);
	}

	private void mergeConnected(NetworkIsland c) {
		List<RouteSegment> potentialNetworkPoints = new ArrayList<>(c.toVisitVertices.valueCollection());
		c.printCurentState("MERGE", 2);
		for (RouteSegment t : potentialNetworkPoints) {
			// 1.3 TODO check toVisitVertices including depth
			if (c.toVisitVertices.size() >= MAX_NEIGHBOORS_N_POINTS) {
				break;
			}
			if (!c.toVisitVertices.contains(calculateRoutePointInternalId(t))) {
				continue;
			}
			int parentToVisit = c.toVisitVertices.size();
			// long id = t.getRoad().getId() / 64;
			NetworkIsland bc = new NetworkIsland(c,t );
			buildRoadNetworkIsland(bc);
			int incPointsAfterMerge = 0;
			for (long l : bc.visitedVertices.keys()) {
				if (c.toVisitVertices.containsKey(l)) {
					incPointsAfterMerge--;
				}
			}
			for (long l : bc.toVisitVertices.keys()) {
				if (!c.toVisitVertices.containsKey(l)) {
					incPointsAfterMerge++;
				}
			}
			// it could only increase by 1 (1->2)
			if ((incPointsAfterMerge <= 2 || coeffToMinimize(c.visitedVerticesSize(),
					parentToVisit) > coeffToMinimize(bc.visitedVerticesSize(), parentToVisit + incPointsAfterMerge) )) {
				int sz = c.visitedVerticesSize();
				c.visitedVertices.putAll(bc.visitedVertices);
				for (long l : bc.visitedVertices.keys()) {
					c.toVisitVertices.remove(l);
				}
				for (long k : bc.toVisitVertices.keys()) {
					if (!c.visitedVertices.containsKey(k)) {
						c.toVisitVertices.put(k, bc.toVisitVertices.get(k));
					}
				}
				c.printCurentState(incPointsAfterMerge == 0 ? "IGNORE": " MERGED", 2, String.format(" - before %d -> %d", sz, parentToVisit));
			} else {
				c.printCurentState(" NOMERGE", 3, String.format(" - %d -> %d", bc.visitedVerticesSize(),
						parentToVisit + incPointsAfterMerge));
			}
		}
	}

	private double coeffToMinimize(int internalSegments, int boundaryPoints) {
		return boundaryPoints * (boundaryPoints - 1) / 2.0 / internalSegments;
	}

	private void mergeStraights(NetworkIsland c, PriorityQueue<RouteSegment> queue) {
		boolean foundStraights = true;
		while (foundStraights && !c.toVisitVertices.isEmpty() && c.toVisitVertices.size() < MAX_NEIGHBOORS_N_POINTS) {
			foundStraights = false;
			for (RouteSegment segment : c.toVisitVertices.valueCollection()) {
				if (!c.testIfNetworkPoint(calculateRoutePointInternalId(segment)) && nonVisitedSegmentsLess(c, segment, 1)) {
					if (proceed(c, segment, queue)) {
						foundStraights = true;
						break;
					}
				}
			}
		}
		c.printCurentState("CONNECT", 2);
	}

	private boolean nonVisitedSegmentsLess(NetworkIsland c, RouteSegment segment, int max) {
		int cnt = countNonVisited(c, segment, segment.getSegmentEnd());
		cnt += countNonVisited(c, segment, segment.getSegmentStart());
		if (cnt <= max) {
			return true;
		}
		return false;
	}

	private int countNonVisited(NetworkIsland c, RouteSegment segment, int ind) {
		int x = segment.getRoad().getPoint31XTile(ind);
		int y = segment.getRoad().getPoint31YTile(ind);
		RouteSegment next = c.getCtx().loadRouteSegment(x, y, 0);
		int cnt = 0;
		while (next != null) {
			long pntId = calculateRoutePointInternalId(next);
			if (!c.testIfVisited(pntId) && !c.testIfPossibleNetworkPoint(pntId)) {
				cnt++;
			}
			next = next.getNext();
		}
		return cnt;
	}
	
	private boolean proceed(NetworkIsland c, RouteSegment segment, PriorityQueue<RouteSegment> queue) {
		if (segment.distanceFromStart > MAX_RADIUS_ISLAND) {
			return false;
		}
		long pntId = calculateRoutePointInternalId(segment);
//		System.out.println(" > " + segment);
		if (c.testIfNetworkPoint(pntId)) {
			return true;
		}
		c.toVisitVertices.remove(pntId);
		if (c.testIfVisited(pntId)) {
			throw new IllegalStateException();
		}
		c.visitedVertices.put(pntId,  DEBUG_STORE_ALL_ROADS ? segment : null);
		int prevX = segment.getRoad().getPoint31XTile(segment.getSegmentStart());
		int prevY = segment.getRoad().getPoint31YTile(segment.getSegmentStart());
		int x = segment.getRoad().getPoint31XTile(segment.getSegmentEnd());
		int y = segment.getRoad().getPoint31YTile(segment.getSegmentEnd());

		float distFromStart = segment.distanceFromStart + (float) MapUtils.squareRootDist31(x, y, prevX, prevY);
		addSegment(c, distFromStart, segment, true, queue);
		addSegment(c, distFromStart, segment, false, queue);
		return true;
	}
	
	
	private void addSegment(NetworkIsland c, float distFromStart, RouteSegment segment, boolean end, PriorityQueue<RouteSegment> queue) {
		int segmentInd = end ? segment.getSegmentEnd() : segment.getSegmentStart();
		int x = segment.getRoad().getPoint31XTile(segmentInd);
		int y = segment.getRoad().getPoint31YTile(segmentInd);
		RouteSegment next = c.getCtx().loadRouteSegment(x, y, 0);
		while (next != null) {
			// next.distanceFromStart == 0
			RouteSegment test = makePositiveDir(next); 
			long nextPnt = calculateRoutePointInternalId(test);
			
			if (!c.testIfVisited(nextPnt) && !c.toVisitVertices.containsKey(nextPnt)) {
//				System.out.println(" + " + test);
				test.distanceFromStart = distFromStart;
				if (queue != null) {
					queue.add(test);
				}
				c.toVisitVertices.put(nextPnt, test);
			}
			RouteSegment opp = makePositiveDir(next.initRouteSegment(!next.isPositive()));
			long oppPnt = opp == null ? 0 : calculateRoutePointInternalId(opp);
			if (opp != null && !c.testIfVisited(oppPnt) && !c.toVisitVertices.containsKey(oppPnt)) {
//				System.out.println(" + " + opp);
				opp.distanceFromStart = distFromStart;
				if (queue != null) {
					queue.add(opp);
				}
				c.toVisitVertices.put(oppPnt, opp);
			}
			next = next.getNext();
		}
	}
	
	///////////////////////////////////////////// BUILD SHORTCUTS //////////////////////////////////////////
	
	private Collection<Entity> buildNetworkShortcuts(TLongObjectHashMap<NetworkDBPoint> networkPoints, HHRoutingPreparationDB networkDB) throws InterruptedException, IOException, SQLException {
		TLongObjectHashMap<Entity> osmObjects = new TLongObjectHashMap<>();
		double sz = networkPoints.size() / 100.0;
		int ind = 0, prevPrintInd = 0;
		long tm = System.currentTimeMillis();
		// 1.3 TODO for long distance causes bugs if (pnt.index != 2005) { 2005-> 1861 } - 3372.75 vs 2598
		BinaryRoutePlanner.PRECISE_DIST_MEASUREMENT = true;
		TLongObjectHashMap<RouteSegment> segments = new  TLongObjectHashMap<>();
		for (NetworkDBPoint pnt : networkPoints.valueCollection()) {
			segments.put(calculateRoutePointInternalId(pnt.roadId, pnt.start, pnt.end), new RouteSegment(null, pnt.start, pnt.end));
			segments.put(calculateRoutePointInternalId(pnt.roadId, pnt.end, pnt.start), new RouteSegment(null, pnt.end, pnt.start));
			HHRoutingUtilities.addNode(osmObjects, pnt, null, "highway", "stop");
		}
		
		
		int maxDirectedPointsGraph = 0;
		int maxFinalSegmentsFound = 0;
		int totalFinalSegmentsFound = 0;
		int totalVisitedDirectSegments = 0;
		RoutingContext ctx = prepareContext(null, null);
		for (NetworkDBPoint pnt : networkPoints.valueCollection()) {
			ind++;
//			if (pnt.index > 2000 || pnt.index < 1800)   { 
//				continue;
//			}
			if (ind % 100 == 0 ) {
				ctx = gcMemoryLimitToUnloadAll(ctx, null, false);
			}
			if (pnt.connected.size() > 0) {
				continue;
			}
			long nt = System.nanoTime();
			RouteSegment s = ctx.loadRouteSegment(pnt.startX, pnt.startY, ctx.config.memoryLimitation);
			while (s != null && (s.getRoad().getId() != pnt.roadId || s.getSegmentStart() != pnt.start
					|| s.getSegmentEnd() != pnt.end)) {
				s = s.getNext();
			}
			if (s == null) {
				throw new IllegalStateException("Error on segment " + pnt.roadId / 64);
			}
			
			HHRoutingUtilities.addNode(osmObjects, pnt, getPoint(s), "highway", "stop"); //"place", "city");
			List<RouteSegment> result = runDijsktra(ctx, s, segments);
			for (RouteSegment t : result) {
				NetworkDBPoint end = networkPoints.get(calculateRoutePointInternalId(t.getRoad().getId(),
						Math.min(t.getSegmentStart(), t.getSegmentEnd()),
						Math.max(t.getSegmentStart(), t.getSegmentEnd())));
				NetworkDBSegment segment = new NetworkDBSegment(t.getDistanceFromStart(), true, pnt, end);
				pnt.connected.add(segment);
				while (t != null) {
					segment.geometry.add(getPoint(t));
					t = t.getParentRoute();
				}
				Collections.reverse(segment.geometry);
				if (DEBUG_STORE_ALL_ROADS) {
					addWay(osmObjects, segment, "highway", "secondary");
				}
//				System.out.println(segment + " " + segment.dist);
				if (segment.dist < 0) {
					throw new IllegalStateException(segment + " dist < " + segment.dist);
				}
			}
			networkDB.insertSegments(pnt.connected);
			if (DEBUG_VERBOSE_LEVEL >= 2) {
				System.out.println(ctx.calculationProgress.getInfo(null));
			}
			
			maxDirectedPointsGraph = Math.max(maxDirectedPointsGraph, ctx.calculationProgress.visitedDirectSegments);
			totalVisitedDirectSegments += ctx.calculationProgress.visitedDirectSegments;
			maxFinalSegmentsFound = Math.max(maxFinalSegmentsFound, ctx.calculationProgress.finalSegmentsFound);
			totalFinalSegmentsFound += ctx.calculationProgress.finalSegmentsFound;
			
			
			if (DEBUG_VERBOSE_LEVEL >= 1 || ind - prevPrintInd > 500) {
				double timePassed = (System.currentTimeMillis() - tm) / 1000.0; 
				double timeLeft = timePassed * (networkPoints.size() / (ind + 1) - 1);
				prevPrintInd = ind;
				System.out.println(String.format("%.2f%% Process %d (%d shortcuts) - %.1f ms, passed %.1f sec, left %.1f sec",
							ind / sz, s.getRoad().getId() / 64, result.size(), (System.nanoTime() - nt) / 1.0e6,
							timePassed, timeLeft));
			}
			// clean up for gc
			pnt.connected.clear();
			if (ind > DEBUG_LIMIT_PROCESS && DEBUG_LIMIT_PROCESS != -1) {
				break;
			}
		}
		System.out.println(String.format("Total segments %d: %d total shorcuts, per border point max %d, avergage %d shortcuts (routing sub graph max %d, avg %d segments)", 
				segments.size(), totalFinalSegmentsFound, maxFinalSegmentsFound, totalFinalSegmentsFound / ind,
				maxDirectedPointsGraph, totalVisitedDirectSegments  / ind));
		return osmObjects.valueCollection();
	}
	

	private List<RouteSegment> runDijsktra(RoutingContext ctx,
			RouteSegment s, TLongObjectHashMap<RouteSegment> segments) throws InterruptedException, IOException {
		
		long pnt1 = calculateRoutePointInternalId(s.getRoad().getId(), s.getSegmentStart(), s.getSegmentEnd());
		long pnt2 = calculateRoutePointInternalId(s.getRoad().getId(), s.getSegmentEnd(), s.getSegmentStart());
		RouteSegment rm1 = segments.remove(pnt1);
		RouteSegment rm2 = segments.remove(pnt2);
		
		List<RouteSegment> res = new ArrayList<>();
		
		ctx.unloadAllData(); // needed for proper multidijsktra work
		ctx.calculationProgress = new RouteCalculationProgress();
		ctx.startX = s.getRoad().getPoint31XTile(s.getSegmentStart(), s.getSegmentEnd()) ;
		ctx.startY = s.getRoad().getPoint31YTile(s.getSegmentStart(), s.getSegmentEnd()) ;
		RouteSegmentPoint pnt = new RouteSegmentPoint(s.getRoad(), s.getSegmentStart(), s.getSegmentEnd(), 0);
		MultiFinalRouteSegment frs = (MultiFinalRouteSegment) new BinaryRoutePlanner().searchRouteInternal(ctx, pnt, null, null, segments);
		
		if (frs != null) {
			TLongSet set = new TLongHashSet();
			for (RouteSegment o : frs.all) {
				// duplicates are possible as alternative routes
				long pntId = calculateRoutePointInternalId(o);
				if (set.add(pntId)) {
					res.add(o);
				}
			}
		}
		
		if (rm1 != null) {
			segments.put(pnt1, rm1);
		}
		if (rm2 != null) {
			segments.put(pnt2, rm2);
		}
		return res;
	}

	
}