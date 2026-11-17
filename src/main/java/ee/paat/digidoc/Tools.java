package ee.paat.digidoc;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Schemas and the single safe boundary between untrusted requests and DigiDoc. */
public final class Tools {
    private final ContainerService service;
    private final McpJsonMapper mapper;
    private final SmartIdSigning signing;

    public Tools(ContainerService service, McpJsonMapper mapper) {
        this(service, mapper, null);
    }

    public Tools(ContainerService service, McpJsonMapper mapper, SmartIdSigning signing) {
        this.service = service;
        this.mapper = mapper;
        this.signing = signing;
    }

    public List<McpServerFeatures.SyncToolSpecification> specifications() {
        var tools = new ArrayList<McpServerFeatures.SyncToolSpecification>();
        tools.add(specification("open_container", "Inspect a DigiDoc container's type, data files and signature count.",
                object(Map.of("type", string(), "data_files", array(object(Map.of(
                        "name", string(), "mime_type", nullableString(), "size_bytes", Map.of("type", "integer")))) ,
                        "signature_count", Map.of("type", "integer")))));
        tools.add(specification("list_signatures", "List signer certificate and signature metadata; does not validate trust.",
                object(Map.of("signatures", array(object(nullableFields("id", "signer_common_name",
                        "personal_identification_code", "claimed_signing_time", "trusted_signing_time",
                        "profile", "signature_method", "certificate_issuer")))))));
        tools.add(specification("extract_file", "Extract one exact data file name into an existing allowed directory, returning its path and SHA-256.",
                object(Map.of("path", string(), "sha256", string()))));
        tools.add(specification("validate_container", "Validate signatures using DigiDoc trust lists; may download trust and certificate status information.",
                validationSchema()));
        if (signing != null) tools.add(signingSpecification());
        return List.copyOf(tools);
    }

