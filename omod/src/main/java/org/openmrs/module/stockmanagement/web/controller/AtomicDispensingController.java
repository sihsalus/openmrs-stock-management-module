package org.openmrs.module.stockmanagement.web.controller;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.StrictErrorHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import org.openmrs.Order;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.stockmanagement.api.dispensing.*;
import org.openmrs.module.stockmanagement.api.model.DispenseOperation;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/** Receipts contain identifiers and revisions, never a second clinical record or inventory balance. */
@Controller("stockmanagement.AtomicDispensingController")
@RequestMapping("/rest/v1/stockmanagement/dispenseoperation")
public class AtomicDispensingController {
    private static final FhirContext FHIR = FhirContext.forR4Cached();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("operationUuid", "action",
        "medicationDispenseUuid", "expectedRevision", "expectedOrderRevision", "fulfillerStatus",
        "stockItemUuid", "stockBatchUuid", "packagingUomUuid", "reason", "medicationDispense"));

    protected AtomicDispensingService service() { return Context.getService(AtomicDispensingService.class); }
    protected boolean authenticated() { return Context.isAuthenticated(); }

    @RequestMapping(method = RequestMethod.POST, consumes = "application/json", produces = "application/json")
    @ResponseBody
    public ResponseEntity<?> apply(@RequestBody Map<String, Object> body) {
        return execute(() -> receipt(service().apply(parse(body))), true);
    }

    @RequestMapping(value = "/{operationUuid}", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public ResponseEntity<?> recover(@PathVariable("operationUuid") String operationUuid) {
        return execute(() -> {
            DispenseOperation result = service().getOperation(operationUuid);
            return result == null ? null : receipt(result);
        }, false);
    }

    @RequestMapping(value = "/latest/{medicationDispenseUuid}", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public ResponseEntity<?> latest(@PathVariable("medicationDispenseUuid") String medicationDispenseUuid) {
        return execute(() -> {
            DispenseOperation result = service().getLatestOperation(medicationDispenseUuid);
            return result == null ? null : receipt(result);
        }, false);
    }

    @RequestMapping(method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public ResponseEntity<?> state(@RequestParam("orderUuid") String orderUuid,
        @RequestParam("locationUuid") String locationUuid) {
        return execute(() -> {
            DispenseOperation latest = service().getLatestForOrder(orderUuid, locationUuid);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("contractVersion", 1);
            result.put("enabled", AtomicDispensingServiceImpl.enabled());
            result.put("orderRevision", latest == null ? null : latest.getUuid());
            return result;
        }, false);
    }

    private ResponseEntity<?> execute(Supplier<Map<String, Object>> action, boolean mutation) {
        if (!authenticated()) { return error(401, "authenticationRequired"); }
        try {
            Map<String, Object> result = action.get();
            return result == null ? error(404, "operationNotFound") : response(200, result);
        } catch (APIAuthenticationException | org.openmrs.api.context.ContextAuthenticationException denied) {
            return error(403, "forbidden");
        } catch (DispenseOperationException failure) {
            String code = failure.getCode().substring("stockmanagement.atomic.".length());
            int status = code.equals("locationForbidden") || code.equals("dispenserMismatch") ? 403
                : code.startsWith("invalid") || code.equals("reasonRequired") || code.equals("quantityPrecision") ? 400 : 409;
            return error(status, code);
        } catch (RuntimeException failure) {
            // A client must recover by operation UUID after a failed/unknown POST, never assume nothing committed.
            return error(503, mutation ? "operationOutcomeUnknown" : "readUnavailable");
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseBody
    public ResponseEntity<?> unreadableBody() { return error(400, "invalidCommand"); }

    static AtomicDispenseCommand parse(Map<String, Object> body) {
        try {
            if (body == null || !FIELDS.containsAll(body.keySet())) { throw new IllegalArgumentException(); }
            AtomicDispenseCommand command = new AtomicDispenseCommand();
            command.setOperationUuid(string(body, "operationUuid"));
            command.setMedicationDispenseUuid(string(body, "medicationDispenseUuid"));
            command.setAction(AtomicDispenseCommand.Action.valueOf(string(body, "action")));
            Object revision = body.get("expectedRevision");
            if (!(revision instanceof Number)) { throw new IllegalArgumentException(); }
            command.setExpectedRevision(new BigDecimal(revision.toString()).intValueExact());
            command.setExpectedOrderRevision(string(body, "expectedOrderRevision"));
            String fulfiller = string(body, "fulfillerStatus");
            command.setFulfillerStatus(fulfiller == null ? null : Order.FulfillerStatus.valueOf(fulfiller));
            command.setStockItemUuid(string(body, "stockItemUuid"));
            command.setStockBatchUuid(string(body, "stockBatchUuid"));
            command.setPackagingUomUuid(string(body, "packagingUomUuid"));
            command.setReason(string(body, "reason"));
            Object clinical = body.get("medicationDispense");
            if (clinical != null) {
                if (!(clinical instanceof Map)) { throw new IllegalArgumentException(); }
                command.setMedicationDispense(FHIR.newJsonParser().setParserErrorHandler(new StrictErrorHandler())
                    .parseResource(org.hl7.fhir.r4.model.MedicationDispense.class, JSON.writeValueAsString(clinical)));
            }
            return command;
        } catch (Exception invalid) {
            throw new DispenseOperationException("stockmanagement.atomic.invalidCommand");
        }
    }

    private static String string(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value != null && !(value instanceof String)) { throw new IllegalArgumentException(); }
        return (String) value;
    }

    static Map<String, Object> receipt(DispenseOperation operation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1);
        result.put("operationUuid", operation.getUuid());
        result.put("medicationDispenseUuid", operation.getMedicationDispense().getUuid());
        result.put("revision", operation.getRevision());
        result.put("action", operation.getAction().name());
        result.put("applied", true);
        return result;
    }

    private static ResponseEntity<?> error(int status, String code) {
        return response(status, Collections.singletonMap("error",
            Collections.singletonMap("code", "stockmanagement.atomic." + code)));
    }
    private static ResponseEntity<?> response(int status, Object body) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }
}
