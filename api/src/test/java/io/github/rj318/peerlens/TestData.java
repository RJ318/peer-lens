package io.github.rj318.peerlens;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads the invented test fixtures under src/test/resources/sec. None of it is real filing data. */
public final class TestData {

    public static final String COMPANY_FACTS = "sec/companyfacts-example.json";
    public static final String TICKERS = "sec/company_tickers-example.json";

    private TestData() {
    }

    public static String read(String path) {
        try (InputStream in = TestData.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing test resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
