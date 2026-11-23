package com.company.retail.servicing.infrastructure;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.servicing.application.LoanScheduleQueryPort;
import com.company.retail.servicing.mapper.LoanScheduleQueryMapper;

import java.util.List;
import java.util.Map;

/**
 * 债权 / 还款计划只读查询 MyBatis 实现（core.loans / core.repayment_plans / core.schedule_items）。
 *
 * <p>只读：不 UPDATE/DELETE 他域表（R02 §6 真相源边界）；W6 未合入时按表字段直接读，
 * 不 import retail-core 代码。</p>
 */
public class MyBatisLoanScheduleQueryPort implements LoanScheduleQueryPort {

    private final LoanScheduleQueryMapper mapper;

    public MyBatisLoanScheduleQueryPort(LoanScheduleQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<LoanView> loadLoansForDpd() {
        return mapper.loadLoansForDpd().stream()
                .map(r -> new LoanView(RowValues.asLong(r, "loan_id"), RowValues.asString(r, "status")))
                .toList();
    }

    @Override
    public List<ItemView> loadScheduleItems() {
        return mapper.loadScheduleItems().stream()
                .map(r -> new ItemView(RowValues.asLong(r, "item_id"),
                        RowValues.asLong(r, "loan_id"),
                        RowValues.asLocalDate(r, "due_date"),
                        RowValues.asString(r, "status")))
                .toList();
    }
}
