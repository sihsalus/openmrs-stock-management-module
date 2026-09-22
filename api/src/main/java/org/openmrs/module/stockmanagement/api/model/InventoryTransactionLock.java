package org.openmrs.module.stockmanagement.api.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/** Single permanent row installed by Liquibase and locked until an inventory transaction commits. */
@Entity(name = "stockmanagement.InventoryTransactionLock")
@Table(name = "stockmgmt_inventory_lock")
public class InventoryTransactionLock {
    @Id @Column(name = "lock_id")
    private Integer id;

    public Integer getId() { return id; }
    public void setId(Integer value) { id = value; }
}
