package net.osmand.router;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;

import gnu.trove.iterator.TLongObjectIterator;
import gnu.trove.map.hash.TLongObjectHashMap;
import net.osmand.PlatformUtil;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.data.LatLon;
import net.osmand.data.QuadRect;
import net.osmand.obf.preparation.DBDialect;
import net.osmand.router.BinaryRoutePlanner.RouteSegment;
import net.osmand.router.HHRoutingGraphCreator.NetworkIsland;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

public class HHRoutingPreparationDB {
	private static final Log LOG = PlatformUtil.getLog(HHRoutingPreparationDB.class);

	public static final String EXT = ".hhdb";

	private Connection conn;
	private PreparedStatement insertSegment;
	private PreparedStatement insertGeometry;
	private PreparedStatement loadGeometry;

	private final int BATCH_SIZE = 10000;
	private int batchInsPoint = 0;
	private PreparedStatement insCluster;
	private PreparedStatement insPoint;


	public static final int FULL_RECREATE = 0;
	public static final int RECREATE_SEGMENTS = 1;
	public static final int READ = 2;



	public HHRoutingPreparationDB(File file, int recreate) throws SQLException {
		if (file.exists() && FULL_RECREATE == recreate) {
			file.delete();
		}
		this.conn = DBDialect.SQLITE.getDatabaseConnection(file.getAbsolutePath(), LOG);
		Statement st = conn.createStatement();
		st.execute("CREATE TABLE IF NOT EXISTS points(idPoint, ind, roadId, start, end, sx31, sy31, ex31, ey31, indexes, PRIMARY key (idPoint))"); // ind unique
		st.execute("CREATE TABLE IF NOT EXISTS clusters(idPoint, indPoint, clusterInd, PRIMARY key (indPoint, clusterInd))");
		st.execute("CREATE TABLE IF NOT EXISTS segments(idPoint, idConnPoint, dist, PRIMARY key (idPoint, idConnPoint))");
		st.execute("CREATE TABLE IF NOT EXISTS geometry(idPoint, idConnPoint, geometry, PRIMARY key (idPoint, idConnPoint))");
		st.execute("CREATE TABLE IF NOT EXISTS routeRegions(id, name, filePointer, size, filename, left, right, top, bottom, PRIMARY key (id))");
		st.execute("CREATE TABLE IF NOT EXISTS routeRegionPoints(id, pntId)");
		if (recreate == RECREATE_SEGMENTS) {
			st.execute("DELETE FROM segments");
		}
		insPoint = conn.prepareStatement("INSERT INTO points(idPoint, ind, roadId, start, end, sx31, sy31, ex31, ey31) "
						+ " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)");
		insCluster = conn.prepareStatement("INSERT INTO clusters(idPoint, indPoint, clusterInd ) VALUES(?, ?, ?)");
		insertSegment = conn.prepareStatement("INSERT INTO segments(idPoint, idConnPoint, dist) " + " VALUES(?, ?, ?)");
		insertGeometry = conn.prepareStatement("INSERT INTO geometry(idPoint, idConnPoint, geometry) " + " VALUES(?, ?, ?)");
		loadGeometry = conn.prepareStatement("SELECT geometry FROM geometry WHERE idPoint = ? AND idConnPoint =? ");
		st.close();
	}

	
	public void loadVisitedVertices(NetworkRouteRegion networkRouteRegion) throws SQLException {
		PreparedStatement ps = conn.prepareStatement("SELECT pntId FROM routeRegionPoints WHERE id = ? ");
		ps.setLong(1, networkRouteRegion.id);
		ResultSet rs = ps.executeQuery();
		if(networkRouteRegion.visitedVertices != null) {
			throw new IllegalStateException();
		}
		networkRouteRegion.visitedVertices = new TLongObjectHashMap<>();
		while(rs.next()) {
			networkRouteRegion.visitedVertices.put(rs.getLong(1), null);
		}
		networkRouteRegion.points = -1;		
		rs.close();
	}
	
	public void insertVisitedVertices(NetworkRouteRegion networkRouteRegion) throws SQLException {
		PreparedStatement ps = conn.prepareStatement("INSERT INTO routeRegionPoints (id, pntId) VALUES (?, ?)");
		int ind = 0;
		for (long k : networkRouteRegion.visitedVertices.keys()) {
			ps.setLong(1, networkRouteRegion.id);
			ps.setLong(2, k);
			ps.addBatch();
			if (ind++ > BATCH_SIZE) {
				ps.executeBatch();
			}
		}
		ps.executeBatch();
		insPoint.executeBatch();
		insCluster.executeBatch();
		
	}
	
