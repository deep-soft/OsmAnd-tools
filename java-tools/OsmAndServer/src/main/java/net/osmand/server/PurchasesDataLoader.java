package net.osmand.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;


@Component
public class PurchasesDataLoader {

	private Map<String, Subscription> subscriptions;
	private Map<String, InApp> inapps;

	@Value("${osmand.web.location}")
	private String websiteLocation;

	@PostConstruct
	public void init() throws IOException {
		initSubscriptions();
		initInApps();
	}

	public void reload() throws IOException {
		init();
	}

	private void initSubscriptions() throws IOException {
		File file = new File(websiteLocation, "sku-subscriptions.json");
		ObjectMapper mapper = new ObjectMapper();
		subscriptions = mapper.readValue(
				file, new TypeReference<>() {}
		);
	}

	private void initInApps() throws IOException {
		File file = new File(websiteLocation, "sku-inapps.json");
		ObjectMapper mapper = new ObjectMapper();
		inapps = mapper.readValue(
				file, new TypeReference<>() {}
		);
	}

	public Map<String, Subscription> getSubscriptions() {
		return subscriptions;
	}

	public Map<String, InApp> getInApps() {
		return inapps;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Subscription(
			@JsonProperty String name,
			@JsonProperty String icon,
			@JsonProperty("feature_pro") JsonNode pro,
			@JsonProperty("feature_maps") JsonNode maps,
			@JsonProperty("feature_contours") JsonNode contours,
			@JsonProperty("feature_nautical") JsonNode nautical,
			@JsonProperty("feature_pro_no_cloud") JsonNode proNoCloud,
			@JsonProperty("feature_live_maps") JsonNode liveMaps,
			@JsonProperty("cross-platform") boolean isCrossPlatform,
			@JsonProperty int duration,
			@JsonProperty("duration_unit") String durationUnit,
			@JsonProperty double retention,
			@JsonProperty("defPriceEurMillis") int defaultPriceEurMillis,
			@JsonProperty String app
	) {
		public boolean isPro() {
			return !pro.isBoolean() || pro.booleanValue();
		}
		
		public boolean isMaps() {
			return maps != null && maps.isBoolean() && maps.booleanValue();
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record InApp(
			@JsonProperty String name,
			@JsonProperty String icon,
			@JsonProperty("cross-platform") boolean isCrossPlatform,
			@JsonProperty("feature_pro") JsonNode pro,
			@JsonProperty("feature_maps") JsonNode maps,
			@JsonProperty("feature_contours") JsonNode contours,
			@JsonProperty("feature_nautical") JsonNode nautical,
			@JsonProperty("feature_pro_no_cloud") JsonNode proNoCloud,
			@JsonProperty("feature_live_maps") JsonNode liveMaps
	) {

		public record InAppProFeatures(
				String expire
		) {
		}

		public boolean isPro() {
			return !pro.isBoolean() || pro.booleanValue();
		}

		public boolean isMaps() {
			return !maps.isBoolean() || maps.booleanValue();
		}

		public InAppProFeatures getProFeatures() {
			if (pro.isObject()) {
				return new InAppProFeatures(pro.path("expire").asText(null));
			} else {
				return null;
			}
		}

		public Date getExpireDate(Date purchaseTime) {
			InAppProFeatures proFeatures = getProFeatures();
			if (proFeatures != null && proFeatures.expire() != null) {
				int years = 0;
				int months = 0;
				int days = 0;
				String exp = proFeatures.expire();
				if (exp.startsWith("y")) {
					years = Integer.parseInt(exp.substring(1));
				} else if (exp.startsWith("m")) {
					months = Integer.parseInt(exp.substring(1));
				} else if (exp.startsWith("d")) {
					days = Integer.parseInt(exp.substring(1));
				}
				Calendar calendar = Calendar.getInstance();
				calendar.setTime(purchaseTime);
				calendar.add(Calendar.YEAR, years);
				calendar.add(Calendar.MONTH, months);
				calendar.add(Calendar.DAY_OF_MONTH, days);
				return calendar.getTime();
			} else {
				return null;
			}
		}
	}
}