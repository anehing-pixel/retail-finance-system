package com.company.retail.servicing.application;

import java.time.LocalDate;
import java.util.List;

/**
 * 债权 / 还款计划只读查询端口（表 core.loans / core.repayment_plans / core.schedule_items）。
 *
 * <p><b>边界说明</b>：loans/schedule_items 的权威写者是 retail-core（R02 §6），本端口
 * <b>只读</b>其表字段（不 import 对方代码、不写他域表）——schedule_items 的 DUE/PAID 流转
 * 归 W6 并行开发，DPD 口径按「due_date &lt; 今日 且 status 非 PAID」容忍并行未合（任务口径）。</p>
 */
public interface LoanScheduleQueryPort {

    /** 非终态债权（终态 SETTLED/EARLY_SETTLED/WRITTEN_OFF/CANCELLED 不再做 DPD 快照）。 */
    List<LoanView> loadLoansForDpd();

    /** 全部期次（JOIN repayment_plans 取 loan_id）。 */
    List<ItemView> loadScheduleItems();

    record LoanView(long loanId, String status) {
    }

    record ItemView(long itemId, long loanId, LocalDate dueDate, String status) {
    }
}