	public void insertSegments(List<NetworkDBSegment> segments) throws SQLException {
		for (NetworkDBSegment s : segments) {
			insertSegment.setLong(1, s.start.index);
			insertSegment.setLong(2, s.end.index);
			insertSegment.setDouble(3, s.dist);
			insertSegment.addBatch();
//			byte[] coordinates = new byte[0];
			byte[] coordinates = new byte[8 * s.geometry.size()];
			for (int t = 0; t < s.geometry.size(); t++) {
				LatLon l = s.geometry.get(t);
				Algorithms.putIntToBytes(coordinates, 8 * t, MapUtils.get31TileNumberX(l.getLongitude()));
				Algorithms.putIntToBytes(coordinates, 8 * t + 4, MapUtils.get31TileNumberY(l.getLatitude()));
			}
			insertGeometry.setLong(1, s.start.index);
			insertGeometry.setLong(2, s.end.index);
			insertGeometry.setBytes(3, coordinates);
			insertGeometry.addBatch();
		}
		insertSegment.executeBatch();
		insertGeometry.executeBatch();
	}
	
	public void loadGeometry(NetworkDBSegment segment, boolean reload) throws SQLException {
		if(!segment.geometry.isEmpty() && !reload) {
			return;
		}
		segment.geometry.clear();
		loadGeometry.setLong(1, segment.start.index);
		loadGeometry.setLong(2, segment.end.index);
		ResultSet rs = loadGeometry.executeQuery();
		if (rs.next()) {
			parseGeometry(segment, rs.getBytes(1));
		}
	}
	
	public int loadNetworkSegments(TLongObjectHashMap<NetworkDBPoint> points) throws SQLException {
		TLongObjectHashMap<NetworkDBPoint> pntsById = new TLongObjectHashMap<>();
		for (NetworkDBPoint p : points.valueCollection()) {
			pntsById.put(p.index, p);
		}
		Statement st = conn.createStatement();
		ResultSet rs = st.executeQuery("SELECT idPoint, idConnPoint, dist from segments");
		int x = 0;
		while (rs.next()) {
			x++;
			NetworkDBPoint start = pntsById.get(rs.getLong(1));
			NetworkDBPoint end = pntsById.get(rs.getLong(2));
			double dist = rs.getDouble(3);
			NetworkDBSegment segment = new NetworkDBSegment(dist, true, start, end);
			NetworkDBSegment rev = new NetworkDBSegment(dist, false, start, end);
			start.connected.add(segment);
			end.connectedReverse.add(rev);
		}
		rs.close();
		st.close();
		return x;
	}

	private void parseGeometry(NetworkDBSegment segment, byte[] geom) {
		for (int k = 0; k < geom.length; k += 8) {
			int x = Algorithms.parseIntFromBytes(geom, k);
			int y = Algorithms.parseIntFromBytes(geom, k + 4);
			LatLon latlon = new LatLon(MapUtils.get31LatitudeY(y), MapUtils.get31LongitudeX(x));
			segment.geometry.add(latlon);
		}
	}

	public TLongObjectHashMap<NetworkDBPoint> getNetworkPoints(boolean byId) throws SQLException {
		Statement st = conn.createStatement();
		ResultSet rs = st.executeQuery("SELECT idPoint, ind, roadId, start, end, sx31, sy31, ex31, ey31, indexes from points");
		TLongObjectHashMap<NetworkDBPoint> mp = new TLongObjectHashMap<>();
		while (rs.next()) {
			NetworkDBPoint pnt = new NetworkDBPoint();
			int p = 1;
			pnt.id = rs.getLong(p++);
			pnt.index = rs.getInt(p++);
			pnt.roadId = rs.getLong(p++);
			pnt.start = rs.getInt(p++);
			pnt.end = rs.getInt(p++);
			pnt.startX = rs.getInt(p++);
			pnt.startY = rs.getInt(p++);
			pnt.endX = rs.getInt(p++);
			pnt.endY = rs.getInt(p++);
			pnt.clusters = Algorithms.stringToArray(rs.getString(p++));
			mp.put(byId ? pnt.id : pnt.index, pnt);
		}
		rs.close();
		st.close();
		return mp;
	}
	
	
	public void insertRegions(List<NetworkRouteRegion> regions) throws SQLException {
		PreparedStatement s = conn
				.prepareStatement("INSERT INTO routeRegions(id, name, filePointer, size, filename, left, right, top, bottom) "
						+ " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)");
		int ind = 0;
		for(NetworkRouteRegion nr : regions) {
			int p = 1;
			nr.id = ind++;
			s.setLong(p++, nr.id);
			s.setString(p++, nr.region.getName());
			s.setLong(p++, nr.region.getFilePointer());
			s.setLong(p++, nr.region.getLength());
			s.setString(p++, nr.file.getName());
			s.setDouble(p++, nr.region.getLeftLongitude());
			s.setDouble(p++, nr.region.getRightLongitude());
			s.setDouble(p++, nr.region.getTopLatitude());
			s.setDouble(p++, nr.region.getBottomLatitude());
			s.addBatch();
		}
		s.executeBatch();
		s.close();
	}

