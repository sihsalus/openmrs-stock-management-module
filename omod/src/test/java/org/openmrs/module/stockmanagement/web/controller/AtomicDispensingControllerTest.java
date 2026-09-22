package org.openmrs.module.stockmanagement.web.controller;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.openmrs.MedicationDispense;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.module.stockmanagement.api.dispensing.*;
import org.openmrs.module.stockmanagement.api.model.DispenseOperation;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises real request binding and response serialization without clinical fixtures. */
public class AtomicDispensingControllerTest {
    private static final String PATH = "/rest/v1/stockmanagement/dispenseoperation";
    private static final String OPERATION = "8d230ddd-c500-41a2-93c0-c04ff9a5b8d9";
    private static final String DISPENSE = "d11ed359-0c1f-4249-b0c1-40e9c5496c09";
    private static final String BODY = "{\"action\":\"VOID\",\"operationUuid\":\"" + OPERATION
        + "\",\"medicationDispenseUuid\":\"" + DISPENSE
        + "\",\"expectedRevision\":3,\"reason\":\"Synthetic recording error\"}";
    private AtomicDispensingService service;
    private MockMvc http;
    private boolean authenticated;

    @Before public void setup() {
        service = mock(AtomicDispensingService.class);
        authenticated = true;
        http = MockMvcBuilders.standaloneSetup(new AtomicDispensingController() {
            @Override protected AtomicDispensingService service() { return service; }
            @Override protected boolean authenticated() { return authenticated; }
        }).build();
    }

    @Test public void bindsACommandAndReturnsAnUncacheableReceipt() throws Exception {
        when(service.apply(any())).thenReturn(receipt());
        http.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.operationUuid").value(OPERATION))
            .andExpect(jsonPath("$.medicationDispenseUuid").value(DISPENSE))
            .andExpect(jsonPath("$.revision").value(4)).andExpect(jsonPath("$.applied").value(true))
            .andExpect(jsonPath("$.patient").doesNotExist()).andExpect(jsonPath("$.reason").doesNotExist());
        ArgumentCaptor<AtomicDispenseCommand> command = ArgumentCaptor.forClass(AtomicDispenseCommand.class);
        verify(service).apply(command.capture());
        assertEquals(3, command.getValue().getExpectedRevision());
        assertEquals(AtomicDispenseCommand.Action.VOID, command.getValue().getAction());
    }

    @Test public void recoveryReturnsTheOriginalReceiptOrAnExplicitNotFound() throws Exception {
        when(service.getOperation(OPERATION)).thenReturn(receipt());
        http.perform(get(PATH + "/" + OPERATION)).andExpect(status().isOk())
            .andExpect(jsonPath("$.operationUuid").value(OPERATION));
        when(service.getOperation(OPERATION)).thenReturn(null);
        http.perform(get(PATH + "/" + OPERATION)).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("stockmanagement.atomic.operationNotFound"))
            .andExpect(header().string("Cache-Control", "no-store"));
        verify(service, never()).apply(any());
    }

    @Test public void latestRevisionUsesTheMedicationDispenseLookup() throws Exception {
        when(service.getLatestOperation(DISPENSE)).thenReturn(receipt());
        http.perform(get(PATH + "/latest/" + DISPENSE)).andExpect(status().isOk())
            .andExpect(jsonPath("$.revision").value(4));
        verify(service).getLatestOperation(DISPENSE);
    }

    @Test public void aMissingSessionCannotExecuteACommand() throws Exception {
        authenticated = false;
        http.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("stockmanagement.atomic.authenticationRequired"));
        verifyNoInteractions(service);
    }

    @Test public void authorizationErrorsDoNotBecomeUnknownOutcomes() throws Exception {
        when(service.apply(any())).thenThrow(new APIAuthenticationException("synthetic denied privilege"));
        http.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("stockmanagement.atomic.forbidden"));
    }

    @Test public void conflictsAreExplicitAndNeverRetriedByTheController() throws Exception {
        when(service.apply(any())).thenThrow(new DispenseOperationException("stockmanagement.atomic.operationConflict"));
        http.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("stockmanagement.atomic.operationConflict"));
        verify(service, times(1)).apply(any());
    }

    @Test public void unexpectedStorageFailuresReturnOnlyARecoveryCode() throws Exception {
        when(service.apply(any())).thenThrow(new IllegalStateException("synthetic private payload marker"));
        String body = http.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("stockmanagement.atomic.operationOutcomeUnknown"))
            .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("payload marker"));
        assertFalse(body.contains("IllegalStateException"));
    }

    @Test public void rejectsMalformedFieldsBeforeCallingTheService() throws Exception {
        for (String invalid : new String[] {
            BODY.replace("\"expectedRevision\":3", "\"expectedRevision\":3.5"),
            BODY.replace("\"expectedRevision\":3", "\"expectedRevision\":2147483648"),
            BODY.replace("\"expectedRevision\":3", "\"expectedRevision\":\"3\""),
            BODY.replace("\"VOID\"", "\"UNKNOWN\""),
            BODY.replace("\"action\"", "\"unknown\""),
            BODY.substring(0, BODY.length() - 1) + ",\"medicationDispense\":\"not an object\"}",
            "{invalid json"
        }) {
            http.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("stockmanagement.atomic.invalidCommand"));
        }
        verifyNoInteractions(service);
    }

    private DispenseOperation receipt() {
        DispenseOperation result = new DispenseOperation();
        result.setUuid(OPERATION);
        MedicationDispense dispense = new MedicationDispense();
        dispense.setUuid(DISPENSE);
        result.setMedicationDispense(dispense);
        result.setAction(AtomicDispenseCommand.Action.VOID);
        result.setRevision(4);
        return result;
    }
}
