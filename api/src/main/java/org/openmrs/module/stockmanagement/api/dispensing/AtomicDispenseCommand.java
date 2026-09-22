package org.openmrs.module.stockmanagement.api.dispensing;

import org.hl7.fhir.r4.model.MedicationDispense;

/** One immutable client intent. Reuse operationUuid only for an identical retry. */
public class AtomicDispenseCommand {
    public enum Action { CREATE, CORRECT, VOID }
    private String operationUuid;
    private String medicationDispenseUuid;
    private int expectedRevision;
    private Action action;
    private String expectedOrderRevision;
    private org.openmrs.Order.FulfillerStatus fulfillerStatus;
    private MedicationDispense medicationDispense;
    private String stockItemUuid;
    private String stockBatchUuid;
    private String packagingUomUuid;
    private String reason;

    public String getExpectedOrderRevision() { return expectedOrderRevision; }
    public void setExpectedOrderRevision(String value) { expectedOrderRevision = value; }
    public org.openmrs.Order.FulfillerStatus getFulfillerStatus() { return fulfillerStatus; }
    public void setFulfillerStatus(org.openmrs.Order.FulfillerStatus value) { fulfillerStatus = value; }
    public String getOperationUuid() { return operationUuid; }
    public void setOperationUuid(String value) { operationUuid = value; }
    public String getMedicationDispenseUuid() { return medicationDispenseUuid; }
    public void setMedicationDispenseUuid(String value) { medicationDispenseUuid = value; }
    public int getExpectedRevision() { return expectedRevision; }
    public void setExpectedRevision(int value) { expectedRevision = value; }
    public Action getAction() { return action; }
    public void setAction(Action value) { action = value; }
    public MedicationDispense getMedicationDispense() { return medicationDispense; }
    public void setMedicationDispense(MedicationDispense value) { medicationDispense = value; }
    public String getStockItemUuid() { return stockItemUuid; }
    public void setStockItemUuid(String value) { stockItemUuid = value; }
    public String getStockBatchUuid() { return stockBatchUuid; }
    public void setStockBatchUuid(String value) { stockBatchUuid = value; }
    public String getPackagingUomUuid() { return packagingUomUuid; }
    public void setPackagingUomUuid(String value) { packagingUomUuid = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
}
