package org.openmrs.module.stockmanagement.api.dispensing;

import java.util.Date;
import org.openmrs.MedicationDispense;
import org.openmrs.User;
import org.openmrs.annotation.Handler;
import org.openmrs.api.handler.VoidHandler;
import org.openmrs.api.handler.UnvoidHandler;
import org.openmrs.module.fhir2.api.translators.MedicationDispenseStatusTranslator;
import org.openmrs.module.stockmanagement.api.dao.DispenseOperationDao;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Core and FHIR validation/void handlers share the same coordination boundary. */
@Handler(supports = { MedicationDispense.class }, order = 10)
@Component("stockmanagement.atomicDispenseGuard")
public class AtomicDispenseGuard implements Validator, VoidHandler<MedicationDispense>, UnvoidHandler<MedicationDispense> {
    @Autowired @Qualifier("stockmanagement.dispenseOperationDao")
    private DispenseOperationDao dao;
    @Autowired
    private MedicationDispenseStatusTranslator statusTranslator;
    public void setDao(DispenseOperationDao value) { dao = value; }
    public void setStatusTranslator(MedicationDispenseStatusTranslator value) { statusTranslator = value; }

    @Override public boolean supports(Class<?> type) { return MedicationDispense.class.isAssignableFrom(type); }

    @Override public void validate(Object target, Errors errors) {
        if (!AtomicDispensingScope.isActive() && needsCoordinator((MedicationDispense) target)) {
            errors.reject("stockmanagement.atomic.coordinatedOperationRequired");
        }
    }

    @Override public void handle(MedicationDispense target, User user, Date date, String reason) {
        if (!AtomicDispensingScope.isActive() && needsCoordinator(target)) {
            throw new DispenseOperationException("stockmanagement.atomic.coordinatedOperationRequired");
        }
    }

    private boolean needsCoordinator(MedicationDispense dispense) {
        if (dispense == null) { return false; }
        if (dispense.getUuid() != null && dao.latest(dispense.getUuid()) != null) { return true; }
        return AtomicDispensingServiceImpl.enabled() && dispense.getStatus() != null
            && statusTranslator.toFhirResource(dispense.getStatus()) ==
                org.hl7.fhir.r4.model.MedicationDispense.MedicationDispenseStatus.COMPLETED;
    }
}
