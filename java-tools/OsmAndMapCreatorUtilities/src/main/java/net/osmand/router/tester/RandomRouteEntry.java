package net.osmand.router.tester;

import net.osmand.data.LatLon;

import java.util.ArrayList;
import java.util.List;

class RandomRouteEntry {
	LatLon start;
	LatLon finish;
	List<LatLon> via = new ArrayList<>(); // inter points

	String profile = "car";
	List<String> params = new ArrayList<>();

	List<TestResult> results = new ArrayList<>();

	class TestResult {
		String type;
		double cost;
		int runTime; // ms
		int visitedSegments;
		// TODO distance, geometry, etc
		RandomRouteEntry parent; // ref to start, finish, etc

		public String toString() {
			return parent.toURL(type);
		}
	}

	public String toString() {
		return toURL("osrm");
	}

	String toURL(String type) {
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
