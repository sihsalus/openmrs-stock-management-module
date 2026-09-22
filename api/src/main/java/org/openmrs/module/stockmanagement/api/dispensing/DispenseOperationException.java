package org.openmrs.module.stockmanagement.api.dispensing;

import org.openmrs.api.APIException;

/** Stable public code; never includes clinical payloads or database messages. */
public class DispenseOperationException extends APIException {
    private final String code;
    public DispenseOperationException(String code) {
        super(code, (Object[]) null);
        this.code = code;
    }
    public String getCode() { return code; }
}
