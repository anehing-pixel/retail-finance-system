package com.company.retail.servicing.mapper;

import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 债权 / 还款计划只读查询 MyBatis Mapper（core.loans / core.repayment_plans / core.schedule_items）。
 *
 * <p>只读：不 UPDATE/DELETE 他域表（R02 §6 真相源边界）；不 import retail-core 代码。</p>
 */
public interface LoanScheduleQueryMapper {

    /** 债权终态（B-7 四分 + CANCELLED）：终态不再做 DPD 快照。 */
    @Select("SELECT loan_id, status FROM core.loans WHERE status NOT IN "
            + "('SETTLED', 'EARLY_SETTLED', 'WRITTEN_OFF', 'CANCELLED') ORDER BY loan_id")
    List<Map<String, Object>> loadLoansForDpd();

    @Select("SELECT si.item_id, p.loan_id, si.due_date, si.status "
            + "FROM core.schedule_items si "
            + "JOIN core.repayment_plans p ON p.plan_id = si.plan_id "
            + "ORDER BY si.item_id")
    List<Map<String, Object>> loadScheduleItems();
}
