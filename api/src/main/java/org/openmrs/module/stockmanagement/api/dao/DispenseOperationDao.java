package org.openmrs.module.stockmanagement.api.dao;

import java.math.BigDecimal;
import java.util.List;
import org.hibernate.LockMode;
import org.hibernate.FlushMode;
import org.hibernate.criterion.Restrictions;
import org.openmrs.Order;
import org.openmrs.module.stockmanagement.api.dispensing.DispenseOperationException;
import org.openmrs.module.stockmanagement.api.model.*;

public class DispenseOperationDao extends DaoBase {
    public void refresh(org.openmrs.MedicationDispense dispense) {
        getSession().refresh(dispense);
    }

    public void lockInventory() {
        Object lock = getSession().createSQLQuery(
            "select lock_id from stockmgmt_inventory_lock where lock_id = 1 for update")
            // A nested warehouse call may hold partially assembled entities. Acquiring the mutex
            // must not flush those entities (or any writes) before the lock has been obtained.
            .setFlushMode(FlushMode.MANUAL).uniqueResult();
        if (lock == null) { throw new DispenseOperationException("stockmanagement.atomic.migrationRequired"); }
    }

    public DispenseOperation latestForOrder(String orderUuid) {
        return (DispenseOperation) getSession().createCriteria(DispenseOperation.class)
            .createAlias("medicationDispense", "dispense").createAlias("dispense.drugOrder", "drugOrder")
            .add(Restrictions.eq("drugOrder.uuid", orderUuid))
            .addOrder(org.hibernate.criterion.Order.desc("id")).setMaxResults(1).uniqueResult();
    }

    public Order lockOrder(String uuid) {
        Order order = (Order) getSession().createCriteria(Order.class)
            .add(Restrictions.eq("uuid", uuid)).setLockMode(LockMode.PESSIMISTIC_WRITE).uniqueResult();
        if (order != null) { getSession().refresh(order); }
        return order;
    }

    public void lockBatches(List<Integer> ids) {
        // Lock actual rows, held until commit, in one deterministic order on every server instance.
        ids.stream().distinct().sorted().forEach(id -> {
            Object batch = getSession().createCriteria(StockBatch.class)
                .add(Restrictions.idEq(id)).setLockMode(LockMode.PESSIMISTIC_WRITE).uniqueResult();
            if (batch == null) { throw new DispenseOperationException("stockmanagement.atomic.batchMissing"); }
        });
    }

    public DispenseOperation find(String uuid) {
        return (DispenseOperation) getSession().createCriteria(DispenseOperation.class)
            .add(Restrictions.eq("uuid", uuid)).uniqueResult();
    }

    public DispenseOperation latest(String dispenseUuid) {
        return (DispenseOperation) getSession().createCriteria(DispenseOperation.class)
            .createAlias("medicationDispense", "dispense")
            .add(Restrictions.eq("dispense.uuid", dispenseUuid))
            .addOrder(org.hibernate.criterion.Order.desc("revision")).setMaxResults(1).uniqueResult();
    }

    public BigDecimal balance(Party party, StockBatch batch) {
        getSession().flush();
        Object value = getSession().createQuery("select sum(t.quantity * u.factor) "
            + "from stockmanagement.StockItemTransaction t join t.stockItemPackagingUOM u "
            + "where t.party = :party and t.stockBatch = :batch")
            .setParameter("party", party).setParameter("batch", batch).uniqueResult();
        return value == null ? BigDecimal.ZERO : (BigDecimal) value;
    }

    public DispenseOperation save(DispenseOperation operation) {
        getSession().save(operation);
        getSession().flush();
        return operation;
    }
}
