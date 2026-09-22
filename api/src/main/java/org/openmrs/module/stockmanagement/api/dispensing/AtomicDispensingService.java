package org.openmrs.module.stockmanagement.api.dispensing;

import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.stockmanagement.api.Privileges;
import org.openmrs.module.stockmanagement.api.model.DispenseOperation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public interface AtomicDispensingService extends OpenmrsService {
    @Authorized(Privileges.TASK_STOCKMANAGEMENT_STOCKITEMS_DISPENSE)
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    DispenseOperation apply(AtomicDispenseCommand command);

    @Authorized(value = { Privileges.TASK_STOCKMANAGEMENT_STOCKITEMS_DISPENSE, "Get Medication Dispenses", "Get Orders" }, requireAll = true)
    @Transactional(readOnly = true)
    DispenseOperation getLatestForOrder(String orderUuid, String dispensingLocationUuid);

    @Authorized(value = { Privileges.TASK_STOCKMANAGEMENT_STOCKITEMS_DISPENSE, "Get Medication Dispenses" }, requireAll = true)
    @Transactional(readOnly = true)
    DispenseOperation getOperation(String operationUuid);

    @Authorized(value = { Privileges.TASK_STOCKMANAGEMENT_STOCKITEMS_DISPENSE, "Get Medication Dispenses" }, requireAll = true)
    @Transactional(readOnly = true)
    DispenseOperation getLatestOperation(String medicationDispenseUuid);
}
