package io.github.rj318.peerlens.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.rj318.peerlens.config.PeerLensProperties;

/** The configured watchlist: which peer groups exist, in which order, and which companies they contain. */
@Component
public class SectorCatalog {

    private final Map<String, PeerLensProperties.Sector> sectors;
    private final Map<String, String> sectorByTicker = new LinkedHashMap<>();

    public SectorCatalog(PeerLensProperties properties) {
        this.sectors = new LinkedHashMap<>(properties.sectors());
        sectors.forEach((key, sector) -> sector.tickers().forEach(ticker -> {
            String previous = sectorByTicker.put(normalize(ticker), key);
            if (previous != null) {
                throw new IllegalStateException(ticker + " is listed in both " + previous + " and " + key);
            }
        }));
    }

    public List<String> keys() {
        return new ArrayList<>(sectors.keySet());
    }

    public Optional<PeerLensProperties.Sector> find(String key) {
        return Optional.ofNullable(sectors.get(key));
    }

    public PeerLensProperties.Sector get(String key) {
        return find(key).orElseThrow(() -> new IllegalArgumentException("Unknown sector: " + key));
    }

    public List<String> tickers(String key) {
        return get(key).tickers().stream().map(SectorCatalog::normalize).toList();
    }

    public List<String> allTickers() {
        return new ArrayList<>(sectorByTicker.keySet());
    }

    public Optional<String> sectorOf(String ticker) {
        return Optional.ofNullable(sectorByTicker.get(normalize(ticker)));
    }

    public static String normalize(String ticker) {
        return ticker.strip().toUpperCase(Locale.ROOT);
    }
}