	public void insertCluster(NetworkIsland cluster, TLongObjectHashMap<Integer> mp) throws SQLException {
		TLongObjectIterator<RouteSegment> it = cluster.toVisitVertices.iterator();
		while (it.hasNext()) {
			batchInsPoint++;
			it.advance();
			long pntId = it.key();
			RouteSegment obj = it.value();
			int pointInd;
			if (!mp.contains(pntId)) {
				pointInd = mp.size();
				mp.put(pntId, pointInd);
				int p = 1;
				insPoint.setLong(p++, pntId);
				insPoint.setInt(p++, pointInd);
				insPoint.setLong(p++, obj.getRoad().getId());
				insPoint.setLong(p++, obj.getSegmentStart());
				insPoint.setLong(p++, obj.getSegmentEnd());
				insPoint.setInt(p++, obj.getRoad().getPoint31XTile(obj.getSegmentStart()));
				insPoint.setInt(p++, obj.getRoad().getPoint31YTile(obj.getSegmentStart()));
				insPoint.setInt(p++, obj.getRoad().getPoint31XTile(obj.getSegmentEnd()));
				insPoint.setInt(p++, obj.getRoad().getPoint31YTile(obj.getSegmentEnd()));
				insPoint.addBatch();
			} else {
				pointInd = mp.get(pntId);
			}
			
			int p2 = 1;
			insCluster.setLong(p2++, pntId);
			insCluster.setInt(p2++, pointInd);
			insCluster.setInt(p2++, cluster.index);
			insCluster.addBatch();

		}
		if (batchInsPoint > BATCH_SIZE) {
			batchInsPoint = 0;
			insPoint.executeBatch();
			insCluster.executeBatch();
		}

	}

	public void close() throws SQLException {
		conn.close();
	}
	
	
	static class NetworkRouteRegion {
		int id = 0;
		RouteRegion region;
		File file;
		int points = -1; // -1 loaded points
		TLongObjectHashMap<RouteSegment> visitedVertices = new TLongObjectHashMap<>();

		public NetworkRouteRegion(RouteRegion r, File f) {
			region = r;
			this.file = f;

		}

		public int getPoints() {
			return points < 0 ? visitedVertices.size() : points;
		}

		public QuadRect getRect() {
			return new QuadRect(region.getLeftLongitude(), region.getTopLatitude(), region.getRightLongitude(), region.getBottomLatitude());
		}

		public boolean intersects(NetworkRouteRegion nrouteRegion) {
			return QuadRect.intersects(getRect(), nrouteRegion.getRect());
		}

		public void unload() {
			if (this.visitedVertices != null && this.visitedVertices.size() > 1000) {
				this.points = this.visitedVertices.size();
				this.visitedVertices = null;
			}
		}
		
		public TLongObjectHashMap<RouteSegment> getVisitedVertices(HHRoutingPreparationDB networkDB) throws SQLException {
			if (points > 0) {
				networkDB.loadVisitedVertices(this);
			}
			return visitedVertices;
		}
		
	}
	
	
	static class NetworkDBSegment {
		final boolean direction;
		final NetworkDBPoint start;
		final NetworkDBPoint end;
		final double dist;
		List<LatLon> geometry = new ArrayList<>();
		
		public NetworkDBSegment(double dist, boolean direction, NetworkDBPoint start, NetworkDBPoint end) {
			this.direction = direction;
			this.start = start;
			this.end = end;
			this.dist = dist;
		}
		
		// routing extra info
		double rtDistanceToEnd; // added once in queue
		double rtCost; // added once in queue
		
		@Override
		public String toString() {
			return String.format("Segment %s -> %s", start, end);
		}
		
	}
	
	static class NetworkDBPoint {
		long id;
		int index;
		int level = 0;
		public long roadId;
		public int start;
		public int end;
		public int startX;
		public int startY;
		public int endX;
		public int endY;
		public int[] clusters;
		
		List<NetworkDBSegment> connected = new ArrayList<NetworkDBSegment>();
		List<NetworkDBSegment> connectedReverse = new ArrayList<NetworkDBSegment>();
		
		// for routing
		NetworkDBSegment rtRouteToPoint;
		double rtDistanceFromStart;
		
		NetworkDBSegment rtRouteToPointRev;
		double rtDistanceFromStartRev;
		
		// indexing
		int rtCnt = 0;
		
		
		@Override
		public String toString() {
			return String.format("Point %d (%d %d-%d)", index, roadId / 64, start, end);
		}
		
		public LatLon getPoint() {
			return new LatLon(MapUtils.get31LatitudeY(this.startY / 2 + this.endY / 2),
					MapUtils.get31LongitudeX(this.startX / 2 + this.endX / 2));
		}

		public void clearRouting() {
			rtRouteToPoint = null;
			rtDistanceFromStart = 0;
			rtRouteToPointRev = null;
			rtDistanceFromStartRev = 0;
		}
	}



}