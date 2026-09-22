package org.openmrs.module.stockmanagement.api.dispensing;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;
import org.openmrs.*;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.fhir2.api.translators.MedicationDispenseTranslator;
import org.openmrs.module.stockmanagement.api.Privileges;
import org.openmrs.module.stockmanagement.api.StockManagementService;
import org.openmrs.module.stockmanagement.api.dao.DispenseOperationDao;
import org.openmrs.module.stockmanagement.api.dao.StockManagementDao;
import org.openmrs.module.stockmanagement.api.model.*;
import org.openmrs.module.stockmanagement.api.dispensing.AtomicDispenseCommand.Action;
import org.openmrs.util.OpenmrsUtil;
import org.openmrs.util.PrivilegeConstants;

public class AtomicDispensingServiceImpl extends BaseOpenmrsService implements AtomicDispensingService {
    public static final String ENABLED_PROPERTY = "stockmanagement.atomicDispensingEnabled";
    private static final FhirContext FHIR = FhirContext.forR4Cached();
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private DispenseOperationDao dao;
    private StockManagementDao stockDao;
    private StockManagementService stockService;
    private MedicationDispenseTranslator<MedicationDispense> translator;

    public void setDao(DispenseOperationDao value) { dao = value; }
    public void setStockDao(StockManagementDao value) { stockDao = value; }
    public void setStockService(StockManagementService value) { stockService = value; }
    public void setTranslator(MedicationDispenseTranslator<MedicationDispense> value) { translator = value; }

