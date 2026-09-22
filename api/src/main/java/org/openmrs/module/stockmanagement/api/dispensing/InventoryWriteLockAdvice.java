package org.openmrs.module.stockmanagement.api.dispensing;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.module.stockmanagement.api.dao.DispenseOperationDao;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs inside the transaction, before warehouse service reads or writes inventory. */
public class InventoryWriteLockAdvice implements MethodInterceptor {
    private DispenseOperationDao dao;
    public void setDao(DispenseOperationDao value) { dao = value; }
    @Override public Object invoke(MethodInvocation invocation) throws Throwable {
        if (TransactionSynchronizationManager.isActualTransactionActive()
            && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            dao.lockInventory();
        }
        return invocation.proceed();
    }
}
