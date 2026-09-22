package org.openmrs.module.stockmanagement.api.dispensing;

import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.*;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.openmrs.*;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.fhir2.api.translators.MedicationDispenseTranslator;
import org.openmrs.module.fhir2.api.FhirMedicationDispenseService;
import org.openmrs.api.APIException;
import org.openmrs.module.stockmanagement.EntityUtil;
import org.openmrs.module.stockmanagement.api.Privileges;
import org.openmrs.module.stockmanagement.api.StockManagementService;
import org.openmrs.module.stockmanagement.api.dao.DispenseOperationDao;
import org.openmrs.module.stockmanagement.api.dao.StockManagementDao;
import org.openmrs.module.stockmanagement.api.dto.StockItemPackagingUOMDTO;
import org.openmrs.module.stockmanagement.api.model.*;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Uses real service transaction boundaries: assertions run after commit or rollback. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AtomicDispensingServiceTest extends BaseModuleContextSensitiveTest {
    @Autowired private DbSessionFactory sessions;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired @Qualifier("stockmanagement.dispenseOperationDao") private DispenseOperationDao dao;
    @Autowired @Qualifier("stockmanagement.atomicStockDao") private StockManagementDao stockDao;
    @Autowired @Qualifier("medicationDispenseTranslatorImpl")
    private MedicationDispenseTranslator<MedicationDispense> translator;
    @Autowired private FhirMedicationDispenseService fhirService;
    private AtomicDispensingService service;
    private String itemUuid;
    private String batchUuid;
    private String uomUuid;
    private String orderUuid;
    private String scopeUuid;
    private Integer partyId;
    private Integer batchId;
    private org.hl7.fhir.r4.model.MedicationDispense clinical;

    @Before public void prepareCommittedSyntheticFixtures() throws Exception {
        deleteAllData();
        initializeInMemoryDatabase();
        executeDataSet("org/openmrs/include/standardTestDataset.xml");
        Context.authenticate(getCredentials());
        executeDataSet(EntityUtil.STOCK_OPERATION_TYPE_DATA_SET);
        executeDataSet("org/openmrs/api/include/MedicationDispenseServiceTest-initialData.xml");
        executeDataSet(EntityUtil.BASE_DATASET_DIR + "AtomicDispensing.xml");
        service = Context.getService(AtomicDispensingService.class);
        tx(() -> {
            Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(
                AtomicDispensingServiceImpl.ENABLED_PROPERTY, "true"));
            MedicationDispense template = Context.getMedicationDispenseService().getMedicationDispense(1);
            DrugOrder order = template.getDrugOrder();
            order.setDateStopped(null);
            order.setAutoExpireDate(null);
            order.setFulfillerStatus(null);
            orderUuid = order.getUuid();
            template.setDispenser(Context.getProviderService().getProvider(1));
            clinical = translator.toFhirResource(template);
            clinical.setId((String) null);
            clinical.setWhenHandedOver(new Date());
            Location location = template.getLocation();

            StockItem item = audit(new StockItem());
            item.setDrug(order.getDrug());
            item.setConcept(order.getConcept());
            sessions.getCurrentSession().save(item);
            itemUuid = item.getUuid();
            StockItemPackagingUOM uom = audit(new StockItemPackagingUOM());
            uom.setStockItem(item);
            uom.setPackagingUom(template.getQuantityUnits());
            uom.setFactor(BigDecimal.ONE);
            sessions.getCurrentSession().save(uom);
            uomUuid = uom.getUuid();
            StockBatch batch = audit(new StockBatch());
            batch.setStockItem(item);
            batch.setBatchNo("SYNTHETIC-ATOMIC-01");
            sessions.getCurrentSession().save(batch);
            batchUuid = batch.getUuid();
            batchId = batch.getId();
            Party party = audit(new Party());
            party.setLocation(location);
            sessions.getCurrentSession().save(party);
            partyId = party.getId();
            StockItemTransaction opening = new StockItemTransaction();
            opening.setStockItem(item);
            opening.setStockBatch(batch);
            opening.setStockItemPackagingUOM(uom);
            opening.setParty(party);
            opening.setQuantity(new BigDecimal("20"));
            opening.setCreator(Context.getAuthenticatedUser());
            opening.setDateCreated(new Date());
            sessions.getCurrentSession().save(opening);

            Privilege dispense = new Privilege(Privileges.TASK_STOCKMANAGEMENT_STOCKITEMS_DISPENSE);
            Context.getUserService().savePrivilege(dispense);
            Role role = new Role("Atomic dispensing test scope");
            role.addPrivilege(dispense);
            Context.getUserService().saveRole(role);
            Context.getAuthenticatedUser().addRole(role);
            UserRoleScope scope = audit(new UserRoleScope());
            scope.setRole(role);
            scope.setUser(Context.getAuthenticatedUser());
            scope.setPermanent(true);
            scope.setEnabled(true);
            sessions.getCurrentSession().save(scope);
            scopeUuid = scope.getUuid();
            UserRoleScopeLocation scopeLocation = audit(new UserRoleScopeLocation());
            scopeLocation.setUserRoleScope(scope);
            scopeLocation.setLocation(location);
            scopeLocation.setEnableDescendants(false);
            sessions.getCurrentSession().save(scopeLocation);
            UserRoleScopeOperationType scopeOperation = audit(new UserRoleScopeOperationType());
            scopeOperation.setUserRoleScope(scope);
            scopeOperation.setStockOperationType((StockOperationType) sessions.getCurrentSession().get(StockOperationType.class, 0));
            sessions.getCurrentSession().save(scopeOperation);
            return null;
        });
        Context.clearSession();
    }

    @After public void removeCommittedSyntheticFixtures() {
        deleteAllData();
    }

    @Test public void createsClinicalRecordAndOneDeductionAndReplaysTheReceipt() {
        AtomicDispenseCommand command = create("4");
        DispenseOperation first = service.apply(command);
        assertEquals(first.getId(), service.apply(command).getId());
        assertBalance("16");
        assertEquals(1, receiptCount());
        assertEquals(2, movementCount());
        assertEquals(Double.valueOf(4), Context.getMedicationDispenseService()
            .getMedicationDispenseByUuid(command.getMedicationDispenseUuid()).getQuantity());
        assertEquals(Order.FulfillerStatus.IN_PROGRESS, Context.getOrderService().getOrderByUuid(orderUuid).getFulfillerStatus());
    }

    @Test public void rejectsReuseOfAnOperationKeyWithDifferentQuantity() {
        AtomicDispenseCommand command = create("4");
        service.apply(command);
        command.getMedicationDispense().getQuantity().setValue(new BigDecimal("5"));
        expectCode("operationConflict", () -> service.apply(command));
        assertBalance("16");
        assertEquals(1, receiptCount());
    }

    @Test public void insufficientStockLeavesNoClinicalRecordReceiptOrDeduction() {
        AtomicDispenseCommand command = create("21");
        expectCode("insufficientStock", () -> service.apply(command));
        assertNull(Context.getMedicationDispenseService().getMedicationDispenseByUuid(command.getMedicationDispenseUuid()));
        assertBalance("20");
        assertEquals(0, receiptCount());
        assertEquals(1, movementCount());
    }

    @Test public void receiptFailureRollsBackAlreadyFlushedClinicalInventoryAndOrderChanges() {
        AtomicDispensingServiceImpl target = AopTestUtils.getUltimateTargetObject(service);
        DispenseOperationDao failingDao = spy(dao);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("synthetic receipt failure after flush");
        }).when(failingDao).save(any(DispenseOperation.class));
        AtomicDispenseCommand command = create("4");
        target.setDao(failingDao);
        try {
            service.apply(command);
            fail("Expected injected failure");
        } catch (IllegalStateException expected) {
            assertEquals("synthetic receipt failure after flush", expected.getMessage());
        } finally {
            target.setDao(dao);
            Context.clearSession();
        }
        assertNull(Context.getMedicationDispenseService().getMedicationDispenseByUuid(command.getMedicationDispenseUuid()));
        assertNull(Context.getOrderService().getOrderByUuid(orderUuid).getFulfillerStatus());
        assertBalance("20");
        assertEquals(0, receiptCount());
        assertEquals(1, movementCount());
        service.apply(command);
        assertBalance("16");
        assertEquals(1, receiptCount());
    }

    @Test public void correctionCompensatesPreviousQuantityAndVoidRestoresTheOpeningBalance() {
        AtomicDispenseCommand first = create("4");
        service.apply(first);
        AtomicDispenseCommand correction = create("7");
        correction.setAction(AtomicDispenseCommand.Action.CORRECT);
        correction.setMedicationDispenseUuid(first.getMedicationDispenseUuid());
        correction.setExpectedRevision(1);
        correction.setExpectedOrderRevision(first.getOperationUuid());
        correction.setReason("Synthetic correction of a recorded quantity");
        service.apply(correction);
        assertBalance("13");
        assertEquals(4, movementCount());
        AtomicDispenseCommand cancellation = new AtomicDispenseCommand();
        cancellation.setAction(AtomicDispenseCommand.Action.VOID);
        cancellation.setOperationUuid(UUID.randomUUID().toString());
        cancellation.setMedicationDispenseUuid(first.getMedicationDispenseUuid());
        cancellation.setExpectedRevision(2);
        cancellation.setExpectedOrderRevision(correction.getOperationUuid());
        cancellation.setReason("Synthetic cancellation of an erroneous record");
        service.apply(cancellation);
        service.apply(cancellation);
        assertBalance("20");
        assertEquals(5, movementCount());
        assertEquals(3, receiptCount());
        assertTrue(Context.getMedicationDispenseService().getMedicationDispenseByUuid(first.getMedicationDispenseUuid()).getVoided());
    }

    @Test public void rejectsStaleOrderRevisionForASecondDispense() {
        AtomicDispenseCommand first = create("4");
        service.apply(first);
        AtomicDispenseCommand second = create("3");
        expectCode("orderRevisionConflict", () -> service.apply(second));
        assertBalance("16");
        second.setExpectedOrderRevision(first.getOperationUuid());
        service.apply(second);
        assertBalance("13");
    }

    @Test public void refusesAnUnscopedLocationEvenForTheAdministrator() {
        tx(() -> {
            sessions.getCurrentSession().createQuery("update stockmanagement.UserRoleScope set enabled = false where uuid = :uuid")
                .setParameter("uuid", scopeUuid).executeUpdate();
            return null;
        });
        Context.clearSession();
        expectCode("locationForbidden", () -> service.apply(create("4")));
        assertBalance("20");
        assertEquals(0, receiptCount());
    }

    @Test public void rejectsQuantitiesThatCannotBeRepresentedExactlyInTheStockLedger() {
        expectCode("quantityPrecision", () -> service.apply(create("2.001")));
        expectCode("quantityPrecision", () -> service.apply(create("100000000")));
        assertBalance("20");
        assertEquals(0, receiptCount());
        service.apply(create("2.25"));
        assertBalance("17.75");
    }

    @Test public void completedFhirCreationMustUseTheCoordinatingTransaction() {
        org.hl7.fhir.r4.model.MedicationDispense payload = create("4").getMedicationDispense();
        expectCoordinatorRejection(() -> fhirService.create(payload));
        assertBalance("20");
        assertEquals(0, receiptCount());
    }

    @Test public void linkedRecordsRemainProtectedAfterDisablingNewAtomicDispenses() {
        AtomicDispenseCommand command = create("4");
        service.apply(command);
        Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(
            AtomicDispensingServiceImpl.ENABLED_PROPERTY, "false"));
        MedicationDispense saved = Context.getMedicationDispenseService()
            .getMedicationDispenseByUuid(command.getMedicationDispenseUuid());
        saved.setQuantity(8.0);
        expectCoordinatorRejection(() -> Context.getMedicationDispenseService().saveMedicationDispense(saved));
        expectCode("coordinatedOperationRequired", () -> fhirService.delete(command.getMedicationDispenseUuid()));
        MedicationDispense reloaded = Context.getMedicationDispenseService()
            .getMedicationDispenseByUuid(command.getMedicationDispenseUuid());
        assertEquals(Double.valueOf(4), reloaded.getQuantity());
        assertFalse(reloaded.getVoided());
        assertBalance("16");
        assertEquals(1, receiptCount());
    }

    @Test public void recordsAnAuthorizedSubstitutionAgainstTheDispensedDrugStock() {
        AtomicDispenseCommand command = create("4");
        String dispensedDrug = prepareSubstitution(command);
        DispenseOperation result = service.apply(command);
        assertEquals(dispensedDrug, result.getMedicationDispense().getDrug().getUuid());
        assertTrue(result.getMedicationDispense().getWasSubstituted());
        assertBalance("20");
        BigDecimal substitutedBalance = tx(() -> dao.balance(
            (Party) sessions.getCurrentSession().get(Party.class, partyId), result.getStockTransaction().getStockBatch()));
        assertEquals(0, new BigDecimal("16").compareTo(substitutedBalance));
    }

    @Test public void aUsedPackagingFactorCannotRewriteTheHistoricalBalance() {
        service.apply(create("4"));
        StockManagementService warehouse = Context.getService(StockManagementService.class);
        StockItemPackagingUOM unit = warehouse.getStockItemPackagingUOMByUuid(uomUuid);
        unit.setFactor(new BigDecimal("10"));
        expectCode("historicalUnitImmutable", () -> warehouse.saveStockItemPackagingUOM(unit));
        assertBalance("16");
        assertEquals(0, BigDecimal.ONE.compareTo(warehouse.getStockItemPackagingUOMByUuid(uomUuid).getFactor()));
    }

    @Test public void aUsedPackagingConceptCannotChangeTheMeaningOfHistoricalQuantities() {
        StockManagementService warehouse = Context.getService(StockManagementService.class);
        StockItemPackagingUOM unit = warehouse.getStockItemPackagingUOMByUuid(uomUuid);
        unit.setPackagingUom(Context.getConceptService().getConcept(22));
        expectCode("historicalUnitImmutable", () -> warehouse.saveStockItemPackagingUOM(unit));
        assertEquals(Integer.valueOf(51), warehouse.getStockItemPackagingUOMByUuid(uomUuid).getPackagingUom().getId());
        assertBalance("20");
    }

    @Test public void anUnusedPackagingDefinitionCanStillBeCorrected() {
        StockManagementService warehouse = Context.getService(StockManagementService.class);
        StockItemPackagingUOM unused = new StockItemPackagingUOM();
        unused.setStockItem(warehouse.getStockItemByUuid(itemUuid));
        unused.setPackagingUom(Context.getConceptService().getConcept(22));
        unused.setFactor(new BigDecimal("5"));
        unused = warehouse.saveStockItemPackagingUOM(unused);
        unused.setFactor(new BigDecimal("6"));
        warehouse.saveStockItemPackagingUOM(unused);
        Context.clearSession();
        assertEquals(0, new BigDecimal("6").compareTo(warehouse.getStockItemPackagingUOMByUuid(unused.getUuid()).getFactor()));
    }

    @Test public void thePackagingDtoPathAlsoProtectsHistoricalFactors() {
        StockManagementService warehouse = Context.getService(StockManagementService.class);
        StockItemPackagingUOMDTO edited = new StockItemPackagingUOMDTO();
        edited.setUuid(uomUuid);
        edited.setStockItemUuid(itemUuid);
        edited.setPackagingUomUuid(Context.getConceptService().getConcept(51).getUuid());
        edited.setFactor(new BigDecimal("10"));
        expectCode("historicalUnitImmutable", () -> warehouse.saveStockItemPackagingUOM(edited));
        assertBalance("20");
    }

    @Test public void aUsedBatchCannotBeReassignedToAnotherStockItem() {
        AtomicDispenseCommand substitution = create("4");
        prepareSubstitution(substitution);
        expectCode("historicalBatchImmutable", () -> tx(() -> {
            dao.lockInventory();
            StockBatch batch = stockDao.getStockBatchByUuid(batchUuid);
            batch.setStockItem(stockDao.getStockItemByUuid(substitution.getStockItemUuid()));
            stockDao.saveStockBatch(batch);
            return null;
        }));
        assertEquals(itemUuid, stockDao.getStockBatchByUuid(batchUuid).getStockItem().getUuid());
        assertBalance("20");
    }

    @Test public void aUsedStockItemCannotBeReassignedToAnotherDrug() {
        StockManagementService warehouse = Context.getService(StockManagementService.class);
        StockItem item = warehouse.getStockItemByUuid(itemUuid);
        String originalDrug = item.getDrug().getUuid();
        item.setDrug(Context.getConceptService().getDrug(3));
        expectCode("historicalItemImmutable", () -> warehouse.saveStockItem(item));
        assertEquals(originalDrug, warehouse.getStockItemByUuid(itemUuid).getDrug().getUuid());
        assertBalance("20");
    }

    @Test public void anExpiredBatchAllowsARecordingCorrectionWithoutAdditionalConsumption() {
        AtomicDispenseCommand first = create("4");
        first.getMedicationDispense().setWhenHandedOver(new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)));
        service.apply(first);
        expireFixtureBatch();
        AtomicDispenseCommand correction = correctionOf(first, "3");
        service.apply(correction);
        assertBalance("17");
        assertEquals(2, receiptCount());
        assertEquals(4, movementCount());
    }

    @Test public void anExpiredBatchCannotBeUsedToIncreaseConsumptionOrRecordDeliveryAfterExpiry() {
        AtomicDispenseCommand first = create("4");
        first.getMedicationDispense().setWhenHandedOver(new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)));
        service.apply(first);
        expireFixtureBatch();
        expectCode("expiredBatch", () -> service.apply(correctionOf(first, "5")));
        AtomicDispenseCommand wrongDate = correctionOf(first, "3");
        wrongDate.getMedicationDispense().setWhenHandedOver(new Date());
        expectCode("expiredBatch", () -> service.apply(wrongDate));
        assertBalance("16");
        assertEquals(1, receiptCount());
        assertEquals(2, movementCount());
    }

    @Test public void aNewDispenseCannotSelectAnExpiredBatch() {
        expireFixtureBatch();
        AtomicDispenseCommand first = create("4");
        first.getMedicationDispense().setWhenHandedOver(new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)));
        expectCode("expiredBatch", () -> service.apply(first));
        assertBalance("20");
        assertEquals(0, receiptCount());
    }

    @Test public void anExpiredCorrectionComparesBaseUnitsWhenThePackagingChanges() {
        AtomicDispenseCommand first = create("4");
        first.getMedicationDispense().setWhenHandedOver(new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)));
        service.apply(first);
        expireFixtureBatch();
        StockManagementService warehouse = Context.getService(StockManagementService.class);
        StockItemPackagingUOM packaging = audit(new StockItemPackagingUOM());
        packaging.setStockItem(warehouse.getStockItemByUuid(itemUuid));
        packaging.setPackagingUom(Context.getConceptService().getConcept(22));
        packaging.setFactor(new BigDecimal("10"));
        warehouse.saveStockItemPackagingUOM(packaging);
        AtomicDispenseCommand correction = correctionOf(first, "1");
        MedicationDispense changedUnit = translator.toOpenmrsType(correction.getMedicationDispense());
        changedUnit.setQuantityUnits(packaging.getPackagingUom());
        correction.setMedicationDispense(translator.toFhirResource(changedUnit));
        correction.getMedicationDispense().setId((String) null);
        correction.setPackagingUomUuid(packaging.getUuid());
        expectCode("expiredBatch", () -> service.apply(correction));
        assertBalance("16");
        assertEquals(1, receiptCount());
    }

    private AtomicDispenseCommand correctionOf(AtomicDispenseCommand first, String quantity) {
        AtomicDispenseCommand correction = create(quantity);
        correction.setAction(AtomicDispenseCommand.Action.CORRECT);
        correction.setMedicationDispenseUuid(first.getMedicationDispenseUuid());
        correction.setMedicationDispense(first.getMedicationDispense().copy());
        correction.getMedicationDispense().getQuantity().setValue(new BigDecimal(quantity));
        correction.setExpectedRevision(1);
        correction.setExpectedOrderRevision(first.getOperationUuid());
        correction.setReason("Synthetic correction of a recorded quantity");
        return correction;
    }

    private void expireFixtureBatch() {
        tx(() -> {
            StockBatch batch = (StockBatch) sessions.getCurrentSession().get(StockBatch.class, batchId);
            batch.setExpiration(new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(5)));
            return null;
        });
        Context.clearSession();
    }

    @Test public void aDrugChangeWithoutDocumentedSubstitutionIsRejected() {
        AtomicDispenseCommand command = create("4");
        prepareSubstitution(command);
        command.getMedicationDispense().setSubstitution(null);
        expectCode("substitutionRequired", () -> service.apply(command));
        assertBalance("20");
        assertEquals(0, receiptCount());
    }

    private String prepareSubstitution(AtomicDispenseCommand command) {
        return tx(() -> {
            Drug alternative = Context.getConceptService().getDrug(3);
            StockItem item = audit(new StockItem());
            item.setDrug(alternative);
            item.setConcept(alternative.getConcept());
            sessions.getCurrentSession().save(item);
            StockItemPackagingUOM uom = audit(new StockItemPackagingUOM());
            uom.setStockItem(item);
            uom.setPackagingUom(Context.getConceptService().getConcept(51));
            uom.setFactor(BigDecimal.ONE);
            sessions.getCurrentSession().save(uom);
            StockBatch batch = audit(new StockBatch());
            batch.setStockItem(item);
            batch.setBatchNo("SYNTHETIC-SUBSTITUTION");
            sessions.getCurrentSession().save(batch);
            StockItemTransaction opening = new StockItemTransaction();
            opening.setStockItem(item);
            opening.setStockBatch(batch);
            opening.setStockItemPackagingUOM(uom);
            opening.setParty((Party) sessions.getCurrentSession().get(Party.class, partyId));
            opening.setQuantity(new BigDecimal("20"));
            opening.setCreator(Context.getAuthenticatedUser());
            opening.setDateCreated(new Date());
            sessions.getCurrentSession().save(opening);
            org.hl7.fhir.r4.model.MedicationDispense converted;
            // Copy the native fixture before translating, without persisting a changed clinical record.
            MedicationDispense temporary = translator.toOpenmrsType(command.getMedicationDispense());
            temporary.setDrug(alternative);
            temporary.setConcept(alternative.getConcept());
            temporary.setWasSubstituted(true);
            temporary.setSubstitutionType(Context.getConceptService().getConcept(11410));
            temporary.setSubstitutionReason(Context.getConceptService().getConcept(11510));
            converted = translator.toFhirResource(temporary);
            converted.setId((String) null);
            command.setMedicationDispense(converted);
            command.setStockItemUuid(item.getUuid());
            command.setStockBatchUuid(batch.getUuid());
            command.setPackagingUomUuid(uom.getUuid());
            return alternative.getUuid();
        });
    }

    private void expectCoordinatorRejection(Runnable action) {
        try {
            action.run();
            fail("Expected coordinated operation validation");
        } catch (APIException | UnprocessableEntityException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("stockmanagement.atomic.coordinatedOperationRequired"));
        } finally { Context.clearSession(); }
    }

    @Test public void concurrentDispensesCannotBothConsumeTheSameOrderRevision() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            AtomicDispenseCommand first = create("14");
            AtomicDispenseCommand second = create("14");
            java.util.List<Future<String>> results = new java.util.ArrayList<>();
            for (AtomicDispenseCommand command : java.util.Arrays.asList(first, second)) {
                results.add(executor.submit(() -> inWorkerSession(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Start barrier timed out"); }
                    try { service.apply(command); return "applied"; }
                    catch (DispenseOperationException failure) { return failure.getCode(); }
                })));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            java.util.List<String> outcomes = new java.util.ArrayList<>();
            for (Future<String> result : results) { outcomes.add(result.get(15, TimeUnit.SECONDS)); }
            assertEquals(1, java.util.Collections.frequency(outcomes, "applied"));
            assertEquals(1, java.util.Collections.frequency(outcomes, "stockmanagement.atomic.orderRevisionConflict"));
            Context.clearSession();
            assertBalance("6");
            assertEquals(1, receiptCount());
            assertEquals(2, movementCount());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test public void warehouseWritesWaitForTheSameDatabaseLock() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch attemptingWrite = new CountDownLatch(1);
        java.util.List<Future<Void>> pending = new java.util.ArrayList<>();
        try {
            tx(() -> {
                dao.lockInventory();
                Future<Void> write = executor.submit(() -> inWorkerSession(() -> {
                    StockManagementService warehouse = Context.getService(StockManagementService.class);
                    StockItem item = warehouse.getStockItemByUuid(itemUuid);
                    item.setCommonName("Synthetic synchronized write");
                    attemptingWrite.countDown();
                    warehouse.saveStockItem(item);
                    return null;
                }));
                pending.add(write);
                try {
                    assertTrue(attemptingWrite.await(10, TimeUnit.SECONDS));
                    write.get(250, TimeUnit.MILLISECONDS);
                    fail("Warehouse write escaped the inventory transaction lock");
                } catch (TimeoutException expected) {
                    // Still blocked while this transaction owns the database row.
                } catch (Exception failure) { throw new AssertionError(failure); }
                return null;
            });
            pending.get(0).get(10, TimeUnit.SECONDS);
            Context.clearSession();
            assertEquals("Synthetic synchronized write", Context.getService(StockManagementService.class)
                .getStockItemByUuid(itemUuid).getCommonName());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private <T> T inWorkerSession(Callable<T> action) throws Exception {
        Context.openSession();
        try {
            Context.authenticate(getCredentials());
            return action.call();
        } finally { Context.closeSession(); }
    }

    private AtomicDispenseCommand create(String quantity) {
        AtomicDispenseCommand result = new AtomicDispenseCommand();
        result.setAction(AtomicDispenseCommand.Action.CREATE);
        result.setOperationUuid(UUID.randomUUID().toString());
        result.setMedicationDispenseUuid(UUID.randomUUID().toString());
        result.setStockItemUuid(itemUuid);
        result.setStockBatchUuid(batchUuid);
        result.setPackagingUomUuid(uomUuid);
        result.setMedicationDispense(clinical.copy());
        result.getMedicationDispense().getQuantity().setValue(new BigDecimal(quantity));
        result.setFulfillerStatus(Order.FulfillerStatus.IN_PROGRESS);
        return result;
    }

    private void expectCode(String code, Runnable action) {
        try {
            action.run();
            fail("Expected " + code);
        } catch (DispenseOperationException expected) {
            assertEquals("stockmanagement.atomic." + code, expected.getCode());
        } finally { Context.clearSession(); }
    }

    private void assertBalance(String expected) {
        BigDecimal actual = tx(() -> dao.balance(
            (Party) sessions.getCurrentSession().get(Party.class, partyId),
            (StockBatch) sessions.getCurrentSession().get(StockBatch.class, batchId)));
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private int receiptCount() { return count("stockmgmt_dispense_operation"); }
    private int movementCount() { return count("stockmgmt_stock_item_transaction"); }
    private int count(String table) {
        return tx(() -> ((Number) sessions.getCurrentSession().createSQLQuery("select count(*) from " + table).uniqueResult()).intValue());
    }
    private <T> T tx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
    private <T extends BaseOpenmrsData> T audit(T value) {
        value.setCreator(Context.getAuthenticatedUser());
        value.setDateCreated(new Date());
        return value;
    }
}
