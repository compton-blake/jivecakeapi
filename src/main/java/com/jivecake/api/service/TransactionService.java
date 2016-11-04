package com.jivecake.api.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.bson.types.ObjectId;
import org.mongodb.morphia.Datastore;
import org.mongodb.morphia.Key;
import org.mongodb.morphia.query.Query;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.jivecake.api.model.Item;
import com.jivecake.api.model.PaypalIPN;
import com.jivecake.api.model.Transaction;

@Singleton
public class TransactionService {
    private final Datastore datastore;
    private final List<String> currencies = Arrays.asList("USD", "EUR");
    private final Predicate<Transaction> usedForCountFilter = (transaction) ->
        transaction.status == this.getPaymentCompleteStatus() ||
        transaction.status == this.getPaymentPendingStatus();

    @Inject
    public TransactionService(Datastore datastore) {
        this.datastore = datastore;
    }

    public Query<Transaction> query() {
        return this.datastore.createQuery(Transaction.class);
    }

    public Transaction read(ObjectId id) {
        Transaction result = this.datastore.find(Transaction.class)
            .field("id").equal(id)
            .get();
        return result;
    }

    public Transaction delete(ObjectId id) {
        Query<Transaction> deleteQuery = this.datastore.createQuery(Transaction.class).filter("id", id);
        Transaction result = this.datastore.findAndDelete(deleteQuery);
        return result;
    }

    public Key<Transaction> save(Transaction itemTransaction) {
        return this.datastore.save(itemTransaction);
    }

    public boolean isValidTransaction(Transaction transaction) {
        return transaction.quantity > 0 && this.currencies.contains(transaction.currency);
    }

    public String getItemTransactionCreatedEventName() {
        return "item.transaction.created";
    }

    public List<List<Transaction>> getTransactionForest(List<Transaction> transactions) {
        Map<Transaction, List<Transaction>> transactionLineageMap = transactions.stream()
            .collect(Collectors.toMap(Function.identity(), transaction -> new ArrayList<Transaction>(Arrays.asList(transaction))));

        Map<ObjectId, Transaction> parentTransactionIdMap;

        do {
            List<ObjectId> parentTransactionIds = transactionLineageMap.entrySet().stream().map(entry -> {
                List<Transaction> lineage = entry.getValue();
                return lineage.get(lineage.size() - 1).id;
            }).collect(Collectors.toList());

            parentTransactionIdMap = this.datastore.find(Transaction.class)
                .field("parentTransactionId").in(parentTransactionIds)
                .asList()
                .stream()
                .collect(Collectors.toMap(transaction -> transaction.parentTransactionId, Function.identity()));

            for (Transaction transaction: transactionLineageMap.keySet()) {
                List<Transaction> lineage = transactionLineageMap.get(transaction);
                Transaction last = lineage.get(lineage.size() - 1);

                if (parentTransactionIdMap.containsKey(last.id)) {
                    lineage.add(parentTransactionIdMap.get(last.id));
                }
            }
        } while(!parentTransactionIdMap.isEmpty());

        List<List<Transaction>> result = transactions.stream()
            .map(transaction -> transactionLineageMap.get(transaction))
            .collect(Collectors.toList());

        return result;
    }

    public void writeToExcel(List<Transaction> transactions, List<JsonNode> users, File file) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Transactions");
        sheet.createFreezePane(0, 1);

        List<ObjectId> itemIds = transactions.stream()
            .map(transaction -> transaction.itemId)
            .collect(Collectors.toList());
        Map<ObjectId, List<Item>> itemById = this.datastore.createQuery(Item.class)
            .field("id").in(itemIds)
            .asList()
            .stream()
            .collect(Collectors.groupingBy(item -> item.id));

        Map<String, List<JsonNode>> userById = users.stream()
            .collect(Collectors.groupingBy(user -> user.get("user_id").asText()));

        String[] headers = {
            "givenName",
            "middleName",
            "familyName",
            "email",
            "itemName",
            "amount",
            "currency",
            "timeCreated",
            "system"
        };

        CellStyle dateStyle = workbook.createCellStyle();
        CreationHelper createHelper = workbook.getCreationHelper();
        dateStyle.setDataFormat(createHelper.createDataFormat().getFormat("m/d/yy h:mm"));

        Row firstRow = sheet.createRow(0);

        for (int index = 0; index < headers.length; index++) {
            Cell cell = firstRow.createCell(index);
            cell.setCellValue(headers[index]);
        }

        for (int index = 0; index < transactions.size(); index++) {
            Row row = sheet.createRow(index + 1);

            Transaction transaction = transactions.get(index);
            Item item = itemById.containsKey(transaction.itemId) ? itemById.get(transaction.itemId).get(0) : null;
            JsonNode user = userById.containsKey(transaction.user_id) ? userById.get(transaction.user_id).get(0) : null;

            this.writeRow(transaction, item, user, row, dateStyle);
        }

        for (int index = 0; index < headers.length; index++) {
            sheet.autoSizeColumn(index);
        }

        FileOutputStream stream = new FileOutputStream(file);
        workbook.write(stream);
        stream.close();
        workbook.close();
    }

    public void writeRow(Transaction subject, Item item, JsonNode user, Row row, CellStyle dateStyle) {
        Cell givenName = row.createCell(0);
        Cell middleName = row.createCell(1);
        Cell familyName = row.createCell(2);
        Cell email = row.createCell(3);
        Cell itemName = row.createCell(4);
        Cell amount = row.createCell(5);
        Cell currency = row.createCell(6);
        Cell timeCreated = row.createCell(7);
        Cell system = row.createCell(8);

        amount.setCellValue(subject.amount);
        currency.setCellValue(subject.currency);

        if (PaypalIPN.class.getSimpleName().equals(subject.linkedObjectClass)) {
            system.setCellValue("paypal");
        } else {
            system.setCellValue("jivecake");
        }

        if (subject.timeCreated != null) {
            timeCreated.setCellValue(subject.timeCreated);
        }

        timeCreated.setCellStyle(dateStyle);

        if (item != null) {
            itemName.setCellValue(item.name);
        }

        if (user == null) {
            givenName.setCellValue(subject.given_name);
            middleName.setCellValue(subject.middleName);
            familyName.setCellValue(subject.family_name);
            email.setCellValue(subject.email);
        } else {
            if (user.has("email")) {
                email.setCellValue(user.get("email").asText());
            }

            if (user.has("user_metadata")) {
                JsonNode meta = user.get("user_metadata");

                if (meta.has("given_name")) {
                    givenName.setCellValue(meta.get("given_name").asText());
                }

                if (meta.has("family_name")) {
                    familyName.setCellValue(meta.get("family_name").asText());
                }
            } else {
                if (user.has("given_name")) {
                    givenName.setCellValue(user.get("given_name").asText());
                }

                if (user.has("family_name")) {
                    familyName.setCellValue(user.get("family_name").asText());
                }
            }
        }
    }

    public Predicate<Transaction> getCountingFilter() {
        return this.usedForCountFilter;
    }

    public int getPaymentCompleteStatus() {
        return 0;
    }

    public int getPaymentPendingStatus() {
        return 1;
    }

    public int getInvalidPaymentStatus() {
        return 2;
    }

    public int getMalformedDataStatus() {
        return 3;
    }

    public int getRefundedStatus() {
        return 4;
    }

    public int getRevokedStatus() {
        return 5;
    }

    public int getPendingWithValidPayment() {
        return 6;
    }

    public int getPendingWithInvalidPayment() {
        return 7;
    }

    public int getTransferredStatus() {
        return 8;
    }
}
