package org.openmrs.module.stockmanagement.api.model;

import java.util.Date;
import javax.persistence.*;
import org.openmrs.BaseOpenmrsObject;
import org.openmrs.MedicationDispense;
import org.openmrs.User;
import org.openmrs.module.stockmanagement.api.dispensing.AtomicDispenseCommand.Action;

/** Durable receipt, not a copy of clinical data or an independent stock balance. */
@Entity(name = "stockmanagement.DispenseOperation")
@Table(name = "stockmgmt_dispense_operation", uniqueConstraints = {
    @UniqueConstraint(columnNames = { "medication_dispense_id", "revision" })
})
public class DispenseOperation extends BaseOpenmrsObject {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dispense_operation_id")
    private Integer id;
    @ManyToOne(optional = false) @JoinColumn(name = "medication_dispense_id")
    private MedicationDispense medicationDispense;
    @Column(name = "revision", nullable = false)
    private int revision;
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;
    @Enumerated(EnumType.STRING) @Column(name = "action", nullable = false, length = 16)
    private Action action;
    @ManyToOne @JoinColumn(name = "stock_transaction_id")
    private StockItemTransaction stockTransaction;
    @ManyToOne @JoinColumn(name = "reversal_transaction_id")
    private StockItemTransaction reversalTransaction;
    @ManyToOne(optional = false) @JoinColumn(name = "creator")
    private User creator;
    @Column(name = "date_created", nullable = false)
    private Date dateCreated;
    @Column(name = "reason", length = 255)
    private String reason;

    @Override public Integer getId() { return id; }
    @Override public void setId(Integer value) { id = value; }
    public MedicationDispense getMedicationDispense() { return medicationDispense; }
    public void setMedicationDispense(MedicationDispense value) { medicationDispense = value; }
    public int getRevision() { return revision; }
    public void setRevision(int value) { revision = value; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { requestHash = value; }
    public Action getAction() { return action; }
    public void setAction(Action value) { action = value; }
    public StockItemTransaction getStockTransaction() { return stockTransaction; }
    public void setStockTransaction(StockItemTransaction value) { stockTransaction = value; }
    public StockItemTransaction getReversalTransaction() { return reversalTransaction; }
    public void setReversalTransaction(StockItemTransaction value) { reversalTransaction = value; }
    public User getCreator() { return creator; }
    public void setCreator(User value) { creator = value; }
    public Date getDateCreated() { return dateCreated; }
    public void setDateCreated(Date value) { dateCreated = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
}
