package com.ledger.apitests.support;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import io.qameta.allure.restassured.AllureRestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.Filter;
import io.restassured.specification.RequestSpecification;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Central place that builds RestAssured {@link RequestSpecification}s for the black-box
 * suite. Two flavors are exposed:
 *
 * <ul>
 *   <li>{@link #spec()} — plain spec (base URI + Allure capture only). Used for every
 *       request, including ones deliberately probing undocumented/edge-case behavior
 *       (malformed UUIDs, wrong content-type, etc.) where wrapping with the OpenAPI
 *       validation filter would abort the test before we can observe/assert the actual
 *       response.</li>
 *   <li>{@link #validatedSpec()} — same, plus {@link OpenApiValidationFilter} bound to the
 *       repo's {@code openapi.yaml}. Any request/response that doesn't conform to the
 *       contract (wrong status code for the operation, wrong schema, missing required
 *       field, wrong type, etc.) fails the test automatically. Used for the "this response
 *       must match the contract" assertions.</li>
 * </ul>
 */
public final class ApiClient {

    public static final String BASE_URI = System.getProperty(
            "api.baseUri", System.getenv().getOrDefault("API_BASE_URI", "http://localhost:8080"));

    private static final String SPEC_PATH = resolveSpecPath();

    /**
     * Validates RESPONSES against openapi.yaml (status code + body schema) but not requests.
     * Deliberately: many tests in this suite send requests that are *intentionally* invalid
     * (blank name, negative amount, missing header, ...) precisely to exercise the API's own
     * validation logic - the interesting contract assertion there is "does the resulting ERROR
     * RESPONSE match the documented ErrorResponse schema", not "was my deliberately-bad request
     * itself schema-valid" (by construction, it isn't). Request-level validation findings are
     * downgraded to IGNORE; response-level findings remain at the default ERROR (fail the test).
     */
    private static final OpenApiValidationFilter OPENAPI_FILTER = new OpenApiValidationFilter(
            OpenApiInteractionValidator.createFor(resolveSpecPathForValidatorWorkaround())
                    .withLevelResolver(LevelResolver.create()
                            .withLevel("validation.request", ValidationReport.Level.IGNORE)
                            .build())
                    .build());

    private ApiClient() {
    }

    private static String resolveSpecPath() {
        String configured = System.getProperty("openapi.spec.path");
        if (configured != null && new File(configured).exists()) {
            return new File(configured).getAbsolutePath();
        }
        // Fallback: repo layout is <repoRoot>/api-tests/<thisModule>, spec lives at <repoRoot>/openapi.yaml
        File fallback = new File(System.getProperty("user.dir"), "../openapi.yaml");
        if (fallback.exists()) {
            return fallback.getAbsolutePath();
        }
        throw new IllegalStateException(
                "Could not locate openapi.yaml. Set -Dopenapi.spec.path=/absolute/path/to/openapi.yaml");
    }

    /**
     * KNOWN THIRD-PARTY TOOLING QUIRK (not a defect in openapi.yaml): swagger-request-validator
     * 3.0.0's bundled OpenAPI 3.1 parser rejects this (otherwise perfectly valid) document with
     * "attribute info.license.identifier is missing". Per the OpenAPI 3.1 / SPDX license-object
     * rules, {@code identifier} (like {@code url}) is OPTIONAL - only {@code name} is required,
     * and openapi.yaml correctly supplies only {@code name: Proprietary}. This appears to be
     * over-strict validation in the library's bundled parser.
     * <p>
     * openapi.yaml itself (the approved, committed source of truth) is never modified. Instead,
     * purely to unblock the third-party validator library, this materializes a scratch copy in
     * the build's temp directory with a no-op {@code identifier} field spliced into the license
     * object and points the OpenAPI-conformance filter at that copy. This does not add, remove,
     * or change any path, operation, schema, or status code - it only silences the parser's
     * false-positive complaint. See the test run report for this caveat.
     */
    private static String resolveSpecPathForValidatorWorkaround() {
        String original = resolveSpecPath();
        try {
            String content = Files.readString(Path.of(original));
            if (content.contains("identifier:")) {
                return original; // already fine, no workaround needed
            }
            String patched = content.replaceFirst(
                    "(?m)^(\\s*)name:\\s*Proprietary\\s*$",
                    "$1name: Proprietary\n$1identifier: UNLICENSED");
            if (patched.equals(content)) {
                // Pattern didn't match (spec's info.license block changed shape) - fail loudly
                // rather than silently validating against an unpatched, still-broken spec.
                throw new IllegalStateException(
                        "OpenAPI validator workaround could not locate the license.name block to patch; "
                                + "openapi.yaml's info.license section may have changed shape. "
                                + "Update ApiClient.resolveSpecPathForValidatorWorkaround().");
            }
            Path scratch = Files.createTempFile("openapi-validator-copy-", ".yaml");
            Files.writeString(scratch, patched);
            scratch.toFile().deleteOnExit();
            return scratch.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed preparing OpenAPI spec for validator workaround", e);
        }
    }

    public static RequestSpecification spec() {
        return new RequestSpecBuilder()
                .setBaseUri(BASE_URI)
                .addFilter(allure())
                .build();
    }

    public static RequestSpecification validatedSpec() {
        return new RequestSpecBuilder()
                .setBaseUri(BASE_URI)
                .addFilter(allure())
                .addFilter(OPENAPI_FILTER)
                .build();
    }

    private static Filter allure() {
        return new AllureRestAssured();
    }
}