    @Override public DispenseOperation apply(AtomicDispenseCommand command) {
        validateCommand(command);
        dao.lockInventory();
        requirePrivilege("app:home.farmacia.editar");
        requirePrivilege(PrivilegeConstants.GET_MEDICATION_DISPENSE);
        // Native save and void both require EDIT; DELETE authorizes purge, which this API never performs.
        // Keep the existing pharmacy action privileges as the separate create/edit/creator-only policy.
        requirePrivilege(PrivilegeConstants.EDIT_MEDICATION_DISPENSE);
        if (command.getAction() != Action.VOID) {
            requirePrivilege(command.getAction() == Action.CREATE
                ? "Task: dispensing.create.dispense" : "Task: dispensing.edit.dispense");
        }

        MedicationDispense existing = Context.getMedicationDispenseService()
            .getMedicationDispenseByUuid(command.getMedicationDispenseUuid());
        String orderUuid = command.getAction() == Action.VOID
            ? (existing == null || existing.getDrugOrder() == null ? null : existing.getDrugOrder().getUuid())
            : prescriptionUuid(command.getMedicationDispense());
        Order locked = dao.lockOrder(orderUuid);
        if (!(locked instanceof DrugOrder) || locked.getVoided()) { fail("orderMissing"); }
        DrugOrder order = (DrugOrder) locked;
        if (existing != null) { dao.refresh(existing); }
        if (command.getAction() == Action.VOID && !Context.hasPrivilege("Task: dispensing.delete.dispense")) {
            requirePrivilege("Task: dispensing.delete.dispense.ifCreator");
            if (existing == null || existing.getDispenser() == null
                || !same(existing.getDispenser().getPerson(), Context.getAuthenticatedUser().getPerson())) {
                fail("dispenserMismatch");
            }
        }

        String hash = fingerprint(command);
        DispenseOperation replay = dao.find(command.getOperationUuid());
        if (replay != null) {
            if (!hash.equals(replay.getRequestHash())
                || !command.getMedicationDispenseUuid().equals(replay.getMedicationDispense().getUuid())) {
                fail("operationConflict");
            }
            requireScope(replay.getMedicationDispense().getLocation());
            return replay;
        }
        DispenseOperation latestOrderOperation = dao.latestForOrder(orderUuid);
        String orderRevision = latestOrderOperation == null ? null : latestOrderOperation.getUuid();
        if (!Objects.equals(command.getExpectedOrderRevision(), orderRevision)) { fail("orderRevisionConflict"); }
        DispenseOperation previous = dao.latest(command.getMedicationDispenseUuid());
        if (command.getExpectedRevision() != (previous == null ? 0 : previous.getRevision())) { fail("revisionConflict"); }
        if (command.getAction() == Action.CREATE) {
            if (!enabled()) { fail("disabled"); }
            if (existing != null || previous != null) { fail("alreadyExists"); }
            if (order.isDiscontinued(new Date()) || !order.isActive()) { fail("orderInactive"); }
        } else {
            if (existing == null || previous == null) { fail("unlinkedDispense"); }
            if (existing.getVoided() || previous.getAction() == Action.VOID) { fail("alreadyVoided"); }
            if (!same(order, existing.getDrugOrder())) { fail("identityMismatch"); }
            requireScope(existing.getLocation());
        }

        MedicationDispense target = existing;
        StockItem item = null;
        StockBatch batch = null;
        StockItemPackagingUOM uom = null;
        Party party = null;
        StockItemTransaction oldMovement = previous == null ? null : previous.getStockTransaction();
        if (command.getAction() != Action.VOID) {
            // Translate into a new object first: invalid requests must not dirty a managed clinical entity.
            target = translator.toOpenmrsType(command.getMedicationDispense());
            target.setUuid(command.getMedicationDispenseUuid());
            validateClinical(target, existing, order);
            requireScope(target.getLocation());
            item = stockDao.getStockItemByUuid(command.getStockItemUuid());
            batch = stockDao.getStockBatchByUuid(command.getStockBatchUuid());
            uom = stockDao.getStockItemPackagingUOMByUuid(command.getPackagingUomUuid());
            party = stockDao.getPartyByLocation(target.getLocation());
            validateStock(target, item, batch, uom, party, oldMovement);
        }
        List<Integer> batches = new ArrayList<>();
        if (oldMovement != null) { batches.add(oldMovement.getStockBatch().getId()); }
        if (batch != null) { batches.add(batch.getId()); }
        dao.lockBatches(batches);

        // Add compensating movements; never rewrite or delete an existing inventory movement.
        StockItemTransaction reversal = null;
        if (oldMovement != null) {
            reversal = movement(oldMovement.getParty(), oldMovement.getStockItem(), oldMovement.getStockBatch(),
                oldMovement.getStockItemPackagingUOM(), oldMovement.getQuantity().negate(), existing);
            stockDao.saveStockItemTransaction(reversal);
        }
        StockItemTransaction deduction = null;
        if (command.getAction() != Action.VOID) {
            BigDecimal quantity = BigDecimal.valueOf(target.getQuantity());
            if (dao.balance(party, batch).compareTo(quantity.multiply(uom.getFactor())) < 0) { fail("insufficientStock"); }
            deduction = movement(party, item, batch, uom, quantity.negate(), target);
            stockDao.saveStockItemTransaction(deduction);
        }

        try (AtomicDispensingScope ignored = AtomicDispensingScope.enter()) {
            if (command.getAction() == Action.VOID) {
                target = Context.getMedicationDispenseService().voidMedicationDispense(existing, command.getReason());
            } else {
                if (existing != null) {
                    target = translator.toOpenmrsType(existing, command.getMedicationDispense());
                }
                target.setUuid(command.getMedicationDispenseUuid());
                target = Context.getMedicationDispenseService().saveMedicationDispense(target);
            }
        }
        // Failure here must also roll back both clinical data and inventory.
        if (order.getFulfillerStatus() != command.getFulfillerStatus()) {
            Context.getOrderService().updateOrderFulfillerStatus(order, command.getFulfillerStatus(), null);
        }
        DispenseOperation operation = new DispenseOperation();
        operation.setUuid(command.getOperationUuid());
        operation.setMedicationDispense(target);
        operation.setRevision(command.getExpectedRevision() + 1);
        operation.setRequestHash(hash);
        operation.setAction(command.getAction());
        operation.setStockTransaction(deduction);
        operation.setReversalTransaction(reversal);
        operation.setCreator(Context.getAuthenticatedUser());
        operation.setDateCreated(new Date());
        operation.setReason(command.getReason());
        return dao.save(operation);
    }

    @Override public DispenseOperation getLatestForOrder(String orderUuid, String locationUuid) {
        requireUuid(orderUuid);
        requireUuid(locationUuid);
        requireScope(Context.getLocationService().getLocationByUuid(locationUuid));
        if (Context.getOrderService().getOrderByUuid(orderUuid) == null) { fail("orderMissing"); }
        return dao.latestForOrder(orderUuid);
    }

    @Override public DispenseOperation getOperation(String uuid) {
        requireUuid(uuid);
        DispenseOperation result = dao.find(uuid);
        if (result != null) { requireScope(result.getMedicationDispense().getLocation()); }
        return result;
    }

    @Override public DispenseOperation getLatestOperation(String uuid) {
        requireUuid(uuid);
        DispenseOperation result = dao.latest(uuid);
        if (result != null) { requireScope(result.getMedicationDispense().getLocation()); }
        return result;
    }