    private McpServerFeatures.SyncToolSpecification signingSpecification() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("path", Map.of("type", "string", "minLength", 1));
        var schema = Map.<String, Object>of("type", "object", "properties", properties,
                "required", List.of("path"), "additionalProperties", false);
        var tool = McpSchema.Tool.builder("sign_with_smartid", schema)
                .description("Sign one file with Smart-ID through the installed DigiDoc4. Creates a new .asice beside it. PIN2 on the phone is the consent.")
                .outputSchema(signingOutputSchema())
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(false)
                        .destructiveHint(true).idempotentHint(false).openWorldHint(true).build())
                .build();
        return new McpServerFeatures.SyncToolSpecification(tool,
                (exchange, request) -> call("sign_with_smartid", request.arguments()));
    }

    private McpServerFeatures.SyncToolSpecification specification(String name, String description,
                                                                  Map<String, Object> output) {
        boolean extract = name.equals("extract_file");
        var properties = new LinkedHashMap<String, Object>();
        properties.put("path", Map.of("type", "string", "minLength", 1));
        if (extract) {
            properties.put("file_name", Map.of("type", "string", "minLength", 1));
            properties.put("output_dir", Map.of("type", "string", "minLength", 1));
            properties.put("overwrite", Map.of("type", "boolean", "default", false));
        }
        var schema = Map.<String, Object>of("type", "object", "properties", properties,
                "required", extract ? List.of("path", "file_name", "output_dir") : List.of("path"),
                "additionalProperties", false);
        var tool = McpSchema.Tool.builder(name, schema).description(description).outputSchema(output)
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(!extract)
                        .destructiveHint(extract).idempotentHint(!extract)
                        .openWorldHint(name.equals("validate_container")).build()).build();
        return new McpServerFeatures.SyncToolSpecification(tool,
                (exchange, request) -> call(name, request.arguments()));
    }

    public McpSchema.CallToolResult call(String name, Map<String, Object> arguments) {
        try {
            if ("sign_with_smartid".equals(name) && signing == null) {
                throw new ToolException("SIGNING_DISABLED", "Smart-ID signing is not enabled.");
            }
            if (arguments == null) throw new IllegalArgumentException();
            Set<String> allowed = switch (name) {
                case "extract_file" -> Set.of("path", "file_name", "output_dir", "overwrite");
                case "open_container", "list_signatures", "validate_container", "sign_with_smartid" -> Set.of("path");
                default -> throw new IllegalArgumentException();
            };
            if (!allowed.containsAll(arguments.keySet())) throw new IllegalArgumentException();
            var path = requiredString(arguments, "path");
            Map<String, Object> result = switch (name) {
                case "open_container" -> service.openContainer(path);
                case "list_signatures" -> service.listSignatures(path);
                case "extract_file" -> {
                    if (arguments.containsKey("overwrite") && !(arguments.get("overwrite") instanceof Boolean)) {
                        throw new IllegalArgumentException();
                    }
                    yield service.extractFile(path, requiredString(arguments, "file_name"),
                            requiredString(arguments, "output_dir"), Boolean.TRUE.equals(arguments.get("overwrite")));
                }
                case "validate_container" -> service.validateContainer(path);
                case "sign_with_smartid" -> signing.sign(path);
                default -> throw new IllegalArgumentException();
            };
            return result(result);
        } catch (ToolException exception) {
            return error(exception.code(), exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return error("INVALID_ARGUMENT", "Tool arguments do not match the required schema.");
        } catch (Exception exception) {
            // Never include exception messages, filesystem paths or container-controlled text.
            return error("OPERATION_FAILED", "The container operation could not be completed.");
        }
    }

    private static String requiredString(Map<String, Object> arguments, String key) {
        if (!(arguments.get(key) instanceof String value) || value.isBlank() || value.length() > 8192) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private McpSchema.CallToolResult error(String code, String message) {
        try {
            // outputSchema describes successful results. Keep failures in text content so
            // strict clients can still read the safe code/message without schema rejection.
            return McpSchema.CallToolResult.builder().isError(true)
                    .addTextContent(mapper.writeValueAsString(Map.of("error", Map.of("code", code, "message", message))))
                    .build();
        } catch (IOException exception) {
            return McpSchema.CallToolResult.builder().isError(true)
                    .addTextContent("{\"error\":{\"code\":\"OPERATION_FAILED\",\"message\":\"Operation failed.\"}}")
                    .build();
        }
    }

    private McpSchema.CallToolResult result(Map<String, Object> value) throws IOException {
        return McpSchema.CallToolResult.builder().structuredContent(value)
                .addTextContent(mapper.writeValueAsString(value)).isError(false).build();
    }

    private static Map<String, Object> string() { return Map.of("type", "string"); }
    private static Map<String, Object> nullableString() { return Map.of("type", List.of("string", "null")); }
    private static Map<String, Object> array(Map<String, Object> items) { return Map.of("type", "array", "items", items); }
    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet()),
                "additionalProperties", false);
    }
    private static Map<String, Object> nullableFields(String... fields) {
        var properties = new LinkedHashMap<String, Object>();
        for (String field : fields) properties.put(field, nullableString());
        return properties;
    }
    private static Map<String, Object> signingOutputSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("path", string());
        properties.put("sha256", string());
        @SuppressWarnings("unchecked")
        Map<String, Object> validationFields = (Map<String, Object>) validationSchema().get("properties");
        properties.putAll(validationFields);
        return object(properties);
    }

    private static Map<String, Object> validationSchema() {
        var diagnostics = array(object(Map.of("code", string(), "message", string())));
        var signature = nullableFields("id", "indication", "sub_indication", "signer", "signing_time", "profile");
        signature.put("errors", diagnostics);
        signature.put("warnings", diagnostics);
        return object(Map.of("result", Map.of("type", "string", "enum", List.of("VALID", "INVALID", "INDETERMINATE")),
                "signatures", array(object(signature)), "errors", diagnostics, "warnings", diagnostics));
    }
}
