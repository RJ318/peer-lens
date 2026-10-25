package io.github.rj318.peerlens.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.rj318.peerlens.config.PeerLensProperties;
import io.github.rj318.peerlens.repository.IngestionRunRepository;
import io.github.rj318.peerlens.service.IngestionService;
import io.github.rj318.peerlens.web.ApiViews.IngestionResultView;
import io.github.rj318.peerlens.web.ApiViews.IngestionRunView;
import io.github.rj318.peerlens.web.ApiViews.IngestionStartedView;

/**
 * Operational endpoints. Disabled unless PEERLENS_ADMIN_TOKEN is set, and the token is compared in
 * constant time so response timing cannot reveal it.
 */
@RestController
@RequestMapping("/api/admin/ingestions")
public class AdminController {

    static final String TOKEN_HEADER = "X-Admin-Token";
    private static final String LATEST = "/api/admin/ingestions/latest";

    private final IngestionService ingestion;
    private final IngestionRunRepository runs;
    private final PeerLensProperties.Admin admin;

    public AdminController(IngestionService ingestion, IngestionRunRepository runs, PeerLensProperties properties) {
        this.ingestion = ingestion;
        this.runs = runs;
        this.admin = properties.admin();
    }

    @PostMapping
    public ResponseEntity<IngestionStartedView> start(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        authorize(token);
        long runId = ingestion.startInBackground("ADMIN").orElseThrow(
                () -> new ApiException(HttpStatus.CONFLICT, "An ingestion run is already in progress."));
        return ResponseEntity.accepted().location(URI.create(LATEST))
                .body(new IngestionStartedView(runId, "RUNNING", LATEST));
    }

    @GetMapping("/latest")
    public IngestionRunView latest(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        authorize(token);
        return runs.findLatest()
                .map(run -> new IngestionRunView(run.id(), run.trigger(), run.status(), run.startedAt(),
                        run.finishedAt(), run.message(), run.results().stream()
                        .map(r -> new IngestionResultView(r.ticker(), r.status(), r.factsLoaded(), r.error()))
                        .toList()))
                .orElseThrow(() -> ApiException.notFound("No ingestion has run yet."));
    }

    private void authorize(String token) {
        if (!admin.enabled()) {
            throw ApiException.notFound("Admin endpoints are disabled. Set PEERLENS_ADMIN_TOKEN to enable them.");
        }
        if (token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), admin.token().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing or invalid " + TOKEN_HEADER + " header.");
        }
    }
}