    public static boolean enabled() {
        // Read only this fixed application setting without granting pharmacy access to all settings.
        boolean needsProxy = !Context.hasPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES);
        if (needsProxy) { Context.addProxyPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES); }
        try {
            return "true".equalsIgnoreCase(Context.getAdministrationService().getGlobalProperty(ENABLED_PROPERTY, "false"));
        } finally {
            if (needsProxy) { Context.removeProxyPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES); }
        }
    }

    private void validateCommand(AtomicDispenseCommand command) {
        if (command == null || command.getAction() == null) { fail("invalidCommand"); }
        requireUuid(command.getOperationUuid());
        requireUuid(command.getMedicationDispenseUuid());
        if (command.getExpectedOrderRevision() != null) { requireUuid(command.getExpectedOrderRevision()); }
        if (command.getAction() != Action.VOID && command.getFulfillerStatus() != Order.FulfillerStatus.COMPLETED
            && command.getFulfillerStatus() != Order.FulfillerStatus.IN_PROGRESS) { fail("invalidOrderStatus"); }
        if (command.getExpectedRevision() < 0 || command.getExpectedRevision() == Integer.MAX_VALUE) { fail("invalidRevision"); }
        if (command.getAction() == Action.CREATE && command.getExpectedRevision() != 0) { fail("invalidRevision"); }
        if (command.getAction() != Action.CREATE && (command.getReason() == null
            || command.getReason().trim().isEmpty() || command.getReason().length() > 255)) { fail("reasonRequired"); }
        if (command.getReason() != null && command.getReason().length() > 255) { fail("invalidCommand"); }
        if (command.getAction() == Action.VOID) {
            if (command.getMedicationDispense() != null || command.getStockItemUuid() != null
                || command.getStockBatchUuid() != null || command.getPackagingUomUuid() != null) { fail("invalidCommand"); }
            return;
        }
        org.hl7.fhir.r4.model.MedicationDispense fhir = command.getMedicationDispense();
        if (fhir == null || fhir.getStatus() != org.hl7.fhir.r4.model.MedicationDispense.MedicationDispenseStatus.COMPLETED
            || fhir.getAuthorizingPrescription().size() != 1 || fhir.getPerformer().size() != 1
            || (fhir.hasId() && !command.getMedicationDispenseUuid().equals(fhir.getIdElement().getIdPart()))) { fail("invalidClinicalRecord"); }
        requireUuid(command.getStockItemUuid());
        requireUuid(command.getStockBatchUuid());
        requireUuid(command.getPackagingUomUuid());
    }

    private void validateClinical(MedicationDispense target, MedicationDispense existing, DrugOrder order) {
        if (target == null || target.getPatient() == null || target.getPatient().getVoided()
            || target.getEncounter() == null || target.getEncounter().getVoided() || target.getLocation() == null
            || target.getLocation().getRetired() || target.getDispenser() == null || target.getDispenser().getRetired()
            || target.getStatus() == null || target.getQuantityUnits() == null || target.getDrug() == null
            || target.getQuantity() == null || !Double.isFinite(target.getQuantity()) || target.getQuantity() <= 0
            || target.getDateHandedOver() == null || target.getDateHandedOver().after(new Date(System.currentTimeMillis() + 300000))) {
            fail("invalidClinicalRecord");
        }
        // The existing ledger stores DECIMAL(10,2); never let SQL round a clinical quantity.
        BigDecimal quantity = BigDecimal.valueOf(target.getQuantity()).stripTrailingZeros();
        if (quantity.scale() > 2 || quantity.compareTo(new BigDecimal("100000000")) >= 0) {
            fail("quantityPrecision");
        }
        if (!same(target.getPatient(), order.getPatient()) || !same(target.getPatient(), target.getEncounter().getPatient())
            || !same(target.getDrugOrder(), order) || !same(target.getEncounter(), order.getEncounter())) { fail("identityMismatch"); }
        if (!same(target.getDrug(), order.getDrug()) && !Boolean.TRUE.equals(target.getWasSubstituted())) {
            fail("substitutionRequired");
        }
        if (Boolean.TRUE.equals(target.getWasSubstituted())) {
            requirePrivilege("Task: dispensing.create.dispense.allowSubstitutions");
            if (target.getSubstitutionType() == null || target.getSubstitutionReason() == null
                || target.getSubstitutionType().getRetired() || target.getSubstitutionReason().getRetired()) {
                fail("substitutionRequired");
            }
        }
        if (existing != null && (!same(target.getPatient(), existing.getPatient())
            || !same(target.getDrugOrder(), existing.getDrugOrder()) || !same(target.getEncounter(), existing.getEncounter())
            || !same(target.getLocation(), existing.getLocation()) || !same(target.getDispenser(), existing.getDispenser()))) { fail("identityMismatch"); }
        if (existing == null && !same(target.getDispenser().getPerson(), Context.getAuthenticatedUser().getPerson())) {
            fail("dispenserMismatch");
        }
    }

    private void validateStock(MedicationDispense target, StockItem item, StockBatch batch, StockItemPackagingUOM uom,
        Party party, StockItemTransaction previous) {
        if (item == null || item.getVoided() || batch == null || batch.getVoided() || uom == null || uom.getVoided()
            || party == null || party.getVoided()) { fail("invalidStockSelection"); }
        if (!same(item.getDrug(), target.getDrug()) || !same(batch.getStockItem(), item) || !same(uom.getStockItem(), item)) {
            fail("stockMismatch");
        }
        if (uom.getFactor() == null || uom.getFactor().signum() <= 0 || !same(uom.getPackagingUom(), target.getQuantityUnits())) {
            fail("unitMismatch");
        }
        if (batch.getExpiration() != null && OpenmrsUtil.firstSecondOfDay(new Date()).after(batch.getExpiration())) {
            // A recording correction may reduce the existing consumption of the same expired lot.
            // It may neither consume more stock nor record a handover after that lot expired.
            boolean correction = previous != null && same(previous.getStockBatch(), batch)
                && same(previous.getParty(), party) && same(previous.getStockItem(), item)
                && previous.getStockItemPackagingUOM() != null && previous.getStockItemPackagingUOM().getFactor() != null
                && previous.getStockItemPackagingUOM().getFactor().signum() > 0
                && previous.getQuantity().signum() < 0
                && BigDecimal.valueOf(target.getQuantity()).multiply(uom.getFactor()).compareTo(
                    previous.getQuantity().negate().multiply(previous.getStockItemPackagingUOM().getFactor())) <= 0
                && !OpenmrsUtil.firstSecondOfDay(target.getDateHandedOver()).after(batch.getExpiration());
            if (!correction) { fail("expiredBatch"); }
        }
    }

    private void requireScope(Location location) {
        if (location == null || !stockService.userHasStockManagementPrivilege(Context.getAuthenticatedUser(), location, null,
            Privileges.TASK_STOCKMANAGEMENT_STOCKITEMS_DISPENSE)) { fail("locationForbidden"); }
    }

    private static StockItemTransaction movement(Party party, StockItem item, StockBatch batch, StockItemPackagingUOM uom,
        BigDecimal quantity, MedicationDispense clinical) {
        StockItemTransaction movement = new StockItemTransaction();
        movement.setParty(party);
        movement.setStockItem(item);
        movement.setStockBatch(batch);
        movement.setStockItemPackagingUOM(uom);
        movement.setQuantity(quantity);
        movement.setPatient(clinical.getPatient());
        movement.setOrder(clinical.getDrugOrder());
        movement.setEncounter(clinical.getEncounter());
        movement.setCreator(Context.getAuthenticatedUser());
        movement.setDateCreated(new Date());
        return movement;
    }

    private static String prescriptionUuid(org.hl7.fhir.r4.model.MedicationDispense dispense) {
        String reference = dispense.getAuthorizingPrescriptionFirstRep().getReference();
        if (reference == null || !reference.startsWith("MedicationRequest/")) { fail("invalidOrderReference"); }
        String uuid = reference.substring("MedicationRequest/".length());
        requireUuid(uuid);
        return uuid;
    }

    static String fingerprint(AtomicDispenseCommand command) {
        try {
            Map<String, Object> fields = new TreeMap<>();
            fields.put("action", command.getAction().name());
            fields.put("expectedOrderRevision", command.getExpectedOrderRevision());
            fields.put("fulfillerStatus", command.getFulfillerStatus() == null ? null : command.getFulfillerStatus().name());
            fields.put("medicationDispenseUuid", command.getMedicationDispenseUuid());
            fields.put("expectedRevision", command.getExpectedRevision());
            fields.put("stockItemUuid", command.getStockItemUuid());
            fields.put("stockBatchUuid", command.getStockBatchUuid());
            fields.put("packagingUomUuid", command.getPackagingUomUuid());
            fields.put("reason", command.getReason());
            fields.put("medicationDispense", command.getMedicationDispense() == null ? null
                : JSON.readValue(FHIR.newJsonParser().encodeResourceToString(command.getMedicationDispense()), Map.class));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(fields));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) { result.append(String.format("%02x", value & 0xff)); }
            return result.toString();
        } catch (Exception error) { throw new DispenseOperationException("stockmanagement.atomic.invalidCommand"); }
    }

    private static boolean same(OpenmrsObject left, OpenmrsObject right) {
        return left != null && right != null && left.getUuid() != null && left.getUuid().equals(right.getUuid());
    }
    static void requireUuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) { fail("invalidUuid"); }
        } catch (IllegalArgumentException error) { fail("invalidUuid"); }
    }
    private static void requirePrivilege(String privilege) { Context.requirePrivilege(privilege); }
    private static void fail(String code) { throw new DispenseOperationException("stockmanagement.atomic." + code); }
}
